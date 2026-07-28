package com.andrecord.app.calendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.andrecord.app.settings.AppSettings

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

    /**
     * Re-scans the watched calendars for the next qualifying event and (re)schedules exactly one
     * exact start alarm for it, replacing any previously scheduled one. Called whenever the "what
     * comes next" answer might have changed: this scheduler's own alarm firing (via
     * CalendarAlarmReceiver), a calendar data change, device boot, or the periodic safety-net
     * check (see Task 8). A no-op (with any existing start alarm cancelled) if the feature is
     * disabled, no calendars are watched, or the exact-alarm permission isn't granted.
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

    /** Schedules the tagged stop alarm for [sessionId] at [stopTimeMillis] (see the design spec
     *  §4 for how the stop time itself is computed). */
    fun scheduleStopAlarm(sessionId: String, stopTimeMillis: Long) {
        val intent = Intent(context, CalendarAlarmReceiver::class.java).apply {
            action = CalendarAlarmReceiver.ACTION_STOP
            putExtra(CalendarAlarmReceiver.EXTRA_SESSION_ID, sessionId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE_STOP, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopTimeMillis, pendingIntent)
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
        /** How far ahead a rescan looks for the next qualifying event (design spec §7). */
        const val LOOKAHEAD_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val REQUEST_CODE_START = 1001
        private const val REQUEST_CODE_STOP = 1002
    }
}
