package com.andrecord.app.accessibility

import android.content.Context
import android.provider.Settings
import com.andrecord.app.triggers.KeyTriggerAccessibilityService

/**
 * Tracks whether [KeyTriggerAccessibilityService] (the volume-key recording trigger) is enabled
 * in the system's Accessibility settings, and whether the user has dismissed the in-app prompt
 * to enable it.
 *
 * Android has no runtime-permission dialog for Accessibility services -- the user must flip it
 * on manually in Settings -- so the UI (see `SessionListScreen`'s banner) instead surfaces a
 * one-tap deep link there via `Settings.ACTION_ACCESSIBILITY_SETTINGS`, and this class answers
 * "is it on, and should we still be nagging about it".
 */
class AccessibilityServiceStatus(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val expectedComponentName: String
        get() = "${context.packageName}/${KeyTriggerAccessibilityService::class.java.name}"

    /** Reads the live system setting via [Context.getContentResolver]. Not unit-testable without
     * a real (or Robolectric) `ContentResolver`; the string-matching it delegates to is. */
    fun isServiceEnabled(): Boolean {
        val accessibilityOn = Settings.Secure.getInt(
            context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
        ) == 1
        if (!accessibilityOn) return false

        val enabledServices = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return isServiceInEnabledList(enabledServices, expectedComponentName)
    }

    fun isDismissed(): Boolean = prefs.getBoolean(KEY_DISMISSED, false)

    fun dismiss() {
        prefs.edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    /** Never shown once the service is actually enabled (dismissal is then moot), and never
     * shown again once the user has dismissed it while it's still disabled. */
    fun shouldShowBanner(): Boolean = !isServiceEnabled() && !isDismissed()

    companion object {
        private const val PREFS_NAME = "accessibility_service_status"
        private const val KEY_DISMISSED = "banner_dismissed"

        /**
         * Pure string-matching logic, extracted so it's unit-testable with plain JUnit (no
         * Android framework / Robolectric needed): does [enabledServicesSetting] -- the raw,
         * colon-separated value of `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` -- contain
         * [expectedComponentName] (a "pkg/pkg.Class" component name)?
         *
         * Each entry is normalized before comparing because Android accepts a "pkg/.Class"
         * shorthand (a leading dot means "relative to the package") in addition to the fully
         * qualified "pkg/pkg.Class" form, and either could appear in the setting string.
         */
        fun isServiceInEnabledList(enabledServicesSetting: String?, expectedComponentName: String): Boolean {
            if (enabledServicesSetting.isNullOrBlank()) return false
            val expected = normalizeComponentName(expectedComponentName) ?: return false
            return enabledServicesSetting.split(':').any { entry ->
                normalizeComponentName(entry.trim()) == expected
            }
        }

        private fun normalizeComponentName(raw: String): String? {
            val sep = raw.indexOf('/')
            if (sep < 0 || sep + 1 >= raw.length) return null
            val pkg = raw.substring(0, sep)
            var cls = raw.substring(sep + 1)
            if (cls.startsWith(".")) cls = pkg + cls
            return "$pkg/$cls"
        }
    }
}
