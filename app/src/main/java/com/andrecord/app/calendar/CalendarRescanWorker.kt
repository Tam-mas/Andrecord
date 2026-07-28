package com.andrecord.app.calendar

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andrecord.app.AndrecordApplication

/**
 * Infrequent backstop, not the precision mechanism (design spec §6): if a calendar change is
 * missed while the app process wasn't running to observe it via the ContentObserver in
 * AndrecordApplication, this eventually picks it up anyway.
 */
class CalendarRescanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as AndrecordApplication).container.calendarAutoRecordScheduler.rescan()
        return Result.success()
    }
}
