package com.github.pandeiro.tempore.slsk.core.domain

/**
 * Persistent result filters (spec §2.3 / §5.4). A group is shown when it contains at
 * least one file passing EVERY enabled constraint:
 *  - [extensions]: selected audio types; empty = every [FileAllowlist.AUDIO] type;
 *  - [bitrateMinKbps]/[bitrateMaxKbps]: inclusive bounds on the client-derived bitrate of
 *    LOSSY files only (lossless files are never bitrate-constrained; a lossy file with an
 *    unknown bitrate is excluded once any bound is set);
 *  - [freeSlotOnly]: peer-level, the group's user must have a free upload slot.
 * Defaults pass everything. Swapped bounds are treated as the same range.
 */
@kotlinx.serialization.Serializable
data class SlskResultFilters(
    val extensions: Set<String> = emptySet(),
    val bitrateMinKbps: Int? = null,
    val bitrateMaxKbps: Int? = null,
    val freeSlotOnly: Boolean = false,
) {
    private val hasFileConstraints: Boolean
        get() = extensions.isNotEmpty() || bitrateMinKbps != null || bitrateMaxKbps != null

    fun matchesFile(file: RemoteFile): Boolean {
        val ext = file.extension.lowercase()
        val typeOk = if (extensions.isEmpty()) {
            ext in FileAllowlist.AUDIO
        } else {
            extensions.any { it.equals(ext, ignoreCase = true) }
        }
        if (!typeOk) return false
        if (file.isLossless || (bitrateMinKbps == null && bitrateMaxKbps == null)) return true
        val bitrate = file.bitrateKbps ?: return false
        val a = bitrateMinKbps ?: 0
        val b = bitrateMaxKbps ?: Int.MAX_VALUE
        return bitrate in minOf(a, b)..maxOf(a, b)
    }

    fun matchesGroup(group: ResultGroup): Boolean {
        if (freeSlotOnly && !group.hasFreeUploadSlot) return false
        if (!hasFileConstraints) return true
        return group.allowedFiles.any(::matchesFile)
    }

    /** Canonical form for persistence: lowercase extensions, ordered bounds. */
    fun normalized(): SlskResultFilters {
        val lo = bitrateMinKbps
        val hi = bitrateMaxKbps
        val swap = lo != null && hi != null && lo > hi
        return copy(
            extensions = extensions.mapTo(sortedSetOf()) { it.lowercase() },
            bitrateMinKbps = if (swap) hi else lo,
            bitrateMaxKbps = if (swap) lo else hi,
        )
    }
}
