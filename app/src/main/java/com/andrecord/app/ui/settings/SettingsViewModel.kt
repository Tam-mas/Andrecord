package com.andrecord.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andrecord.app.calendar.CalendarAutoRecordScheduler
import com.andrecord.app.calendar.CalendarEventRepository
import com.andrecord.app.calendar.CalendarInfo
import com.andrecord.app.calendar.ExactAlarmPermissionStatus
import com.andrecord.app.settings.AppSettings
import com.andrecord.app.settings.ReopenBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val appSettings: AppSettings,
    private val calendarEventRepository: CalendarEventRepository,
    private val exactAlarmPermissionStatus: ExactAlarmPermissionStatus,
    private val calendarAutoRecordScheduler: CalendarAutoRecordScheduler
) : ViewModel() {
    private val _reopenBehavior = MutableStateFlow(appSettings.getReopenBehavior())
    val reopenBehavior: StateFlow<ReopenBehavior> = _reopenBehavior

    private val _calendarAutoRecordEnabled = MutableStateFlow(appSettings.isCalendarAutoRecordEnabled())
    val calendarAutoRecordEnabled: StateFlow<Boolean> = _calendarAutoRecordEnabled

    private val _availableCalendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val availableCalendars: StateFlow<List<CalendarInfo>> = _availableCalendars

    private val _watchedCalendarIds = MutableStateFlow(appSettings.getWatchedCalendarIds())
    val watchedCalendarIds: StateFlow<Set<Long>> = _watchedCalendarIds

    private val _showExactAlarmBanner = MutableStateFlow(false)
    val showExactAlarmBanner: StateFlow<Boolean> = _showExactAlarmBanner

    fun onSelect(behavior: ReopenBehavior) {
        appSettings.setReopenBehavior(behavior)
        _reopenBehavior.value = behavior
    }

    /**
     * Re-reads live state that can change outside this ViewModel: the device's synced calendar
     * list, whether the exact-alarm permission is currently granted, and (per the design spec's
     * setup flow) triggers a rescan so a permission grant made just before returning here -- e.g.
     * the user grants the exact-alarm permission via the banner's deep link and comes back --
     * takes effect immediately instead of waiting for the next periodic safety-net run. Call on
     * resume and on first composition.
     */
    fun refreshCalendarState() {
        _showExactAlarmBanner.value = _calendarAutoRecordEnabled.value && exactAlarmPermissionStatus.shouldShowBanner()
        if (_calendarAutoRecordEnabled.value) {
            // getWatchableCalendars() is a blocking ContentResolver query; run it off the main
            // thread rather than blocking the UI on every resume.
            viewModelScope.launch(Dispatchers.IO) {
                val calendars = calendarEventRepository.getWatchableCalendars()
                _availableCalendars.value = calendars
            }
        }
        calendarAutoRecordScheduler.rescanAsync()
    }

    /** Called once READ_CALENDAR has been granted (or immediately, when turning the feature
     *  off) -- see SettingsScreen's permission-request launcher. */
    fun setCalendarAutoRecordEnabled(enabled: Boolean) {
        appSettings.setCalendarAutoRecordEnabled(enabled)
        _calendarAutoRecordEnabled.value = enabled
        refreshCalendarState()
    }

    fun toggleWatchedCalendar(calendarId: Long) {
        val current = _watchedCalendarIds.value
        val updated = if (calendarId in current) current - calendarId else current + calendarId
        appSettings.setWatchedCalendarIds(updated)
        _watchedCalendarIds.value = updated
        calendarAutoRecordScheduler.rescanAsync()
    }

    fun dismissExactAlarmBanner() {
        exactAlarmPermissionStatus.dismiss()
        _showExactAlarmBanner.value = _calendarAutoRecordEnabled.value && exactAlarmPermissionStatus.shouldShowBanner()
    }
}
