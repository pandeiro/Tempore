package com.github.pandeiro.tempore.slsk.core.ranking

import com.github.pandeiro.tempore.slsk.core.domain.QualityClass
import com.github.pandeiro.tempore.slsk.core.domain.RemoteFile
import com.github.pandeiro.tempore.slsk.core.domain.ResultGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun flac(bitDepth: Int, sampleRate: Int) = RemoteFile(
    filename = "D\\Album\\track.flac",
    size = 10_000_000,
    extension = "flac",
    sampleRate = sampleRate,
    bitDepth = bitDepth,
)

private fun mp3(kbps: Int) = RemoteFile(
    filename = "D\\Album\\track.mp3",
    size = 10_000_000,
    extension = "mp3",
    lengthSeconds = 100,
    bitrateKbps = kbps,
)

private fun group(
    id: String,
    files: List<RemoteFile>,
    freeSlot: Boolean = false,
    uploadSpeed: Long = 0,
    queue: Int = 0,
) = ResultGroup(
    username = id,
    directory = "D\\Album",
    files = files,
    hasFreeUploadSlot = freeSlot,
    uploadSpeedBps = uploadSpeed,
    queueLength = queue,
)

class RankingEngineTest {

    private val engine = RankingEngine()

    @Test
    fun `lossless-first, 16slash44 before hi-res before lossy`() {
        val ranked = engine.rank(
            listOf(
                group("mp3-320", listOf(mp3(320))),
                group("hirez", listOf(flac(24, 96_000))),
                group("flac-16", listOf(flac(16, 44_100))),
            ),
        )
        assertEquals(listOf("flac-16", "hirez", "mp3-320"), ranked.map { it.username })
    }

    @Test
    fun `groups offer a mix so a peer with flac and mp3 ranks in the flac tier`() {
        val ranked = engine.rank(
            listOf(
                group("mixed", listOf(mp3(320), flac(16, 44_100))),
                group("only-mp3", listOf(mp3(320))),
            ),
        )
        assertEquals("mixed", ranked.first().username)
    }

    @Test
    fun `no free slot is pushed below a free-slot peer on quality tie`() {
        val busy = group("busy", listOf(mp3(320)), freeSlot = false, uploadSpeed = 999_999)
        val free = group("free", listOf(mp3(320)), freeSlot = true, uploadSpeed = 1)
        val ranked = engine.rank(listOf(busy, free))
        assertEquals(listOf("free", "busy"), ranked.map { it.username })
    }

    @Test
    fun `speed desc, queue asc, fileCount desc on ties`() {
        val slow = group("slow", listOf(mp3(320)), freeSlot = true, uploadSpeed = 10, queue = 0)
        val fast = group("fast", listOf(mp3(320)), freeSlot = true, uploadSpeed = 100, queue = 0)
        assertEquals(listOf("fast", "slow"), engine.rank(listOf(slow, fast)).map { it.username })

        val queued = group("queued", listOf(mp3(320)), freeSlot = true, uploadSpeed = 100, queue = 3)
        val idle = group("idle", listOf(mp3(320)), freeSlot = true, uploadSpeed = 100, queue = 1)
        assertEquals(listOf("idle", "queued"), engine.rank(listOf(queued, idle)).map { it.username })

        val fewer = group("fewer", files = listOf(mp3(320)), freeSlot = true, uploadSpeed = 100, queue = 1)
        val more = group(
            "more",
            files = listOf(mp3(320), mp3(320), mp3(320)),
            freeSlot = true, uploadSpeed = 100, queue = 1,
        )
        assertEquals(listOf("more", "fewer"), engine.rank(listOf(fewer, more)).map { it.username })
    }

    @Test
    fun `locked-only peers still rank (not hidden) but unknown quality is last`() {
        val unknown = group("unknown", listOf(RemoteFile("D\\x.wma", 1L, "wma")))
        val ranked = engine.rank(
            listOf(unknown, group("known", listOf(flac(16, 44_100)))),
        )
        assertEquals(listOf("known", "unknown"), ranked.map { it.username })
    }
}
