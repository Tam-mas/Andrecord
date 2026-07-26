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
    fun `rename updates title`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 1000L)

        repo.rename("s1", "Budget sync")

        assertEquals("Budget sync", db.sessionDao().getById("s1")?.title)
        db.close()
    }
}
