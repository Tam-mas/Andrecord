package com.andrecord.app.recording

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the periodic flush RecordingService's capture loop performs while recording. The bug
 * this guards against was invisible to any single-flush test: only repeated flushes over an
 * accumulating buffer -- i.e. any recording longer than one flush interval -- revealed that every
 * earlier utterance was being re-inserted on each tick.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingServiceFlushTest {

    private fun buildRepo(): Pair<SessionRepository, AndrecordDatabase> {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AndrecordDatabase::class.java
        ).allowMainThreadQueries().build()
        return SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { } to db
    }

    private fun utterance(index: Int) = AsrEvent.Final(
        startMs = index * 1000L,
        endMs = index * 1000L + 800L,
        text = "utterance $index"
    )

    @Test
    fun `repeated flush cycles persist each utterance exactly once`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 0L)
        val pending = mutableListOf<AsrEvent.Final>()

        var next = 0
        // Six flush cycles, two new utterances each -- roughly a 30-second recording at the
        // service's 5-second flush interval.
        repeat(6) {
            pending.add(utterance(next++))
            pending.add(utterance(next++))
            RecordingService.flushSegments(repo, "s1", pending)
        }

        val segments = repo.getSegmentsOnce("s1")
        assertEquals(12, segments.size)
        assertEquals((0 until 12).map { "utterance $it" }, segments.map { it.text })
        assertEquals(segments.map { it.text }.distinct().size, segments.size)
        assertTrue(segments.all { it.speakerLabel == null })
        db.close()
    }

    @Test
    fun `flush drains the buffer so a subsequent empty flush writes nothing`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 0L)
        val pending = mutableListOf(utterance(0))

        RecordingService.flushSegments(repo, "s1", pending)
        assertTrue(pending.isEmpty())
        RecordingService.flushSegments(repo, "s1", pending)

        assertEquals(1, repo.getSegmentsOnce("s1").size)
        db.close()
    }
}
