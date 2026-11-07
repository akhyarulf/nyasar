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
     * Satu-satunya pemanggil sekarang RecordingViewModel, yang bind sekali
     * saat ViewModel dibuat — sebelum user menekan Start. BIND_AUTO_CREATE
     * memang diperlukan di sini: service yang nanti di-start lewat
     * startForegroundService() harus punya instance terikat yang sudah ada,
     * sesuai doc class di atas. Flag ini tidak lagi bisa dipanggil dengan
     * 0 karena tidak ada lagi layar navigasi terpisah yang butuh "bind,
     * jangan pernah start".
     */
    fun bind() {
        if (bound) return
        val intent = Intent(context, RecordingService::class.java)
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        if (!bound) return
        context.unbindService(connection)
        bound = false
        _service.value = null
    }
}
