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
import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.data.TranscriptSegment
import com.andrecord.app.workers.DiarizationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class RecordingService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var audioRecord: AudioRecord? = null
    private var sessionId: String? = null
    private var startTime: Long = 0L
    private var wavFile: File? = null
    private var recordingJob: Job? = null

    // Signals the capture loop to stop. A plain (non-volatile) `audioRecord = null` write from
    // stopRecording()'s calling thread is not guaranteed to be visible promptly to the capture
    // loop running on a Dispatchers.Default thread, and worse, stopRecording() must not release
    // audioRecord/the ASR engine itself while the capture loop may still be mid-call on them
    // (a use-after-free otherwise, since AudioRecord and the sherpa-onnx stream are single-owner,
    // not thread-safe resources). Only the capture loop's own coroutine tears them down; this
    // flag is just the cross-thread signal to do so.
    @Volatile
    private var stopRequested = false

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
        val container = (application as AndrecordApplication).container
        wavFile = File(filesDir, "audio/$id.wav").apply { parentFile?.mkdirs() }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            scope.launch { container.sessionRepository.markError(id, "Microphone permission not granted") }
            stopSelf()
            return
        }
        if (!hasEnoughStorage()) {
            scope.launch { container.sessionRepository.markError(id, "Not enough storage to record") }
            stopSelf()
            return
        }

        val notification = buildNotification("Recording…")
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        vibrate(longArrayOf(0, 150))

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2
        )
        audioRecord = record
        container.streamingAsrEngine.start()
        record.startRecording()

        recordingJob = scope.launch {
            val pcmFile = RandomAccessFile(wavFile, "rw")
            writeWavPlaceholderHeader(pcmFile)
            val buffer = ShortArray(minBufferSize)
            var lastFlush = System.currentTimeMillis()
            var failureReason: String? = null

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
                pcmFile.write(shortArrayToBytes(buffer, read))

                container.streamingAsrEngine.acceptWaveform(floatSamples)
                var event = container.streamingAsrEngine.poll()
                while (event != null) {
                    if (event is AsrEvent.Final) {
                        container.pendingAsrSegments.getOrPut(id) { mutableListOf() }.add(event)
                    }
                    event = container.streamingAsrEngine.poll()
                }

                if (System.currentTimeMillis() - lastFlush > FLUSH_INTERVAL_MS) {
                    flushPendingSegments(id)
                    lastFlush = System.currentTimeMillis()
                }
            }
            finalizeWavHeader(pcmFile)
            pcmFile.close()

            // Only this coroutine ever touches `record`/the ASR engine, so tearing them down
            // here (rather than from stopRecording()'s caller thread) avoids releasing them out
            // from under a still-running acceptWaveform()/read() call.
            releaseAudioRecord(record)
            audioRecord = null
            container.streamingAsrEngine.stop()

            if (failureReason != null) {
                // Preserve whatever was already flushed to the DB rather than losing the session.
                flushPendingSegments(id)
                container.sessionRepository.markError(id, failureReason)
                ServiceCompat.stopForeground(this@RecordingService, Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
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

    private suspend fun flushPendingSegments(id: String) {
        val container = (application as AndrecordApplication).container
        val pending = container.pendingAsrSegments[id].orEmpty().toList()
        for (segment in pending) {
            container.sessionRepository.appendSegment(
                TranscriptSegment(sessionId = id, startMs = segment.startMs, endMs = segment.endMs, speakerLabel = null, text = segment.text)
            )
        }
    }

    private fun stopRecording() {
        val id = sessionId ?: return
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

            container.sessionRepository.markProcessing(
                id, endTime, durationMs,
                audioFilePath = wavFile!!.absolutePath,
                audioDeleteAt = endTime + TimeUnit.DAYS.toMillis(7)
            )
            val request = OneTimeWorkRequestBuilder<DiarizationWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(DiarizationWorker.KEY_SESSION_ID, id)
                        .putString(DiarizationWorker.KEY_WAV_PATH, wavFile!!.absolutePath)
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
    }
}
