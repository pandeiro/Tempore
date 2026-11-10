package com.github.pandeiro.tempore.slsk.core.api

import com.github.pandeiro.tempore.slsk.core.domain.QualityClass
import com.github.pandeiro.tempore.slsk.core.domain.RemoteFile
import com.github.pandeiro.tempore.slsk.core.domain.StateTokens
import com.github.pandeiro.tempore.slsk.core.domain.TransferLifecycle
import com.github.pandeiro.tempore.slsk.core.ranking.GroupingEngine
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-sample round trips against slsk/contract/fixtures (copied into test resources):
 * parse with the production Json settings, map, group, and assert the quality/lifecycle
 * outcomes the live slskd 0.26.1 payload produces.
 */
class FixtureRoundTripTest {

    private val searchResponses: List<SearchResponseDto> =
        SlskdApi.json.decodeFromString(
            ListSerializer(SearchResponseDto.serializer()),
            resource("/fixtures/searches.responses.0.26.1.json"),
        )

    private val transfers: List<TransferUserDto> =
        SlskdApi.json.decodeFromString(
            ListSerializer(TransferUserDto.serializer()),
            resource("/fixtures/transfers.downloads.0.26.1.json"),
        )

    private val searchState: SearchStateDto =
        SlskdApi.json.decodeFromString(
            SearchStateDto.serializer(),
            resource("/fixtures/searches.state.completed.0.26.1.json"),
        )

    private fun resource(path: String): String =
        javaClass.getResourceAsStream(path)!!.readBytes().decodeToString()

    private val allFiles: List<SearchFileDto> =
        searchResponses.flatMap { it.files.orEmpty() + it.lockedFiles.orEmpty() }

    @Test
    fun `fixture parses with the production Json settings`() {
        assertEquals(25, searchResponses.size)
        assertTrue(allFiles.size > 300)
    }

    @Test
    fun `flac 16slash44 classifies LOSSLESS_16_44`() {
        val fixtureFlac = searchResponses.asSequence()
            .flatMap { it.files.orEmpty().asSequence() }
            .firstOrNull { it.sampleRate == 44_100 && it.bitDepth == 16 && it.extension == "flac" }
        requireNotNull(fixtureFlac)
        val mapped = SlskdMapper.toRemoteFile(fixtureFlac)!!
        assertEquals(QualityClass.LOSSLESS_16_44, mapped.qualityClass)
        assertTrue(mapped.isLossless)
    }

    @Test
    fun `hi-res depth24 or rate96k classifies LOSSLESS_HIREZ`() {
        val hires = allFiles.filterMapped()
            .first { it.bitDepth == 24 || (it.sampleRate ?: 0) > 48_000 }
        assertEquals(QualityClass.LOSSLESS_HIREZ, hires.qualityClass)
    }

    @Test
    fun `mp3 files with empty extension derive quality from bitrate`() {
        val mp3 = allFiles.first { SlskdMapper.extensionFromFilename(it.filename!!) == "mp3" }
        assertEquals("", mp3.extension) // fixture quirk: empty extension field
        val mapped = SlskdMapper.toRemoteFile(mp3)!!
        assertEquals("mp3", mapped.extension)
        assertEquals(QualityClass.MP3_320, mapped.qualityClass)
        assertEquals(320, mp3.bitRate)
    }

    @Test
    fun `lossy and archive entries flow through mapping without crashing`() {
        val mappedAll = allFiles.mapNotNull { SlskdMapper.toRemoteFile(it) }
        assertTrue(mappedAll.isNotEmpty())
        // Non-audio/non-image entries still map (they are filtered later by the allowlist),
        // but never end up lossless or carry a bitrate.
        val archives = mappedAll.filter { it.extension in listOf("nfo", "m3u") }
        assertTrue(archives.isNotEmpty())
        assertTrue(archives.none { it.isLossless })
        assertTrue(archives.none { it.qualityClass != QualityClass.UNKNOWN })
    }

    // ------------------------------------------------------------- grouping

    @Test
    fun `grouping produces groups per directory with allowlist applied`() {
        val peers = searchResponses.map {
            GroupingEngine.PeerFiles(
                username = it.username,
                files = SlskdMapper.filesOf(it),
                hasFreeUploadSlot = it.hasFreeUploadSlot ?: false,
                uploadSpeedBps = it.uploadSpeed ?: 0,
                queueLength = it.queueLength ?: 0,
            )
        }
        val groups = GroupingEngine().group(peers)
        assertTrue(groups.isNotEmpty())
        // usernames aliased PEERxx
        assertTrue(groups.all { it.username.startsWith("PEER") })
        // extension allowlist: no nfo/m3u files anywhere
        assertTrue(groups.all { g -> g.files.all { it.extension !in listOf("nfo", "m3u") } })
        // flac files present
        assertTrue(groups.any { g -> g.files.any { it.extension == "flac" } })
        // jpg cover kept (allowed)
        assertTrue(groups.any { g -> g.files.any { it.extension == "jpg" } })
        // group key = (username, directory); every group unique
        assertEquals(groups.map { it.username to it.directory }.distinct().size, groups.size)
    }

    // ------------------------------------------------------------- transfers

    @Test
    fun `Completed Succeeded maps to SUCCEEDED in transfers fixture`() {
        val groups = SlskdMapper.toTransferGroups(transfers)
        val states = groups.flatMap { it.transfers }.map { it.lifecycle }
        assertTrue(TransferLifecycle.SUCCEEDED in states)
        // Redacted fixture usernames carry through
        assertTrue(groups.all { it.username.isNotBlank() })
    }

    @Test
    fun `Completed Aborted maps to FAILED`() {
        val groups = SlskdMapper.toTransferGroups(transfers)
        val aborted = groups.flatMap { it.transfers }.filter { it.rawState.contains("Aborted") }
        assertTrue(aborted.isNotEmpty())
        assertTrue(aborted.all { it.lifecycle == TransferLifecycle.FAILED })
        // no accidental success classification
        assertTrue(aborted.none { it.lifecycle == TransferLifecycle.SUCCEEDED })
    }

    @Test
    fun `state fixture parses Completed ResponseLimitReached`() {
        assertEquals(true, searchState.isComplete)
        val tokens = StateTokens.parse(searchState.state)
        assertTrue(StateTokens.contains(tokens, "Completed", "ResponseLimitReached"))
        assertTrue(!StateTokens.failed(tokens))
        assertEquals(0, searchState.responses?.size) // state-only snapshot carries no responses
    }

    private fun List<SearchFileDto>.filterMapped(): List<RemoteFile> =
        mapNotNull { SlskdMapper.toRemoteFile(it) }
}
