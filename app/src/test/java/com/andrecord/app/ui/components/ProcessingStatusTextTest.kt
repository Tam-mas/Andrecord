package com.andrecord.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingStatusTextTest {

    @Test
    fun `null progress shows a plain processing message`() {
        assertEquals("Processing…", formatProcessingStatus(null, null))
    }

    @Test
    fun `progress with an eta in minutes`() {
        assertEquals("42% · ~2 min remaining", formatProcessingStatus(42, 120_000L))
    }

    @Test
    fun `progress with an eta under a minute`() {
        assertEquals("90% · ~30s remaining", formatProcessingStatus(90, 30_000L))
    }

    @Test
    fun `progress with a zero eta still shows a nonzero seconds estimate`() {
        assertEquals("100% · ~1s remaining", formatProcessingStatus(100, 0L))
    }

    @Test
    fun `progress without an eta omits the remaining-time text`() {
        assertEquals("10%", formatProcessingStatus(10, null))
    }
}
