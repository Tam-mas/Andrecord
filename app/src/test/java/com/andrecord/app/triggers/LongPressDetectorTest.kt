package com.andrecord.app.triggers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressDetectorTest {

    @Test
    fun `holding for 1000ms or more counts as a long press`() {
        var now = 0L
        val detector = LongPressDetector(holdMs = 1000L, clock = { now })

        detector.onKeyDown()
        now = 1000L
        val result = detector.onKeyUp()

        assertTrue(result)
    }

    @Test
    fun `holding for less than 1000ms does not count`() {
        var now = 0L
        val detector = LongPressDetector(holdMs = 1000L, clock = { now })

        detector.onKeyDown()
        now = 500L
        val result = detector.onKeyUp()

        assertFalse(result)
    }
}
