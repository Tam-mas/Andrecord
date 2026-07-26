package com.andrecord.app

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.asr.StreamingAsrEngine
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.diarization.DiarizationEngine
import com.andrecord.app.recording.RecordingController
import com.andrecord.app.workers.RetentionWorker
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class AppContainer(app: Application) {
    private val database = AndrecordDatabase.build(app)

    val sessionRepository = SessionRepository(
        database.sessionDao(),
        database.transcriptSegmentDao()
    ) { path -> File(path).delete() }

    val pendingAsrSegments = ConcurrentHashMap<String, MutableList<AsrEvent.Final>>()

    lateinit var streamingAsrEngine: StreamingAsrEngine
    lateinit var diarizationEngine: DiarizationEngine
    lateinit var recordingController: RecordingController
}

class AndrecordApplication : Application(), Configuration.Provider {
    lateinit var container: AppContainer

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        scheduleRetention()
    }

    private fun scheduleRetention() {
        val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "audio_retention", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }
}
