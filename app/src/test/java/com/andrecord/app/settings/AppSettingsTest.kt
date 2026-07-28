package com.andrecord.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `parseReopenBehavior returns the matching enum value`() {
        assertEquals(ReopenBehavior.SESSION_LIST, AppSettings.parseReopenBehavior("SESSION_LIST"))
    }

    @Test
    fun `parseReopenBehavior defaults to LIVE_VIEW for null`() {
        assertEquals(ReopenBehavior.LIVE_VIEW, AppSettings.parseReopenBehavior(null))
    }

    @Test
    fun `parseReopenBehavior defaults to LIVE_VIEW for an unrecognized value`() {
        assertEquals(ReopenBehavior.LIVE_VIEW, AppSettings.parseReopenBehavior("garbage"))
    }

    @Test
    fun `parseWatchedCalendarIds converts string set to longs`() {
        assertEquals(setOf(1L, 2L, 3L), AppSettings.parseWatchedCalendarIds(setOf("1", "2", "3")))
    }

    @Test
    fun `parseWatchedCalendarIds defaults to empty set for null`() {
        assertEquals(emptySet<Long>(), AppSettings.parseWatchedCalendarIds(null))
    }

    @Test
    fun `parseWatchedCalendarIds drops entries that are not valid longs`() {
        assertEquals(setOf(1L), AppSettings.parseWatchedCalendarIds(setOf("1", "garbage")))
    }
}
