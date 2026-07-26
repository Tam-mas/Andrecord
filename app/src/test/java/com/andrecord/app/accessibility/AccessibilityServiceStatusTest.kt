package com.andrecord.app.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceStatusTest {

    private val expected = "com.andrecord.app/com.andrecord.app.triggers.KeyTriggerAccessibilityService"

    @Test
    fun `null settings string is not enabled`() {
        assertFalse(AccessibilityServiceStatus.isServiceInEnabledList(null, expected))
    }

    @Test
    fun `blank settings string is not enabled`() {
        assertFalse(AccessibilityServiceStatus.isServiceInEnabledList("", expected))
    }

    @Test
    fun `matches a single fully-qualified entry`() {
        val settings = "com.andrecord.app/com.andrecord.app.triggers.KeyTriggerAccessibilityService"
        assertTrue(AccessibilityServiceStatus.isServiceInEnabledList(settings, expected))
    }

    @Test
    fun `matches the leading-dot shorthand form`() {
        val settings = "com.andrecord.app/.triggers.KeyTriggerAccessibilityService"
        assertTrue(AccessibilityServiceStatus.isServiceInEnabledList(settings, expected))
    }

    @Test
    fun `matches when present among multiple colon-separated services`() {
        val settings = "com.other.app/com.other.app.SomeService:" +
            "com.andrecord.app/com.andrecord.app.triggers.KeyTriggerAccessibilityService:" +
            "com.another.app/.AnotherService"
        assertTrue(AccessibilityServiceStatus.isServiceInEnabledList(settings, expected))
    }

    @Test
    fun `does not match when absent from the list`() {
        val settings = "com.other.app/com.other.app.SomeService:com.another.app/.AnotherService"
        assertFalse(AccessibilityServiceStatus.isServiceInEnabledList(settings, expected))
    }

    @Test
    fun `does not match a different service in the same package`() {
        val settings = "com.andrecord.app/com.andrecord.app.triggers.SomeOtherService"
        assertFalse(AccessibilityServiceStatus.isServiceInEnabledList(settings, expected))
    }

    @Test
    fun `tolerates surrounding whitespace around colon-separated entries`() {
        val settings = " com.other.app/com.other.app.SomeService : " +
            "com.andrecord.app/com.andrecord.app.triggers.KeyTriggerAccessibilityService "
        assertTrue(AccessibilityServiceStatus.isServiceInEnabledList(settings, expected))
    }

    @Test
    fun `malformed entry without a slash is ignored rather than crashing`() {
        val settings = "not-a-valid-component:com.andrecord.app/com.andrecord.app.triggers.KeyTriggerAccessibilityService"
        assertTrue(AccessibilityServiceStatus.isServiceInEnabledList(settings, expected))
    }
}
