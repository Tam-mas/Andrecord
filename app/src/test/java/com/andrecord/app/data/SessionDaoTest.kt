package com.andrecord.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionDaoTest {

    private fun buildDb() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AndrecordDatabase::class.java
    ).allowMainThreadQueries().build()

    @Test
    fun `insert and read back a session`() = runTest {
        val db = buildDb()
        val session = Session(
            id = "s1",
            startTime = 1000L,
            endTime = null,
            durationMs = null,
            title = "Jul 26, 2026, 2:15 PM",
            status = SessionStatus.RECORDING,
            speakerCount = null,
            audioFilePath = "/data/audio/s1.wav",
            audioDeleteAt = null
        )
        db.sessionDao().insert(session)

        val loaded = db.sessionDao().getById("s1")

        assertEquals("Jul 26, 2026, 2:15 PM", loaded?.title)
        assertEquals(SessionStatus.RECORDING, loaded?.status)
        db.close()
    }

    @Test
    fun `sessions ordered newest first`() = runTest {
        val db = buildDb()
        db.sessionDao().insert(sessionAt("s1", 1000L))
        db.sessionDao().insert(sessionAt("s2", 2000L))

        val all = db.sessionDao().getAllOnce()

        assertEquals(listOf("s2", "s1"), all.map { it.id })
        db.close()
    }

    private fun sessionAt(id: String, startTime: Long) = Session(
        id = id, startTime = startTime, endTime = null, durationMs = null,
        title = id, status = SessionStatus.READY, speakerCount = null,
        audioFilePath = null, audioDeleteAt = null
    )
}
