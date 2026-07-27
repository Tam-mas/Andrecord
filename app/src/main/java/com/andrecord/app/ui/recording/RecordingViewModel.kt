package com.andrecord.app.ui.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andrecord.app.recording.LiveTranscriptSnapshot
import com.andrecord.app.recording.LiveTranscriptState
import com.andrecord.app.recording.RecordingController
import com.andrecord.app.recording.RecordingState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RecordingViewModel(
    liveTranscriptState: LiveTranscriptState,
    private val recordingController: RecordingController
) : ViewModel() {

    val snapshot: StateFlow<LiveTranscriptSnapshot> = liveTranscriptState.snapshot
    val recordingState: StateFlow<RecordingState> = recordingController.state

    fun onStopClick() {
        viewModelScope.launch {
            // toggle() is the controller's only public state transition -- there is no standalone
            // stop(). Guarding on RECORDING here means a Stop tap that loses the race against some
            // other path already ending the recording (volume-key stop, the notification's own
            // Stop action, a mid-recording failure) is a no-op instead of toggling an IDLE
            // controller back into a brand new, unattended recording.
            if (recordingController.state.value == RecordingState.RECORDING) {
                recordingController.toggle()
            }
        }
    }
}
