package com.andrecord.app.asr

import com.andrecord.app.diarization.SpeakerSegment

/**
 * Turns diarization's speaker-turn segments into the chunks fed to Whisper one at a time.
 *
 * sherpa-onnx's offline Whisper recognizer processes audio in a fixed ~30-second window per call
 * with no built-in long-form chunking (verified via `javap` against `OfflineWhisperModelConfig` /
 * `OfflineRecognizer` -- there is no streaming or automatic-windowing entry point). Rather than add
 * a separate VAD pass to find chunk boundaries, this reuses diarization's own segment boundaries:
 * each speaker turn becomes one chunk (sub-split if it's too long), so the resulting text is already
 * correctly speaker-attributed by construction -- no separate ASR-to-diarization alignment needed.
 */
object WhisperChunker {

    /** Padding added on each side of a segment so words at its edges aren't clipped. */
    const val PADDING_MS = 250L

    /** Kept comfortably under Whisper's ~30s window to leave headroom for the model's own
     *  internal padding/positional-embedding behavior. */
    const val MAX_CHUNK_MS = 28_000L

    data class Chunk(val startMs: Long, val endMs: Long, val speakerIndex: Int)

    fun chunk(totalDurationMs: Long, segments: List<SpeakerSegment>): List<Chunk> {
        val sorted = segments.sortedBy { it.startMs }
        val chunks = mutableListOf<Chunk>()

        for ((index, segment) in sorted.withIndex()) {
            val prev = sorted.getOrNull(index - 1)
            val next = sorted.getOrNull(index + 1)

            // Clamp against the MIDPOINT of the gap to each neighbor, not the neighbor's raw
            // start/end. Clamping against the raw endpoint only stops this segment's padding from
            // entering the neighbor's actual speech -- it does nothing to stop both segments'
            // padding from claiming the same territory when the gap between them is smaller than
            // 2x PADDING_MS. The midpoint is the furthest either side can go without the two
            // padded chunks overlapping each other.
            val leftBound = if (prev != null) (prev.endMs + segment.startMs) / 2 else 0L
            val rightBound = if (next != null) (segment.endMs + next.startMs) / 2 else totalDurationMs

            val paddedStart = (segment.startMs - PADDING_MS).coerceAtLeast(leftBound).coerceAtLeast(0L)
            val paddedEnd = (segment.endMs + PADDING_MS).coerceAtMost(rightBound).coerceAtMost(totalDurationMs)

            if (paddedStart >= paddedEnd) {
                // Padding was fully squeezed out by an adjacent segment or the recording's own
                // bounds -- fall back to the segment's own unpadded span rather than emit a
                // zero-or-negative-length chunk.
                chunks.add(Chunk(segment.startMs, segment.endMs, segment.speakerIndex))
                continue
            }

            var cursor = paddedStart
            while (cursor < paddedEnd) {
                val end = (cursor + MAX_CHUNK_MS).coerceAtMost(paddedEnd)
                chunks.add(Chunk(cursor, end, segment.speakerIndex))
                cursor = end
            }
        }

        return chunks
    }
}
