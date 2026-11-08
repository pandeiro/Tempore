package com.github.pandeiro.tempore.slsk.core.ranking

import com.github.pandeiro.tempore.slsk.core.domain.QualityClass
import com.github.pandeiro.tempore.slsk.core.domain.ResultGroup

/**
 * Ranks result groups for the Soulseek results screen.
 *
 * Default preference:
 *  1. best quality class first — lossless is preferred over lossy, 16/44 as the practical
 *     sweet spot before hi-res (`recommendedOrder`);
 *  2. free upload slot wins (peers without one are pushed down);
 *  3. higher upload speed;
 *  4. shorter peer queue;
 *  5. more files in the group.
 *
 * The comparator is parameterised for testability and future sort-mode UI.
 */
open class RankingEngine(
    private val qualityOrder: List<QualityClass> = DEFAULT_QUALITY_ORDER,
    private val tieBreak: (ResultGroup, ResultGroup) -> Int = { a, b -> b.files.size.compareTo(a.files.size) },
) {

    val comparator: Comparator<ResultGroup> = Comparator { a, b ->
        val qa = qualityOrder.indexOf(a.medalQuality())
        val qb = qualityOrder.indexOf(b.medalQuality())
        val quality = compareValues(indexOrLast(qa), indexOrLast(qb))
        if (quality != 0) return@Comparator quality
        if (a.hasFreeUploadSlot != b.hasFreeUploadSlot) return@Comparator if (b.hasFreeUploadSlot) 1 else -1
        val speed = b.uploadSpeedBps.compareTo(a.uploadSpeedBps)
        if (speed != 0) return@Comparator speed
        val queue = a.queueLength.compareTo(b.queueLength)
        if (queue != 0) return@Comparator queue
        tieBreak(a, b)
    }

    /** Stable sort following [comparator]. */
    fun rank(groups: List<ResultGroup>): List<ResultGroup> = groups.sortedWith(comparator)

    /**
     * The group's best quality: peers list files together, so a peer that offers 16/44 FLAC
     * alongside MP3 320 is ranked in the FLAC tier.
     */
    private fun ResultGroup.medalQuality(): QualityClass =
        allowedFiles.minByOrNull { qualityOrder.indexOf(it.qualityClass) }?.qualityClass
            ?: QualityClass.UNKNOWN

    private companion object {
        val DEFAULT_QUALITY_ORDER = listOf(
            QualityClass.LOSSLESS_16_44,
            QualityClass.LOSSLESS_HIREZ,
            QualityClass.MP3_320,
            QualityClass.MP3_VBR,
            QualityClass.LOSSY_OTHER,
            QualityClass.UNKNOWN,
        )
    }
}

internal fun indexOrLast(index: Int): Int = if (index < 0) Int.MAX_VALUE else index
