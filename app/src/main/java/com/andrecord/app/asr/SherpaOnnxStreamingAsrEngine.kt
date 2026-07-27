package com.andrecord.app.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Streaming ASR engine backed by sherpa-onnx's real Kotlin/JNI API
 * (package com.k2fsa.sherpa.onnx, verified against sherpa-onnx v1.13.4:
 * https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OnlineRecognizer.kt
 * and the compiled classes.jar inside sherpa-onnx-1.13.4.aar).
 *
 * Note that unlike the original sketch for this class, [OnlineTransducerModelConfig]
 * is not passed directly as `OnlineRecognizerConfig.modelConfig` -- it must be nested
 * inside an [OnlineModelConfig], which is what actually carries `tokens` and `modelType`.
 */
class SherpaOnnxStreamingAsrEngine(private val context: Context) : StreamingAsrEngine {

    private lateinit var recognizer: OnlineRecognizer
    private lateinit var stream: OnlineStream
    private val pendingEvents = ConcurrentLinkedQueue<AsrEvent>()
    private var samplesProcessed = 0L
    private var lastEmittedText = ""
    private var segmentStartMs = 0L

    override fun start() {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = FEATURE_DIM,
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = assetPath("models/asr/encoder.onnx"),
                    decoder = assetPath("models/asr/decoder.onnx"),
                    joiner = assetPath("models/asr/joiner.onnx"),
                ),
                tokens = assetPath("models/asr/tokens.txt"),
                modelType = "zipformer2",
                numThreads = 2,
            ),
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
        )
        recognizer = OnlineRecognizer(assetManager = context.assets, config = config)
        stream = recognizer.createStream()
        samplesProcessed = 0L
        lastEmittedText = ""
        segmentStartMs = 0L
    }

    override fun acceptWaveform(samples: FloatArray) {
        stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
        samplesProcessed += samples.size
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        val text = recognizer.getResult(stream).text
        val nowMs = (samplesProcessed * 1000L) / SAMPLE_RATE

        if (recognizer.isEndpoint(stream)) {
            if (text.isNotBlank()) {
                pendingEvents.add(AsrEvent.Final(startMs = segmentStartMs, endMs = nowMs, text = text))
            }
            recognizer.reset(stream)
            segmentStartMs = nowMs
            lastEmittedText = ""
        } else if (text != lastEmittedText) {
            lastEmittedText = text
            pendingEvents.add(AsrEvent.Partial(text))
        }
    }

    override fun poll(): AsrEvent? = pendingEvents.poll()

    override fun stop() {
        drainTrailingFinal()
        stream.release()
        recognizer.release()
    }

    /**
     * Emits whatever hypothesis is still in flight when recording stops as one last
     * [AsrEvent.Final]. [acceptWaveform] only produces a Final when sherpa-onnx's endpoint rule
     * fires (roughly 1.4s of trailing silence), so without this the last thing said before the
     * user hits stop -- typically the closing sentence of the meeting -- is decoded and then
     * discarded with the stream. Callers must poll() once more after stop() to observe it.
     */
    private fun drainTrailingFinal() {
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        val text = recognizer.getResult(stream).text
        if (text.isNotBlank()) {
            val nowMs = (samplesProcessed * 1000L) / SAMPLE_RATE
            pendingEvents.add(AsrEvent.Final(startMs = segmentStartMs, endMs = nowMs, text = text))
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
