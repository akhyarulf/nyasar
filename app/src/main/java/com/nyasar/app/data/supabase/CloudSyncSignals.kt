package com.nyasar.app.data.supabase

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide auto-refresh hub (realtime requirement, 2026-09): the moment
 * cloud data behind a screen changes, subscribed screens silently re-sync —
 * no pull-to-refresh, no app restart.
 *
 * Two kinds of signal flow through one [events] SharedFlow:
 *
 *  1. SERVER PUSH — a single Realtime channel subscribes to the public
 *     tables Browse/Saved/Detail render from (`routes`, `route_likes`,
 *     `saved_routes`, `route_comments`). Any INSERT/UPDATE/DELETE on them
 *     (from THIS device or ANY other user) emits [CloudEvent].Data.
 *     likes_count/comments_count live on `routes`, so like/comment storms
 *     ride the same channel.
 *  2. LOCAL TRIGGERS — [notifyForeground] fires when the app returns from
 *     background (websocket may have dropped mid-absence — a silent refresh
 *     reconciles whatever happened offline).
 *
 * Why SharedFlow with extraBufferCapacity instead of a plain MutableStateFlow:
 * multiple identical events can arrive in one burst (bulk import, trigger
 * backfill); a StateFlow would conflate them into one refresh — fine — but a
 * SharedFlow also lets VMs react to events that arrived while they were being
 * recreated (replay 1 keeps the freshest consumers honest after rotation).
 *
 * Connection-loss resilience: if the websocket drops, Supabase-kt reconnects
 * automatically; missed server events are covered by the foreground refresh
 * trigger and by each screen's own re-entry refresh. The app is NEVER left
 * permanently stale by a transport failure — worst case it refreshes slightly
 * later.
 *
 * RLS note: Realtime delivers only rows the subscriber's auth can read —
 * drafts/privates of other users never reach this process.
 */
object CloudSyncSignals {

    sealed class CloudEvent {
        /** A relevant public table changed server-side. */
        data object Data : CloudEvent()

        /** App returned to the foreground — reconcile missed changes. */
        data object Foreground : CloudEvent()
    }

    private val _events = MutableSharedFlow<CloudEvent>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    /** Raw server-change ticks BEFORE throttling — consumers never see this. */
    private val _dataTicks = MutableSharedFlow<Unit>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Shared coroutine scope for the channel + throttle pipeline. Declared
     *  BEFORE the init block that pipes ticks through it (Kotlin initializes
     *  properties in declaration order). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Seed replay with Foreground so a freshly created VM refreshes once —
     *  replaces the old "fetch-once at init" contract without polling. */
    init {
        _events.tryEmit(CloudEvent.Foreground)
        // SERVER-FRIENDLINESS GUARANTEE: coalesce event bursts into at most
        // ONE refresh per window. A popular route taking 30 likes in a minute
        // produces 30 websocket frames (a few bytes each) but only ONE
        // browse fetch — strictly cheaper than 30 users each pulling to
        // refresh. sample() keeps only the latest tick per window.
        _dataTicks
            .sample(REFRESH_COALESCE_MS)
            .onEach { _events.tryEmit(CloudEvent.Data) }
            .launchIn(scope)
    }

    private var channel: RealtimeChannel? = null
    private val started = AtomicBoolean(false)

    /**
     * Idempotent process-lifetime start: opens the channel and pipes
     * Postgres changes into [events]. Safe to call repeatedly (only the
     * first call wins) and harmless on unconfigured builds (caller checks
     * [SupabaseClientProvider.isConfigured] first).
     */
    fun start(client: SupabaseClient) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                val ch = client.channel("nyasar-cloud-sync")
                // One flow per table: postgresChangeFlow's builder takes a
                // single `table` — combining four flows on the shared channel
                // is the same websocket, negligible overhead.
                val routesChanges = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "routes"
                }
                val likeChanges = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "route_likes"
                }
                val saveChanges = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "saved_routes"
                }
                val commentChanges = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "route_comments"
                }
                listOf(routesChanges, likeChanges, saveChanges, commentChanges)
                    .merge()
                    .onEach { _dataTicks.tryEmit(Unit) }
                    .launchIn(scope)
                channel = ch
                // blockUntilSubscribed: the first events can't be silently
                // dropped between channel creation and subscription.
                ch.subscribe(blockUntilSubscribed = true)
                Log.i(TAG, "Realtime channel subscribed (routes/likes/saves/comments)")
            } catch (e: Exception) {
                // Never crash the app for the refresh channel: Foreground
                // + pull-to-refresh remain as full fallbacks. Drop the
                // half-built channel so a later start() retry cannot stack
                // a second channel on the SAME topic id.
                channel = null
                Log.e(TAG, "Realtime subscribe failed (auto-refresh falls back to foreground refresh)", e)
                started.set(false)
            }
        }
    }

    /** Called from MainActivity's lifecycle observer on ON_START. */
    fun notifyForeground() {
        _events.tryEmit(CloudEvent.Foreground)
    }

    /** Minimum spacing between server-change-triggered refreshes. Foreground
     *  refreshes are exempt (one per foregrounding, not a loop). Declared
     *  before use in the init block — const is compile-time anyway. */
    private const val REFRESH_COALESCE_MS = 3_000L

    private const val TAG = "CloudSyncSignals"
}
