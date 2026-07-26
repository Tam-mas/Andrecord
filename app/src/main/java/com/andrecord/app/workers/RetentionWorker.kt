package com.andrecord.app.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andrecord.app.AndrecordApplication
import com.andrecord.app.data.SessionRepository

class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = (applicationContext as AndrecordApplication).container.sessionRepository
        runRetention(repository, System.currentTimeMillis())
        return Result.success()
    }

    companion object {
        suspend fun runRetention(repository: SessionRepository, now: Long) {
            repository.deleteExpiredAudio(now)
        }
    }
}
