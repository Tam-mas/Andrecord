package com.andrecord.app.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Offline ASR refinement engine backed by sherpa-onnx's real Kotlin/JNI API (package
 * com.k2fsa.sherpa.onnx, same AAR vendored for the streaming ASR and diarization engines,
 * verified via `javap -p` against sherpa-onnx-api.jar -- see this file's plan task for the full
 * verified field list). English-only (`language = "en"`), matching this app's scope; `task =
 * "transcribe"` (not "translate", which would convert non-English speech to English text --
 * irrelevant here, but the field is required regardless of language).
 */
class SherpaOnnxWhisperAsrEngine(private val context: Context) : OfflineAsrEngine {

    // Deferred: loading the Whisper encoder + decoder graphs is expensive, and this engine has no
    // start()/stop() lifecycle of its own -- same reasoning as SherpaOnnxDiarizationEngine's lazy
    // `diarizer` property.
    private val recognizer: OfflineRecognizer by lazy {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = FEATURE_DIM,
            ),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = assetPath("models/asr_offline/small.en-encoder.onnx"),
                    decoder = assetPath("models/asr_offline/small.en-decoder.onnx"),
                    language = "en",
                    task = "transcribe",
                ),
                tokens = assetPath("models/asr_offline/small.en-tokens.txt"),
                modelType = "whisper",
                // Higher than the streaming engine's numThreads=2: this runs in the background
                // during post-recording processing (not competing with live capture for CPU), and
                // the user has prioritized transcription quality/thoroughness over processing
                // speed, so there's no reason to leave cores idle here.
                numThreads = 4,
            ),
        )
        OfflineRecognizer(assetManager = context.assets, config = config)
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    /** sherpa-onnx resolves model paths relative to the AssetManager root, so the
     *  path is used as-is (no leading slash, no "assets/" prefix). */
    private fun assetPath(path: String) = path

    companion object {
        const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
    }
}
