package com.andrecord.app.asr

sealed class AsrEvent {
    data class Partial(val text: String) : AsrEvent()
    data class Final(val startMs: Long, val endMs: Long, val text: String) : AsrEvent()
}

interface StreamingAsrEngine {
    fun start()
    /** samples are 16kHz mono PCM, normalized to [-1.0, 1.0] */
    fun acceptWaveform(samples: FloatArray)
    /** Returns the next available event, or null if none is ready yet. Call after every acceptWaveform. */
    fun poll(): AsrEvent?
    fun stop()
}
