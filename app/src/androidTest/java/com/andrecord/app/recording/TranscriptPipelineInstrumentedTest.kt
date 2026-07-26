package com.andrecord.app.recording

import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.asr.SherpaOnnxStreamingAsrEngine
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.diarization.SherpaOnnxDiarizationEngine
import com.andrecord.app.workers.DiarizationWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end, on-device check that a recording's transcript is written exactly once.
 *
 * This replays the real two-speaker fixture WAV through the real sherpa-onnx ASR and
 * diarization engines in exactly the shape RecordingService's capture loop uses it -- accumulate
 * finals, flush every 5 seconds of audio, drain once more after stop(), final flush, then run
 * DiarizationWorker's alignment -- because the duplication bug this guards against only appeared
 * once a recording outlived a single flush interval. It substitutes the fixture for the
 * microphone: the emulator's virtual mic records silence, so a real mic-driven run produces no
 * transcript at all and proves nothing about duplication.
 *
 * Native sherpa-onnx .so files mean this cannot run under Robolectric; run with
 * `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptPipelineInstrumentedTest {

    @Test
    fun aRecordingSpanningSeveralFlushCyclesIsTranscribedExactlyOnce() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context

        val wavFile = File(appContext.cacheDir, "two_speaker_test.wav")
        testContext.assets.open("two_speaker_test.wav").use { input ->
            wavFile.outputStream().use { output -> input.copyTo(output) }
        }

        val db = Room.inMemoryDatabaseBuilder(appContext, AndrecordDatabase::class.java).build()
        val repository = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        val sessionId = "pipeline-test"
        repository.createSession(sessionId, startTime = 0L)
        repository.markProcessing(
            sessionId, endTime = 16_000L, durationMs = 16_000L,
            audioFilePath = wavFile.absolutePath, audioDeleteAt = Long.MAX_VALUE
        )

        val asrEngine = SherpaOnnxStreamingAsrEngine(appContext)
        asrEngine.start()
        val pending = mutableListOf<AsrEvent.Final>()
        val samples = readWavAsFloatPcm(wavFile)

        val chunkSize = 1600 // 100ms at 16kHz, as the capture loop reads it
        val chunksPerFlush = 50 // == the service's 5-second flush interval
        var chunkIndex = 0
        var flushCount = 0
        for (i in samples.indices step chunkSize) {
            asrEngine.acceptWaveform(samples.copyOfRange(i, minOf(i + chunkSize, samples.size)))
            drainInto(asrEngine, pending)
            if (++chunkIndex % chunksPerFlush == 0) {
                RecordingService.flushSegments(repository, sessionId, pending)
                flushCount++
            }
        }
        // Mirrors the service's teardown: stop() drains the trailing hypothesis, one more poll
        // picks it up, and a final flush makes everything durable before diarization runs.
        asrEngine.stop()
        drainInto(asrEngine, pending)
        RecordingService.flushSegments(repository, sessionId, pending)

        val flushed = repository.getSegmentsOnce(sessionId)
        Log.i(
            "TranscriptPipelineTest",
            "flushes=$flushCount flushedRows=${flushed.size} " + flushed.joinToString { "[${it.startMs}-${it.endMs}] ${it.text}" }
        )

        val speakerCount = DiarizationWorker.runDiarization(
            repository, SherpaOnnxDiarizationEngine(appContext), sessionId, wavFile.absolutePath
        )
        val aligned = repository.getSegmentsOnce(sessionId)
        Log.i(
            "TranscriptPipelineTest",
            "speakerCount=$speakerCount alignedRows=${aligned.size} " + aligned.joinToString { "[${it.speakerLabel}] ${it.text}" }
        )

        assertTrue("Expected more than one flush cycle, got $flushCount", flushCount > 1)
        assertTrue("Expected a non-empty transcript", flushed.isNotEmpty())
        // The core assertion: diarization labels the flushed rows in place, it does not add a
        // second labeled copy alongside them.
        assertEquals(flushed.size, aligned.size)
        assertEquals(flushed.map { it.text }, aligned.map { it.text })
        assertEquals(
            "Duplicated utterances: " + aligned.map { it.text },
            aligned.map { it.startMs to it.text }.distinct().size,
            aligned.size
        )

        // ...and a WorkManager retry re-derives rather than accumulates.
        DiarizationWorker.runDiarization(
            repository, SherpaOnnxDiarizationEngine(appContext), sessionId, wavFile.absolutePath
        )
        val afterRetry = repository.getSegmentsOnce(sessionId)
        assertEquals(aligned.size, afterRetry.size)
        assertEquals(aligned.map { it.text }, afterRetry.map { it.text })
        assertEquals(aligned.map { it.speakerLabel }, afterRetry.map { it.speakerLabel })

        db.close()
    }

    private fun drainInto(engine: SherpaOnnxStreamingAsrEngine, into: MutableList<AsrEvent.Final>) {
        var event = engine.poll()
        while (event != null) {
            if (event is AsrEvent.Final) into.add(event)
            event = engine.poll()
        }
    }

    private fun readWavAsFloatPcm(file: File): FloatArray {
        val bytes = file.readBytes()
        val pcmBytes = bytes.copyOfRange(44, bytes.size) // skip the 44-byte WAV header
        val samples = FloatArray(pcmBytes.size / 2)
        for (i in samples.indices) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo) / 32768.0f
        }
        return samples
    }
}
