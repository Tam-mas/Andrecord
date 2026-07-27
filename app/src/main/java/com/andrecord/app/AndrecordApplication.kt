package com.andrecord.app

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.andrecord.app.accessibility.AccessibilityServiceStatus
import com.andrecord.app.asr.OfflineAsrEngine
import com.andrecord.app.asr.SherpaOnnxStreamingAsrEngine
import com.andrecord.app.asr.SherpaOnnxWhisperAsrEngine
import com.andrecord.app.asr.StreamingAsrEngine
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.diarization.DiarizationEngine
import com.andrecord.app.diarization.SherpaOnnxDiarizationEngine
import com.andrecord.app.recording.AndroidRecordingServiceStarter
import com.andrecord.app.recording.LiveTranscriptState
import com.andrecord.app.recording.RecordingController
import com.andrecord.app.settings.AppSettings
import com.andrecord.app.workers.RetentionWorker
import com.andrecord.app.workers.TranscriptionWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class AppContainer(app: Application) {
    private val database = AndrecordDatabase.build(app)

    val sessionRepository = SessionRepository(
        database.sessionDao(),
        database.transcriptSegmentDao()
    ) { path -> File(path).delete() }

    val accessibilityServiceStatus = AccessibilityServiceStatus(app)
    val liveTranscriptState = LiveTranscriptState()
    val appSettings = AppSettings(app)

    lateinit var streamingAsrEngine: StreamingAsrEngine
    lateinit var diarizationEngine: DiarizationEngine
    lateinit var whisperAsrEngine: OfflineAsrEngine
    lateinit var recordingController: RecordingController
}

class AndrecordApplication : Application(), Configuration.Provider {
    lateinit var container: AppContainer

    // Application-lifetime scope for the startup work that has to happen off the main thread.
    // SupervisorJob so one failed startup task can't cancel the others.
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.streamingAsrEngine = SherpaOnnxStreamingAsrEngine(this)
        container.diarizationEngine = SherpaOnnxDiarizationEngine(this)
        container.whisperAsrEngine = SherpaOnnxWhisperAsrEngine(this)
        container.recordingController = RecordingController(container.sessionRepository, AndroidRecordingServiceStarter(this))
        container.sessionRepository.transcriptionEnqueuer = { sessionId, wavFilePath, durationMs, startTime ->
            TranscriptionWorker.enqueue(this, sessionId, wavFilePath, durationMs, startTime)
        }
        reconcileInterruptedSessions()
        scheduleRetention()
    }

    /**
     * A session whose process was killed mid-recording stays in RECORDING status forever --
     * nothing else ever moves it on, and the UI offers no way to clear it. Sweep once at startup
     * so those rows surface as errored instead of pretending to still be recording. Safe here
     * because the app process has just been created, so no recording can legitimately be in
     * flight yet.
     */
    private fun reconcileInterruptedSessions() {
        appScope.launch { container.sessionRepository.reconcileInterruptedSessions() }
    }

    private fun scheduleRetention() {
        val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "audio_retention", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }
}
