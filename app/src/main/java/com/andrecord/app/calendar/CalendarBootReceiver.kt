package com.andrecord.app.calendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andrecord.app.AndrecordApplication

/**
 * `AlarmManager` alarms don't survive a device restart, so this re-runs the scheduler from
 * scratch immediately on boot (design spec §8). A meeting starting in the narrow window between
 * boot and this receiver running could theoretically be missed -- an accepted, rare limitation.
 */
class CalendarBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as AndrecordApplication).container
        container.calendarAutoRecordScheduler.rescanAsync()
    }
}
