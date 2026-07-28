package com.andrecord.app.ui.list

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.andrecord.app.accessibility.AccessibilityServiceStatus
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.recording.LiveTranscriptState
import com.andrecord.app.recording.RecordingController
import com.andrecord.app.recording.RecordingServiceStarter
import com.andrecord.app.recording.RecordingState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the fix for the persistent recording bar / FAB going stale: `recordingState` used to be
 * a local StateFlow that this ViewModel only ever wrote to from `onRecordButtonClick()`, so any
 * other way of starting/stopping a recording (the live view's Stop button, a volume-key/Quick-Tap
 * trigger, a mid-recording failure reported via `reportRecordingEnded()`) left this screen showing
 * outdated state. It's now `recordingController.state` directly, so every transition -- regardless
 * of who drove it -- is visible immediately.
 */
@RunWith(RobolectricTestRunner::class)
class SessionListViewModelTest {

    private class NoOpServiceStarter : RecordingServiceStarter {
        override fun startRecording(sessionId: String, calendarName: String?) {}
        override fun stopRecording() {}
    }

    private fun buildViewModel(): Pair<SessionListViewModel, RecordingController> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AndrecordDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        val controller = RecordingController(
            repository = repo,
            serviceStarter = NoOpServiceStarter(),
            idGenerator = { "s1" }
        )
        val viewModel = SessionListViewModel(
            repository = repo,
            recordingController = controller,
            accessibilityServiceStatus = AccessibilityServiceStatus(context),
            liveTranscriptState = LiveTranscriptState()
        )
        return viewModel to controller
    }

    @Test
    fun `recordingState is the same StateFlow instance as the controller's`() {
        val (viewModel, controller) = buildViewModel()

        assertEquals(controller.state, viewModel.recordingState)
    }

    @Test
    fun `recordingState reflects a toggle driven directly by the controller, not just onRecordButtonClick`() = runTest {
        val (viewModel, controller) = buildViewModel()
        assertEquals(RecordingState.IDLE, viewModel.recordingState.value)

        // Simulates a start/stop that didn't go through this screen's FAB at all -- e.g. a
        // volume-key trigger or the live recording screen's own Stop button calling the
        // controller directly.
        controller.toggle()

        assertEquals(RecordingState.RECORDING, viewModel.recordingState.value)
    }

    @Test
    fun `recordingState reflects reportRecordingEnded from a mid-recording failure`() = runTest {
        val (viewModel, controller) = buildViewModel()
        controller.toggle()
        assertEquals(RecordingState.RECORDING, viewModel.recordingState.value)

        controller.reportRecordingEnded()

        assertEquals(RecordingState.IDLE, viewModel.recordingState.value)
    }
}
