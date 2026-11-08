package com.github.pandeiro.tempore.slsk.core.ranking

import com.github.pandeiro.tempore.slsk.core.domain.FileAllowlist
import com.github.pandeiro.tempore.slsk.core.domain.RemoteFile
import com.github.pandeiro.tempore.slsk.core.domain.ResultGroup

/**
 * Groups peer files into display units keyed by (username, directory), honouring the
 * extension allowlist and the locked-files presentation rule.
 *
 * Presentation rules:
 *  - disallowed extensions (exe/zip/m3u/nfo/...) are HIDDEN entirely and never enqueued;
 *  - locked files REMAIN VISIBLE but stay marked ([RemoteFile.isLocked] == true);
 *  - a group only survives when it still holds at least one visible file.
 */
open class GroupingEngine {

    /** Filtered per-directory groups. Order is stable: alphabetical by (username, directory). */
    fun group(groups: List<PeerFiles>): List<ResultGroup> {
        val combined = LinkedHashMap<Pair<String, String>, MutableList<RemoteFile>>()
        val peerMeta = HashMap<String, PeerFiles>()
        for (peer in groups) {
            val username = peer.username ?: continue
            if (peerMeta.putIfAbsent(username, peer) == null) peerMeta[username] = peer
            for (file in peer.files) {
                val directory = file.directory
                val bucket = combined.getOrPut(username to directory) { mutableListOf() }
                bucket.add(file)
            }
        }
        val result = ArrayList<ResultGroup>()
        for ((key, files) in combined) {
            val peer = requireNotNull(peerMeta[key.first]) { "peer meta vanished for ${key.first}" }
            val visible = files.filter { FileAllowlist.isAllowed(it.extension) }
            if (visible.isEmpty()) continue // whole folder is executables/archives → hidden
            result.add(
                ResultGroup(
                    username = key.first,
                    directory = key.second,
                    files = visible,
                    hasFreeUploadSlot = peer.hasFreeUploadSlot,
                    uploadSpeedBps = peer.uploadSpeedBps,
                    queueLength = peer.queueLength,
                )
            )
        }
        return result.sortedWith(compareBy({ it.username }, { it.directory }))
    }

    /** Per-peer input snapshot produced from a search response. */
    data class PeerFiles(
        val username: String?,
        val files: List<RemoteFile>,
        val hasFreeUploadSlot: Boolean,
        val uploadSpeedBps: Long,
        val queueLength: Int,
    )

    companion object {

        /**
         * Display title from a directory path: prefers the last 1–2 path segments with
         * bracketed noise (`[FLAC]`, `(2007)`), disc/cd/media/volume tokens and stray
         * separators stripped. `…\In Rainbows (2007) Disc 01` -> `In Rainbows`. Pure-noise
         * segments ("Digital Media 01") climb one level.
         */
        fun titleFor(directory: String): String {
            val segments = directory.split('\\', '/')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (segments.isEmpty()) return directory.trim()
            for (segment in segments.reversed().take(2)) {
                GroupingEngine.asTitle(segment).takeIf { it.isNotEmpty() }?.let { return it }
            }
            // Everything within reach was noise: fall back to the raw last segment.
            return segments.last()
        }

        private val brackets = Regex("[(\\[{<][^)}\\]>]*[)}\\]>]")
        private val parenNoise = Regex("\\([^)]*\\)")
        private val discToken = Regex(
            "(?i)\\b(disc|cd|media|part|pt|volume|vol|side|lp)\\s*[0-9ivx]+\\b",
        )
        private val yearToken = Regex("\\b(19|20)[0-9]{2}\\b")
        private val separatorNoise = Regex("\\s{2,}")
        /** Segment heads that name nothing (scene/sort labels under which the real album hides). */
        private val noiseSegment = Regex(
            "(?i)^(digital|cd|disc|media|part|pt|volume|vol|side|lp|ep|web|scene|album|" +
                "remaster(ed)?|bonus|unreleased|24\\s*bit|16\\s*bit|hi-?res|lossless|mp3|flac|[0-9]+(\\.\\d+)?)$",
        )

        internal fun cleanSegment(segment: String): String {
            var cleaned = segment
            cleaned = brackets.replace(cleaned, " ")
            cleaned = discToken.replace(cleaned, " ")
            cleaned = yearToken.replace(cleaned, " ")
            // A trailing/wrapping `(2007)`-style parenthetical that survived year-stripping.
            cleaned = parenNoise.replace(cleaned, " ")
            cleaned = cleaned.trim().trimEnd('-', '_', '.', ' ', '\t')
            cleaned = separatorNoise.replace(cleaned, " ").trim()
            return cleaned
        }

        internal fun asTitle(segment: String): String {
            val cleaned = cleanSegment(segment)
            // Pure noise (e.g. "Digital Media 01") yields no title → let the caller climb.
            return if (cleaned.isEmpty() || noiseSegment.matches(cleaned)) "" else cleaned
        }
    }
}
