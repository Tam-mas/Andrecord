package com.andrecord.app.recording

interface RecordingServiceStarter {
    fun startRecording(sessionId: String)
    fun stopRecording()
}
