package com.nyasar.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.settings.AppSettings
import com.nyasar.app.data.settings.SettingsRepository
import com.nyasar.app.map.providers.TileProviderFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = SettingsRepository(app)

    val settings: StateFlow<AppSettings?> = repository.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    val availableProviders = TileProviderFactory.all()

    fun selectProvider(providerId: String) {
        viewModelScope.launch { repository.setProvider(providerId) }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setLanguageMode(mode: String) {
        viewModelScope.launch { repository.setLanguageMode(mode) }
    }

    fun setKeepScreenOnWhileRecording(enabled: Boolean) {
        viewModelScope.launch { repository.setKeepScreenOnWhileRecording(enabled) }
    }

    fun setAutoPauseEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoPauseEnabled(enabled) }
    }

    fun setSpeedUnit(unit: String) {
        viewModelScope.launch { repository.setSpeedUnit(unit) }
    }

    /** DATA > "cache" (spec): app-private cache dir, mainly GPX exports
     *  written by [com.nyasar.app.gpx.GpxExporter]. Safe to wipe any time —
     *  nothing here is the source of truth (routes/activities live in Room
     *  + app-private files dir, untouched by this). */
    fun cacheSizeBytes(): Long = getApplication<Application>().cacheDir.walkBottomUp()
        .filter { it.isFile }.sumOf { it.length() }

    fun clearCache() {
        getApplication<Application>().cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
