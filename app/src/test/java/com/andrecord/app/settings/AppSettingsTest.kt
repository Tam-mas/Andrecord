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
}
