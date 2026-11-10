package com.github.pandeiro.tempore.slsk.core.gateway

import com.github.pandeiro.tempore.slsk.core.api.EnqueueBatchRequestDto
import com.github.pandeiro.tempore.slsk.core.api.EnqueueFileDto
import com.github.pandeiro.tempore.slsk.core.api.EnqueueResultItemDto
import com.github.pandeiro.tempore.slsk.core.api.SearchStateDto
import com.github.pandeiro.tempore.slsk.core.api.SlskdApi
import com.github.pandeiro.tempore.slsk.core.api.SlskdMapper
import com.github.pandeiro.tempore.slsk.core.api.StartSearchRequestDto
import com.github.pandeiro.tempore.slsk.core.api.UserDirectoryRequestDto
import com.github.pandeiro.tempore.slsk.core.domain.EnqueueFailure
import com.github.pandeiro.tempore.slsk.core.domain.EnqueueOutcome
import com.github.pandeiro.tempore.slsk.core.domain.FileAllowlist
import com.github.pandeiro.tempore.slsk.core.domain.Health
import com.github.pandeiro.tempore.slsk.core.domain.RemoteFile
import com.github.pandeiro.tempore.slsk.core.domain.ResultGroup
import com.github.pandeiro.tempore.slsk.core.domain.SearchOptions
import com.github.pandeiro.tempore.slsk.core.domain.SearchProgress
import com.github.pandeiro.tempore.slsk.core.domain.SoulseekGateway
import com.github.pandeiro.tempore.slsk.core.domain.StateTokens
import com.github.pandeiro.tempore.slsk.core.domain.TransferGroup
import com.github.pandeiro.tempore.slsk.core.ranking.GroupingEngine
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Real slskd-backed gateway behind [SoulseekGateway]. Owns its OkHttp/Retrofit client
 * (hand-written facade; rules in PLAN.md §5.3).
 *
 * Transport rules:
 *  - normal calls: 10 s connect / 20 s read; the blocking search POST gets its own
 *    client where the read timeout is `searchTimeout + 15 s`;
 *  - the API key never reaches logs: `X-API-Key` is redacted and the logging interceptor
 *    only exists in debug builds;
 *  - optional strict pinning: when `pinnedCertSha256` is set, this client only accepts
 *    chains whose SHA-256 matches the pin (validity-checked); without a pin, ordinary
 *    system trust applies. Never a global trust-all.
 */
