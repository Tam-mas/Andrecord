package com.andrecord.app.diarization

data class SpeakerSegment(val startMs: Long, val endMs: Long, val speakerIndex: Int)

interface DiarizationEngine {
    /** Runs the full offline diarization pipeline over a WAV file and returns speaker-labeled segments. */
    fun diarize(wavFilePath: String): List<SpeakerSegment>
}
