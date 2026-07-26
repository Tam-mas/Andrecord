package com.andrecord.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTranscriptStateTest {

    @Test
    fun `start seeds sessionId and startTime with an empty transcript`() {
        val state = LiveTranscriptState()

        state.start("s1", 1000L)

        val snapshot = state.snapshot.value
        assertEquals("s1", snapshot.sessionId)
        assertEquals(1000L, snapshot.startTime)
        assertEquals(emptyList<String>(), snapshot.finalLines)
        assertNull(snapshot.partialLine)
    }

    @Test
    fun `appendFinal appends permanently and clears the partial`() {
        val state = LiveTranscriptState()
        state.start("s1", 1000L)
        state.updatePartial("hello the")

        state.appendFinal("hello there")

        val snapshot = state.snapshot.value
        assertEquals(listOf("hello there"), snapshot.finalLines)
        assertNull(snapshot.partialLine)
    }

    @Test
    fun `appendFinal appends to existing lines rather than replacing them`() {
        val state = LiveTranscriptState()
        state.start("s1", 1000L)
        state.appendFinal("first")

        state.appendFinal("second")

        assertEquals(listOf("first", "second"), state.snapshot.value.finalLines)
    }

    @Test
    fun `updatePartial replaces rather than appends`() {
        val state = LiveTranscriptState()
        state.start("s1", 1000L)

        state.updatePartial("hello")
        state.updatePartial("hello there")

        assertEquals("hello there", state.snapshot.value.partialLine)
    }

    @Test
    fun `clear resets to an empty snapshot`() {
        val state = LiveTranscriptState()
        state.start("s1", 1000L)
        state.appendFinal("hi")
        state.updatePartial("more")

        state.clear()

        assertEquals(LiveTranscriptSnapshot(), state.snapshot.value)
    }
}
