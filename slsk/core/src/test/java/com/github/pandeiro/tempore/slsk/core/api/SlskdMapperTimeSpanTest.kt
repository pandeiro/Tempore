package com.github.pandeiro.tempore.slsk.core.api

import com.github.pandeiro.tempore.slsk.core.api.SlskdMapper.parseTimeSpanSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression for PR review issue #6: the day form must not split at the wrong '.'. */
class SlskdMapperTimeSpanTest {

    @Test
    fun `day form parses to epoch-seconds`() {
        // 1 day, 02h, 03m, 04s = 93784 s (the value the review reproduces).
        assertEquals(93784L, parseTimeSpanSeconds("1.02:03:04.5"))
        assertEquals(93784L, parseTimeSpanSeconds("1.02:03:04"))
    }

    @Test
    fun `hour forms parse`() {
        assertEquals(300L, parseTimeSpanSeconds("00:05:00"))
        assertEquals(0L, parseTimeSpanSeconds("00:00:00"))
        assertEquals(5L, parseTimeSpanSeconds("00:00:05.9661420"))
        // 1 day + 21h = 45 h = 162000 s
        assertEquals(162_000L, parseTimeSpanSeconds("1.21:00:00"))
    }

    @Test
    fun `short hh-mm and garbage handled`() {
        // .NET `h:mm` means HOURS:MINUTES → 5 h = 18000 s
        assertEquals(18_000L, parseTimeSpanSeconds("05:00"))
        assertNull(parseTimeSpanSeconds("not a timespan"))
        assertNull(parseTimeSpanSeconds(null))
        assertNull(parseTimeSpanSeconds("  "))
    }

    @Test
    fun `negative sign respected`() {
        assertEquals(-125L, parseTimeSpanSeconds("-00:02:05"))
    }
}
