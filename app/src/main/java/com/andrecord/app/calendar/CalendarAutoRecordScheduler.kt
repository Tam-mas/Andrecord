package com.andrecord.app.calendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.andrecord.app.settings.AppSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Schedules exactly one exact `AlarmManager` alarm for the next qualifying calendar event's
 * start time, rather than a recurring poll -- see the design spec §2 for why (WorkManager's
 * 15-minute periodic minimum is too imprecise for a meeting's actual start time; a
 * continuously-running poll would drain battery for a feature that should be invisible until a
 * meeting happens).
 */
class CalendarAutoRecordScheduler(
    private val context: Context,
    private val eventRepository: CalendarEventRepository,
    private val appSettings: AppSettings
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val exceptionHandler = CoroutineExceptionHandler { _, e -> Log.w(TAG, "Async rescan failed", e) }
    private val asyncScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    /**
     * Re-scans the watched calendars for the next qualifying event and (re)schedules exactly one
     * exact start alarm for it, replacing any previously scheduled one. Called whenever the "what
     * comes next" answer might have changed: this scheduler's own alarm firing (via
     * CalendarAlarmReceiver), a calendar data change, device boot, or the periodic safety-net
     * check (see Task 8). A no-op (with any existing start alarm cancelled) if the feature is
     * disabled, no calendars are watched, or the exact-alarm permission isn't granted.
     *
     * Performs blocking `ContentResolver` I/O -- callers on the main thread must use
     * [rescanAsync] instead. [CalendarAlarmReceiver] calls this directly since it already runs on
     * its own `Dispatchers.IO`-backed coroutine and wants the rescan to finish before its
     * `goAsync()` result is finished.
     */
    fun rescan() {
        cancelStartAlarm()
        if (!appSettings.isCalendarAutoRecordEnabled()) return
        if (alarmManager?.canScheduleExactAlarms() != true) return
        val watchedIds = appSettings.getWatchedCalendarIds()
        if (watchedIds.isEmpty()) return

        val now = System.currentTimeMillis()
        val events = eventRepository.getEventsInWindow(now, now + LOOKAHEAD_WINDOW_MILLIS)
        val next = CalendarQualification.findNextQualifyingEvent(events, watchedIds, now) ?: return
        scheduleStartAlarm(next)
    }

    /**
     * Fire-and-forget wrapper around [rescan] for callers that run on the main thread: the
     * calendar `ContentObserver` and periodic-worker-adjacent glue in `AndrecordApplication`,
     * `CalendarBootReceiver`, and the Settings UI's toggle/checkbox handlers.
     */
    fun rescanAsync() {
        asyncScope.launch { rescan() }
    }

    /** Schedules the tagged stop alarm for [sessionId] at [stopTimeMillis] (see the design spec
     *  §4 for how the stop time itself is computed). A no-op if the exact-alarm permission has
     *  been revoked since the recording started -- the recording keeps running, it just won't
     *  auto-stop via this alarm; a manual stop remains available. */
    fun scheduleStopAlarm(sessionId: String, stopTimeMillis: Long) {
        if (alarmManager?.canScheduleExactAlarms() != true) {
            Log.w(TAG, "Cannot schedule stop alarm for session $sessionId: exact-alarm permission not granted")
            return
        }
        val intent = Intent(context, CalendarAlarmReceiver::class.java).apply {
            action = CalendarAlarmReceiver.ACTION_STOP
            putExtra(CalendarAlarmReceiver.EXTRA_SESSION_ID, sessionId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE_STOP, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopTimeMillis, pendingIntent)
    }

    private fun scheduleStartAlarm(event: CalendarEvent) {
        val intent = Intent(context, CalendarAlarmReceiver::class.java).apply {
            action = CalendarAlarmReceiver.ACTION_START
            putExtra(CalendarAlarmReceiver.EXTRA_EVENT_ID, event.eventId)
            putExtra(CalendarAlarmReceiver.EXTRA_EVENT_START, event.startTimeMillis)
            putExtra(CalendarAlarmReceiver.EXTRA_CALENDAR_NAME, event.calendarName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE_START, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, event.startTimeMillis, pendingIntent)
    }

    private fun cancelStartAlarm() {
        val intent = Intent(context, CalendarAlarmReceiver::class.java).apply { action = CalendarAlarmReceiver.ACTION_START }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE_START, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let { alarmManager?.cancel(it) }
    }

    companion object {
        private const val TAG = "CalendarAutoRecordScheduler"
        /** How far ahead a rescan looks for the next qualifying event (design spec §7). */
        const val LOOKAHEAD_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val REQUEST_CODE_START = 1001
        private const val REQUEST_CODE_STOP = 1002
    }
}
