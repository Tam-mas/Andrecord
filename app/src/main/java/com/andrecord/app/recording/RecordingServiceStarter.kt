package com.andrecord.app.recording

interface RecordingServiceStarter {
    fun startRecording(sessionId: String)
    fun stopRecording()
}

class AndroidRecordingServiceStarter(private val context: android.content.Context) : RecordingServiceStarter {
    override fun startRecording(sessionId: String) {
        val intent = android.content.Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_SESSION_ID, sessionId)
        context.startForegroundService(intent)
    }

    override fun stopRecording() {
        val intent = android.content.Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_STOP)
        context.startService(intent)
    }
}
