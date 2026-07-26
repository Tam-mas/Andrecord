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

    private fun buildController(
        starter: FakeServiceStarter,
        ids: List<String> = listOf("fixed-id")
    ): Pair<RecordingController, AndrecordDatabase> {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AndrecordDatabase::class.java
        ).allowMainThreadQueries().build()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        val idQueue = ArrayDeque(ids)
        val controller = RecordingController(
            repository = repo,
            serviceStarter = starter,
            idGenerator = { idQueue.removeFirstOrNull() ?: "fixed-id" },
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

    @Test
    fun `reportRecordingEnded returns the controller to idle`() = runTest {
        val starter = FakeServiceStarter()
        val (controller, db) = buildController(starter)
        controller.toggle()
        assertEquals(RecordingState.RECORDING, controller.currentState())

        controller.reportRecordingEnded()

        assertEquals(RecordingState.IDLE, controller.currentState())
        db.close()
    }

    /** RecordingService.abortStart(): a pre-flight failure, before the capture loop ever runs. */
    @Test
    fun `after a failed start the next toggle starts a fresh session instead of stopping`() = runTest {
        val starter = FakeServiceStarter()
        val (controller, db) = buildController(starter, ids = listOf("failed-id", "next-id"))
        controller.toggle()
        controller.reportRecordingEnded()

        val state = controller.toggle()

        assertEquals(RecordingState.RECORDING, state)
        assertEquals(false, starter.stopCalled)
        assertEquals("next-id", starter.startedSessionId)
        assertEquals(SessionStatus.RECORDING, db.sessionDao().getById("next-id")?.status)
        db.close()
    }

    /**
     * The sibling case: the capture loop dies part-way through a recording that did start (ran out
     * of storage, mic permission revoked mid-recording, mic became unavailable). That path used to
     * call markError() + stopSelf() without telling the controller, leaving it in RECORDING, so the
     * next trigger press routed to stop() and flipped the just-errored session back to PROCESSING.
     * It now reports through the same call as the pre-flight path, which is why that method is
     * named for the outcome rather than for a failed start.
     */
    @Test
    fun `after a mid-recording failure the next toggle starts a fresh session instead of stopping`() = runTest {
        val starter = FakeServiceStarter()
        val (controller, db) = buildController(starter, ids = listOf("errored-id", "next-id"))
        controller.toggle()
        assertEquals(RecordingState.RECORDING, controller.currentState())

        controller.reportRecordingEnded()
        assertEquals(RecordingState.IDLE, controller.currentState())

        val state = controller.toggle()

        assertEquals(RecordingState.RECORDING, state)
        assertEquals(false, starter.stopCalled)
        assertEquals("next-id", starter.startedSessionId)
        assertEquals(SessionStatus.RECORDING, db.sessionDao().getById("next-id")?.status)
        db.close()
    }
}
