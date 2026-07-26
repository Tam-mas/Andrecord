package com.andrecord.app.ui.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andrecord.app.recording.LiveTranscriptSnapshot
import com.andrecord.app.recording.LiveTranscriptState
import com.andrecord.app.recording.RecordingController
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RecordingViewModel(
    liveTranscriptState: LiveTranscriptState,
    private val recordingController: RecordingController
) : ViewModel() {

    val snapshot: StateFlow<LiveTranscriptSnapshot> = liveTranscriptState.snapshot

    fun onStopClick() {
        viewModelScope.launch { recordingController.toggle() }
    }
}
