package com.nyasar.app.recording

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin bind-lifecycle wrapper so a ViewModel can observe RecordingService's
 * state without dealing with ServiceConnection callbacks directly. The
 * service itself keeps running as a started service even if this unbinds
 * (e.g. Activity destroyed while recording continues in background) —
 * binding here is only for pushing live state into the UI when visible.
 */
class RecordingServiceConnection(private val context: Context) {

    private val _service = MutableStateFlow<RecordingService?>(null)
    val service: StateFlow<RecordingService?> = _service.asStateFlow()

    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            _service.value = (binder as? RecordingService.LocalBinder)?.service()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _service.value = null
        }
    }

    /**
     * P3I audit fix: was `Context.BIND_AUTO_CREATE`, which silently starts
     * RecordingService if it isn't already running — directly contradicting
     * the "only bind, never start" contract NavigationViewModel documents
     * for this call (see its comment above `recordingConnection.bind()`).
     * If NavigationViewModel's isRecordingServiceRunning() check races with
     * the service actually stopping (handleStop()/stopSelf() in
     * RecordingService), BIND_AUTO_CREATE would spin up a brand-new,
     * never-started instance — activityId null, no GPS collection — whose
     * default RecordingUiState().status is IDLE, which incorrectly passes
     * NavigationViewModel's `status != RecordingStatus.STOPPED` check.
     * Navigation would then register a fix listener on a service that will
     * never receive a fix: silently stuck with no GPS source.
     *
     * Passing 0 (no flags) makes bind() fail (return false) if the service
     * isn't already running, instead of creating it — which is what "only
     * bind" actually requires. Callers already handle a failed/absent
     * connection by falling back to their own GPS stream (see
     * NavigationViewModel's else branch), so this fails safely into that
     * path instead of into a zombie service.
     */
    /**
     * P3I audit fix: [autoCreate] now caller-controlled instead of always
     * `Context.BIND_AUTO_CREATE`. Two call sites need opposite behavior:
     *
     * - RecordingViewModel.init binds once at ViewModel creation, before
     *   the user has necessarily pressed Start — it relies on
     *   BIND_AUTO_CREATE to stand up the (bound+started) service instance
     *   that a later startForegroundService() call then targets, per this
     *   class's own doc comment above. autoCreate = true here (the
     *   default) preserves that.
     *
     * - NavigationViewModel binds only when it has independently confirmed
     *   (isRecordingServiceRunning()) that recording is already active, and
     *   documents "only bind, never start" as a hard requirement — if
     *   RecordingService isn't actually running (e.g. a race with it
     *   stopping between that check and this call), BIND_AUTO_CREATE would
     *   silently create a fresh, never-started instance whose default
     *   RecordingUiState().status (IDLE) incorrectly passes NavigationView
     *   Model's `status != STOPPED` check, leaving navigation registered
     *   against a service that will never emit a fix — silently stuck with
     *   no GPS source. NavigationViewModel must pass autoCreate = false so
     *   a lost race fails the bind cleanly and falls through to its own
     *   LocationRepository stream instead.
     */
    fun bind(autoCreate: Boolean = true) {
        if (bound) return
        val intent = Intent(context, RecordingService::class.java)
        bound = context.bindService(intent, connection, if (autoCreate) Context.BIND_AUTO_CREATE else 0)
    }

    fun unbind() {
        if (!bound) return
        context.unbindService(connection)
        bound = false
        _service.value = null
    }
}
