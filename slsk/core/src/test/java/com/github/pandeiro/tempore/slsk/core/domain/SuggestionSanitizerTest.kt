package com.github.pandeiro.tempore.slsk.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionSanitizerTest {

    @Test
    fun drops_overlong_candidates_from_joined_multi_artist_tags() {
        val joined = (1..12).joinToString(" • ") { "Artist Number $it" }
        val out = SuggestionSanitizer.sanitize(listOf("Boards of Canada", joined, "Geogaddi"))
        assertEquals(listOf("Boards of Canada", "Geogaddi"), out)
    }

    @Test
    fun keeps_a_candidate_exactly_at_the_limit() {
        val sixty = "x".repeat(SuggestionSanitizer.MAX_LENGTH)
        assertEquals(listOf(sixty), SuggestionSanitizer.sanitize(listOf(sixty, sixty + "y")))
    }

    @Test
    fun trims_collapses_whitespace_dedupes_case_insensitively_and_caps() {
        val raw = listOf("  Aphex  Twin ", "aphex twin", null, "", "   ") + (1..20).map { "Song $it" }
        val out = SuggestionSanitizer.sanitize(raw)
        assertEquals("Aphex Twin", out.first())
        assertEquals(SuggestionSanitizer.MAX_ITEMS, out.size)
        assertEquals(out.size, out.map { it.lowercase() }.toSet().size)
    }
}
