package com.andrecord.app

import android.app.Application
import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.asr.StreamingAsrEngine
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.diarization.DiarizationEngine
import com.andrecord.app.recording.RecordingController
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AppContainer(app: Application) {
    private val database = AndrecordDatabase.build(app)

    val sessionRepository = SessionRepository(
        database.sessionDao(),
        database.transcriptSegmentDao()
    ) { path -> File(path).delete() }

    val pendingAsrSegments = ConcurrentHashMap<String, MutableList<AsrEvent.Final>>()

    // Assigned by later tasks once their concrete implementations exist:
    // streamingAsrEngine by the sherpa-onnx streaming ASR task, diarizationEngine by the
    // sherpa-onnx diarization task, recordingController by the RecordingService task.
    lateinit var streamingAsrEngine: StreamingAsrEngine
    lateinit var diarizationEngine: DiarizationEngine
    lateinit var recordingController: RecordingController
}

class AndrecordApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
