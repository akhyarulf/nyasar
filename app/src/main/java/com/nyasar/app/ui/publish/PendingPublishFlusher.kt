package com.nyasar.app.ui.publish

import android.content.Context
import android.util.Log

/**
 * Static entry the sync hooks call (login initial-sync). PublishViewModel
 * is a normal AndroidViewModel, so the flusher builds its own from the
 * APPLICATION context — no store owner needed for a headless call site,
 * viewModelScope still works, and the flush is a finite job on
 * [PublishViewModel]'s scope (fire-and-forget, same contract as
 * BackupManager's auto-backup).
 *
 * Idempotent by the pipeline itself (anti-double probe + dequeue on
 * success/duplicate), so overlapping calls can at worst duplicate work,
 * never duplicate cloud rows.
 */
object PendingPublishFlusher {

    private const val TAG = "PendingPublishFlush"

    fun flush(context: Context) {
        val app = context.applicationContext
        if (app !is android.app.Application) {
            Log.w(TAG, "flush skipped: non-application context")
            return
        }
        try {
            PublishViewModel(app).flushInScope()
        } catch (e: Exception) {
            Log.e(TAG, "flush launch failed", e)
        }
    }
}
