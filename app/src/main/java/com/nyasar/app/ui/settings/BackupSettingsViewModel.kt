package com.nyasar.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.R
import com.nyasar.app.backup.BackupManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the Settings "Backup & Pulihkan" section (Fase 3): two manual
 * actions — backup-all and restore-all — over [BackupManager]. Toast-based
 * feedback like PublishRouteSheet; the rows disable while a job runs.
 *
 * The offline-at-trailhead reality (no retry queue yet — the queue design is
 * still an open PROJECT_CONTEXT item) means an auto-backup right after a
 * recording can fail with NETWORK; "Backup sekarang" here is the manual
 * retry path the user runs once they have signal.
 */
class BackupSettingsViewModel(app: Application) : AndroidViewModel(app) {

    sealed class BackupUi {
        data object Idle : BackupUi()
        data object Working : BackupUi()
        /** [args] formats [messageRes]'s placeholders (uploaded/skipped…). */
        data class Done(val messageRes: Int, val args: List<Any>) : BackupUi()
        data class Failed(val errorRes: Int) : BackupUi()
    }

    private val _ui = MutableStateFlow<BackupUi>(BackupUi.Idle)
    val ui: StateFlow<BackupUi> = _ui.asStateFlow()

    private val manager get() = BackupManager.get(getApplication())

    fun backupAll() {
        if (_ui.value is BackupUi.Working) return
        viewModelScope.launch {
            _ui.value = BackupUi.Working
            _ui.value = when (val r = manager.backupAll()) {
                is BackupManager.BackupResult.Success ->
                    BackupUi.Done(R.string.backup_done, listOf(r.uploaded, r.skipped))
                is BackupManager.BackupResult.Failure -> BackupUi.Failed(errorResFor(r.kind))
            }
        }
    }

    fun restoreAll() {
        if (_ui.value is BackupUi.Working) return
        viewModelScope.launch {
            _ui.value = BackupUi.Working
            _ui.value = when (val r = manager.restoreAll()) {
                is BackupManager.RestoreResult.Success ->
                    BackupUi.Done(R.string.restore_done, listOf(r.restoredRoutes, r.restoredActivities, r.skipped))
                is BackupManager.RestoreResult.Failure -> BackupUi.Failed(errorResFor(r.kind))
            }
        }
    }

    /** Toasts read the result, then the UI returns to idle. */
    fun clearResult() {
        if (_ui.value !is BackupUi.Working) _ui.value = BackupUi.Idle
    }

    private fun errorResFor(kind: BackupManager.BackupResult.Kind): Int = when (kind) {
        BackupManager.BackupResult.Kind.NOT_SIGNED_IN -> R.string.backup_error_signin
        BackupManager.BackupResult.Kind.NOT_CONFIGURED -> R.string.backup_error_not_configured
        BackupManager.BackupResult.Kind.NETWORK -> R.string.browse_error_network
        BackupManager.BackupResult.Kind.UNKNOWN -> R.string.browse_error_unknown
    }
}
