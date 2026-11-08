package com.github.pandeiro.tempore.slsk.core.api

import com.github.pandeiro.tempore.slsk.core.domain.Health
import com.github.pandeiro.tempore.slsk.core.domain.RemoteFile
import com.github.pandeiro.tempore.slsk.core.domain.StateTokens
import com.github.pandeiro.tempore.slsk.core.domain.Transfer
import com.github.pandeiro.tempore.slsk.core.domain.TransferGroup
import com.github.pandeiro.tempore.slsk.core.domain.TransferLifecycle
import kotlin.math.roundToInt

/**
 * Pure DTO -> domain mapping for slskd payloads. Lives here (not in the gateway) so it can
 * be unit-tested directly against the golden fixtures. Lossy bitrate is DERIVED from
 * size/length because slskd omits `bitRate` on live responses (SPIKE.md).
 *
 * All functions are(expression-free) pure and thread-safe.
 */
internal object SlskdMapper {

    fun toHealth(dto: ApplicationDto): Health {
        val full = dto.version?.full
        return Health(
            version = full?.takeUnless { it.isBlank() } ?: "unknown",
            isConnected = dto.server?.isConnected ?: false,
            isLoggedIn = dto.server?.isLoggedIn ?: false,
        )
    }

    fun toRemoteFile(dto: SearchFileDto, forceLocked: Boolean = false): RemoteFile? {
        val filename = dto.filename ?: return null
        val size = dto.size ?: 0L
        val rawExtension = dto.extension?.trim().orEmpty()
        val extension = rawExtension.ifEmpty { extensionFromFilename(filename) }
        val length = dto.length?.takeIf { it > 0 }
        val bitrate = when {
            dto.bitRate != null && dto.bitRate > 0 -> dto.bitRate
            length != null -> deriveBitrateKbps(size, length)
            else -> null
        }
        return RemoteFile(
            filename = filename,
            size = size,
            extension = extension,
            sampleRate = dto.sampleRate?.takeIf { it > 0 },
            bitDepth = dto.bitDepth?.takeIf { it > 0 },
            lengthSeconds = length,
            isLocked = forceLocked || (dto.isLocked ?: false),
            bitrateKbps = bitrate,
        )
    }

    /** Peer response -> (peer metadata, selectable files). Locked files stay visible but marked. */
    fun filesOf(response: SearchResponseDto): List<RemoteFile> {
        val regular = response.files.orEmpty().mapNotNull { toRemoteFile(it) }
        val locked = response.lockedFiles.orEmpty().mapNotNull { toRemoteFile(it, forceLocked = true) }
        return regular + locked
    }

    /**
     * slskd omits `bitRate` on live search responses (SPIKE.md/SPECS): derive kbps from
     * bytes*8/seconds, rounded to the nearest whole kbps. Returns null when length is unknown.
     */
    fun deriveBitrateKbps(sizeBytes: Long, lengthSeconds: Int): Int? {
        if (lengthSeconds <= 0 || sizeBytes < 0) return null
        return ((sizeBytes * 8.0) / lengthSeconds / 1000.0).roundToInt()
    }

    /** `Music\Artist\Album X (2007) Disc 01\08 Song.mp3` -> `mp3`; empty for no extension. */
    fun extensionFromFilename(filename: String): String {
        val name = filename.substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        return if (dot >= 0 && dot < name.length - 1) name.substring(dot + 1).lowercase() else ""
    }

    /**
     * Transfers DTO tree -> flat groups of [Transfer]. State strings are composite token
     * sets (`Completed, Succeeded`, `Queued, Remotely`, ...); see [lifecycleOf].
     */
    fun toTransferGroups(users: List<TransferUserDto>): List<TransferGroup> {
        return users.mapNotNull { user ->
            val username = user.username?.takeUnless { it.isBlank() } ?: return@mapNotNull null
            val dirs = user.directories.orEmpty().mapNotNull { dir ->
                val directory = dir.directory ?: return@mapNotNull null
                val transfers = dir.files.orEmpty().mapNotNull { toTransfer(it, username, directory) }
                if (transfers.isEmpty()) null else TransferGroup(
                    username = username,
                    directory = directory,
                    transfers = transfers,
                )
            }
            if (dirs.isEmpty()) null else dirs
        }.flatten()
    }

