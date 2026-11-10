package com.github.pandeiro.tempore.slsk.ui

import com.github.pandeiro.tempore.slsk.core.domain.QualityClass
import com.github.pandeiro.tempore.slsk.core.domain.RemoteFile
import com.github.pandeiro.tempore.slsk.core.domain.ResultGroup

/**
 * Ranking + filtering for result groups (spec §5.4). Default sort: preferred format
 * → free upload slot → upload speed → shorter queue → more files.
 */
object SlskRanking {

    fun qualityRank(quality: QualityClass): Int = when (quality) {
        QualityClass.LOSSLESS_16_44 -> 0
        QualityClass.LOSSLESS_HIREZ -> 1
        QualityClass.MP3_320 -> 2
        QualityClass.MP3_VBR -> 3
        QualityClass.LOSSY_OTHER -> 4
        QualityClass.UNKNOWN -> 5
    }

    fun label(quality: QualityClass): String = when (quality) {
        QualityClass.LOSSLESS_16_44 -> "Lossless 16/44.1"
        QualityClass.LOSSLESS_HIREZ -> "Lossless hi-res"
        QualityClass.MP3_320 -> "MP3 320"
        QualityClass.MP3_VBR -> "MP3 VBR"
        QualityClass.LOSSY_OTHER -> "Lossy"
        QualityClass.UNKNOWN -> "Unknown"
    }

    /** Best quality for the pill: same preference order as core's RankingEngine. */
    fun bestQuality(group: ResultGroup): QualityClass =
        group.allowedFiles.minByOrNull { qualityRank(it.qualityClass) }?.qualityClass ?: QualityClass.UNKNOWN

    /** Default §5.4 sort is owned by core's `RankingEngine`; delegate, don't duplicate. */
    fun sort(groups: List<ResultGroup>): List<ResultGroup> =
        com.github.pandeiro.tempore.slsk.core.ranking.RankingEngine().rank(groups)

    fun firstLineLabel(group: ResultGroup): String = firstLinePath(group.directory)

    fun firstLinePath(directory: String): String {
        val dir = directory
        val segments = dir.split('\\').filter { it.isNotBlank() }
        val tail = segments.takeLast(2).joinToString(" \\ ")
        return tail.ifBlank { dir }
    }
}

/**
 * Filter set controlled from the results toolbar menu.
 */
data class SlskResultFilters(
    val losslessOnly: Boolean = false,
    val min320Kbps: Boolean = false,
    val freeSlotOnly: Boolean = false,
) {
    fun matches(group: ResultGroup): Boolean {
        if (freeSlotOnly && !group.hasFreeUploadSlot) return false
        if (losslessOnly && group.allowedFiles.none { it.isLossless }) return false
        if (min320Kbps && group.allowedFiles.none { (it.bitrateKbps ?: 0) >= 320 }) return false
        return true
    }

    val activeCount: Int get() = listOf(losslessOnly, min320Kbps, freeSlotOnly).count { it }

    fun toTitleSuffix(): String = when (activeCount) {
        0 -> ""
        1, 2, 3 -> " (\u2713 $activeCount)"
        else -> ""
    }
}

/** Human size. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.1f GB".format(gb)
        mb >= 1.0 -> "%.0f MB".format(mb)
        kb >= 1.0 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}

/** Human speed. */
fun formatSpeed(bytesPerSecond: Long): String = if (bytesPerSecond <= 0) {
    ""
} else {
    formatBytes(bytesPerSecond) + "/s"
}

/** Clean score title: strips brackets and years per spec §5.4. */
fun cleanTitle(raw: String): String =
    raw.replace(Regex("\\((?:19|20)\\d{2}\\)"), " ")
        .replace(Regex("\\[[^\\]]*]"), " ")
        .replace(Regex("_{2,}"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim(' ', '-', '.', '_')
