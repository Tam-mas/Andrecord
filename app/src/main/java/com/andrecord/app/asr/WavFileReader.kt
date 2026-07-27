package com.andrecord.app.asr

import com.k2fsa.sherpa.onnx.WaveReader

data class WavSamples(val samples: FloatArray, val sampleRate: Int)

/**
 * Reads an entire WAV file's samples into memory. Exists as a thin, fakeable seam around
 * sherpa-onnx's [WaveReader] (a native JNI call -- verified via `javap` as `native`, so it cannot
 * run inside a plain-JVM Robolectric unit test) so [com.andrecord.app.workers.TranscriptionWorker]'s
 * chunk-slicing orchestration logic can be unit tested without touching native code, the same way
 * [com.andrecord.app.diarization.DiarizationEngine] and [OfflineAsrEngine] already are.
 */
fun interface WavFileReader {
    fun read(wavFilePath: String): WavSamples
}

class SherpaOnnxWavFileReader : WavFileReader {
    override fun read(wavFilePath: String): WavSamples {
        val wave = WaveReader.readWaveFromFile(wavFilePath)
        return WavSamples(wave.samples, wave.sampleRate)
    }
}