    fun toTransfer(dto: TransferFileDto, defaultUsername: String, defaultDirectory: String): Transfer? {
        val id = dto.id ?: return null
        val filename = dto.filename ?: return null
        val username = dto.username?.takeUnless { it.isBlank() } ?: defaultUsername
        val directory = if (defaultDirectory.isBlank()) directoryOf(filename) else defaultDirectory
        val tokens = StateTokens.parse(dto.state)
        return Transfer(
            id = id,
            username = username,
            directory = directory,
            filename = filename,
            size = dto.size ?: 0L,
            bytesTransferred = dto.bytesTransferred ?: 0L,
            rawState = dto.state.orEmpty(),
            lifecycle = lifecycleOf(tokens, dto.state),
            averageSpeedBps = dto.averageSpeed?.let { if (it.isFinite()) it.toLong() else null },
            percentComplete = dto.percentComplete?.let { if (it.isFinite()) it.roundToInt() else null },
            remainingTimeSeconds = parseTimeSpanSeconds(dto.remainingTime),
        )
    }

    /** `Directory` fallback when the tree did not carry one. */
    fun directoryOf(filename: String): String {
        val i = filename.lastIndexOf('\\')
        return if (i >= 0) filename.substring(0, i) else ""
    }

    fun lifecycleOf(tokens: List<String>, rawState: String?): TransferLifecycle = when {
        StateTokens.contains(tokens, "Completed", "Succeeded") -> TransferLifecycle.SUCCEEDED
        StateTokens.contains(tokens, "Completed", "Aborted") -> TransferLifecycle.FAILED
        rawState != null && rawState.contains("Downloading", ignoreCase = true) -> TransferLifecycle.DOWNLOADING
        StateTokens.isDownloading(tokens) -> TransferLifecycle.DOWNLOADING
        StateTokens.isQueuedRemote(tokens) -> TransferLifecycle.QUEUED_REMOTE
        StateTokens.isQueuedLocally(tokens) -> TransferLifecycle.QUEUED_LOCAL
        StateTokens.contains(tokens, "Requested") -> TransferLifecycle.QUEUED_LOCAL
        StateTokens.failed(tokens) -> TransferLifecycle.FAILED
        StateTokens.contains(tokens, "Cancelled") -> TransferLifecycle.CANCELLED
        else -> TransferLifecycle.UNKNOWN
    }

    /**
     * Parses .NET TimeSpan strings seen on live transfers (`00:00:05.9661420`,
     * `1.02:03:04.5`, `00:10:00`). Returns null for null/blank/unparseable.
     */
    fun parseTimeSpanSeconds(raw: String?): Long? {
        val value = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val negative = value.startsWith("-") || value.startsWith("+")
        val body = if (value[0] == '-' || value[0] == '+') value.substring(1) else value
        val colonCount = body.count { it == ':' }
        // `.NET always prints at least h:mm:ss for non-independent values; a leading `d.`
        // (day) adds a fourth field. A TimeStamp-like `<hh:mm:ss>.<fraction>` splits at the
        // FIRST '.', which can only be the decimal separator or the day boundary.
        val dot = body.indexOf('.')
        val whole = if (dot >= 0) body.substring(0, dot) else body
        val parts = whole.split(':')
        if (parts.size !in 2..4) return null
        val day = if (parts.size == 4) parts[0].toLongOrNull() ?: return null else 0L
        val clock = if (parts.size == 4) parts.drop(1) else parts
        val hours = clock[0].toLongOrNull() ?: return null
        val minutes = clock[1].toLongOrNull() ?: return null
        val seconds = clock.getOrNull(2)?.toLongOrNull() ?: 0L
        val sign = if (negative) -1L else 1L
        return sign * (((day * 24 + hours) * 60 + minutes) * 60 + seconds)
    }
}
