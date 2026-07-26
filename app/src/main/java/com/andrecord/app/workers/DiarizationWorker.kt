package com.andrecord.app.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.andrecord.app.AndrecordApplication
import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.diarization.DiarizationEngine
import com.andrecord.app.diarization.TranscriptAligner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiarizationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val wavFilePath = inputData.getString(KEY_WAV_PATH) ?: return Result.failure()

        // Promote to a foreground-service-backed worker: diarization of a long meeting can run
        // well past WorkManager's standard ~10 minute execution ceiling, and being killed at that
        // ceiling burns a retry attempt for no reason. A promoted worker has no such limit as
        // long as a notification is shown. Best-effort: if the platform refuses the promotion
        // (e.g. notifications denied, or a background-start restriction), we still attempt the
        // work rather than failing outright -- short recordings finish inside the normal limit.
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            // Intentionally continue unpromoted; see above.
        }

        val container = (applicationContext as AndrecordApplication).container

        val speakerCount = try {
            runDiarization(container.sessionRepository, container.diarizationEngine, sessionId, wavFilePath)
        } catch (e: Exception) {
            null
        }

        if (speakerCount == null) {
            // Exhausted usefulness of retrying at the call-site policy (WorkManager retries this
            // whole doWork() on RETRY); if we already retried, fail soft so the session is still usable.
            if (runAttemptCount >= MAX_ATTEMPTS) {
                container.sessionRepository.finalizeReady(sessionId, speakerCount = null)
                notifyReady(sessionId)
                return Result.success()
            }
            return Result.retry()
        }

        notifyReady(sessionId)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL_ID, "Transcript processing", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(applicationContext, PROGRESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Processing meeting transcript…")
            .setOngoing(true)
            .build()
        // minSdk is 34, so the typed overload is always required: an untyped foreground service
        // is rejected on Android 14+. `dataSync` is the correct type for local post-processing
        // (the mic is already released by this point, so `microphone` would be both wrong and
        // permission-gated). The type is declared on WorkManager's SystemForegroundService in
        // AndroidManifest.xml via tools:node="merge".
        return ForegroundInfo(
            PROGRESS_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun notifyReady(sessionId: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transcript ready", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val minutes = (inputData.getLong(KEY_DURATION_MS, 0L) / 60000L).toInt()
        val startedAt = SimpleDateFormat("h:mm a", Locale.US).format(Date(inputData.getLong(KEY_START_TIME, 0L)))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$minutes-minute meeting transcript ready")
            .setContentText("Started $startedAt")
            .setAutoCancel(true)
            .build()
        manager.notify(sessionId.hashCode(), notification)
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_WAV_PATH = "wav_path"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_START_TIME = "start_time"
        private const val CHANNEL_ID = "transcript_ready"
        private const val PROGRESS_CHANNEL_ID = "transcript_processing"
        private const val PROGRESS_NOTIFICATION_ID = 2
        private const val MAX_ATTEMPTS = 3

        /**
         * Reads the session's already-flushed, unlabeled transcript rows from the repository,
         * aligns them against diarization output, and swaps them for the speaker-labeled result.
         *
         * The unlabeled rows in Room -- not an in-memory buffer -- are deliberately the source of
         * truth here. RecordingService flushes them (including a final flush before it enqueues
         * this worker), so they survive the process death WorkManager is specifically designed to
         * outlive; an in-memory buffer would be empty in the fresh process and the session would
         * silently finalize with no transcript at all. It also makes retries idempotent: each run
         * re-derives from whatever rows exist and replaces them wholesale, so nothing accumulates.
         */
        suspend fun runDiarization(
            repository: SessionRepository,
            engine: DiarizationEngine,
            sessionId: String,
            wavFilePath: String
        ): Int? {
            val flushedSegments = repository.getSegmentsOnce(sessionId)
            val asrSegments = flushedSegments.map {
                AsrEvent.Final(startMs = it.startMs, endMs = it.endMs, text = it.text)
            }
            val speakerSegments = engine.diarize(wavFilePath)
            val speakerCount = TranscriptAligner.speakerCount(speakerSegments)
            val transcriptSegments = TranscriptAligner.align(sessionId, asrSegments, speakerSegments)
            repository.replaceSegments(sessionId, transcriptSegments)
            repository.finalizeReady(sessionId, speakerCount)
            return speakerCount
        }
    }
}
