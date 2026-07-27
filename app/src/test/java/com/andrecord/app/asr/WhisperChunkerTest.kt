package com.andrecord.app.asr

import com.andrecord.app.diarization.SpeakerSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperChunkerTest {

    @Test
    fun `one chunk per segment, padded on both sides`() {
        val segments = listOf(SpeakerSegment(startMs = 5000, endMs = 8000, speakerIndex = 0))

        val chunks = WhisperChunker.chunk(totalDurationMs = 20_000, segments = segments)

        assertEquals(1, chunks.size)
        assertEquals(WhisperChunker.Chunk(startMs = 4750, endMs = 8250, speakerIndex = 0), chunks[0])
    }

    @Test
    fun `padding clamps at the recording's start and end`() {
        val segments = listOf(SpeakerSegment(startMs = 0, endMs = 100, speakerIndex = 0))

        val chunks = WhisperChunker.chunk(totalDurationMs = 150, segments = segments)

        assertEquals(1, chunks.size)
        assertEquals(0L, chunks[0].startMs)
        assertEquals(150L, chunks[0].endMs)
    }

    @Test
    fun `padding does not overlap into an adjacent segment's audio`() {
        // Two segments only 300ms apart -- less than double the 250ms padding margin, so naive
        // padding on both sides would overlap by 200ms if not clamped against the neighbor.
        val segments = listOf(
            SpeakerSegment(startMs = 0, endMs = 5000, speakerIndex = 0),
            SpeakerSegment(startMs = 5300, endMs = 10_000, speakerIndex = 1)
        )

        val chunks = WhisperChunker.chunk(totalDurationMs = 10_000, segments = segments)

        assertEquals(2, chunks.size)
        assertEquals(5150L, chunks[0].endMs)
        assertEquals(5150L, chunks[1].startMs)
    }

    @Test
    fun `a segment longer than the whisper window is sub-split into multiple chunks`() {
        // 35 seconds, padded to 35.25s -- longer than MAX_CHUNK_MS (28s), so it must split into two.
        val segments = listOf(SpeakerSegment(startMs = 0, endMs = 35_000, speakerIndex = 2))

        val chunks = WhisperChunker.chunk(totalDurationMs = 40_000, segments = segments)

        assertEquals(2, chunks.size)
        assertEquals(0L, chunks[0].startMs)
        assertEquals(28_000L, chunks[0].endMs)
        assertEquals(2, chunks[0].speakerIndex)
        assertEquals(28_000L, chunks[1].startMs)
        assertEquals(35_250L, chunks[1].endMs)
        assertEquals(2, chunks[1].speakerIndex)
    }

    @Test
    fun `empty segment list produces no chunks`() {
        val chunks = WhisperChunker.chunk(totalDurationMs = 10_000, segments = emptyList())

        assertEquals(emptyList<WhisperChunker.Chunk>(), chunks)
    }
}
