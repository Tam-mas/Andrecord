package com.andrecord.app.ui.settings

import androidx.lifecycle.ViewModel
import com.andrecord.app.settings.AppSettings
import com.andrecord.app.settings.ReopenBehavior
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(private val appSettings: AppSettings) : ViewModel() {
    private val _reopenBehavior = MutableStateFlow(appSettings.getReopenBehavior())
    val reopenBehavior: StateFlow<ReopenBehavior> = _reopenBehavior

    fun onSelect(behavior: ReopenBehavior) {
        appSettings.setReopenBehavior(behavior)
        _reopenBehavior.value = behavior
    }
}
