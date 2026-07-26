package com.andrecord.app.ui.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingScreenTest {

    @Test
    fun `formatElapsed renders minutes and seconds with zero-padded seconds`() {
        assertEquals("0:00", formatElapsed(startMillis = 1000L, nowMillis = 1000L))
        assertEquals("0:05", formatElapsed(startMillis = 1000L, nowMillis = 6000L))
        assertEquals("1:00", formatElapsed(startMillis = 0L, nowMillis = 60_000L))
        assertEquals("12:34", formatElapsed(startMillis = 0L, nowMillis = 754_000L))
    }

    @Test
    fun `formatElapsed never goes negative`() {
        assertEquals("0:00", formatElapsed(startMillis = 5000L, nowMillis = 1000L))
    }
}
