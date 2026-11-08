package com.github.pandeiro.tempore.slsk.core.domain

/** Formats allowed to be shown/enqueued; everything else is hidden and never enqueued. */
object FileAllowlist {
    val AUDIO = setOf("flac", "mp3", "m4a", "ogg", "opus", "wav", "aiff", "wv", "ape")
    val IMAGE = setOf("jpg", "jpeg", "png", "gif", "bmp")
    fun isAllowed(extension: String?): Boolean {
        val e = extension?.lowercase() ?: return false
        return e in AUDIO || e in IMAGE
    }

    /** True for files we note but never show as actionable results. */
    fun isExecutableLike(extension: String?): Boolean {
        val e = extension?.lowercase() ?: return false
        return e !in AUDIO && e !in IMAGE
    }
}

enum class QualityClass {
    LOSSLESS_16_44, LOSSLESS_HIREZ, MP3_320, MP3_VBR, LOSSY_OTHER, UNKNOWN
}

/** One peer-shared file. Paths are Soulseek-style, backslash-separated regardless of peer OS. */
data class RemoteFile(
    val filename: String,
    val size: Long,
    val extension: String,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val lengthSeconds: Int? = null,
    val isLocked: Boolean = false,
    val bitrateKbps: Int? = null,
) {
    /** Directory prefix: everything before the last backslash segment. */
    val directory: String by lazy {
        val i = filename.lastIndexOf('\\')
        if (i >= 0) filename.substring(0, i) else ""
    }

    val isLossless: Boolean by lazy { extension.lowercase() in losslessExts }
    val qualityClass: QualityClass by lazy {
        when {
            isLossless ->
                if (bitDepth == null) QualityClass.LOSSLESS_16_44
                else if (bitDepth > 16 || (sampleRate ?: 44_100) > 48_000) QualityClass.LOSSLESS_HIREZ
                else QualityClass.LOSSLESS_16_44

            bitrateKbps == null -> if (lengthSeconds == null || lengthSeconds <= 0) QualityClass.UNKNOWN
            else QualityClass.LOSSY_OTHER
            bitrateKbps >= 320 -> QualityClass.MP3_320
            bitrateKbps >= 190 -> QualityClass.MP3_VBR
            else -> QualityClass.LOSSY_OTHER
        }
    }

    companion object {
        val losslessExts = setOf("flac", "wav", "aiff", "wv", "ape")
    }
}

/** Result grouping key: (username, directory). */
data class ResultGroup(
    val username: String,
    val directory: String,
    val files: List<RemoteFile>,
    val hasFreeUploadSlot: Boolean,
    val uploadSpeedBps: Long,
    val queueLength: Int,
) {
    val allowedFiles: List<RemoteFile> by lazy { files.filter { FileAllowlist.isAllowed(it.extension) } }
}

/** Live slskd state, parsed as token sets. */
data class Health(
    val version: String,
    val isConnected: Boolean,
    val isLoggedIn: Boolean,
)

/** Snapshot of a running/finished search. */
data class SearchProgress(
    val searchId: String,
    val stateTokens: List<String>,
    val isComplete: Boolean,
    val responseCount: Int,
    val fileCount: Int,
    val groups: List<ResultGroup>,
)

object StateTokens {
    fun parse(rawState: String?): List<String> =
        rawState?.split(',')?.map { it.trim() }.orEmpty()

    fun contains(tokens: List<String>, vararg matches: String) =
        matches.all { m -> tokens.any { it.equals(m, ignoreCase = true) } }

    fun succeeded(tokens: List<String>) = contains(tokens, "Completed", "Succeeded")
    fun failed(tokens: List<String>) = contains(tokens, "Aborted", "Rejected", "Errored", "TimedOut")
    fun isQueuedRemote(tokens: List<String>) = contains(tokens, "Queued", "Remotely")
    fun isQueuedLocally(tokens: List<String>) = contains(tokens, "Queued") && !isQueuedRemote(tokens)
    fun isDownloading(tokens: List<String>) = tokens.contains("Downloading") || tokens.contains("Transferring")
}
