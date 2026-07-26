package com.andrecord.app.recording

import android.Manifest
import android.app.Notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.andrecord.app.AndrecordApplication
import com.andrecord.app.AppContainer
import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.data.TranscriptSegment
import com.andrecord.app.workers.DiarizationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class RecordingService : Service() {

    // SupervisorJob (rather than a plain Job) so that an uncaught failure in one child
    // coroutine (e.g. the capture-loop job) doesn't cancel the shared parent and silently
    // prevent unrelated sibling coroutines (e.g. a later scope.launch from stopRecording()
    // or from a subsequent recording's error-marking) from ever running.
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var audioRecord: AudioRecord? = null

    // sessionId/wavFile are written from the main thread (startRecording/abortStart) *and* from
    // the capture coroutine's mid-recording failure path, and read by stopRecording() on the main
    // thread. Volatile so the failure path's clear-out is promptly visible to a later stop rather
    // than leaving it acting on a recording that is already over.
    @Volatile
    private var sessionId: String? = null
    @Volatile
    private var wavFile: File? = null
    private var startTime: Long = 0L
    private var recordingJob: Job? = null

    // Set by the capture coroutine, before it completes, to the id of a recording it failed and
    // marked ERROR itself. stopRecording()'s join() returns for an already-completed job whether
    // it succeeded or failed, so it consults this to tell the two apart; keyed by id rather than a
    // bare boolean so it can never be misread as applying to a different recording.
    @Volatile
    private var failedSessionId: String? = null

    // Signals the capture loop to stop. A plain (non-volatile) `audioRecord = null` write from
    // stopRecording()'s calling thread is not guaranteed to be visible promptly to the capture
    // loop running on a Dispatchers.Default thread, and worse, stopRecording() must not release
    // audioRecord/the ASR engine itself while the capture loop may still be mid-call on them
    // (a use-after-free otherwise, since AudioRecord and the sherpa-onnx stream are single-owner,
    // not thread-safe resources). Only the capture loop's own coroutine tears them down; this
    // flag is just the cross-thread signal to do so.
    @Volatile
    private var stopRequested = false

    // Guards stopRecording() so it only runs its stop/teardown logic once per recording
    // session. Without this, a second ACTION_STOP delivered after stopRecording() has already
    // completed (e.g. the notification's "Stop" button, which sends ACTION_STOP straight to the
    // service, bypassing RecordingController's IDLE/RECORDING guard) would pass the sessionId
    // != null check again and, since join() on an already-completed job returns immediately,
    // re-run markProcessing()/enqueue DiarizationWorker even after a mid-recording failure has
    // already called markError(). Reset at the start of the next startRecording(), not inside
    // stopRecording() itself, so a genuinely new session isn't blocked by a stale flag.
    @Volatile
    private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getStringExtra(EXTRA_SESSION_ID)!!)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(id: String) {
        sessionId = id
        startTime = System.currentTimeMillis()
        stopRequested = false
        stopping = false
        val container = (application as AndrecordApplication).container
        wavFile = File(filesDir, "audio/$id.wav").apply { parentFile?.mkdirs() }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            abortStart(id, "Microphone permission not granted")
            return
        }
        if (!hasEnoughStorage()) {
            abortStart(id, "Not enough storage to record")
            return
        }

        // getMinBufferSize() returns a negative ERROR/ERROR_BAD_VALUE constant when the requested
        // format isn't supported or the audio service is unreachable; ShortArray(negative) below
        // would throw, so treat it as a pre-flight failure like any other.
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufferSize <= 0) {
            abortStart(id, "Microphone is unavailable")
            return
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2
            )
        } catch (e: IllegalArgumentException) {
            abortStart(id, "Microphone is unavailable")
            return
        }
        // The AudioRecord constructor does not throw when the mic is already owned by another
        // app (a call in progress, a voice assistant that hasn't released it yet) -- it hands
        // back an object in STATE_UNINITIALIZED, and startRecording() on that object throws
        // IllegalStateException straight onto the caller's thread, crashing the service.
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            abortStart(id, "Microphone is in use by another app")
            return
        }

        val notification = buildNotification("Recording…")
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        vibrate(longArrayOf(0, 150))

        audioRecord = record
        container.streamingAsrEngine.start()
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            container.streamingAsrEngine.stop()
            releaseAudioRecord(record)
            audioRecord = null
            abortStart(id, "Microphone is in use by another app")
            return
        }

        recordingJob = scope.launch {
            // Opened inside the try below rather than here: a throw out here (a WAV file that
            // can't be opened) would skip the teardown entirely, leaking the microphone and the
            // ASR stream and letting the exception escape the coroutine -- the same silent
            // "join() returns, stop marks it PROCESSING" failure described on `step`.
            var pcmFile: RandomAccessFile? = null
            var lastFlush = System.currentTimeMillis()
            var failureReason: String? = null

            // Runs one finalization step, turning a throw into a failureReason instead of letting
            // it escape this coroutine. Everything after the capture loop goes through this,
            // because an escaping exception here is worse than a plain crash: join() returns for
            // an exceptionally-completed job exactly as it does for a successful one, so
            // stopRecording() would carry on to markProcessing() + enqueue diarization while the
            // final flush and markError() were both skipped -- a truncated or empty transcript
            // presented as a successful recording, with the exception itself disappearing into the
            // default uncaught-exception handler. Steps are guarded individually so a failure in
            // one still lets the others run: finalizeWavHeader()'s seek/write can hit the very
            // out-of-storage condition that got us here, and the microphone must be released
            // regardless.
            suspend fun step(what: String, block: suspend () -> Unit) {
                try {
                    block()
                } catch (e: Exception) {
                    if (failureReason == null) failureReason = "$what failed: ${e.message}"
                }
            }

            // Finalized ASR utterances that haven't reached Room yet. Deliberately local to this
            // coroutine (rather than the process-wide map this used to accumulate into): only this
            // coroutine touches it, flushPendingSegments() drains what it writes, and nothing
            // outside this service reads it -- DiarizationWorker now reads the flushed rows back
            // out of Room instead, so it survives the process death WorkManager outlives.
            val pendingSegments = mutableListOf<AsrEvent.Final>()

            try {
                val out = RandomAccessFile(wavFile, "rw")
                pcmFile = out
                writeWavPlaceholderHeader(out)
                val buffer = ShortArray(minBufferSize)

                while (!stopRequested) {
                    if (!hasEnoughStorage()) {
                        failureReason = "Ran out of storage"
                        break
                    }
                    val read = try {
                        record.read(buffer, 0, buffer.size)
                    } catch (e: SecurityException) {
                        failureReason = "Microphone permission was revoked"
                        break
                    }
                    if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION) {
                        failureReason = "Microphone became unavailable"
                        break
                    }
                    if (read <= 0) continue
                    val floatSamples = FloatArray(read) { buffer[it] / 32768.0f }
                    out.write(shortArrayToBytes(buffer, read))

                    container.streamingAsrEngine.acceptWaveform(floatSamples)
                    drainAsrEvents(container, pendingSegments)

                    if (System.currentTimeMillis() - lastFlush > FLUSH_INTERVAL_MS) {
                        flushPendingSegments(id, pendingSegments)
                        lastFlush = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                // Any unanticipated failure (e.g. an IOException from pcmFile.write()) that
                // isn't one of the two named failure modes above, which already set
                // failureReason and break cleanly. Falling through here (rather than letting
                // the exception propagate out of the coroutine) ensures the `finally` block
                // below still runs so the WAV file is finalized/closed and the AudioRecord/ASR
                // engine are released, instead of leaking them and leaving stopRecording()'s
                // join() to proceed against a never-finalized file.
                failureReason = failureReason ?: "Unexpected error: ${e.message}"
            } finally {
                pcmFile?.let { file ->
                    step("Finalizing the recording file") { finalizeWavHeader(file) }
                    step("Closing the recording file") { file.close() }
                }

                // Only this coroutine ever touches `record`/the ASR engine, so tearing them
                // down here (rather than from stopRecording()'s caller thread) avoids releasing
                // them out from under a still-running acceptWaveform()/read() call.
                step("Releasing the microphone") { releaseAudioRecord(record) }
                audioRecord = null
                // stop() drains sherpa-onnx's last in-progress hypothesis as one more Final event
                // before releasing the stream, so poll once more here to pick up the trailing
                // utterance -- otherwise whatever was said in the ~1.4s before the endpoint rule
                // would have fired (i.e. the last sentence, right before the user hits stop) is
                // decoded and then thrown away. This is the last point at which anyone can still
                // observe it: stopRecording() only joins this job.
                step("Stopping transcription") {
                    container.streamingAsrEngine.stop()
                    drainAsrEvents(container, pendingSegments)
                }
            }

            // Final flush. The 5-second cadence above always leaves a tail unwritten, and
            // DiarizationWorker reads these rows back out of Room as its source of truth, so
            // everything must be durable before stopRecording()'s join() returns and enqueues it.
            step("Saving the transcript") { flushPendingSegments(id, pendingSegments) }

            val reason = failureReason
            if (reason != null) {
                // Publish the failure before this job completes so stopRecording()'s join() is
                // guaranteed to see it. A stop can legitimately race a mid-recording failure --
                // the user hits the trigger in the same instant the disk fills -- and without this
                // it would pass its guards, join this job, and flip the just-errored session back
                // to PROCESSING with diarization enqueued over it.
                failedSessionId = id

                // Give the shared state back, exactly as abortStart() does for a pre-flight
                // failure: clearing sessionId/wavFile makes any later ACTION_STOP a clean no-op,
                // and resetting the controller means the user's next trigger press starts a fresh
                // recording instead of "stopping" this dead one. Guarded on the id still being
                // ours because everything above this point can suspend: a new recording may
                // already have been started on this same service instance, and clearing its state
                // or tearing down its foreground service would turn one failure into two.
                val stillCurrent = sessionId == id
                if (stillCurrent) {
                    sessionId = null
                    wavFile = null
                    container.recordingController.reportRecordingEnded()
                }

                try {
                    container.sessionRepository.markError(id, reason)
                } catch (e: Exception) {
                    // Last line of defence, and deliberately swallowed: there is nowhere left to
                    // route this to, and the session simply stays in RECORDING until the startup
                    // sweep (reconcileInterruptedSessions) surfaces it as errored. Rethrowing
                    // would take out the state resets above with it.
                }

                if (stillCurrent) {
                    ServiceCompat.stopForeground(this@RecordingService, Service.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun releaseAudioRecord(record: AudioRecord) {
        try {
            record.stop()
        } catch (e: IllegalStateException) {
            // Already stopped or in an invalid state (e.g. after ERROR_DEAD_OBJECT); we're
            // tearing down regardless, so there's nothing more to do here.
        }
        record.release()
    }

    private fun hasEnoughStorage(): Boolean {
        val stat = android.os.StatFs(filesDir.path)
        return stat.availableBytes > MIN_FREE_BYTES
    }

    private fun drainAsrEvents(container: AppContainer, into: MutableList<AsrEvent.Final>) {
        var event = container.streamingAsrEngine.poll()
        while (event != null) {
            if (event is AsrEvent.Final) into.add(event)
            event = container.streamingAsrEngine.poll()
        }
    }

    private suspend fun flushPendingSegments(id: String, pending: MutableList<AsrEvent.Final>) {
        flushSegments((application as AndrecordApplication).container.sessionRepository, id, pending)
    }

    /**
     * Abandons a recording that never actually got off the ground, leaving no trace that a later
     * stop could act on: the session is marked ERROR, this service's per-session state is cleared
     * so a stray ACTION_STOP no-ops instead of flipping the session back to PROCESSING, and the
     * shared RecordingController is reset to IDLE so the user's next trigger press starts a new
     * recording rather than "stopping" this dead one.
     */
    private fun abortStart(id: String, reason: String) {
        val container = (application as AndrecordApplication).container
        scope.launch { container.sessionRepository.markError(id, reason) }
        container.recordingController.reportRecordingEnded()
        sessionId = null
        wavFile = null

        // We were launched via startForegroundService(), so the platform still expects a
        // startForeground() call within its grace window even on this path; skipping it risks a
        // ForegroundServiceDidNotStartInTimeException. Best-effort: starting a microphone-typed
        // foreground service requires RECORD_AUDIO, which on the permission-denied path is
        // precisely what we don't have, and the platform answers with a SecurityException. There
        // is no better type available here, and crashing would be strictly worse than the
        // timeout we're trying to avoid.
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification("Recording failed"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            ServiceCompat.stopForeground(this, Service.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // See above; nothing further to do, we're tearing down regardless.
        }
        stopSelf()
    }

    private fun stopRecording() {
        // Checked before `stopping` so that a failed start (which clears sessionId) can never be
        // "stopped": without this, stop would sail past every guard, join an already-completed
        // job as a no-op, and flip the errored session back to PROCESSING with a WAV that was
        // never written.
        val id = sessionId ?: return
        val file = wavFile ?: return
        if (stopping) return
        stopping = true
        val container = (application as AndrecordApplication).container
        val endTime = System.currentTimeMillis()
        val durationMs = endTime - startTime

        stopRequested = true
        vibrate(longArrayOf(0, 100, 100, 100))

        scope.launch {
            // Wait for the capture loop to notice the stop signal and finish releasing the mic,
            // finalizing the WAV header, and stopping the ASR engine, before we read the (now
            // final) file path or hand it to DiarizationWorker.
            recordingJob?.join()

            // join() completes the same way for a job that failed as for one that finished
            // cleanly, so ask explicitly. If the capture loop failed this recording it has already
            // marked it ERROR and handed back the controller; continuing here would resurrect it
            // as PROCESSING and enqueue diarization over a WAV that was never finished.
            if (failedSessionId == id) return@launch

            container.sessionRepository.markProcessing(
                id, endTime, durationMs,
                audioFilePath = file.absolutePath,
                audioDeleteAt = endTime + TimeUnit.DAYS.toMillis(7)
            )
            val request = OneTimeWorkRequestBuilder<DiarizationWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(DiarizationWorker.KEY_SESSION_ID, id)
                        .putString(DiarizationWorker.KEY_WAV_PATH, file.absolutePath)
                        .putLong(DiarizationWorker.KEY_DURATION_MS, durationMs)
                        .putLong(DiarizationWorker.KEY_START_TIME, startTime)
                        .build()
                )
                .build()
            WorkManager.getInstance(applicationContext).enqueue(request)
            ServiceCompat.stopForeground(this@RecordingService, Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(text)
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun writeWavPlaceholderHeader(file: RandomAccessFile) {
        file.write(ByteArray(44)) // rewritten with real sizes in finalizeWavHeader
    }

    private fun finalizeWavHeader(file: RandomAccessFile) {
        val dataSize = file.length() - 44
        file.seek(0)
        file.write(buildWavHeader(dataSize.toInt(), SAMPLE_RATE))
    }

    private fun buildWavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val totalSize = 36 + dataSize
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()); header.putInt(totalSize); header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()); header.putInt(16); header.putShort(1); header.putShort(1)
        header.putInt(sampleRate); header.putInt(sampleRate * 2); header.putShort(2); header.putShort(16)
        header.put("data".toByteArray()); header.putInt(dataSize)
        return header.array()
    }

    private fun shortArrayToBytes(samples: ShortArray, count: Int): ByteArray {
        val bytes = java.nio.ByteBuffer.allocate(count * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(samples[i])
        return bytes.array()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_START = "com.andrecord.app.action.START"
        const val ACTION_STOP = "com.andrecord.app.action.STOP"
        const val EXTRA_SESSION_ID = "session_id"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "recording"
        private const val SAMPLE_RATE = 16000
        private const val FLUSH_INTERVAL_MS = 5000L
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024 // 50MB headroom

        /**
         * Writes the not-yet-persisted utterances in [pending] to Room as unlabeled segments and
         * drains them.
         *
         * Draining is the whole point, and why this lives in the companion where it can be tested
         * directly against a real repository: the old implementation re-read the entire
         * accumulated list on every 5-second tick and inserted all of it again, so a long meeting
         * wrote each utterance dozens of times over. Inserting before clearing means a failed
         * insert leaves the segments queued for the next attempt rather than dropping them.
         */
        suspend fun flushSegments(
            repository: SessionRepository,
            sessionId: String,
            pending: MutableList<AsrEvent.Final>
        ) {
            if (pending.isEmpty()) return
            repository.appendSegments(
                pending.map {
                    TranscriptSegment(
                        sessionId = sessionId, startMs = it.startMs, endMs = it.endMs,
                        speakerLabel = null, text = it.text
                    )
                }
            )
            pending.clear()
        }
    }
}
