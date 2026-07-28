package com.andrecord.app.ui.recording

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.recording.LiveTranscriptState
import com.andrecord.app.recording.RecordingController
import com.andrecord.app.recording.RecordingServiceStarter
import com.andrecord.app.recording.RecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the fix for RecordingScreen's Stop button: it used to call `toggle()` unconditionally,
 * so if the recording had already ended some other way (volume-key stop, the notification's own
 * Stop action, a mid-recording failure) before the user tapped Stop, `toggle()` on an
 * already-IDLE controller started a brand new recording that then ran unattended in the
 * background once the screen navigated back. `onStopClick()` now only calls the controller when
 * it's actually RECORDING.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecordingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeServiceStarter : RecordingServiceStarter {
        var startCount = 0
        var stopCount = 0
        override fun startRecording(sessionId: String, calendarName: String?) { startCount++ }
        override fun stopRecording() { stopCount++ }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(starter: FakeServiceStarter): Pair<RecordingViewModel, RecordingController> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AndrecordDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        val controller = RecordingController(
            repository = repo,
            serviceStarter = starter,
            idGenerator = { "s1" }
        )
        val viewModel = RecordingViewModel(LiveTranscriptState(), controller)
        return viewModel to controller
    }

    @Test
    fun `onStopClick stops an active recording`() = runTest {
        val starter = FakeServiceStarter()
        val (viewModel, controller) = buildViewModel(starter)
        controller.toggle() // starts
        assertEquals(RecordingState.RECORDING, controller.state.value)

        viewModel.onStopClick()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, starter.stopCount)
        assertEquals(RecordingState.IDLE, controller.state.value)
    }

    @Test
    fun `onStopClick is a no-op when the recording already ended some other way`() = runTest {
        val starter = FakeServiceStarter()
        val (viewModel, controller) = buildViewModel(starter)
        controller.toggle() // starts
        // Simulates the recording having already ended via a different path (volume-key stop,
        // the notification's Stop action, a mid-recording failure) before the user's Stop tap on
        // this screen is processed.
        controller.reportRecordingEnded()
        assertEquals(RecordingState.IDLE, controller.state.value)

        viewModel.onStopClick()
        testDispatcher.scheduler.advanceUntilIdle()

        // startCount is 1 from the setup toggle() above; it must NOT have gone up to 2, which is
        // what would happen if onStopClick() called toggle() unconditionally on an IDLE
        // controller (starting a fresh, unattended recording instead of no-op'ing).
        assertEquals(1, starter.startCount)
        assertEquals(0, starter.stopCount)
        assertEquals(RecordingState.IDLE, controller.state.value)
    }

    @Test
    fun `recordingState mirrors the controller's state`() = runTest {
        val starter = FakeServiceStarter()
        val (viewModel, controller) = buildViewModel(starter)

        assertEquals(RecordingState.IDLE, viewModel.recordingState.value)

        controller.toggle()

        assertEquals(RecordingState.RECORDING, viewModel.recordingState.value)
    }
}
