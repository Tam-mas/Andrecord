package com.andrecord.app.asr

import com.andrecord.app.diarization.SpeakerSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        // Sub-split chunks overlap by PADDING_MS (250ms) rather than butt-joining, so chunk 1 starts
        // at 28000 - 250 = 27750 instead of exactly 28000.
        val segments = listOf(SpeakerSegment(startMs = 0, endMs = 35_000, speakerIndex = 2))

        val chunks = WhisperChunker.chunk(totalDurationMs = 40_000, segments = segments)

        assertEquals(2, chunks.size)
        assertEquals(0L, chunks[0].startMs)
        assertEquals(28_000L, chunks[0].endMs)
        assertEquals(2, chunks[0].speakerIndex)
        assertEquals(27_750L, chunks[1].startMs)
        assertEquals(35_250L, chunks[1].endMs)
        assertEquals(2, chunks[1].speakerIndex)
    }

    @Test
    fun `overlapping diarization segments do not truncate either segment's own real span`() {
        // Pyannote-based diarization can emit overlapping segments for simultaneous speech.
        // Segment B starts (4500) before segment A ends (5000). The naive midpoint clamp would
        // compute a leftBound/rightBound that lands inside the neighbor's own span, truncating real
        // speech; the fix clamps bounds so they can only ever reduce padding, never eat into a
        // segment's own [startMs, endMs).
        val segments = listOf(
            SpeakerSegment(startMs = 0, endMs = 5000, speakerIndex = 0),
            SpeakerSegment(startMs = 4500, endMs = 9000, speakerIndex = 1)
        )

        val chunks = WhisperChunker.chunk(totalDurationMs = 9000, segments = segments)

        assertEquals(2, chunks.size)
        val chunkA = chunks[0]
        val chunkB = chunks[1]
        assertTrue("chunk for segment A must still cover its own [0, 5000] span", chunkA.startMs <= 0 && chunkA.endMs >= 5000)
        assertTrue("chunk for segment B must still cover its own [4500, 9000] span", chunkB.startMs <= 4500 && chunkB.endMs >= 9000)
    }

    @Test
    fun `empty segment list produces no chunks`() {
        val chunks = WhisperChunker.chunk(totalDurationMs = 10_000, segments = emptyList())

        assertEquals(emptyList<WhisperChunker.Chunk>(), chunks)
    }
}
