package com.github.pandeiro.tempore.slsk.core.domain

import kotlinx.coroutines.flow.Flow

data class SearchOptions(
    val searchText: String,
    val searchTimeoutMs: Long = 8_000L,
    val responseLimit: Int = 250,
    val fileLimit: Int = 20_000,
    val searchId: String? = null, // client-generated GUID; GroupingEngine caller supplies
)

data class EnqueueFailure(
    val username: String,
    val filename: String,
    val reason: String,
)

/** Read the failures list, never trust the 2xx alone. */
data class EnqueueOutcome(
    val accepted: Boolean,
    val failureCount: Int,
    val failures: List<EnqueueFailure>,
)

enum class TransferLifecycle { QUEUED_REMOTE, QUEUED_LOCAL, DOWNLOADING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }

data class Transfer(
    val id: String,
    val username: String,
    val directory: String,
    val filename: String,
    val size: Long,
    val bytesTransferred: Long,
    val rawState: String,
    val lifecycle: TransferLifecycle,
    val averageSpeedBps: Long?,
    val percentComplete: Int?,
    val remainingTimeSeconds: Long?,
)

/** Grouped by (username, directory) for the activity screen. */
data class TransferGroup(
    val username: String,
    val directory: String,
    val transfers: List<Transfer>,
) {
    val allSucceeded: Boolean get() = transfers.isNotEmpty() && transfers.all { it.lifecycle == TransferLifecycle.SUCCEEDED }
    val anyFailed: Boolean get() = transfers.any { it.lifecycle == TransferLifecycle.FAILED }
    val totalBytes: Long get() = transfers.sumOf { it.size }
    val transferredBytes: Long get() = transfers.sumOf { it.bytesTransferred }
}

/** Single slskd-authoritative session object; screens come and go, this stays. */
interface SoulseekGateway {
    /** Reachable + slskd's Soulseek connection state. Used by Test connection and pre-search checks. */
    suspend fun health(): Health

    /**
     * Starts a search on slskd and streams progress until complete (or the flow's
     * collector cancels). Results stream in; the caller owns cancel semantics.
     */
    fun search(query: String, options: SearchOptions): Flow<SearchProgress>

    suspend fun folderContents(username: String, directory: String): List<RemoteFile>

    /** Dedupes against current transfers by (username, filename) so retries never double-queue. */
    suspend fun enqueue(username: String, files: List<RemoteFile>, searchId: String?): EnqueueOutcome

    /** Snapshot flow; exact transfer ordering and content comes from slskd. */
    fun transfers(): Flow<List<TransferGroup>>

    suspend fun cancel(username: String, transferId: String, remove: Boolean)

    suspend fun clearCompleted()
}
