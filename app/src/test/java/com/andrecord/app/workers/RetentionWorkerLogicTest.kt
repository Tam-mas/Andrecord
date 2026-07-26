package com.andrecord.app.workers

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RetentionWorkerLogicTest {

    @Test
    fun `runRetention clears expired audio paths`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AndrecordDatabase::class.java
        ).allowMainThreadQueries().build()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 1000L, durationMs = 1000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 5000L)

        RetentionWorker.runRetention(repo, now = 6000L)

        assertNull(db.sessionDao().getById("s1")?.audioFilePath)
        db.close()
    }
}
