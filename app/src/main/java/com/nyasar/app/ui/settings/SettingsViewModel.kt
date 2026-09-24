package com.nyasar.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.db.AppDatabase
import com.nyasar.app.data.settings.AppSettings
import com.nyasar.app.data.settings.SettingsRepository
import com.nyasar.app.map.OfflineMapManager
import com.nyasar.app.map.providers.TileProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StorageSummary(
    val cacheBytes: Long = 0,
    val databaseBytes: Long = 0,
    val routeBytes: Long = 0,
    val activityBytes: Long = 0,
    val waypointCount: Int = 0,
    val pendingCount: Int = 0,
    val filesBytes: Long = 0,
    val routeCount: Int = 0,
    val activityCount: Int = 0,
    val offlineBytes: Long = 0
) {
    val totalBytes: Long
        get() = cacheBytes + databaseBytes + filesBytes + offlineBytes
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = SettingsRepository(app)
    private val appContext = app.applicationContext
    private val database = AppDatabase.get(appContext)
    private val offlineMapManager = OfflineMapManager(appContext)

    private val _storage = MutableStateFlow(StorageSummary())
    val storage: StateFlow<StorageSummary> = _storage.asStateFlow()
    private val _storageBusy = MutableStateFlow(false)
    val storageBusy: StateFlow<Boolean> = _storageBusy.asStateFlow()
    private val _storageMessage = MutableStateFlow<String?>(null)
    val storageMessage: StateFlow<String?> = _storageMessage.asStateFlow()

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

    fun setOfflineOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setOfflineOverlayEnabled(enabled) }
    }

    /** DATA > storage: read-only snapshot of all app-managed local storage. */
    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            _storage.value = calculateStorage()
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            appContext.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            _storage.value = calculateStorage()
        }
    }

    /** Delete downloaded map regions. The UI already confirms each item; this
     *  bulk action is intentionally explicit and never touches Room data. */
    fun clearOfflineMaps(onComplete: (Boolean) -> Unit = {}) {
        _storageBusy.value = true
        offlineMapManager.listRegions { regions ->
            if (regions.isEmpty()) {
                _storageBusy.value = false
                _storageMessage.value = "Tidak ada peta offline tersimpan."
                onComplete(true)
                return@listRegions
            }
            var remaining = regions.size
            var failed = false
            fun finishOne(ok: Boolean) {
                failed = failed || !ok
                remaining--
                if (remaining == 0) {
                    _storageBusy.value = false
                    com.nyasar.app.map.OfflineCoverageStore.get(appContext).notifyChanged()
                    refreshStorage()
                    _storageMessage.value = if (failed) "Sebagian peta offline gagal dihapus." else "Semua peta offline dihapus."
                    onComplete(!failed)
                }
            }
            regions.forEach { region -> offlineMapManager.deleteRegion(region, ::finishOne) }
        }
    }

    /** Delete all local routes, recordings, waypoints and the publish queue.
     *  Cloud data is not touched. Callers must show a destructive confirmation. */
    fun clearAllLocalData(onComplete: (Boolean) -> Unit = {}) {
        _storageBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // Preserve an in-progress recording: the service may be writing
                // to it, and silently deleting it would be data loss.
                if (database.activityDao().getActiveOrNull() != null) {
                    error("Selesaikan atau hentikan recording yang sedang berjalan terlebih dahulu.")
                }
                val routeIds = database.routeDao().getAllOnce().map { it.id }
                val activityIds = database.activityDao().getStoredOnce().map { it.id }
                routeIds.forEach { id ->
                    database.routeDao().getById(id)?.let { route ->
                        java.io.File(route.localGpxFilePath).delete()
                    }
                }
                activityIds.forEach { id ->
                    database.waypointDao().unlinkWaypointsForActivity(id)
                }
                database.activityDao().deletePointsForStoredActivities()
                database.activityDao().deleteStoredActivities()
                database.routeDao().deleteAll()
                database.waypointDao().deleteAll()
                database.pendingPublishDao().clearAll()
                java.io.File(appContext.filesDir, "routes").deleteRecursively()
                appContext.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            }.onSuccess {
                _storageMessage.value = "Data lokal dihapus. Data cloud tidak berubah."
                onComplete(true)
            }.onFailure { error ->
                _storageMessage.value = error.message ?: "Gagal menghapus data lokal."
                onComplete(false)
            }
            _storageBusy.value = false
            refreshStorage()
        }
    }

    private suspend fun calculateStorage(): StorageSummary {
        val routes = database.routeDao().getAllOnce()
        val routeBytes = routes.sumOf { java.io.File(it.localGpxFilePath).let { f -> if (f.exists()) f.length() else 0L } }
        val activities = database.activityDao().getStoredOnce()
        val activityBytes = activities.sumOf { activity ->
            val points = database.activityDao().getPointCount(activity.id).toLong()
            // Approximate Room storage; the exact page allocation is not public.
            96L + points * 72L
        }
        val waypointCount = database.waypointDao().getAllOnce().size
        val pendingCount = database.pendingPublishDao().count()
        val cacheBytes = appContext.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        val dbFile = appContext.getDatabasePath("nyasar.db")
        val databaseBytes = if (dbFile.exists()) dbFile.length() else 0L
        val filesBytes = appContext.filesDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        val offlineBytes = offlineMapManager.storageBytes()
        return StorageSummary(
            cacheBytes = cacheBytes,
            databaseBytes = databaseBytes,
            routeBytes = routeBytes,
            activityBytes = activityBytes,
            waypointCount = waypointCount,
            pendingCount = pendingCount,
            filesBytes = filesBytes,
            routeCount = routes.size,
            activityCount = activities.size,
            offlineBytes = offlineBytes
        )
    }
}
