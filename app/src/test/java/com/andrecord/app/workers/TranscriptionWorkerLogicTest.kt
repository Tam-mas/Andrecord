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
        override fun transcribe(samples: FloatArray, sampleRate: Int): String {
            val index = callCount
            callCount++
            if (index in throwOnCallIndex) throw RuntimeException("simulated transcription failure")
            return textByCallIndex[index]
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
        repo.markProcessing("s1", endTime = 5000L, durationMs = 5000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        val diarization = FakeDiarizationEngine(listOf(SpeakerSegment(startMs = 0, endMs = 5000, speakerIndex = 0)))
        val asr = FakeAsrEngine(listOf(""))

        TranscriptionWorker.runTranscription(repo, diarization, asr, FakeWavFileReader(5000L), "s1", "/audio/s1.wav")

        assertTrue(db.transcriptSegmentDao().getForSession("s1").first().isEmpty())
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
