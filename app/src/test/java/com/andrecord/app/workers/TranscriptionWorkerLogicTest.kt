package com.andrecord.app.workers

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.data.SessionStatus
import com.andrecord.app.asr.OfflineAsrEngine
import com.andrecord.app.asr.WavFileReader
import com.andrecord.app.asr.WavSamples
import com.andrecord.app.diarization.DiarizationEngine
import com.andrecord.app.diarization.SpeakerSegment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranscriptionWorkerLogicTest {

    private class FakeDiarizationEngine(private val segments: List<SpeakerSegment>) : DiarizationEngine {
        var diarizeCalls = 0
        override fun diarize(wavFilePath: String): List<SpeakerSegment> {
            diarizeCalls++
            return segments
        }
    }

    /** Returns text keyed by call order (0-indexed); throws for any index in [throwOnCallIndex]. */
    private class FakeAsrEngine(
        private val textByCallIndex: List<String>,
        private val throwOnCallIndex: Set<Int> = emptySet()
    ) : OfflineAsrEngine {
        var callCount = 0
        var releaseCalls = 0
        override fun transcribe(samples: FloatArray, sampleRate: Int): String {
            val index = callCount
            callCount++
            if (index in throwOnCallIndex) throw RuntimeException("simulated transcription failure")
            return textByCallIndex[index]
        }
        override fun release() {
            releaseCalls++
        }
    }

    /** Synthetic silent samples of the given duration -- no real file I/O and no native code,
     *  since diarization/ASR are both faked and never actually run on the sample values. */
    private class FakeWavFileReader(private val durationMs: Long, private val sampleRate: Int = 16000) : WavFileReader {
        override fun read(wavFilePath: String): WavSamples =
            WavSamples(FloatArray(((durationMs * sampleRate) / 1000L).toInt()), sampleRate)
    }

    private fun buildDb(): AndrecordDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AndrecordDatabase::class.java
    ).allowMainThreadQueries().build()

    @Test
    fun `runTranscription builds segments directly from diarization and whisper output`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 5000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        val diarization = FakeDiarizationEngine(listOf(SpeakerSegment(startMs = 0, endMs = 5000, speakerIndex = 0)))
        val asr = FakeAsrEngine(listOf("hello there"))

        val speakerCount = TranscriptionWorker.runTranscription(repo, diarization, asr, FakeWavFileReader(5000L), "s1", "/audio/s1.wav")

        assertEquals(1, speakerCount)
        assertEquals(SessionStatus.READY, db.sessionDao().getById("s1")?.status)
        val segments = db.transcriptSegmentDao().getForSession("s1").first()
        assertEquals(1, segments.size)
        assertEquals("hello there", segments[0].text)
        assertEquals("Speaker 1", segments[0].speakerLabel)
        db.close()
    }

    @Test
    fun `a chunk that throws gets a placeholder and the rest still succeed`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 9000L, durationMs = 9000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        // Two segments far enough apart that padding produces exactly one chunk each.
        val diarization = FakeDiarizationEngine(
            listOf(
                SpeakerSegment(startMs = 0, endMs = 3000, speakerIndex = 0),
                SpeakerSegment(startMs = 4000, endMs = 9000, speakerIndex = 1)
            )
        )
        val asr = FakeAsrEngine(textByCallIndex = listOf("", "good to see you"), throwOnCallIndex = setOf(0))

        TranscriptionWorker.runTranscription(repo, diarization, asr, FakeWavFileReader(9000L), "s1", "/audio/s1.wav")

        val segments = db.transcriptSegmentDao().getForSession("s1").first()
        assertEquals(2, segments.size)
        assertEquals("[transcription failed for this segment]", segments[0].text)
        assertEquals("Speaker 1", segments[0].speakerLabel)
        assertEquals("good to see you", segments[1].text)
        assertEquals("Speaker 2", segments[1].speakerLabel)
        assertEquals(SessionStatus.READY, db.sessionDao().getById("s1")?.status)
        db.close()
    }

    @Test
    fun `a blank whisper result is dropped rather than inserted as an empty segment`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 9000L, durationMs = 9000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        // Two segments far enough apart that padding produces exactly one chunk each: the first
        // chunk's transcription legitimately returns blank text (not a thrown exception), the
        // second returns real text -- at least one non-blank segment is produced overall, so this
        // still finalizes to READY, but the blank chunk must be dropped rather than inserted as an
        // empty-string segment.
        val diarization = FakeDiarizationEngine(
            listOf(
                SpeakerSegment(startMs = 0, endMs = 3000, speakerIndex = 0),
                SpeakerSegment(startMs = 4000, endMs = 9000, speakerIndex = 1)
            )
        )
        val asr = FakeAsrEngine(listOf("", "good to see you"))

        TranscriptionWorker.runTranscription(repo, diarization, asr, FakeWavFileReader(9000L), "s1", "/audio/s1.wav")

        val segments = db.transcriptSegmentDao().getForSession("s1").first()
        assertEquals(1, segments.size)
        assertEquals("good to see you", segments[0].text)
        assertEquals(SessionStatus.READY, db.sessionDao().getById("s1")?.status)
        db.close()
    }

    @Test(expected = IllegalStateException::class)
    fun `when every chunk fails, runTranscription throws instead of finalizing`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 5000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        val diarization = FakeDiarizationEngine(listOf(SpeakerSegment(startMs = 0, endMs = 5000, speakerIndex = 0)))
        val asr = FakeAsrEngine(textByCallIndex = listOf(""), throwOnCallIndex = setOf(0))

        try {
            TranscriptionWorker.runTranscription(repo, diarization, asr, FakeWavFileReader(5000L), "s1", "/audio/s1.wav")
        } finally {
            db.close()
        }
    }

    @Test
    fun `progress is reported per chunk and left at 100 percent when the final check throws before finalizing`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 9000L, durationMs = 9000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        // Two segments far enough apart to produce exactly one chunk each -- both fail, so
        // runTranscription throws after the loop (and after both progress updates) completes,
        // leaving finalizeReady's progress-clearing never reached.
        val diarization = FakeDiarizationEngine(
            listOf(
                SpeakerSegment(startMs = 0, endMs = 3000, speakerIndex = 0),
                SpeakerSegment(startMs = 4000, endMs = 9000, speakerIndex = 1)
            )
        )
        val asr = FakeAsrEngine(textByCallIndex = listOf("", ""), throwOnCallIndex = setOf(0, 1))

        try {
            TranscriptionWorker.runTranscription(repo, diarization, asr, FakeWavFileReader(9000L), "s1", "/audio/s1.wav")
        } catch (e: IllegalStateException) {
            // Expected -- see the sibling "every chunk fails" test.
        }

        val session = db.sessionDao().getById("s1")
        assertEquals(100, session?.processingProgressPercent)
        assertEquals(0L, session?.processingEtaMillis)
        db.close()
    }

    @Test
    fun `computeProgressPercent divides chunks done by total chunks`() {
        assertEquals(0, TranscriptionWorker.computeProgressPercent(0, 4))
        assertEquals(25, TranscriptionWorker.computeProgressPercent(1, 4))
        assertEquals(50, TranscriptionWorker.computeProgressPercent(2, 4))
        assertEquals(100, TranscriptionWorker.computeProgressPercent(4, 4))
    }

    @Test
    fun `computeProgressPercent treats zero total chunks as fully complete`() {
        assertEquals(100, TranscriptionWorker.computeProgressPercent(0, 0))
    }

    @Test
    fun `estimateRemainingMillis is null before the first chunk finishes`() {
        assertEquals(null, TranscriptionWorker.estimateRemainingMillis(elapsedMillis = 5000L, chunksDone = 0, totalChunks = 4))
    }

    @Test
    fun `estimateRemainingMillis extrapolates from the average time per chunk so far`() {
        // 2 of 4 chunks done in 4000ms -- 2000ms/chunk average, 2 chunks remaining.
        assertEquals(4000L, TranscriptionWorker.estimateRemainingMillis(elapsedMillis = 4000L, chunksDone = 2, totalChunks = 4))
    }

    @Test
    fun `estimateRemainingMillis is zero once every chunk is done`() {
        assertEquals(0L, TranscriptionWorker.estimateRemainingMillis(elapsedMillis = 8000L, chunksDone = 4, totalChunks = 4))
    }

    @Test(expected = IllegalStateException::class)
    fun `when every chunk produces only blank text, runTranscription throws instead of finalizing`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 5000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        val diarization = FakeDiarizationEngine(listOf(SpeakerSegment(startMs = 0, endMs = 5000, speakerIndex = 0)))
        // Whisper "succeeds" (doesn't throw) on every chunk but silently produces no output --
        // Bug B from the review: successCount alone would have made this look like a success.
        val asr = FakeAsrEngine(listOf(""))

        try {
            TranscriptionWorker.runTranscription(repo, diarization, asr, FakeWavFileReader(5000L), "s1", "/audio/s1.wav")
        } finally {
            db.close()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `when diarization finds zero speaker segments, runTranscription throws instead of finalizing with an empty transcript`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 5000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        // Bug A from the review: zero diarization segments means zero chunks, and the old
        // `chunks.isEmpty() || successCount > 0` check treated that as automatic success -- which
        // would have wiped out any transcript segments RecordingService already flushed during
        // recording via replaceSegments(sessionId, emptyList()).
        val diarization = FakeDiarizationEngine(emptyList())
        val asr = FakeAsrEngine(emptyList())

        try {
            TranscriptionWorker.runTranscription(repo, diarization, asr, FakeWavFileReader(5000L), "s1", "/audio/s1.wav")
        } finally {
            db.close()
        }
    }

    @Test
    fun `re-running runTranscription is idempotent and does not duplicate segments`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 5000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        val diarization = FakeDiarizationEngine(listOf(SpeakerSegment(startMs = 0, endMs = 5000, speakerIndex = 0)))
        val wavFileReader = FakeWavFileReader(5000L)

        TranscriptionWorker.runTranscription(repo, diarization, FakeAsrEngine(listOf("first pass")), wavFileReader, "s1", "/audio/s1.wav")
        val afterFirstRun = db.transcriptSegmentDao().getForSession("s1").first()

        // Simulates a manual retry (Task 8) or a WorkManager retry re-running the same worker.
        TranscriptionWorker.runTranscription(repo, diarization, FakeAsrEngine(listOf("second pass")), wavFileReader, "s1", "/audio/s1.wav")
        val afterSecondRun = db.transcriptSegmentDao().getForSession("s1").first()

        assertEquals(1, afterFirstRun.size)
        assertEquals(1, afterSecondRun.size)
        assertEquals("second pass", afterSecondRun[0].text)
        assertEquals(2, diarization.diarizeCalls)
        db.close()
    }
}
