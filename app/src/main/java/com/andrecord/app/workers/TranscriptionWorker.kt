package com.andrecord.app.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.andrecord.app.AndrecordApplication
import com.andrecord.app.asr.OfflineAsrEngine
import com.andrecord.app.asr.SherpaOnnxWavFileReader
import com.andrecord.app.asr.WavFileReader
import com.andrecord.app.asr.WhisperChunker
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.data.TranscriptSegment
import com.andrecord.app.diarization.DiarizationEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val wavFilePath = inputData.getString(KEY_WAV_PATH) ?: return Result.failure()

        // Promote to a foreground-service-backed worker: diarization + per-chunk Whisper decoding
        // of a long meeting can run well past WorkManager's standard ~10 minute execution ceiling,
        // and being killed at that ceiling burns a retry attempt for no reason. Best-effort: if the
        // platform refuses the promotion, still attempt the work rather than failing outright.
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            // Intentionally continue unpromoted; see above.
        }

        val container = (applicationContext as AndrecordApplication).container

        val speakerCount = try {
            runTranscription(
                container.sessionRepository,
                container.diarizationEngine,
                container.whisperAsrEngine,
                SherpaOnnxWavFileReader(),
                sessionId,
                wavFilePath
            )
        } catch (e: Exception) {
            Log.w(TAG, "runTranscription failed for session $sessionId", e)
            null
        } finally {
            // Whisper's native model memory (hundreds of MB) is only needed for the duration of
            // this worker run -- release it as soon as we're done, success or failure, rather than
            // holding it for the rest of the app process's lifetime.
            container.whisperAsrEngine.release()
        }

        if (speakerCount == null) {
            if (runAttemptCount >= MAX_ATTEMPTS) {
                container.sessionRepository.markProcessingFailed(sessionId)
                notifyFailed(sessionId)
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
        return ForegroundInfo(
            PROGRESS_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun notifyReady(sessionId: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
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

    private fun notifyFailed(sessionId: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transcript processing failed", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Transcript processing failed")
            .setContentText("Tap the session to retry")
            .setAutoCancel(true)
            .build()
        manager.notify(sessionId.hashCode(), notification)
    }

    companion object {
        private const val TAG = "TranscriptionWorker"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_WAV_PATH = "wav_path"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_START_TIME = "start_time"
        private const val CHANNEL_ID = "transcript_ready"
        private const val PROGRESS_CHANNEL_ID = "transcript_processing"
        private const val PROGRESS_NOTIFICATION_ID = 2
        private const val MAX_ATTEMPTS = 3
        private const val FAILURE_PLACEHOLDER = "[transcription failed for this segment]"

        /**
         * Diarizes the WAV, chunks the result via [WhisperChunker], transcribes each chunk
         * independently, and builds the session's transcript directly from chunk boundaries +
         * speaker index + Whisper text -- no separate ASR-to-diarization alignment step, since
         * each chunk is transcribed from audio that's already speaker-labeled by construction.
         *
         * A chunk whose transcription throws gets a [FAILURE_PLACEHOLDER] segment rather than
         * aborting the whole session -- a transcript with one visible gap is better than losing an
         * entire meeting's transcript to one bad audio slice. If the end result is zero non-blank
         * transcript segments -- whether because every chunk threw, every chunk silently returned
         * blank text, or diarization itself produced zero speaker segments -- that's treated as a
         * systemic failure (not a one-off bad chunk) and this function throws, so the caller's
         * retry/[SessionRepository.markProcessingFailed] logic in [doWork] applies rather than
         * silently finalizing a session to READY with no (or almost no) transcript.
         *
         * `replaceSegments()`'s wholesale replace makes re-running this idempotent across
         * WorkManager retries and manual reprocessing alike.
         */
        suspend fun runTranscription(
            repository: SessionRepository,
            diarizationEngine: DiarizationEngine,
            asrEngine: OfflineAsrEngine,
            wavFileReader: WavFileReader,
            sessionId: String,
            wavFilePath: String
        ): Int? {
            val speakerSegments = diarizationEngine.diarize(wavFilePath)
            val speakerCount = speakerSegments.map { it.speakerIndex }.distinct().size

            val wave = wavFileReader.read(wavFilePath)
            val totalDurationMs = (wave.samples.size.toLong() * 1000L) / wave.sampleRate
            val chunks = WhisperChunker.chunk(totalDurationMs, speakerSegments)

            val transcriptSegments = mutableListOf<TranscriptSegment>()
            // Counts only chunks that produced real, non-blank Whisper output -- NOT
            // FAILURE_PLACEHOLDER segments from a thrown exception. Both end up as non-blank
            // strings in transcriptSegments (a placeholder is deliberately visible in the
            // transcript rather than silently dropped), so transcriptSegments.isNotEmpty() alone
            // can't tell "got real output" apart from "every chunk threw" -- which would
            // silently finalize an all-placeholder transcript as READY, exactly the kind of
            // silent failure this check exists to prevent.
            var realSegmentCount = 0
            for (chunk in chunks) {
                val chunkSamples = sliceSamples(wave.samples, wave.sampleRate, chunk.startMs, chunk.endMs)
                val transcribed = try {
                    asrEngine.transcribe(chunkSamples, wave.sampleRate)
                } catch (e: Exception) {
                    Log.w(TAG, "Chunk transcription failed for session $sessionId (${chunk.startMs}-${chunk.endMs}ms)", e)
                    null
                }
                val text = when {
                    transcribed != null && transcribed.isNotBlank() -> {
                        realSegmentCount++
                        transcribed
                    }
                    transcribed == null -> FAILURE_PLACEHOLDER
                    else -> null // transcribed successfully but blank -- dropped, not even a placeholder
                }
                if (text != null) {
                    transcriptSegments.add(
                        TranscriptSegment(
                            sessionId = sessionId,
                            startMs = chunk.startMs,
                            endMs = chunk.endMs,
                            speakerLabel = "Speaker ${chunk.speakerIndex + 1}",
                            text = text
                        )
                    )
                }
            }

            check(realSegmentCount > 0) {
                "No transcript segments produced for session $sessionId (${chunks.size} chunks attempted)"
            }

            repository.replaceSegments(sessionId, transcriptSegments)
            repository.finalizeReady(sessionId, speakerCount)
            return speakerCount
        }

        private fun sliceSamples(samples: FloatArray, sampleRate: Int, startMs: Long, endMs: Long): FloatArray {
            val startIdx = ((startMs * sampleRate) / 1000L).toInt().coerceIn(0, samples.size)
            val endIdx = ((endMs * sampleRate) / 1000L).toInt().coerceIn(startIdx, samples.size)
            return samples.copyOfRange(startIdx, endIdx)
        }

        /** Builds and enqueues the [TranscriptionWorker] request. Shared by RecordingService's
         *  normal post-recording enqueue and by [SessionRepository.retryProcessing], so the
         *  WorkManager request-building code exists in exactly one place. */
        fun enqueue(context: Context, sessionId: String, wavFilePath: String, durationMs: Long, startTime: Long) {
            val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_SESSION_ID, sessionId)
                        .putString(KEY_WAV_PATH, wavFilePath)
                        .putLong(KEY_DURATION_MS, durationMs)
                        .putLong(KEY_START_TIME, startTime)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
