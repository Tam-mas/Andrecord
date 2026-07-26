package com.andrecord.app.asr

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke test for the real sherpa-onnx JNI integration. This cannot run under
 * Robolectric (native .so loaded via System.loadLibrary needs a real device/emulator ABI
 * match), so it lives in androidTest and is run with connectedDebugAndroidTest.
 *
 * The bundled test_clip_16k_mono.wav is sherpa-onnx's own official test clip
 * (sherpa-onnx-streaming-zipformer-en-2023-06-26-mobile/test_wavs/0.wav), whose reference
 * transcript per the model's own test_wavs/trans.txt is:
 *   "AFTER EARLY NIGHTFALL THE YELLOW LAMPS WOULD LIGHT UP HERE AND THERE THE SQUALID
 *    QUARTER OF THE BROTHELS"
 */
@RunWith(AndroidJUnit4::class)
class SherpaOnnxStreamingAsrEngineSmokeTest {

    @Test
    fun recognizesNonEmptyTextFromAShortSpokenTestClip() {
        // The engine loads its ONNX model files from the app-under-test's own assets
        // (app/src/main/assets), so it needs the app context...
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = SherpaOnnxStreamingAsrEngine(appContext)
        engine.start()

        // ...but the WAV test fixture lives in this test module's own assets
        // (app/src/androidTest/assets), which are packaged into the separate test APK and
        // are only visible through the instrumentation context, not the app context.
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val speechSamples = readTestClipAsFloatPcm(testContext)
        // Append ~2s of trailing silence: the model's default endpoint rule (rule2) only
        // fires after >= 1.4s of trailing silence following non-silence, and the raw test
        // clip has no such gap at the end (it's a single continuous utterance). Real usage
        // (recording a person talking, then pausing) naturally provides this trailing gap;
        // we synthesize it here so the streaming engine actually emits an AsrEvent.Final.
        val samples = speechSamples + FloatArray(2 * SherpaOnnxStreamingAsrEngine.SAMPLE_RATE)

        val chunkSize = 1600 // 100ms chunks at 16kHz
        val finals = StringBuilder()
        var lastPartial = ""
        for (i in samples.indices step chunkSize) {
            val chunk = samples.copyOfRange(i, minOf(i + chunkSize, samples.size))
            engine.acceptWaveform(chunk)
            var event = engine.poll()
            while (event != null) {
                when (event) {
                    is AsrEvent.Final -> finals.append(event.text).append(" ")
                    is AsrEvent.Partial -> lastPartial = event.text
                }
                event = engine.poll()
            }
        }
        engine.stop()

        val recognized = finals.toString().ifBlank { lastPartial }
        Log.i("SherpaSmokeTest", "recognized='$recognized' finals='$finals' lastPartial='$lastPartial'")

        assertTrue("Expected non-empty transcript, got finals='$finals' lastPartial='$lastPartial'", recognized.isNotBlank())
    }

    private fun readTestClipAsFloatPcm(context: android.content.Context): FloatArray {
        context.assets.open("test_clip_16k_mono.wav").use { input ->
            val bytes = input.readBytes()
            val pcmBytes = bytes.copyOfRange(44, bytes.size) // skip the 44-byte WAV header
            val samples = FloatArray(pcmBytes.size / 2)
            for (i in samples.indices) {
                val lo = pcmBytes[i * 2].toInt() and 0xFF
                val hi = pcmBytes[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                samples[i] = sample / 32768.0f
            }
            return samples
        }
    }
}
