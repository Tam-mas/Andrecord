package com.andrecord.app.settings

import android.content.Context

enum class ReopenBehavior { LIVE_VIEW, SESSION_LIST }

/**
 * Persists the one setting this app has: what happens when you open it while a recording is
 * already active (e.g. one started via Quick Tap or the volume-key hold while the app was
 * closed). Uses SharedPreferences directly -- a single boolean/enum flag doesn't need anything
 * heavier -- following the same pattern as [com.andrecord.app.accessibility.AccessibilityServiceStatus].
 */
class AppSettings(private val context: Context) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun getReopenBehavior(): ReopenBehavior =
        parseReopenBehavior(prefs.getString(KEY_REOPEN_BEHAVIOR, null))

    fun setReopenBehavior(behavior: ReopenBehavior) {
        prefs.edit().putString(KEY_REOPEN_BEHAVIOR, behavior.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_REOPEN_BEHAVIOR = "reopen_behavior"

        /** Pure parsing logic, extracted so it's unit-testable without a real Context. */
        fun parseReopenBehavior(raw: String?): ReopenBehavior =
            ReopenBehavior.entries.find { it.name == raw } ?: ReopenBehavior.LIVE_VIEW
    }
}
