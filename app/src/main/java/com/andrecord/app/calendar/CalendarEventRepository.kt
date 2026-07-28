package com.andrecord.app.calendar

data class CalendarInfo(val id: Long, val displayName: String)

data class CalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val calendarName: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val isAllDay: Boolean,
    val attendeeCount: Int
)

interface CalendarEventRepository {
    /** Every calendar synced on the device, for the Settings multi-select list. */
    fun getWatchableCalendars(): List<CalendarInfo>

    /** Every event instance (recurrence-expanded) starting or ending within
     *  [windowStartMillis, windowEndMillis), across all calendars -- not pre-filtered by
     *  watched-calendar selection or qualification rules; see [CalendarQualification] for that. */
    fun getEventsInWindow(windowStartMillis: Long, windowEndMillis: Long): List<CalendarEvent>
}
