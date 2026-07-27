package com.andrecord.app.recording

import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.asr.SherpaOnnxStreamingAsrEngine
import com.andrecord.app.asr.SherpaOnnxWavFileReader
import com.andrecord.app.asr.SherpaOnnxWhisperAsrEngine
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.diarization.SherpaOnnxDiarizationEngine
import com.andrecord.app.workers.TranscriptionWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end, on-device check of the real recording -> transcript pipeline, using the real
 * sherpa-onnx streaming ASR, offline diarization, and offline Whisper engines together -- not the
 * fakes the Robolectric unit tests use. This is the only test in the project that exercises all
 * three native engines in combination.
 *
 * Originally written against `DiarizationWorker`, which aligned RecordingService's live-flushed
 * ASR segments against diarization output, so the natural invariant to check was "diarization
 * labels the flushed rows in place, it does not add a second labeled copy alongside them" --
 * flushed-row-count and text had to match aligned-row-count and text exactly. `TranscriptionWorker`
 * (see plan docs/superpowers/plans/2026-07-27-transcript-refinement.md) replaced that: it diarizes
 * the WAV, chunks the result via WhisperChunker, and transcribes each chunk fresh via Whisper --
 * it no longer reads the flushed rows at all, so there is no longer a 1:1 mapping between flushed
 * rows and the final transcript to assert on. The flush-cycle simulation below is kept (it still
 * exercises a real, unchanged part of the app -- RecordingService.flushSegments() with a real
 * streaming ASR engine), but the "written exactly once" guard is now checked by running
 * TranscriptionWorker's real pipeline twice against the same session (simulating a WorkManager
 * retry) and asserting the second run reproduces the first exactly rather than duplicating or
 * drifting -- the same spirit as the original test, retargeted at the function that actually
 * owns finalizing a session's transcript today.
 *
 * It substitutes the fixture for the microphone: the emulator's virtual mic records silence, so a
 * real mic-driven run produces no transcript at all and proves nothing about duplication.
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

        // Real streaming ASR, replayed against the real fixture the same way RecordingService's
        // capture loop would -- proves the live-flush mechanism itself still works, independent of
        // whether TranscriptionWorker ends up reading these rows.
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
        asrEngine.stop()
        drainInto(asrEngine, pending)
        RecordingService.flushSegments(repository, sessionId, pending)

        val flushed = repository.getSegmentsOnce(sessionId)
        Log.i(
            "TranscriptPipelineTest",
            "flushes=$flushCount flushedRows=${flushed.size} " + flushed.joinToString { "[${it.startMs}-${it.endMs}] ${it.text}" }
        )
        assertTrue("Expected more than one flush cycle, got $flushCount", flushCount > 1)
        assertTrue("Expected some flushed rows from live capture", flushed.isNotEmpty())

        // The real pipeline: diarize the WAV, chunk it, transcribe each chunk via real Whisper.
        val speakerCount = TranscriptionWorker.runTranscription(
            repository,
            SherpaOnnxDiarizationEngine(appContext),
            SherpaOnnxWhisperAsrEngine(appContext),
            SherpaOnnxWavFileReader(),
            sessionId,
            wavFile.absolutePath
        )
        val firstRun = repository.getSegmentsOnce(sessionId)
        Log.i(
            "TranscriptPipelineTest",
            "speakerCount=$speakerCount segments=${firstRun.size} " + firstRun.joinToString { "[${it.speakerLabel}] ${it.text}" }
        )

        assertTrue("Expected at least 2 distinct speakers, got $speakerCount", (speakerCount ?: 0) >= 2)
        assertTrue("Expected a non-empty transcript from the real pipeline", firstRun.isNotEmpty())
        assertTrue("Expected every segment to have real (non-blank) text", firstRun.all { it.text.isNotBlank() })
        assertTrue("Expected every segment to have a speaker label", firstRun.all { it.speakerLabel != null })

        // A WorkManager retry re-derives the transcript from scratch rather than accumulating a
        // second copy alongside the first -- this is the "written exactly once" guarantee,
        // exercised here against the real native engines rather than the unit test's fakes.
        TranscriptionWorker.runTranscription(
            repository,
            SherpaOnnxDiarizationEngine(appContext),
            SherpaOnnxWhisperAsrEngine(appContext),
            SherpaOnnxWavFileReader(),
            sessionId,
            wavFile.absolutePath
        )
        val secondRun = repository.getSegmentsOnce(sessionId)

        assertEquals("Retry should not change the segment count", firstRun.size, secondRun.size)
        assertEquals("Retry should reproduce the same text", firstRun.map { it.text }, secondRun.map { it.text })
        assertEquals("Retry should reproduce the same speaker labels", firstRun.map { it.speakerLabel }, secondRun.map { it.speakerLabel })

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
