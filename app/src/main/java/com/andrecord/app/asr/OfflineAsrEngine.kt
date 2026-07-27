package com.andrecord.app.asr

interface OfflineAsrEngine {
    /** Transcribes one chunk of audio (expected to be no longer than Whisper's ~30s window --
     *  see [WhisperChunker]) to text. */
    fun transcribe(samples: FloatArray, sampleRate: Int): String

    /** Releases any native resources held by this engine. Safe to call even if nothing has been
     *  loaded yet; a subsequent [transcribe] call is expected to transparently reload whatever it
     *  needs. */
    fun release()
}
