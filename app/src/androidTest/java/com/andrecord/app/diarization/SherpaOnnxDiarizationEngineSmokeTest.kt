package com.andrecord.app.diarization

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device smoke test for the real sherpa-onnx offline speaker diarization JNI
 * integration. Like the streaming ASR smoke test (Task 7), this cannot run under
 * Robolectric -- the native .so needs a real device/emulator ABI match -- so it lives
 * in androidTest and is run with connectedDebugAndroidTest.
 *
 * The bundled two_speaker_test.wav is sherpa-onnx's own official two-speaker English
 * test clip from its speaker-segmentation-models release
 * (https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/1-two-speakers-en.wav),
 * 16 kHz mono, 16 seconds, a real (not synthesized) two-person conversation recording --
 * not a self-recorded or TTS-synthesized clip. It's a real known-good fixture from the
 * library's own release, the same rationale Task 7 used for its ASR test clip.
 *
 * [SherpaOnnxDiarizationEngine.diarize] takes a filesystem path (it calls
 * WaveReader.readWaveFromFile), not an asset path, so this test copies the bundled test
 * asset out to a real file before invoking the engine -- mirroring how a real recorded
 * session WAV would exist on disk in production.
 */
@RunWith(AndroidJUnit4::class)
class SherpaOnnxDiarizationEngineSmokeTest {

    @Test
    fun diarizesATwoSpeakerTestClipIntoAtLeastTwoSpeakerSegments() {
        // The engine loads its ONNX model files from the app-under-test's own assets
        // (app/src/main/assets), so it needs the app context.
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = SherpaOnnxDiarizationEngine(appContext)

        // The WAV test fixture lives in this test module's own assets
        // (app/src/androidTest/assets), packaged into the separate test APK and only
        // visible through the instrumentation context, not the app context (same
        // asset-context split documented in Task 7's ASR smoke test).
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val wavFile = File(appContext.cacheDir, "two_speaker_test.wav")
        testContext.assets.open("two_speaker_test.wav").use { input ->
            wavFile.outputStream().use { output -> input.copyTo(output) }
        }

        val segments = engine.diarize(wavFile.absolutePath)
        val speakerCount = TranscriptAligner.speakerCount(segments)

        Log.i(
            "SherpaDiarizationSmokeTest",
            "segments=${segments.size} speakerCount=$speakerCount " +
                segments.joinToString { "[${it.startMs}-${it.endMs}ms spk=${it.speakerIndex}]" }
        )

        assertTrue(
            "Expected at least 2 distinct speakers, got: $speakerCount (segments=$segments)",
            speakerCount >= 2
        )
    }
}
