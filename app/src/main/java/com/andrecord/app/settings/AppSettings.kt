package com.andrecord.app.settings

import android.content.Context

enum class ReopenBehavior { LIVE_VIEW, SESSION_LIST }

/**
 * Persists this app's settings via SharedPreferences directly -- small, direct flags don't need
 * anything heavier -- following the same pattern as
 * [com.andrecord.app.accessibility.AccessibilityServiceStatus].
 */
class AppSettings(private val context: Context) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun getReopenBehavior(): ReopenBehavior =
        parseReopenBehavior(prefs.getString(KEY_REOPEN_BEHAVIOR, null))

    fun setReopenBehavior(behavior: ReopenBehavior) {
        prefs.edit().putString(KEY_REOPEN_BEHAVIOR, behavior.name).apply()
    }

    fun isCalendarAutoRecordEnabled(): Boolean =
        prefs.getBoolean(KEY_CALENDAR_AUTO_RECORD_ENABLED, false)

    fun setCalendarAutoRecordEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALENDAR_AUTO_RECORD_ENABLED, enabled).apply()
    }

    fun getWatchedCalendarIds(): Set<Long> =
        parseWatchedCalendarIds(prefs.getStringSet(KEY_WATCHED_CALENDAR_IDS, null))

    fun setWatchedCalendarIds(ids: Set<Long>) {
        prefs.edit().putStringSet(KEY_WATCHED_CALENDAR_IDS, ids.map { it.toString() }.toSet()).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_REOPEN_BEHAVIOR = "reopen_behavior"
        private const val KEY_CALENDAR_AUTO_RECORD_ENABLED = "calendar_auto_record_enabled"
        private const val KEY_WATCHED_CALENDAR_IDS = "watched_calendar_ids"

        /** Pure parsing logic, extracted so it's unit-testable without a real Context. */
        fun parseReopenBehavior(raw: String?): ReopenBehavior =
            ReopenBehavior.entries.find { it.name == raw } ?: ReopenBehavior.LIVE_VIEW

        /** Pure parsing logic, extracted so it's unit-testable without a real Context. Drops any
         *  entry that isn't a valid [Long] rather than throwing -- SharedPreferences string sets
         *  are just strings on disk, and a corrupted or manually-edited entry shouldn't crash the
         *  whole read. */
        fun parseWatchedCalendarIds(raw: Set<String>?): Set<Long> =
            raw?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }
}
