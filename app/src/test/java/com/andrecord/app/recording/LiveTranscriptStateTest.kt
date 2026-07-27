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

    /**
     * Regression test for a stop-then-immediate-restart race: the old session's teardown (running
     * on its own coroutine) reaching clear() after a new recording's start() has already run must
     * not wipe out the new recording's startTime/transcript for the rest of its duration.
     */
    @Test
    fun `clearIf is a no-op when a newer session has already started`() {
        val state = LiveTranscriptState()
        state.start("old-session", 1000L)
        state.appendFinal("stale trailing utterance")
        state.start("new-session", 5000L)

        state.clearIf("old-session")

        val snapshot = state.snapshot.value
        assertEquals("new-session", snapshot.sessionId)
        assertEquals(5000L, snapshot.startTime)
        assertEquals(emptyList<String>(), snapshot.finalLines)
    }

    @Test
    fun `clearIf clears when the session id still matches`() {
        val state = LiveTranscriptState()
        state.start("s1", 1000L)
        state.appendFinal("hi")

        state.clearIf("s1")

        assertEquals(LiveTranscriptSnapshot(), state.snapshot.value)
    }

    @Test
    fun `appendFinalIf is a no-op when a newer session has already started`() {
        val state = LiveTranscriptState()
        state.start("old-session", 1000L)
        state.start("new-session", 5000L)

        state.appendFinalIf("old-session", "stale trailing utterance")

        assertEquals(emptyList<String>(), state.snapshot.value.finalLines)
    }

    @Test
    fun `appendFinalIf appends when the session id still matches`() {
        val state = LiveTranscriptState()
        state.start("s1", 1000L)

        state.appendFinalIf("s1", "hello there")

        assertEquals(listOf("hello there"), state.snapshot.value.finalLines)
    }

    @Test
    fun `updatePartialIf is a no-op when a newer session has already started`() {
        val state = LiveTranscriptState()
        state.start("old-session", 1000L)
        state.start("new-session", 5000L)

        state.updatePartialIf("old-session", "stale partial")

        assertNull(state.snapshot.value.partialLine)
    }

    @Test
    fun `updatePartialIf updates when the session id still matches`() {
        val state = LiveTranscriptState()
        state.start("s1", 1000L)

        state.updatePartialIf("s1", "hello")

        assertEquals("hello", state.snapshot.value.partialLine)
    }
}
