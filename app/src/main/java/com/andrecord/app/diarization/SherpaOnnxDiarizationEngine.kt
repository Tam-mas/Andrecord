package com.andrecord.app.diarization

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.WaveReader

/**
 * Offline speaker diarization engine backed by sherpa-onnx's real Kotlin/JNI API
 * (package com.k2fsa.sherpa.onnx, same AAR vendored for Task 7's streaming ASR engine,
 * verified against sherpa-onnx v1.13.4). Sources consulted:
 * https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OfflineSpeakerDiarization.kt
 * (also cross-checked against `javap -p` output of the compiled classes.jar inside
 * sherpa-onnx-1.13.4.aar -- source and bytecode agree) and the real Android demo app
 * that exercises this exact class end-to-end:
 * https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxSpeakerDiarization
 * (see SpeakerDiarizationObject.kt and screens/Home.kt).
 *
 * The brief's sketch for this file does not match the real API in two structural ways:
 *
 * 1. [OfflineSpeakerDiarizationConfig] has no flat `segmentationModel`/`embeddingModel`
 *    string fields. The segmentation model path is nested three levels deep inside
 *    [OfflineSpeakerSegmentationModelConfig.pyannote] (an
 *    [OfflineSpeakerSegmentationPyannoteModelConfig] with a single `model` field), and
 *    the embedding model path lives on [SpeakerEmbeddingExtractorConfig.model].
 * 2. [OfflineSpeakerDiarization.process] takes only the sample array -- there is no
 *    separate `sampleRate` parameter, and the model does not accept arbitrary sample
 *    rates. The diarizer has its own expected rate, queried via
 *    [OfflineSpeakerDiarization.sampleRate], and the real demo app explicitly rejects
 *    (rather than resamples) input at any other rate. `process()` also returns the
 *    `OfflineSpeakerDiarizationSegment[]` array directly -- there is no `result.segments`
 *    wrapper object.
 */
class SherpaOnnxDiarizationEngine(private val context: Context) : DiarizationEngine {

    // Deferred: loading the segmentation + embedding models is expensive (~32 MB of
    // ONNX graphs) and this engine has no start()/stop() lifecycle of its own, so we
    // avoid paying that cost at AndrecordApplication startup if diarization is never
    // actually invoked in a given app run.
    private val diarizer: OfflineSpeakerDiarization by lazy {
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = assetPath("models/diarization/segmentation.onnx"),
                ),
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = assetPath("models/diarization/embedding.onnx"),
                numThreads = 2,
            ),
        )
        OfflineSpeakerDiarization(assetManager = context.assets, config = config)
    }

    override fun diarize(wavFilePath: String): List<SpeakerSegment> {
        val wave = WaveReader.readWaveFromFile(wavFilePath)
        check(wave.sampleRate == diarizer.sampleRate()) {
            "Diarizer expects ${diarizer.sampleRate()} Hz audio but $wavFilePath is ${wave.sampleRate} Hz"
        }
        val segments = diarizer.process(wave.samples)
        return segments.map {
            SpeakerSegment(
                startMs = (it.start * 1000).toLong(),
                endMs = (it.end * 1000).toLong(),
                speakerIndex = it.speaker,
            )
        }
    }

    /** sherpa-onnx resolves model paths relative to the AssetManager root, so the
     *  path is used as-is (no leading slash, no "assets/" prefix). */
    private fun assetPath(path: String) = path
}
