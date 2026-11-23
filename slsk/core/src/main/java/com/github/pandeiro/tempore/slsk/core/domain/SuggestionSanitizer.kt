package com.github.pandeiro.tempore.slsk.core.domain

/**
 * Library autocomplete hygiene (PLAN.md §8). Servers sometimes join multi-artist tags into one
 * enormous "artist" name — a data/parsing issue we can't fix upstream — so overlong candidates
 * are dropped instead of rendered. Pure + JVM-tested.
 */
object SuggestionSanitizer {
    const val MAX_LENGTH = 60
    const val MAX_ITEMS = 10

    fun sanitize(raw: List<String?>, maxLength: Int = MAX_LENGTH, maxItems: Int = MAX_ITEMS): List<String> =
        raw.asSequence()
            .mapNotNull { it?.replace(WHITESPACE, " ")?.trim() }
            .filter { it.isNotEmpty() && it.length <= maxLength }
            .distinctBy { it.lowercase() }
            .take(maxItems)
            .toList()

    private val WHITESPACE = Regex("\\s+")
}
