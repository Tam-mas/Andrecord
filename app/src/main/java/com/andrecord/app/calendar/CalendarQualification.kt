package com.andrecord.app.calendar

/**
 * Pure "does this event qualify for auto-record, and when should it stop" logic -- no Android
 * dependency, fully unit-testable against plain [CalendarEvent] values. See
 * docs/superpowers/specs/2026-07-27-calendar-auto-record-design.md §3-4 for the rules this
 * implements.
 */
object CalendarQualification {

    /** Default stop time is the event's end time plus this grace period, absorbing a meeting
     *  that runs slightly long while staying fully automatic (see the design spec §4). */
    const val GRACE_PERIOD_MILLIS = 5 * 60 * 1000L

    /** Minimum gap enforced between one recording's computed stop time and a following
     *  back-to-back recording's start time (see [computeStopTimeMillis]). Back-to-back calendar
     *  events can have zero gap between them, but scheduling the stop-A and start-B alarms for
     *  the identical instant is a real race: RecordingService's stop teardown (mic release, WAV
     *  finalize, ASR stop) completes asynchronously relative to RecordingController.stop()
     *  returning, so a start alarm firing at that same instant can race the still-in-flight
     *  teardown of the previous recording. This gap gives that teardown a safety margin to
     *  complete first, at the cost of B's recording starting slightly late when the two events
     *  are truly back-to-back with no gap at all.
     */
    const val HANDOFF_GAP_MILLIS = 15_000L

    fun isQualifying(event: CalendarEvent, watchedCalendarIds: Set<Long>): Boolean =
        event.calendarId in watchedCalendarIds && !event.isAllDay && event.attendeeCount >= 2

    /** The soonest qualifying event starting at or after [nowMillis], or `null` if none qualify. */
    fun findNextQualifyingEvent(
        events: List<CalendarEvent>,
        watchedCalendarIds: Set<Long>,
        nowMillis: Long
    ): CalendarEvent? =
        events
            .filter { isQualifying(it, watchedCalendarIds) && it.startTimeMillis >= nowMillis }
            .minByOrNull { it.startTimeMillis }

    /**
     * The default stop time is [event]'s end time plus [GRACE_PERIOD_MILLIS]. If another
     * qualifying event on a watched calendar starts within that grace window, the stop time is
     * brought forward to [HANDOFF_GAP_MILLIS] before that next event's start time instead (or
     * [event]'s own end time, whichever is later) -- so a back-to-back meeting gets its own clean
     * recording rather than one continuous session bleeding across both, with enough of a gap
     * between the two alarms that the first recording's teardown can complete before the second
     * one starts.
     */
    fun computeStopTimeMillis(
        event: CalendarEvent,
        allEvents: List<CalendarEvent>,
        watchedCalendarIds: Set<Long>
    ): Long {
        val defaultStop = event.endTimeMillis + GRACE_PERIOD_MILLIS
        val nextQualifying = allEvents
            .filter { it.eventId != event.eventId }
            .filter { isQualifying(it, watchedCalendarIds) }
            .filter { it.startTimeMillis > event.startTimeMillis && it.startTimeMillis < defaultStop }
            .minByOrNull { it.startTimeMillis }
        return if (nextQualifying != null) {
            maxOf(nextQualifying.startTimeMillis - HANDOFF_GAP_MILLIS, event.endTimeMillis)
        } else {
            defaultStop
        }
    }
}
