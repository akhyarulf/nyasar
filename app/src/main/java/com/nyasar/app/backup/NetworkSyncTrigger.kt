package com.nyasar.app.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Network-trigger for the buttonless sync (konsep "tanpa tombol", kelanjutan
 * 2026-09): registers a default network callback ONCE per process and fires
 * the flush whenever the device transitions offline → online. This closes
 * the gap where a user records offline at the summit and regains signal at
 * basecamp WITHOUT restarting the app — until now the pending publishes and
 * backup backlogs only drained at login / app start.
 *
 * What runs on regain:
 *  1. PendingPublishFlusher — drains the offline publish queue (only does
 *     work when a session exists).
 *  2. BackupManager.backupAll-style initial sync — the same once-per-user
 *     guard as login sync applies; if the user was never logged in this is
 *     a cheap no-op.
 *
 * Callbacks are delivered on a background executor (ConnectivityManager's
 * default), so the launch here stays off the main thread. The callback is
 * process-lifetime — registered from Application start, never unregistered
 * (same cost profile as the OS's own default network listener).
 */
object NetworkSyncTrigger {

    private const val TAG = "NetworkSyncTrigger"

    @Volatile private var registered = false

    fun register(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val appContext = context.applicationContext
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) {
                Log.w(TAG, "no ConnectivityManager — network-trigger sync unavailable")
                return
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                cm.registerNetworkCallback(
                    request,
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            Log.i(TAG, "network available — flushing pending work")
                            // 1) Offline publish queue first (user-visible
                            //    feature — their activity goes public).
                            try {
                                com.nyasar.app.ui.publish.PendingPublishFlusher.flush(appContext)
                            } catch (e: Exception) {
                                Log.e(TAG, "pending-publish flush failed", e)
                            }
                            // 2) Backup backlog — restore+backup are both
                            //    idempotent; the once-per-user guard makes a
                            //    repeat free. Runs AFTER the publish flush
                            //    so fresh user actions win the bandwidth race.
                            try {
                                BackupManager.scheduleNetworkSync(appContext)
                            } catch (e: Exception) {
                                Log.e(TAG, "backup network-sync failed", e)
                            }
                        }
                    }
                )
                registered = true
                Log.i(TAG, "network sync trigger registered")
            } catch (e: Exception) {
                Log.e(TAG, "registerNetworkCallback failed", e)
            }
        }
    }
}
