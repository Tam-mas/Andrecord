package com.andrecord.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionRepositoryTest {

    private fun buildRepo(deletedPaths: MutableList<String> = mutableListOf()): Pair<SessionRepository, AndrecordDatabase> {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AndrecordDatabase::class.java
        ).allowMainThreadQueries().build()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { path -> deletedPaths.add(path) }
        return repo to db
    }

    @Test
    fun `createSession then markProcessing then finalizeReady moves through statuses`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 1000L)

        repo.markProcessing("s1", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 999_999L)
        assertEquals(SessionStatus.PROCESSING, db.sessionDao().getById("s1")?.status)

        repo.finalizeReady("s1", speakerCount = 3)
        val finalSession = db.sessionDao().getById("s1")
        assertEquals(SessionStatus.READY, finalSession?.status)
        assertEquals(3, finalSession?.speakerCount)
        db.close()
    }

    @Test
    fun `deleteExpiredAudio removes file and clears path only for expired sessions`() = runTest {
        val deleted = mutableListOf<String>()
        val (repo, db) = buildRepo(deleted)
        repo.createSession("s1", startTime = 1000L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 2000L)
        repo.createSession("s2", startTime = 1000L)
        repo.markProcessing("s2", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/s2.wav", audioDeleteAt = 9_000_000L)

        repo.deleteExpiredAudio(now = 3000L)

        assertEquals(listOf("/audio/s1.wav"), deleted)
        assertNull(db.sessionDao().getById("s1")?.audioFilePath)
        assertTrue(db.sessionDao().getById("s2")?.audioFilePath == "/audio/s2.wav")
        db.close()
    }

    @Test
    fun `reconcileInterruptedSessions marks only stuck RECORDING sessions as errored`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("stuck", startTime = 1000L)
        repo.createSession("processing", startTime = 1000L)
        repo.markProcessing("processing", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/p.wav", audioDeleteAt = 9_000_000L)
        repo.createSession("ready", startTime = 1000L)
        repo.finalizeReady("ready", speakerCount = 2)

        repo.reconcileInterruptedSessions()

        val stuck = db.sessionDao().getById("stuck")
        assertEquals(SessionStatus.ERROR, stuck?.status)
        assertTrue(stuck?.title?.contains("Interrupted") == true)
        assertEquals(SessionStatus.PROCESSING, db.sessionDao().getById("processing")?.status)
        assertEquals(SessionStatus.READY, db.sessionDao().getById("ready")?.status)
        db.close()
    }

    @Test
    fun `reconcileInterruptedSessions is a no-op on a second run`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("stuck", startTime = 1000L)

        repo.reconcileInterruptedSessions()
        val afterFirst = db.sessionDao().getById("stuck")?.title
        repo.reconcileInterruptedSessions()

        assertEquals(afterFirst, db.sessionDao().getById("stuck")?.title)
        db.close()
    }

    @Test
    fun `replaceSegments swaps a session's segments without touching other sessions`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 1000L)
        repo.createSession("s2", startTime = 1000L)
        repo.appendSegments(
            listOf(
                TranscriptSegment(sessionId = "s1", startMs = 0, endMs = 100, speakerLabel = null, text = "old"),
                TranscriptSegment(sessionId = "s2", startMs = 0, endMs = 100, speakerLabel = null, text = "other")
            )
        )

        repo.replaceSegments(
            "s1",
            listOf(TranscriptSegment(sessionId = "s1", startMs = 0, endMs = 100, speakerLabel = "Speaker 1", text = "old"))
        )

        val s1 = repo.getSegmentsOnce("s1")
        assertEquals(1, s1.size)
        assertEquals("Speaker 1", s1[0].speakerLabel)
        assertEquals(1, repo.getSegmentsOnce("s2").size)
        db.close()
    }

    @Test
    fun `rename updates title`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 1000L)

        repo.rename("s1", "Budget sync")

        assertEquals("Budget sync", db.sessionDao().getById("s1")?.title)
        db.close()
    }
}
