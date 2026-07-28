package com.andrecord.app.calendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.andrecord.app.AndrecordApplication
import com.andrecord.app.recording.RecordingState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles both the start and stop alarms scheduled by [CalendarAutoRecordScheduler]. Registered
 * with `android:exported="false"` in the manifest -- these alarms only ever come from this app's
 * own `PendingIntent`s, never from another app.
 *
 * `goAsync()` is required because both branches call suspend functions on
 * [com.andrecord.app.recording.RecordingController] (`startForCalendarEvent`/`stopIfActive`):
 * without it, the receiver's process could be killed the moment `onReceive` returns, before the
 * launched coroutine finishes.
 */
class CalendarAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val exceptionHandler = CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "Failed handling ${intent.action}", e)
        }
        CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler).launch {
            try {
                when (intent.action) {
                    ACTION_START -> handleStart(context, intent)
                    ACTION_STOP -> handleStop(context, intent)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Re-verifies the event this alarm was scheduled for is still valid (still exists, same
     * start time, still qualifies) before starting a recording -- see the design spec §7-8: the
     * event may have been deleted, moved, or edited below the attendee threshold since this alarm
     * was scheduled. If a recording is already active, this is a quiet no-op rather than
     * interrupting it. Either way, rescans afterward so the *next* qualifying event still gets
     * scheduled.
     */
    private suspend fun handleStart(context: Context, intent: Intent) {
        val container = (context.applicationContext as AndrecordApplication).container
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val expectedStart = intent.getLongExtra(EXTRA_EVENT_START, -1L)
        val calendarName = intent.getStringExtra(EXTRA_CALENDAR_NAME)

        if (calendarName != null && container.recordingController.currentState() == RecordingState.IDLE) {
            val watchedIds = container.appSettings.getWatchedCalendarIds()
            val windowStart = expectedStart - VERIFICATION_LOOKBEHIND_MILLIS
            val windowEnd = expectedStart + VERIFICATION_LOOKAHEAD_MILLIS
            val events = container.calendarEventRepository.getEventsInWindow(windowStart, windowEnd)
            val event = events.find { it.eventId == eventId && it.startTimeMillis == expectedStart }

            if (event != null && CalendarQualification.isQualifying(event, watchedIds)) {
                val sessionId = container.recordingController.startForCalendarEvent(calendarName)
                if (sessionId != null) {
                    val stopTime = CalendarQualification.computeStopTimeMillis(event, events, watchedIds)
                    container.calendarAutoRecordScheduler.scheduleStopAlarm(sessionId, stopTime)
                }
            }
        }
        container.calendarAutoRecordScheduler.rescan()
    }

    private suspend fun handleStop(context: Context, intent: Intent) {
        val container = (context.applicationContext as AndrecordApplication).container
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        container.recordingController.stopIfActive(sessionId)
    }

    companion object {
        private const val TAG = "CalendarAlarmReceiver"
        const val ACTION_START = "com.andrecord.app.calendar.action.START"
        const val ACTION_STOP = "com.andrecord.app.calendar.action.STOP"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_EVENT_START = "event_start"
        const val EXTRA_CALENDAR_NAME = "calendar_name"
        const val EXTRA_SESSION_ID = "session_id"

        /** Window padding around the expected start time when re-querying the specific event by
         *  id, to tolerate any minor clock/provider rounding rather than requiring an exact
         *  millisecond match on the query bounds themselves (the exact match is still enforced
         *  afterward, against [EXTRA_EVENT_START]). */
        private const val VERIFICATION_LOOKBEHIND_MILLIS = 60_000L

        /** Re-verification only needs to look far enough ahead to find back-to-back qualifying
         *  events within the stop-time grace window ([CalendarQualification.GRACE_PERIOD_MILLIS]),
         *  not the scheduler's full multi-day lookahead -- 24 hours comfortably covers any
         *  realistic single meeting's duration while cutting the cost of this query (including its
         *  per-event attendee-count lookup) roughly 7x versus the previous 7-day window. */
        private const val VERIFICATION_LOOKAHEAD_MILLIS = 24L * 60 * 60 * 1000
    }
}
