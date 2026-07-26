package com.andrecord.app.workers

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.data.SessionStatus
import com.andrecord.app.diarization.DiarizationEngine
import com.andrecord.app.diarization.SpeakerSegment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiarizationWorkerLogicTest {

    private class FakeDiarizationEngine(private val segments: List<SpeakerSegment>) : DiarizationEngine {
        override fun diarize(wavFilePath: String): List<SpeakerSegment> = segments
    }

    @Test
    fun `runDiarization aligns segments, writes transcript, and finalizes session`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AndrecordDatabase::class.java
        ).allowMainThreadQueries().build()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 5000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        val engine = FakeDiarizationEngine(listOf(SpeakerSegment(0, 5000, speakerIndex = 0)))
        val asrSegments = listOf(AsrEvent.Final(startMs = 0, endMs = 2000, text = "hi there"))

        val speakerCount = DiarizationWorker.runDiarization(repo, engine, "s1", "/audio/s1.wav", asrSegments)

        assertEquals(1, speakerCount)
        assertEquals(SessionStatus.READY, db.sessionDao().getById("s1")?.status)
        assertEquals(1, db.sessionDao().getById("s1")?.speakerCount)
        val segments = db.transcriptSegmentDao().getForSession("s1")
        db.close()
    }
}
