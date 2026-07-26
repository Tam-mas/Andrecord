package com.andrecord.app.diarization

import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.data.TranscriptSegment

object TranscriptAligner {

    fun align(
        sessionId: String,
        asrSegments: List<AsrEvent.Final>,
        speakerSegments: List<SpeakerSegment>
    ): List<TranscriptSegment> = asrSegments.map { asr ->
        val label = bestSpeakerLabel(asr, speakerSegments)
        TranscriptSegment(
            sessionId = sessionId,
            startMs = asr.startMs,
            endMs = asr.endMs,
            speakerLabel = label,
            text = asr.text
        )
    }

    fun speakerCount(speakerSegments: List<SpeakerSegment>): Int =
        speakerSegments.map { it.speakerIndex }.distinct().size

    private fun bestSpeakerLabel(asr: AsrEvent.Final, speakerSegments: List<SpeakerSegment>): String? {
        var bestOverlap = 0L
        var bestIndex: Int? = null
        for (seg in speakerSegments) {
            val overlap = overlapMs(asr.startMs, asr.endMs, seg.startMs, seg.endMs)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestIndex = seg.speakerIndex
            }
        }
        return bestIndex?.let { "Speaker ${it + 1}" }
    }

    private fun overlapMs(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Long =
        (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0L)
}
