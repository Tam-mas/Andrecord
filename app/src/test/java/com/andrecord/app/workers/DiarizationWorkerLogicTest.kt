package com.andrecord.app.workers

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.data.SessionStatus
import com.andrecord.app.data.TranscriptSegment
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
class DiarizationWorkerLogicTest {

    private class FakeDiarizationEngine(private val segments: List<SpeakerSegment>) : DiarizationEngine {
        var diarizeCalls = 0
        override fun diarize(wavFilePath: String): List<SpeakerSegment> {
            diarizeCalls++
            return segments
        }
    }

    private fun buildDb(): AndrecordDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AndrecordDatabase::class.java
    ).allowMainThreadQueries().build()

    /**
     * Mirrors what RecordingService's capture loop flushes while recording: unlabeled
     * segments, already durably in Room by the time the worker runs.
     */
    private suspend fun flushUnlabeled(repo: SessionRepository, sessionId: String, vararg rows: Triple<Long, Long, String>) {
        repo.appendSegments(
            rows.map { (start, end, text) ->
                TranscriptSegment(sessionId = sessionId, startMs = start, endMs = end, speakerLabel = null, text = text)
            }
        )
    }

    @Test
    fun `runDiarization labels the already-flushed segments and finalizes session`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 5000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        flushUnlabeled(repo, "s1", Triple(0L, 2000L, "hi there"))
        val engine = FakeDiarizationEngine(listOf(SpeakerSegment(0, 5000, speakerIndex = 0)))

        val speakerCount = DiarizationWorker.runDiarization(repo, engine, "s1", "/audio/s1.wav")

        assertEquals(1, speakerCount)
        assertEquals(SessionStatus.READY, db.sessionDao().getById("s1")?.status)
        assertEquals(1, db.sessionDao().getById("s1")?.speakerCount)

        val segments = db.transcriptSegmentDao().getForSession("s1").first()
        assertEquals(1, segments.size)
        assertEquals("hi there", segments[0].text)
        assertEquals("Speaker 1", segments[0].speakerLabel)

        db.close()
    }

    @Test
    fun `runDiarization replaces pre-flushed unlabeled rows rather than adding to them`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 9000L, durationMs = 9000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        flushUnlabeled(
            repo, "s1",
            Triple(0L, 2000L, "hi there"),
            Triple(3000L, 5000L, "good to see you"),
            Triple(6000L, 8000L, "likewise")
        )
        val engine = FakeDiarizationEngine(
            listOf(SpeakerSegment(0, 5000, speakerIndex = 0), SpeakerSegment(5000, 9000, speakerIndex = 1))
        )

        DiarizationWorker.runDiarization(repo, engine, "s1", "/audio/s1.wav")

        val segments = db.transcriptSegmentDao().getForSession("s1").first()
        assertEquals(3, segments.size)
        assertEquals(listOf("hi there", "good to see you", "likewise"), segments.map { it.text })
        assertEquals(listOf("Speaker 1", "Speaker 1", "Speaker 2"), segments.map { it.speakerLabel })
        assertTrue(segments.none { it.speakerLabel == null })

        db.close()
    }

    @Test
    fun `re-running runDiarization is idempotent and does not duplicate segments`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 9000L, durationMs = 9000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        flushUnlabeled(
            repo, "s1",
            Triple(0L, 2000L, "hi there"),
            Triple(6000L, 8000L, "likewise")
        )
        val engine = FakeDiarizationEngine(
            listOf(SpeakerSegment(0, 5000, speakerIndex = 0), SpeakerSegment(5000, 9000, speakerIndex = 1))
        )

        DiarizationWorker.runDiarization(repo, engine, "s1", "/audio/s1.wav")
        val afterFirstRun = db.transcriptSegmentDao().getForSession("s1").first()

        // Simulates a WorkManager retry of an already-completed alignment.
        DiarizationWorker.runDiarization(repo, engine, "s1", "/audio/s1.wav")
        val afterSecondRun = db.transcriptSegmentDao().getForSession("s1").first()

        assertEquals(2, afterFirstRun.size)
        assertEquals(2, afterSecondRun.size)
        assertEquals(afterFirstRun.map { it.text }, afterSecondRun.map { it.text })
        assertEquals(afterFirstRun.map { it.speakerLabel }, afterSecondRun.map { it.speakerLabel })
        assertEquals(2, engine.diarizeCalls)

        db.close()
    }

    @Test
    fun `runDiarization with no flushed segments still finalizes without writing rows`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 5000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        val engine = FakeDiarizationEngine(listOf(SpeakerSegment(0, 5000, speakerIndex = 0)))

        val speakerCount = DiarizationWorker.runDiarization(repo, engine, "s1", "/audio/s1.wav")

        assertEquals(1, speakerCount)
        assertTrue(db.transcriptSegmentDao().getForSession("s1").first().isEmpty())
        assertEquals(SessionStatus.READY, db.sessionDao().getById("s1")?.status)

        db.close()
    }
}