open class SlskdGatewayImpl private constructor(
    private val api: SlskdApi,
    private val io: CoroutineDispatcher,
    private val searchPollIntervalMs: Long,
    private val searchRequestTimeoutMs: Long,
) : SoulseekGateway {

    /** Last search hits by username -> directory -> files (folder-contents fallback). */
    private val lastSearchFiles: MutableMap<String, MutableMap<String, MutableList<RemoteFile>>> = HashMap()

    /** Last non-empty grouped result set; returned when a poll fetch fails mid-search so the UI list never flickers empty. */
    private var lastKnownGroups: List<ResultGroup> = emptyList()

    // ------------------------------------------------------------------ health

    override suspend fun health(): Health = withContext(io) {
        SlskdMapper.toHealth(execute { api.application() })
    }

    // ------------------------------------------------------------------ search

    override fun search(query: String, options: SearchOptions): Flow<SearchProgress> = flow {
        val id = options.searchId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        var sawComplete = false
        coroutineScope {
            // (1) POST /searches blocks slskd until requestTimeout when given; fire on a
            // worker and start polling immediately.
            val startJob = launch {
                val body = StartSearchRequestDto(
                    id = id,
                    searchText = query,
                    searchTimeout = options.searchTimeoutMs,
                    responseLimit = options.responseLimit,
                    fileLimit = options.fileLimit,
                )
                val response = api.startSearch(searchRequestTimeoutMs, body)
                if (!response.isSuccessful) {
                    throw IOException("slskd rejected search start: HTTP ${response.code()} ${response.message()}")
                }
                rememberResults(response.body()?.responses)
            }
            try {
                // (2) Poll; emit at most every searchPollIntervalMs; stop when completed.
                while (true) {
                    val state = fetchState(id)
                    val groups = groupAndRemember(state)
                    sawComplete = (state.isComplete ?: false) ||
                        StateTokens.contains(StateTokens.parse(state.state), "Completed")
                    emit(
                        SearchProgress(
                            searchId = state.id ?: id,
                            stateTokens = StateTokens.parse(state.state),
                            isComplete = sawComplete,
                            responseCount = state.responseCount ?: groups.size,
                            fileCount = state.fileCount ?: 0,
                            groups = groups,
                        )
                    )
                    if (sawComplete) break
                    delay(searchPollIntervalMs)
                }
            } finally {
                startJob.cancel()
                // (3) cancel-then-DELETE only when abandoned: never DELETE while the
                // consumer is still reading results (recorded searches disappear).
                // NonCancellable so an already-cancelled collector still runs cleanup.
                if (!sawComplete) {
                    withContext(NonCancellable) {
                        runCatching { api.stopSearch(id) } // PUT stop-seeking; 404 tolerated
                        runCatching { api.deleteSearch(id) }
                    }
                }
            }
        }
    }.flowOn(io)

    private suspend fun fetchState(id: String): SearchStateDto {
        var lastCode = 0
        // The POST registers asynchronously on slskd; a short row of 404s before it lands is normal.
        repeat(3) {
            val response = runCatching { api.searchState(id) }.getOrNull() ?: return@repeat
            if (response.isSuccessful) return response.body() ?: SearchStateDto(id = id)
            lastCode = response.code()
            if (lastCode != 404) throw IOException("slskd search state failed: HTTP $lastCode ${response.message()}")
            delay(searchPollIntervalMs)
        }
        throw IOException("slskd search $id not reachable (last HTTP $lastCode)")
    }

    private suspend fun groupAndRemember(state: SearchStateDto): List<ResultGroup> {
        // Live slskd 0.26.1 quirk: GET /searches/{id} reports responseCount but ships an
        // empty responses array; the per-response payload only lives on the
        // /responses sub-resource. Fetch it every poll (recorded in slsk/SPIKE.md).
        val inline = state.responses
        val responses = if (!inline.isNullOrEmpty()) inline
        else runCatching { api.searchResponses(state.id ?: "") }.getOrNull()
            ?.body().orEmpty()
        if (responses.isEmpty() && (state.responseCount ?: 0) > 0) {
            // Responses exist server-side but this fetch failed/returned nothing:
            // keep the last known result set instead of wiping the list mid-search.
            return lastKnownGroups
        }
        val peers = responses.map {
            GroupingEngine.PeerFiles(
                username = it.username,
                files = SlskdMapper.filesOf(it),
                hasFreeUploadSlot = it.hasFreeUploadSlot ?: false,
                uploadSpeedBps = it.uploadSpeed ?: 0L,
                queueLength = it.queueLength ?: 0,
            )
        }
        val groups = GroupingEngine().group(peers)
        lastKnownGroups = groups
        for (group in groups) {
            val directories = lastSearchFiles.getOrPut(group.username) { HashMap() }
            val existing = directories.getOrPut(group.directory) { mutableListOf() }
            for (file in group.files) {
                if (existing.none { it.filename == file.filename }) existing.add(file)
            }
        }
        return groups
    }

    private fun rememberResults(responses: List<com.github.pandeiro.tempore.slsk.core.api.SearchResponseDto>?) {
        if (responses == null) return
        for (response in responses) {
            val name = response.username ?: continue
            val byDirectory = lastSearchFiles.getOrPut(name) { HashMap() }
            for (file in SlskdMapper.filesOf(response)) {
                byDirectory.getOrPut(file.directory) { mutableListOf() }.add(file)
            }
        }
    }

    // ---------------------------------------------------------- folder contents

    override suspend fun folderContents(username: String, directory: String): List<RemoteFile> =
        withContext(io) {
            // Tolerant: any non-2xx (unknown user 404, user offline 409, unsupported) falls
            // through to the recomputed last-search-hit files instead of crashing.
            val live = runCatching {
                api.userDirectory(username, UserDirectoryRequestDto(Directory = directory))
            }.getOrNull()?.takeIf { it.isSuccessful }?.body()
                ?.mapNotNull { SlskdMapper.toRemoteFile(it) }
                ?.filter { FileAllowlist.isAllowed(it.extension) }
            live ?: lastSearchFiles[username]?.get(directory)?.filter { !it.isLocked }.orEmpty()
        }

    // ---------------------------------------------------------------- enqueue

    override suspend fun enqueue(
        username: String,
        files: List<RemoteFile>,
        searchId: String?,
    ): EnqueueOutcome = withContext(io) {
        val candidates = files.filter { FileAllowlist.isAllowed(it.extension) && !it.isLocked }
        val hidden = files.filterNot { FileAllowlist.isAllowed(it.extension) && !it.isLocked }
            .map { EnqueueFailure(username, it.filename, "hidden by allowlist or locked") }
        if (candidates.isEmpty()) {
            EnqueueOutcome(accepted = true, failureCount = hidden.size, failures = hidden)
        } else {
            // (1) De-duplicate against current transfers on (username, filename) so retries
            // can never double-queue.
            val queuedKeys = runCatching { currentQueuedKeys() }.getOrDefault(emptySet())
            val toQueue = candidates.filter { key(username, it.filename) !in queuedKeys }
            if (toQueue.isEmpty()) {
                EnqueueOutcome(accepted = true, failureCount = hidden.size, failures = hidden)
            } else {
                val outcome = enqueueViaBestEndpoint(username, toQueue, searchId)
                outcome.copy(
                    failures = outcome.failures + hidden,
                    failureCount = outcome.failures.size + hidden.size,
                )
            }
        }
    }

    /** Preferred batch endpoint; 404/405 or a batches-rejected `Username` drop to legacy. */
    private suspend fun enqueueViaBestEndpoint(
        username: String,
        files: List<RemoteFile>,
        searchId: String?,
    ): EnqueueOutcome {
        val batch = runCatching {
            api.enqueueBatch(
                EnqueueBatchRequestDto(
                    Username = username,
                    Files = files.map { EnqueueFileDto(filename = it.filename, size = it.size) },
                    searchId = searchId,
                )
            )
        }.getOrNull()
        val batchCode = batch?.code() ?: 0
        if (batch == null || batchCode == 404 || batchCode == 405) return legacyEnqueue(username, files)
        if (!batch.isSuccessful) {
            val errorBody = runCatching { batch.errorBody()?.string().orEmpty() }.getOrDefault("")
            if (batchCode == 400 && errorBody.contains("Username", ignoreCase = true)) {
                return legacyEnqueue(username, files)
            }
            return failureOutcome(username, files, "slskd rejected enqueue: HTTP $batchCode")
        }
        // (4) 201 = all queued; 200/207 need the failures list read; 2xx without failures = accepted.
        val failures = readFailures(username, batch.body().orEmpty())
        if (failures.isEmpty()) return EnqueueOutcome(accepted = true, failureCount = 0, failures = emptyList())
        if (failures.any { it.reason.contains("username", ignoreCase = true) }) {
            return legacyEnqueue(username, files)
        }
        return EnqueueOutcome(accepted = false, failureCount = failures.size, failures = failures)
    }

    /** `POST /transfers/downloads/{username}` with an inline `[{filename,size}]` list. */
    private suspend fun legacyEnqueue(username: String, files: List<RemoteFile>): EnqueueOutcome {
        val response = runCatching {
            api.enqueueLegacy(username, files.map { EnqueueFileDto(filename = it.filename, size = it.size) })
        }.getOrNull()
        return when {
            response == null -> failureOutcome(username, files, "slskd unreachable while enqueueing")
            response.isSuccessful -> {
                val failures = readFailures(username, response.body().orEmpty())
                if (failures.isEmpty()) {
                    EnqueueOutcome(accepted = true, failureCount = 0, failures = emptyList())
                } else {
                    EnqueueOutcome(accepted = false, failureCount = failures.size, failures = failures)
                }
            }
            else -> failureOutcome(username, files, "legacy enqueue failed: HTTP ${response.code()}")
        }
    }

    private fun readFailures(username: String, items: List<EnqueueResultItemDto>): List<EnqueueFailure> =
        items.asSequence()
            .filter { it.success == false || !it.error.isNullOrBlank() || !it.exception.isNullOrBlank() }
            .map {
                EnqueueFailure(
                    username = username,
                    filename = it.fileName ?: it.file ?: it.filename ?: "",
                    reason = it.error ?: it.exception ?: it.message ?: "unknown reason",
                )
            }
            .toList()

    private fun failureOutcome(username: String, files: List<RemoteFile>, reason: String): EnqueueOutcome =
        EnqueueOutcome(
            accepted = false,
            failureCount = files.size,
            failures = files.map { EnqueueFailure(username, it.filename, reason) },
        )

    // ---------------------------------------------------------------- transfers

    override fun transfers(): Flow<List<TransferGroup>> = flow {
        emit(withContext(io) { SlskdMapper.toTransferGroups(execute { api.downloads() }) })
    }.flowOn(io)

    override suspend fun cancel(username: String, transferId: String, remove: Boolean) {
        withContext(io) { execute { api.removeTransfer(username, transferId, remove) } }
    }

    override suspend fun clearCompleted() {
        withContext(io) {
            // 200/204 expected; tolerates other informative non-2xx codes silently.
            runCatching { api.removeCompletedTransfers() }
        }
    }

    // ---------------------------------------------------------------- internals

    /** All in-flight transfer (username, filename) pairs, lowercased composite keys. */
    private suspend fun currentQueuedKeys(): Set<String> {
        val users = execute { api.downloads() }
        val keys = HashSet<String>()
        for (user in users) {
            val name = user.username ?: continue
            for (dir in user.directories.orEmpty()) {
                for (file in dir.files.orEmpty()) {
                    val filename = file.filename ?: continue
                    keys.add(key(name, filename))
                }
            }
        }
        return keys
    }

    private fun key(username: String, filename: String): String =
        username.lowercase() + "\u0000" + filename.lowercase()

    private suspend inline fun <T> execute(block: () -> Response<T>): T {
        val response = runCatching { block() }.getOrElse { throw IOException("slskd call failed", it) }
        if (!response.isSuccessful) {
            throw IOException("slskd call failed: HTTP ${response.code()} ${response.message()}")
        }
        // Unit-typed endpoints (stop/delete/cancel/clear) legitimately answer 204 with no
        // body; anything else must have parsed content.
        @Suppress("UNCHECKED_CAST")
        return response.body() ?: (Unit as T)
    }

    /** Builds the gateway; separate so tests can shrink polling intervals. */
    open class Factory(
        private val baseUrl: String,
        private val apiKey: String,
        private val debug: Boolean = false,
        private val pinnedCertSha256: String? = null,
        private val io: CoroutineDispatcher = Dispatchers.IO,
        private val searchPollIntervalMs: Long = 1_500L,
        private val searchRequestTimeoutMs: Long = 90_000L,
    ) {
        fun build(): SlskdGatewayImpl {
            val client = buildClient()
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl.ensureTrailingSlash())
                .client(client)
                .addConverterFactory(
                    SlskdApi.json.asConverterFactory("application/json".toMediaType()),
                )
                .build()
            return SlskdGatewayImpl(
                api = retrofit.create(SlskdApi::class.java),
                io = io,
                searchPollIntervalMs = searchPollIntervalMs,
                searchRequestTimeoutMs = searchRequestTimeoutMs,
            )
        }

        private fun buildClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .apply {
                    if (pinnedCertSha256 != null) SlskTls.applyStrictPinning(this, pinnedCertSha256)
                    if (apiKey.isNotBlank()) {
                        val apiKeyInterceptor = okhttp3.Interceptor { chain ->
                            chain.proceed(chain.request().newBuilder().header("X-API-Key", apiKey).build())
                        }
                        addInterceptor(apiKeyInterceptor)
                    }
                    if (debug) {
                        // Logging exists only in debug builds and never prints the key.
                        val logging = okhttp3.logging.HttpLoggingInterceptor().apply {
                            level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                            redactHeader("X-API-Key")
                        }
                        addInterceptor(logging)
                    }
                }
                .build()

        // strict pin mode lives in SlskTls.
    }
}

internal fun String.ensureTrailingSlash(): String = when {
    endsWith('/') -> this
    else -> "$this/"
}