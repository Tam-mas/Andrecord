package com.andrecord.app.asr

interface OfflineAsrEngine {
    /** Transcribes one chunk of audio (expected to be no longer than Whisper's ~30s window --
     *  see [WhisperChunker]) to text. */
    fun transcribe(samples: FloatArray, sampleRate: Int): String
}
