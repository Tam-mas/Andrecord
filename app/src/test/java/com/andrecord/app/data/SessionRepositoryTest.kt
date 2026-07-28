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

    /**
     * The delete and the insert must be one transaction. Separately, a process death between them
     * (the WorkManager-retry scenario this pipeline is designed around) would leave the session
     * with zero rows, and the retry would then diarize nothing and finalize an empty session as
     * READY without an error anywhere.
     *
     * Rolling back a failed insert is the observable consequence of that transaction, so that is
     * what this asserts: the replacement's second row points at a session that doesn't exist, so
     * the insert half fails on the foreign key. Against the previous delete-then-insert
     * implementation the delete had already committed by then and the transcript was gone.
     */
    @Test
    fun `replaceSegments rolls the delete back when the insert fails`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 1000L)
        repo.appendSegments(
            listOf(
                TranscriptSegment(sessionId = "s1", startMs = 0, endMs = 100, speakerLabel = null, text = "first"),
                TranscriptSegment(sessionId = "s1", startMs = 100, endMs = 200, speakerLabel = null, text = "second")
            )
        )

        var threw = false
        try {
            repo.replaceSegments(
                "s1",
                listOf(
                    TranscriptSegment(sessionId = "s1", startMs = 0, endMs = 100, speakerLabel = "Speaker 1", text = "first"),
                    TranscriptSegment(sessionId = "no-such-session", startMs = 100, endMs = 200, speakerLabel = "Speaker 2", text = "second")
                )
            )
        } catch (e: Exception) {
            threw = true
        }

        assertTrue(threw)
        val survivors = repo.getSegmentsOnce("s1")
        assertEquals(listOf("first", "second"), survivors.map { it.text })
        assertTrue(survivors.all { it.speakerLabel == null })
        db.close()
    }

    @Test
    fun `replaceSegments with an empty list clears the session's segments`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 1000L)
        repo.appendSegments(
            listOf(TranscriptSegment(sessionId = "s1", startMs = 0, endMs = 100, speakerLabel = null, text = "old"))
        )

        repo.replaceSegments("s1", emptyList())

        assertTrue(repo.getSegmentsOnce("s1").isEmpty())
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

    @Test
    fun `markProcessingFailed sets ERROR without touching the title`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 1000L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 999_999L)
        val titleBefore = db.sessionDao().getById("s1")?.title

        repo.markProcessingFailed("s1")

        val session = db.sessionDao().getById("s1")
        assertEquals(SessionStatus.ERROR, session?.status)
        assertEquals(titleBefore, session?.title)
        db.close()
    }

    @Test
    fun `retryProcessing moves an errored session back to PROCESSING and re-enqueues`() = runTest {
        val enqueued = mutableListOf<Quadruple>()
        val (repo, db) = buildRepo()
        repo.transcriptionEnqueuer = { sessionId, wavFilePath, durationMs, startTime ->
            enqueued.add(Quadruple(sessionId, wavFilePath, durationMs, startTime))
        }
        repo.createSession("s1", startTime = 1000L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 999_999L)
        repo.markProcessingFailed("s1")

        repo.retryProcessing("s1")

        assertEquals(SessionStatus.PROCESSING, db.sessionDao().getById("s1")?.status)
        assertEquals(1, enqueued.size)
        assertEquals("s1", enqueued[0].sessionId)
        assertEquals("/audio/s1.wav", enqueued[0].wavFilePath)
        assertEquals(4000L, enqueued[0].durationMs)
        assertEquals(1000L, enqueued[0].startTime)
        db.close()
    }

    @Test
    fun `retryProcessing does nothing when the session has no audio file`() = runTest {
        val enqueued = mutableListOf<Quadruple>()
        val (repo, db) = buildRepo()
        repo.transcriptionEnqueuer = { sessionId, wavFilePath, durationMs, startTime ->
            enqueued.add(Quadruple(sessionId, wavFilePath, durationMs, startTime))
        }
        repo.createSession("s1", startTime = 1000L)
        // Never reached markProcessing, so audioFilePath/durationMs are still null -- mirrors a
        // session whose recording itself failed, which markProcessingFailed/retryProcessing should
        // never apply to since there is no audio to reprocess.
        repo.markError("s1", "some reason")

        repo.retryProcessing("s1")

        assertEquals(SessionStatus.ERROR, db.sessionDao().getById("s1")?.status)
        assertEquals(0, enqueued.size)
        db.close()
    }

    @Test
    fun `createSession with a calendar name appends it to the title`() = runTest {
        val (repo, db) = buildRepo()

        repo.createSession("s1", startTime = 1000L, calendarName = "Work")

        val title = db.sessionDao().getById("s1")?.title
        assertTrue("Expected title to end with the calendar name, was: $title", title!!.endsWith("— Work"))
        db.close()
    }

    @Test
    fun `createSession without a calendar name keeps the plain title`() = runTest {
        val (repo, db) = buildRepo()

        repo.createSession("s1", startTime = 1000L)

        val title = db.sessionDao().getById("s1")?.title
        assertEquals(false, title!!.contains("—"))
        db.close()
    }

    @Test
    fun `updateProcessingProgress sets progress and eta`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 1000L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 999_999L)

        repo.updateProcessingProgress("s1", progressPercent = 42, etaMillis = 12_000L)

        val session = db.sessionDao().getById("s1")
        assertEquals(42, session?.processingProgressPercent)
        assertEquals(12_000L, session?.processingEtaMillis)
        db.close()
    }

    @Test
    fun `finalizeReady clears processing progress`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 1000L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 999_999L)
        repo.updateProcessingProgress("s1", progressPercent = 80, etaMillis = 2000L)

        repo.finalizeReady("s1", speakerCount = 2)

        val session = db.sessionDao().getById("s1")
        assertNull(session?.processingProgressPercent)
        assertNull(session?.processingEtaMillis)
        db.close()
    }

    @Test
    fun `markProcessingFailed clears processing progress`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 1000L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 999_999L)
        repo.updateProcessingProgress("s1", progressPercent = 60, etaMillis = 3000L)

        repo.markProcessingFailed("s1")

        val session = db.sessionDao().getById("s1")
        assertNull(session?.processingProgressPercent)
        assertNull(session?.processingEtaMillis)
        db.close()
    }

    @Test
    fun `retryProcessing clears stale progress from a previous failed attempt`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 1000L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 999_999L)
        repo.updateProcessingProgress("s1", progressPercent = 90, etaMillis = 500L)
        repo.markProcessingFailed("s1")

        repo.retryProcessing("s1")

        val session = db.sessionDao().getById("s1")
        assertEquals(SessionStatus.PROCESSING, session?.status)
        assertNull(session?.processingProgressPercent)
        assertNull(session?.processingEtaMillis)
        db.close()
    }

    private data class Quadruple(val sessionId: String, val wavFilePath: String, val durationMs: Long, val startTime: Long)
}
