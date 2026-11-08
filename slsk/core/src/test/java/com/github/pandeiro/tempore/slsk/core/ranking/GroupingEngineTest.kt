package com.github.pandeiro.tempore.slsk.core.ranking

import com.github.pandeiro.tempore.slsk.core.domain.RemoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun file(
    name: String,
    ext: String = "flac",
    size: Long = 1_000_000,
    locked: Boolean = false,
): RemoteFile = RemoteFile(
    filename = "Music\\Artist\\Album\\$name.$ext",
    size = size,
    extension = ext,
    isLocked = locked,
)

class GroupingEngineTest {

    private val engine = GroupingEngine()

    @Test
    fun `groups by username and directory`() {
        val groups = engine.group(
            listOf(
                GroupingEngine.PeerFiles(
                    username = "PEER01",
                    files = listOf(
                        file("01 15 Step.flac"),
                        RemoteFile("Music\\Artist\\Album Deluxe\\02 Bodysnatchers.flac", 1L, "flac"),
                        file("03 Nude.flac"),
                    ),
                    hasFreeUploadSlot = true,
                    uploadSpeedBps = 100_000,
                    queueLength = 0,
                ),
                GroupingEngine.PeerFiles(
                    username = "PEER02",
                    files = listOf(file("01 15 Step.flac")),
                    hasFreeUploadSlot = false,
                    uploadSpeedBps = 50_000,
                    queueLength = 5,
                ),
            )
        )
        assertEquals(listOf("Music\\Artist\\Album", "Music\\Artist\\Album Deluxe", "Music\\Artist\\Album"), groups.map { it.directory })
        assertEquals(listOf("PEER01", "PEER01", "PEER02"), groups.map { it.username })
        assertTrue(groups[0].hasFreeUploadSlot)
        assertEquals(100_000, groups[0].uploadSpeedBps)
        assertEquals(0, groups[0].queueLength)
    }

    @Test
    fun `disallowed extensions are hidden`() {
        val groups = engine.group(
            listOf(
                GroupingEngine.PeerFiles(
                    username = "PEER01",
                    files = listOf(
                        file("album.nfo", "nfo"),
                        file("setup.exe", "exe"),
                        file("bundle.zip", "zip"),
                        file("playlist.m3u", "m3u"),
                        file("01 Song.flac", "flac"),
                    ),
                    hasFreeUploadSlot = true,
                    uploadSpeedBps = 1,
                    queueLength = 0,
                ),
            )
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("flac"), groups[0].files.map { it.extension })
        assertEquals(groups[0].files, groups[0].allowedFiles)
    }

    @Test
    fun `a directory with no allowed file is dropped entirely`() {
        val groups = engine.group(
            listOf(
                GroupingEngine.PeerFiles(
                    username = "PEER01",
                    files = listOf(file("setup.exe", "exe"), file("notes.zip", "zip")),
                    hasFreeUploadSlot = true,
                    uploadSpeedBps = 1,
                    queueLength = 0,
                ),
            )
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `locked files stay visible but marked and never become allowedFiles only`() {
        val groups = engine.group(
            listOf(
                GroupingEngine.PeerFiles(
                    username = "PEER01",
                    files = listOf(
                        file("lockedtrack.flac", locked = true),
                        file("freetrack.flac"),
                    ),
                    hasFreeUploadSlot = true,
                    uploadSpeedBps = 1,
                    queueLength = 0,
                ),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].files.size)
        assertEquals(2, groups[0].allowedFiles.size)
        val lockedFile = groups[0].files.first { it.filename.contains("lockedtrack") }
        assertEquals(true, lockedFile.isLocked)
    }

    // ------------------------------------------------------------ title cleaning

    @Test
    fun `title strips brackets years and disc tokens`() {
        assertEquals("In Rainbows", GroupingEngine.titleFor("Radiohead\\In Rainbows (2007) Disc 01"))
        assertEquals("In Rainbows", GroupingEngine.titleFor("Music\\!Sorted\\In Rainbows (2007)\\Digital Media 01"))
        assertEquals("In Rainbows", GroupingEngine.titleFor("Radiohead\\In Rainbows [FLAC]"))
        assertEquals(
            "Pink Floyd - Dark Side of the Moon",
            GroupingEngine.titleFor("Pink Floyd\\Pink Floyd - Dark Side of the Moon - 1973 CD 02"),
        )
        assertEquals("Midnight Marauders", GroupingEngine.titleFor("@complete\\A Tribe Called Quest\\Midnight Marauders"))
    }
}
