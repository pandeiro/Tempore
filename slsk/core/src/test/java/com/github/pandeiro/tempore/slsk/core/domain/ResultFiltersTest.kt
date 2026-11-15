package com.github.pandeiro.tempore.slsk.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultFiltersTest {

    private fun file(ext: String, kbps: Int? = null) =
        RemoteFile(filename = "Music\\Album\\01.$ext", size = 1L, extension = ext, bitrateKbps = kbps)

    private fun group(vararg files: RemoteFile, freeSlot: Boolean = true) =
        ResultGroup("user", "Music\\Album", files.toList(), freeSlot, 0L, 0)

    @Test
    fun `default filters pass every group including image-only and no-slot`() {
        val f = SlskResultFilters()
        assertTrue(f.matchesGroup(group(file("jpg"))))
        assertTrue(f.matchesGroup(group(file("mp3", 128), freeSlot = false)))
    }

    @Test
    fun `single extension selection keeps only that type`() {
        val f = SlskResultFilters(extensions = setOf("flac"))
        assertTrue(f.matchesFile(file("flac")))
        assertFalse(f.matchesFile(file("mp3", 320)))
        assertFalse(f.matchesGroup(group(file("mp3", 320), file("jpg"))))
    }

    @Test
    fun `lossless files ignore bitrate bounds`() {
        val f = SlskResultFilters(bitrateMinKbps = 320)
        assertTrue(f.matchesFile(file("flac")))
        assertTrue(f.matchesFile(file("wav", 100)))
    }

    @Test
    fun `lossy files are bounded inclusively`() {
        val f = SlskResultFilters(bitrateMinKbps = 192, bitrateMaxKbps = 320)
        assertTrue(f.matchesFile(file("mp3", 192)))
        assertTrue(f.matchesFile(file("ogg", 320)))
        assertFalse(f.matchesFile(file("mp3", 191)))
        assertFalse(f.matchesFile(file("opus", 321)))
    }

    @Test
    fun `lossy with unknown bitrate is excluded once a bound is set`() {
        assertFalse(SlskResultFilters(bitrateMaxKbps = 320).matchesFile(file("mp3", null)))
        assertTrue(SlskResultFilters().matchesFile(file("mp3", null)))
    }

    @Test
    fun `flac plus min 320 keeps flac but drops 256 mp3`() {
        val f = SlskResultFilters(extensions = setOf("flac", "mp3"), bitrateMinKbps = 320)
        assertTrue(f.matchesFile(file("flac")))
        assertFalse(f.matchesFile(file("mp3", 256)))
    }

    @Test
    fun `group matches when any single file passes all constraints`() {
        val f = SlskResultFilters(extensions = setOf("mp3"), bitrateMinKbps = 320)
        assertTrue(f.matchesGroup(group(file("mp3", 128), file("mp3", 320))))
        assertFalse(f.matchesGroup(group(file("mp3", 128), file("flac"))))
    }

    @Test
    fun `free slot only rejects peers without a slot`() {
        val f = SlskResultFilters(freeSlotOnly = true)
        assertFalse(f.matchesGroup(group(file("flac"), freeSlot = false)))
        assertTrue(f.matchesGroup(group(file("flac"), freeSlot = true)))
    }

    @Test
    fun `swapped bounds behave as the same range and normalize`() {
        val f = SlskResultFilters(bitrateMinKbps = 320, bitrateMaxKbps = 192)
        assertTrue(f.matchesFile(file("mp3", 256)))
        val n = f.normalized()
        assertEquals(192, n.bitrateMinKbps)
        assertEquals(320, n.bitrateMaxKbps)
    }

    @Test
    fun `extension matching is case-insensitive`() {
        val f = SlskResultFilters(extensions = setOf("FLAC"))
        assertTrue(f.matchesFile(file("flac")))
        assertTrue(SlskResultFilters(extensions = setOf("mp3")).matchesFile(file("MP3", 320)))
        assertEquals(setOf("flac"), f.normalized().extensions)
    }
}
