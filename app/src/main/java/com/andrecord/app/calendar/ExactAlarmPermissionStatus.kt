package com.andrecord.app.calendar

import android.app.AlarmManager
import android.content.Context

/**
 * Tracks whether this app can schedule exact alarms (needed to start a recording precisely when
 * a calendar event begins), and whether the user has dismissed the in-app prompt to grant it.
 *
 * `SCHEDULE_EXACT_ALARM` has no runtime-permission dialog -- the user must flip it on manually in
 * Settings -- so the UI (see SettingsScreen's banner) instead surfaces a one-tap deep link there
 * via `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, mirroring
 * [com.andrecord.app.accessibility.AccessibilityServiceStatus]'s exact pattern for the volume-key
 * trigger's accessibility-service permission.
 */
class ExactAlarmPermissionStatus(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun isGranted(): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return alarmManager?.canScheduleExactAlarms() ?: false
    }

    fun isDismissed(): Boolean = prefs.getBoolean(KEY_DISMISSED, false)

    fun dismiss() {
        prefs.edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    /** Never shown once the permission is actually granted (dismissal is then moot), and never
     * shown again once the user has dismissed it while still ungranted. */
    fun shouldShowBanner(): Boolean = !isGranted() && !isDismissed()

    companion object {
        private const val PREFS_NAME = "exact_alarm_permission_status"
        private const val KEY_DISMISSED = "banner_dismissed"
    }
}
