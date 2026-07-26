package com.andrecord.app.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andrecord.app.AndrecordApplication
import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.diarization.DiarizationEngine
import com.andrecord.app.diarization.TranscriptAligner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class DiarizationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val wavFilePath = inputData.getString(KEY_WAV_PATH) ?: return Result.failure()

        val container = (applicationContext as AndrecordApplication).container
        val pendingAsrSegments = container.pendingAsrSegments.remove(sessionId).orEmpty()

        val speakerCount = try {
            runDiarization(container.sessionRepository, container.diarizationEngine, sessionId, wavFilePath, pendingAsrSegments)
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
        private const val MAX_ATTEMPTS = 3

        suspend fun runDiarization(
            repository: SessionRepository,
            engine: DiarizationEngine,
            sessionId: String,
            wavFilePath: String,
            pendingAsrSegments: List<AsrEvent.Final>
        ): Int? {
            val speakerSegments = engine.diarize(wavFilePath)
            val speakerCount = TranscriptAligner.speakerCount(speakerSegments)
            val transcriptSegments = TranscriptAligner.align(sessionId, pendingAsrSegments, speakerSegments)
            transcriptSegments.forEach { repository.appendSegment(it) }
            repository.finalizeReady(sessionId, speakerCount)
            return speakerCount
        }
    }
}
