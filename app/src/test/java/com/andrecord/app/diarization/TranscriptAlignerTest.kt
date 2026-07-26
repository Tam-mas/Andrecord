package com.andrecord.app.diarization

import com.andrecord.app.asr.AsrEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptAlignerTest {

    @Test
    fun `assigns speaker label by max timestamp overlap`() {
        val asrSegments = listOf(
            AsrEvent.Final(startMs = 0, endMs = 2000, text = "hello there"),
            AsrEvent.Final(startMs = 2000, endMs = 5000, text = "how are you")
        )
        val speakerSegments = listOf(
            SpeakerSegment(startMs = 0, endMs = 2200, speakerIndex = 0),
            SpeakerSegment(startMs = 2200, endMs = 6000, speakerIndex = 1)
        )

        val result = TranscriptAligner.align("s1", asrSegments, speakerSegments)

        assertEquals("Speaker 1", result[0].speakerLabel)
        assertEquals("Speaker 2", result[1].speakerLabel)
        assertEquals("hello there", result[0].text)
        assertEquals("s1", result[0].sessionId)
    }

    @Test
    fun `asr segment with no overlapping speaker segment gets null label`() {
        val asrSegments = listOf(AsrEvent.Final(startMs = 10_000, endMs = 12_000, text = "orphaned"))
        val speakerSegments = listOf(SpeakerSegment(startMs = 0, endMs = 1000, speakerIndex = 0))

        val result = TranscriptAligner.align("s1", asrSegments, speakerSegments)

        assertEquals(null, result[0].speakerLabel)
    }

    @Test
    fun `speakerCount returns distinct speaker index count`() {
        val speakerSegments = listOf(
            SpeakerSegment(0, 1000, speakerIndex = 0),
            SpeakerSegment(1000, 2000, speakerIndex = 1),
            SpeakerSegment(2000, 3000, speakerIndex = 0)
        )

        assertEquals(2, TranscriptAligner.speakerCount(speakerSegments))
    }
}
