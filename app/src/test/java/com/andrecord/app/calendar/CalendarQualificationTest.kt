package com.andrecord.app.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarQualificationTest {

    private fun event(
        eventId: Long,
        calendarId: Long = 1L,
        start: Long,
        end: Long,
        isAllDay: Boolean = false,
        attendeeCount: Int = 2
    ) = CalendarEvent(
        eventId = eventId,
        calendarId = calendarId,
        calendarName = "Work",
        startTimeMillis = start,
        endTimeMillis = end,
        isAllDay = isAllDay,
        attendeeCount = attendeeCount
    )

    @Test
    fun `isQualifying requires a watched calendar, non-all-day, and 2+ attendees`() {
        val watched = setOf(1L)
        assertEquals(true, CalendarQualification.isQualifying(event(1, start = 0, end = 1000), watched))
        assertEquals(false, CalendarQualification.isQualifying(event(1, calendarId = 2L, start = 0, end = 1000), watched))
        assertEquals(false, CalendarQualification.isQualifying(event(1, start = 0, end = 1000, isAllDay = true), watched))
        assertEquals(false, CalendarQualification.isQualifying(event(1, start = 0, end = 1000, attendeeCount = 1), watched))
    }

    @Test
    fun `findNextQualifyingEvent picks the soonest future qualifying event`() {
        val watched = setOf(1L)
        val events = listOf(
            event(1, start = 5000, end = 6000),
            event(2, start = 3000, end = 4000),
            event(3, calendarId = 2L, start = 1000, end = 2000) // not on a watched calendar
        )

        val next = CalendarQualification.findNextQualifyingEvent(events, watched, nowMillis = 2000)

        assertEquals(2L, next?.eventId)
    }

    @Test
    fun `findNextQualifyingEvent ignores events that already started`() {
        val watched = setOf(1L)
        val events = listOf(event(1, start = 1000, end = 2000))

        val next = CalendarQualification.findNextQualifyingEvent(events, watched, nowMillis = 1500)

        assertNull(next)
    }

    @Test
    fun `computeStopTimeMillis defaults to end time plus the grace period`() {
        val watched = setOf(1L)
        val target = event(1, start = 0, end = 60_000)

        val stop = CalendarQualification.computeStopTimeMillis(target, listOf(target), watched)

        assertEquals(60_000L + CalendarQualification.GRACE_PERIOD_MILLIS, stop)
    }

    @Test
    fun `computeStopTimeMillis brings the stop forward to a back-to-back qualifying event`() {
        val watched = setOf(1L)
        val target = event(1, start = 0, end = 60_000)
        val backToBack = event(2, start = 61_000, end = 120_000) // starts inside the 5-min grace window

        val stop = CalendarQualification.computeStopTimeMillis(target, listOf(target, backToBack), watched)

        assertEquals(61_000L, stop)
    }

    @Test
    fun `computeStopTimeMillis ignores a nearby event that does not qualify`() {
        val watched = setOf(1L)
        val target = event(1, start = 0, end = 60_000)
        val nonQualifying = event(2, start = 61_000, end = 120_000, attendeeCount = 1)

        val stop = CalendarQualification.computeStopTimeMillis(target, listOf(target, nonQualifying), watched)

        assertEquals(60_000L + CalendarQualification.GRACE_PERIOD_MILLIS, stop)
    }

    @Test
    fun `computeStopTimeMillis ignores a qualifying event that starts after the grace window`() {
        val watched = setOf(1L)
        val target = event(1, start = 0, end = 60_000)
        val farAway = event(2, start = 600_000, end = 700_000)

        val stop = CalendarQualification.computeStopTimeMillis(target, listOf(target, farAway), watched)

        assertEquals(60_000L + CalendarQualification.GRACE_PERIOD_MILLIS, stop)
    }
}
