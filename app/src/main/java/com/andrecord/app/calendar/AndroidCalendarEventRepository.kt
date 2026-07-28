package com.andrecord.app.calendar

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract

/**
 * Real `CalendarContract` implementation. `CalendarContract.Instances` (not `Events`) is
 * queried so recurring events are already expanded into concrete start/end-time occurrences
 * within the window -- `Events` alone would return one row per recurring series, not per
 * occurrence. Querying `Instances` with a time window is the documented pattern: build the URI
 * via `Instances.CONTENT_URI.buildUpon()` with the window's start/end appended as path segments
 * via `ContentUris.appendId`.
 *
 * `SecurityException` is caught in both methods (returning an empty result) rather than
 * propagated: `READ_CALENDAR` is a revocable runtime permission, and this repository is queried
 * from a `BroadcastReceiver` (see CalendarAlarmReceiver) where an uncaught exception would crash
 * the whole receiver. A missing/revoked permission is surfaced separately as a Settings state,
 * not as a crash here.
 */
class AndroidCalendarEventRepository(private val context: Context) : CalendarEventRepository {

    override fun getWatchableCalendars(): List<CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )
        val calendars = mutableListOf<CalendarInfo>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    calendars.add(CalendarInfo(cursor.getLong(idIndex), cursor.getString(nameIndex) ?: "Calendar"))
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return calendars
    }

    override fun getEventsInWindow(windowStartMillis: Long, windowEndMillis: Long): List<CalendarEvent> {
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, windowStartMillis)
        ContentUris.appendId(uriBuilder, windowEndMillis)
        val uri = uriBuilder.build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )
        val calendarNames = getWatchableCalendars().associate { it.id to it.displayName }
        val events = mutableListOf<CalendarEvent>()
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val eventIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val calendarIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
                val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(eventIdIndex)
                    val calendarId = cursor.getLong(calendarIdIndex)
                    events.add(
                        CalendarEvent(
                            eventId = eventId,
                            calendarId = calendarId,
                            calendarName = calendarNames[calendarId] ?: "Calendar",
                            startTimeMillis = cursor.getLong(beginIndex),
                            endTimeMillis = cursor.getLong(endIndex),
                            isAllDay = cursor.getInt(allDayIndex) != 0,
                            attendeeCount = getAttendeeCount(eventId)
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return events
    }

    private fun getAttendeeCount(eventId: Long): Int {
        val projection = arrayOf(CalendarContract.Attendees._ID)
        val selection = "${CalendarContract.Attendees.EVENT_ID} = ?"
        return try {
            context.contentResolver.query(
                CalendarContract.Attendees.CONTENT_URI, projection, selection, arrayOf(eventId.toString()), null
            )?.use { cursor -> cursor.count } ?: 0
        } catch (e: SecurityException) {
            0
        }
    }
}
