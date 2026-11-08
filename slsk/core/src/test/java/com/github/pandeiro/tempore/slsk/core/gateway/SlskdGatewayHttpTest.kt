package com.github.pandeiro.tempore.slsk.core.gateway

import com.github.pandeiro.tempore.slsk.core.domain.RemoteFile
import com.github.pandeiro.tempore.slsk.core.domain.SearchOptions
import com.github.pandeiro.tempore.slsk.core.domain.SearchProgress
import com.github.pandeiro.tempore.slsk.core.domain.TransferLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Route dispatcher with per-test handlers + request/body recording. */
internal class RecordingHandler : Dispatcher() {
    var route: (RecordedRequest) -> MockResponse = {
        MockResponse().setResponseCode(404).setBody("{}")
    }
    val recorded = mutableListOf<RecordedRequest>()
    val bodies = mutableListOf<String>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        recorded.add(request)
        bodies.add(request.body.readUtf8())
        return route(request)
    }
}

internal fun ok(body: String, code: Int = 200): MockResponse =
    MockResponse().setResponseCode(code).setBody(body)

/**
 * Contract tests over a real MockWebServer + real Retrofit client, mirroring the
 * live-probed slskd 0.26.1 surface in slsk/SPIKE.md.
 */
class SlskdGatewayHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var gateway: SlskdGatewayImpl
    private val handler = RecordingHandler()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = handler
        server.start()
        gateway = SlskdGatewayImpl.Factory(
            baseUrl = server.url("/").toString(),
            apiKey = "secret-key",
            searchPollIntervalMs = 10,
            searchRequestTimeoutMs = 60_000,
        ).build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------ health

    @Test
    fun health_reads_application_version_and_server_flags() = runBlocking {
        handler.route = { req ->
            assertEquals("/api/v0/application", req.path)
            assertEquals("secret-key", req.getHeader("X-API-Key"))
            ok("""{"version":{"full":"0.26.1.0"},"server":{"isConnected":true,"isLoggedIn":true}}""")
        }
        val health = gateway.health()
        assertEquals("0.26.1.0", health.version)
        assertTrue(health.isConnected)
        assertTrue(health.isLoggedIn)
    }

    // ------------------------------------------------------------------ search

    @Test
    fun search_polls_state_groups_results_and_does_not_delete_completed_searches() = runBlocking {
        val searchId = "1c8eb99f-ed6e-4a74-898f-4432cb177cb8"
        var stateCalls = 0
        handler.route = { req ->
            when {
                req.method == "POST" && req.path!!.startsWith("/api/v0/searches") -> {
                    val body = handler.bodies.last()
                    assertTrue(Regex("\"id\"\\s*:\\s*\"[0-9a-fA-F-]{36}\"").containsMatchIn(body))
                    assertTrue(body.contains("\"searchText\""))
                    assertTrue(body.contains("\"searchTimeout\""))
                    assertTrue(body.contains("\"responseLimit\""))
                    assertTrue(body.contains("\"fileLimit\""))
                    ok("""{"id":"$searchId","isComplete":false,"state":"InProgress"}""")
                }
                req.method == "GET" && req.path == "/api/v0/searches/$searchId/responses" -> ok("[]")
                req.method == "GET" && req.path == "/api/v0/searches/$searchId" -> {
                    if (stateCalls++ == 0) {
                        ok(
                            """
                            {"id":"$searchId","isComplete":false,"state":"InProgress",
                             "responseCount":1,"fileCount":2,
                             "unknownFieldForTolerance":true,
                             "responses":[{"username":"PEER01","hasFreeUploadSlot":true,"queueLength":0,
                               "uploadSpeed":1000,"fileCount":2,
                               "files":[{"code":1,"filename":"D\\Album\\01 Song.flac","size":27046286,
                                 "extension":"flac","bitDepth":16,"sampleRate":44100,"length":237,"isLocked":false},
                                {"code":1,"filename":"D\\Album\\02 Song.mp3","size":9620671,
                                 "extension":"","length":238,"isLocked":false}]}]}
                            """.trimIndent(),
                        )
                    } else {
                        // responses omitted -> gateway falls through to GET /responses
                        ok(
                            """{"id":"$searchId","isComplete":true,"state":"Completed, ResponseLimitReached",""" +
                                """"responseCount":1,"fileCount":2}""",
                        )
                    }
                }
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }

        val events = mutableListOf<SearchProgress>()
        withTimeout(15_000) {
            gateway.search(
                "radiohead",
                SearchOptions(searchText = "radiohead", searchId = searchId),
            ).collect { events.add(it) }
        }

        assertEquals(2, events.size)
        assertFalse(events[0].isComplete)
        assertEquals(1, events[0].groups.size)
        val files = events[0].groups[0].files
        assertEquals(2, files.size)
        val flac = files.first { it.filename.endsWith(".flac") }
        assertEquals("flac", flac.extension) // derived from filename when the field is empty
        assertEquals(com.github.pandeiro.tempore.slsk.core.domain.QualityClass.LOSSLESS_16_44, flac.qualityClass)
        val mp3 = files.first { it.filename.endsWith(".mp3") }
        assertEquals("mp3", mp3.extension) // empty extension field -> derived
        assertFalse(mp3.bitrateKbps == null)
        assertEquals(323, mp3.bitrateKbps)
        assertTrue(events[1].isComplete)

        // Completed searches are NOT deleted while/results after consumption:
        assertTrue(handler.recorded.none { it.method == "DELETE" })
        assertTrue(handler.recorded.none { it.method == "PUT" })
        // The responses endpoint served the empty final poll.
        assertTrue(handler.recorded.any { it.method == "GET" && it.path == "/api/v0/searches/$searchId/responses" })
        // POST carried the blocking requestTimeout query.
        val post = handler.recorded.first { it.method == "POST" }
        assertTrue(post.path!!.contains("requestTimeout="))
    }

    @Test
    fun abandoned_search_is_stopped_then_deleted() = runBlocking {
        val searchId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        handler.route = { req ->
            when {
                req.method == "POST" && req.path!!.startsWith("/api/v0/searches") ->
                    ok("""{"id":"$searchId","isComplete":false,"state":"InProgress"}""")
                req.method == "GET" && req.path == "/api/v0/searches/$searchId/responses" -> ok("[]")
                req.method == "GET" && req.path == "/api/v0/searches/$searchId" ->
                    ok("""{"id":"$searchId","isComplete":false,"state":"InProgress","responseCount":0,"fileCount":0}""")
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        withTimeout(15_000) {
            gateway.search(
                "abandoned",
                SearchOptions(searchText = "abandoned", searchId = searchId),
            )
                .take(1) // collector abandons after the first progress snapshot
                .collect { }
        }
        withTimeout(10_000) {
            while (true) {
                val stopped = handler.recorded.any { it.method == "PUT" }
                val deleted = handler.recorded.any { it.method == "DELETE" }
                if (stopped && deleted) break
            }
        }
        assertEquals("/api/v0/searches/$searchId", handler.recorded.first { it.method == "PUT" }.path)
        assertEquals("/api/v0/searches/$searchId", handler.recorded.first { it.method == "DELETE" }.path)
    }

    // ------------------------------------------------------------------ enqueue

    @Test
    fun enqueue_batch_posts_top_level_Username_with_files() = runBlocking {
        handler.route = { req ->
            when {
                req.method == "GET" && req.path == "/api/v0/transfers/downloads" -> ok("[]")
                req.method == "POST" && req.path!!.startsWith("/api/v0/transfers/downloads/batches") -> {
                    val body = handler.bodies.last()
                    // Top-level Username is REQUIRED by slskd (SPIKE.md).
                    assertTrue(
                        "Username must be top-level: $body",
                        Regex("^\\{\\s*\"Username\"\\s*:").containsMatchIn(body),
                    )
                    assertTrue(body.contains("\"Files\":[{\"filename\":\"D"))
                    assertTrue(body.contains("\"searchId\""))
                    ok("""[{"fileName":"D\\Album\\01 Song.flac","success":true}]""", code = 201)
                }
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        val outcome = gateway.enqueue(
            username = "PEER01",
            files = listOf(RemoteFile("D\\Album\\01 Song.flac", 27_046_286, "flac")),
            searchId = "1c8eb99f-ed6e-4a74-898f-4432cb177cb8",
        )
        assertTrue(outcome.accepted)
        assertEquals(0, outcome.failureCount)
    }

    @Test
    fun enqueue_falls_back_to_legacy_endpoint_on_404_batches() = runBlocking {
        handler.route = { req ->
            when {
                req.method == "GET" && req.path == "/api/v0/transfers/downloads" -> ok("[]")
                req.method == "POST" && req.path!!.startsWith("/api/v0/transfers/downloads/batches") ->
                    MockResponse().setResponseCode(404).setBody("{}")
                req.method == "POST" && req.path == "/api/v0/transfers/downloads/PEER01" -> {
                    val body = handler.bodies.last()
                    assertTrue(body.contains("[{\"filename\":\"D"))
                    ok("""[]""")
                }
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        val outcome = gateway.enqueue(
            username = "PEER01",
            files = listOf(RemoteFile("D\\Album\\01 Song.flac", 10L, "flac")),
            searchId = null,
        )
        assertTrue(outcome.accepted)
    }

    @Test
    fun enqueue_207_partial_failures_are_surfaced() = runBlocking {
        handler.route = { req ->
            when {
                req.method == "GET" && req.path == "/api/v0/transfers/downloads" -> ok("[]")
                req.method == "POST" && req.path!!.startsWith("/api/v0/transfers/downloads/batches") ->
                    MockResponse().setResponseCode(207).setBody(
                        """
                        [{"fileName":"D\\Album\\01 Song.flac","success":true},
                         {"fileName":"D\\Album\\02 Song.flac","success":false,"error":"user is offline"}]
                        """.trimIndent(),
                    )
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        val outcome = gateway.enqueue(
            username = "PEER01",
            files = listOf(
                RemoteFile("D\\Album\\01 Song.flac", 10L, "flac"),
                RemoteFile("D\\Album\\02 Song.flac", 20L, "flac"),
            ),
            searchId = null,
        )
        assertFalse(outcome.accepted)
        assertEquals(1, outcome.failureCount)
        assertEquals("D\\Album\\02 Song.flac", outcome.failures.single().filename)
        assertEquals("user is offline", outcome.failures.single().reason)
    }

    @Test
    fun enqueue_dedupes_against_current_transfers() = runBlocking {
        handler.route = { req ->
            when {
                req.method == "GET" && req.path == "/api/v0/transfers/downloads" ->
                    ok(
                        """
                        [{"username":"PEER01","directories":[{"directory":"D\\Album","fileCount":1,
                          "files":[{"id":"t1","username":"PEER01","filename":"D\\Album\\01 Song.flac",
                          "size":27046286,"state":"Completed, Succeeded"}]}]}]
                        """.trimIndent(),
                    )
                req.method == "POST" && req.path!!.startsWith("/api/v0/transfers/downloads/batches") -> {
                    val body = handler.bodies.last()
                    // Only the NEW file reaches the batch; already-queued one must not.
                    assertTrue(body.contains("02 Song.flac"))
                    assertTrue(!body.contains("01 Song.flac"))
                    ok("""[{"fileName":"D\\Album\\02 Song.flac","success":true}]""", code = 201)
                }
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        val outcome = gateway.enqueue(
            username = "PEER01",
            files = listOf(
                RemoteFile("D\\Album\\01 Song.flac", 27_046_286, "flac"),
                RemoteFile("D\\Album\\02 Song.flac", 4_000_000, "flac"),
            ),
            searchId = null,
        )
        assertTrue(outcome.accepted)
    }

    // ------------------------------------------------------------------ transfers

    @Test
    fun transfers_snapshot_maps_state_tokens_to_lifecycle_groups() = runBlocking {
        handler.route = { req ->
            when {
                req.method == "GET" && req.path == "/api/v0/transfers/downloads" ->
                    ok(fixtureTransfersJson())
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        val groups = withTimeout(10_000) { gateway.transfers().first() }
        assertTrue(groups.isNotEmpty())
        val all = groups.flatMap { it.transfers }
        assertTrue(all.any { it.lifecycle == TransferLifecycle.SUCCEEDED })
        assertTrue(all.any { it.lifecycle == TransferLifecycle.FAILED })
        assertTrue(all.first { it.lifecycle == TransferLifecycle.SUCCEEDED }.percentComplete == 100)
    }

    @Test
    fun cancel_and_clearCompleted_hit_documented_endpoints() = runBlocking {
        handler.route = { req ->
            when {
                // remove flag present as documented; the exact final assertions live below.
                req.method == "DELETE" -> MockResponse().also {
                    val code = if (req.path!!.startsWith("/api/v0/transfers/downloads/all/completed")) 200 else 204
                    it.setResponseCode(code)
                    if (code == 200) it.setBody("{}")
                }
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        gateway.cancel("PEER01", "t1", remove = true)
        gateway.cancel("PEER01", "t2", remove = false)
        gateway.clearCompleted()
        val deletes = handler.recorded.filter { it.method == "DELETE" }
        assertEquals(3, deletes.size)
        assertEquals("/api/v0/transfers/downloads/PEER01/t1?remove=true", deletes[0].path)
        assertEquals("/api/v0/transfers/downloads/PEER01/t2?remove=false", deletes[1].path)
        assertEquals("/api/v0/transfers/downloads/all/completed", deletes[2].path)
    }

    // ------------------------------------------------------------------ helpers

    private fun fixtureTransfersJson(): String =
        javaClass.getResourceAsStream("/fixtures/transfers.downloads.0.26.1.json")!!
            .readBytes().decodeToString()
}
