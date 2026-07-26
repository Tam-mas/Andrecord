package com.andrecord.app.ui.components

import com.andrecord.app.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineProportionsTest {

    @Test
    fun `merges adjacent same-speaker segments and computes fractions`() {
        val segments = listOf(
            TranscriptSegment(sessionId = "s1", startMs = 0, endMs = 1000, speakerLabel = "Speaker 1", text = "a"),
            TranscriptSegment(sessionId = "s1", startMs = 1000, endMs = 2000, speakerLabel = "Speaker 1", text = "b"),
            TranscriptSegment(sessionId = "s1", startMs = 2000, endMs = 4000, speakerLabel = "Speaker 2", text = "c"),
        )

        val result = computeTimelineProportions(segments)

        assertEquals(listOf("Speaker 1" to 0.5f, "Speaker 2" to 0.5f), result)
    }

    @Test
    fun `empty segments produces empty proportions`() {
        assertEquals(emptyList<Pair<String?, Float>>(), computeTimelineProportions(emptyList()))
    }

    @Test
    fun `merges consecutive null speakers`() {
        val segments = listOf(
            TranscriptSegment(sessionId = "s1", startMs = 0, endMs = 1000, speakerLabel = null, text = "a"),
            TranscriptSegment(sessionId = "s1", startMs = 1000, endMs = 2000, speakerLabel = null, text = "b"),
            TranscriptSegment(sessionId = "s1", startMs = 2000, endMs = 3000, speakerLabel = "Speaker 1", text = "c"),
        )

        val result = computeTimelineProportions(segments)

        assertEquals(listOf(null to (2f / 3f), "Speaker 1" to (1f / 3f)), result)
    }
}
