package com.andrecord.app.recording

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.data.SessionStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordingControllerTest {

    private class FakeServiceStarter : RecordingServiceStarter {
        var startedSessionId: String? = null
        var stopCalled = false
        override fun startRecording(sessionId: String) { startedSessionId = sessionId }
        override fun stopRecording() { stopCalled = true }
    }

    private fun buildController(starter: FakeServiceStarter): Pair<RecordingController, AndrecordDatabase> {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AndrecordDatabase::class.java
        ).allowMainThreadQueries().build()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        val controller = RecordingController(
            repository = repo,
            serviceStarter = starter,
            idGenerator = { "fixed-id" },
            clock = { 42_000L }
        )
        return controller to db
    }

    @Test
    fun `toggle from idle starts a new session`() = runTest {
        val starter = FakeServiceStarter()
        val (controller, db) = buildController(starter)

        val state = controller.toggle()

        assertEquals(RecordingState.RECORDING, state)
        assertEquals("fixed-id", starter.startedSessionId)
        assertEquals(SessionStatus.RECORDING, db.sessionDao().getById("fixed-id")?.status)
        db.close()
    }

    @Test
    fun `toggle again while recording stops it`() = runTest {
        val starter = FakeServiceStarter()
        val (controller, db) = buildController(starter)
        controller.toggle()

        val state = controller.toggle()

        assertEquals(RecordingState.IDLE, state)
        assertEquals(true, starter.stopCalled)
        db.close()
    }
}
