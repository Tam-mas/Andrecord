package com.andrecord.app.ui.settings

import androidx.lifecycle.ViewModel
import com.andrecord.app.calendar.CalendarAutoRecordScheduler
import com.andrecord.app.calendar.CalendarEventRepository
import com.andrecord.app.calendar.CalendarInfo
import com.andrecord.app.calendar.ExactAlarmPermissionStatus
import com.andrecord.app.settings.AppSettings
import com.andrecord.app.settings.ReopenBehavior
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    /** Re-reads live state that can change outside this ViewModel: the device's synced calendar
     *  list, and whether the exact-alarm permission is currently granted. Call on resume, since
     *  the user's path here is granting the permission in system Settings and returning. */
    fun refreshCalendarState() {
        if (_calendarAutoRecordEnabled.value) {
            _availableCalendars.value = calendarEventRepository.getWatchableCalendars()
        }
        _showExactAlarmBanner.value = _calendarAutoRecordEnabled.value && exactAlarmPermissionStatus.shouldShowBanner()
    }

    /** Called once READ_CALENDAR has been granted (or immediately, when turning the feature
     *  off) -- see SettingsScreen's permission-request launcher. */
    fun setCalendarAutoRecordEnabled(enabled: Boolean) {
        appSettings.setCalendarAutoRecordEnabled(enabled)
        _calendarAutoRecordEnabled.value = enabled
        refreshCalendarState()
        calendarAutoRecordScheduler.rescan()
    }

    fun toggleWatchedCalendar(calendarId: Long) {
        val current = _watchedCalendarIds.value
        val updated = if (calendarId in current) current - calendarId else current + calendarId
        appSettings.setWatchedCalendarIds(updated)
        _watchedCalendarIds.value = updated
        calendarAutoRecordScheduler.rescan()
    }

    fun dismissExactAlarmBanner() {
        exactAlarmPermissionStatus.dismiss()
        _showExactAlarmBanner.value = _calendarAutoRecordEnabled.value && exactAlarmPermissionStatus.shouldShowBanner()
    }
}
