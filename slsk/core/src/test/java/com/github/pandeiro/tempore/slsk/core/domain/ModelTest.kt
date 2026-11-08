package com.github.pandeiro.tempore.slsk.core.domain

import com.github.pandeiro.tempore.slsk.core.api.SlskdMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    // ------------------------------------------------------------ StateTokens

    @Test
    fun `Completed Succeeded parses to succeeded`() {
        val tokens = StateTokens.parse("Completed, Succeeded")
        assertTrue(StateTokens.succeeded(tokens))
        assertTrue(!StateTokens.failed(tokens))
    }

    @Test
    fun `Completed Aborted parses to failed`() {
        val tokens = StateTokens.parse("Completed, Aborted")
        assertTrue(StateTokens.failed(tokens))
        assertTrue(!StateTokens.succeeded(tokens))
    }

    @Test
    fun `Queued Remotely vs local queue`() {
        val remote = StateTokens.parse("Queued, Remotely")
        assertTrue(StateTokens.isQueuedRemote(remote))
        assertTrue(!StateTokens.isQueuedLocally(remote))

        val local = StateTokens.parse("Queued, Locally")
        assertTrue(StateTokens.isQueuedLocally(local))
        assertTrue(!StateTokens.isQueuedRemote(local))

        assertTrue(StateTokens.isQueuedLocally(StateTokens.parse("Queued")))
    }

    @Test
    fun `response limit state is not a failure`() {
        val tokens = StateTokens.parse("Completed, ResponseLimitReached")
        assertTrue(StateTokens.contains(tokens, "Completed", "ResponseLimitReached"))
        assertTrue(!StateTokens.failed(tokens))
    }

    @Test
    fun `null state parses to empty without crashing`() {
        assertTrue(StateTokens.parse(null).isEmpty())
    }

    // ------------------------------------------------------------ quality classes

    @Test
    fun `flac 16slash44 is LOSSLESS_16_44`() {
        val file = RemoteFile(
            filename = "Radiohead\\In Rainbows (2007)\\01 15 Step.flac",
            size = 27_046_286,
            extension = "flac",
            sampleRate = 44_100,
            bitDepth = 16,
            lengthSeconds = 237,
        )
        assertEquals(QualityClass.LOSSLESS_16_44, file.qualityClass)
        assertTrue(file.isLossless)
    }

    @Test
    fun `hi-res files are LOSSLESS_HIREZ`() {
        val hires24 = RemoteFile(
            "x.flac", 1L, "flac", sampleRate = 44_100, bitDepth = 24,
        )
        assertEquals(QualityClass.LOSSLESS_HIREZ, hires24.qualityClass)

        val hires96 = RemoteFile(
            "x.flac", 1L, "flac", sampleRate = 96_000, bitDepth = 16,
        )
        assertEquals(QualityClass.LOSSLESS_HIREZ, hires96.qualityClass)
    }

    @Test
    fun `derived bitrate path maps to 320 and vbr and other`() {
        // (size*8/seconds) rounded: 9 620 671 bytes / 238 s -> 323 kbps (fixture mp3 @320).
        assertEquals(323, SlskdMapper.deriveBitrateKbps(9_620_671, 238))
        val c320 = RemoteFile("y.mp3", 9_620_671L, "mp3", lengthSeconds = 238, bitrateKbps = SlskdMapper.deriveBitrateKbps(9_620_671, 238))
        assertEquals(QualityClass.MP3_320, c320.qualityClass)

        // 7 000 000 bytes / 238 s -> 235 kbps -> VBR band (>=190).
        val vbr = RemoteFile("y.mp3", 7_000_000L, "mp3", lengthSeconds = 238, bitrateKbps = SlskdMapper.deriveBitrateKbps(7_000_000, 238))
        assertEquals(QualityClass.MP3_VBR, vbr.qualityClass)

        // 2 000 000 bytes / 238 s -> 67 kbps -> other lossy.
        val low = RemoteFile("y.mp3", 2_000_000L, "mp3", lengthSeconds = 238, bitrateKbps = SlskdMapper.deriveBitrateKbps(2_000_000, 238))
        assertEquals(QualityClass.LOSSY_OTHER, low.qualityClass)
    }

    @Test
    fun `explicit bitRate wins over derivation`() {
        val file = RemoteFile("z.mp3", 1L, "mp3", lengthSeconds = 100, bitrateKbps = 320)
        assertEquals(QualityClass.MP3_320, file.qualityClass)
    }

    @Test
    fun `lossy without derivable bitrate is unknown`() {
        val file = RemoteFile("z.mp3", 1_000L, "mp3")
        assertEquals(QualityClass.UNKNOWN, file.qualityClass)
    }

    @Test
    fun `file allowlist hides executable-like archives`() {
        assertTrue(FileAllowlist.isAllowed("flac"))
        assertTrue(FileAllowlist.isAllowed("JPG"))
        assertTrue(!FileAllowlist.isAllowed("exe"))
        assertTrue(!FileAllowlist.isAllowed("zip"))
        assertTrue(FileAllowlist.isExecutableLike("exe"))
        assertTrue(!FileAllowlist.isAllowed(null))
    }

    // ------------------------------------------------------------ directory parsing

    @Test
    fun `directory strips after last backslash`() {
        val file = RemoteFile("Music\\Artist\\Album\\01 Song.mp3", 1L, "mp3")
        assertEquals("Music\\Artist\\Album", file.directory)
    }
}
