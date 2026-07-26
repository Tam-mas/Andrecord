package com.andrecord.app.ui.detail

import com.andrecord.app.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDetailViewModelTest {

    @Test
    fun `buildShareText joins segments with speaker label prefix`() {
        val segments = listOf(
            TranscriptSegment(sessionId = "s1", startMs = 0, endMs = 1000, speakerLabel = "Speaker 1", text = "hello there"),
            TranscriptSegment(sessionId = "s1", startMs = 1000, endMs = 2000, speakerLabel = "Speaker 2", text = "hi"),
        )

        val result = SessionDetailViewModel.buildShareText(segments)

        assertEquals("[Speaker 1] hello there\n\n[Speaker 2] hi", result)
    }

    @Test
    fun `buildShareText falls back to Unknown for a null speaker label`() {
        val segments = listOf(
            TranscriptSegment(sessionId = "s1", startMs = 0, endMs = 1000, speakerLabel = null, text = "orphaned text"),
        )

        val result = SessionDetailViewModel.buildShareText(segments)

        assertEquals("[Unknown] orphaned text", result)
    }

    @Test
    fun `buildShareText returns empty string for no segments`() {
        assertEquals("", SessionDetailViewModel.buildShareText(emptyList()))
    }
}
