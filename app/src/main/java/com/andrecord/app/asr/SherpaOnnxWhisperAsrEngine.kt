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

    // Lazily constructed on first use, and explicitly released via [release] once the caller
    // (TranscriptionWorker) is done with it for this run -- unlike SherpaOnnxDiarizationEngine's
    // `diarizer`, this engine's native memory is large enough (hundreds of MB) and infrequent
    // enough in use (once per recording) that holding it for the app's entire process lifetime is
    // wasteful. `by lazy` can't be reset, so this uses a nullable var + helper instead.
    private var recognizer: OfflineRecognizer? = null

    private fun getOrCreateRecognizer(): OfflineRecognizer = recognizer ?: run {
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
        OfflineRecognizer(assetManager = context.assets, config = config).also { recognizer = it }
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val recognizer = getOrCreateRecognizer()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
    }

    /** sherpa-onnx resolves model paths relative to the AssetManager root, so the
     *  path is used as-is (no leading slash, no "assets/" prefix). */
    private fun assetPath(path: String) = path

    companion object {
        const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
    }
}
