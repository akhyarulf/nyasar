@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.nyasar.app.ui.browse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
// Pull-to-refresh (2026-09 fix): material3 1.2.1 (BOM 2024.06) TIDAK punya
// rememberPullToRefreshState/PullToRefreshContainer — itu API M3 1.3+. Versi
// stabil untuk BOM ini ada di compose material M2 (pullRefresh), yang
// tercakup penuh oleh compose-bom 2024.06.
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.filled.MyLocation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.R
import com.nyasar.app.data.supabase.BrowseRepository
import com.nyasar.app.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.gotrue.auth
import com.nyasar.app.recording.SportType
import com.nyasar.app.ui.components.AuthorAvatar
import com.nyasar.app.ui.components.DifficultyChip
import com.nyasar.app.ui.components.EmptyState
import com.nyasar.app.ui.components.LocalFeedScrollState
import com.nyasar.app.ui.components.NyasarTabHeader
import com.nyasar.app.ui.components.StaticMapPreview
import com.nyasar.app.ui.components.TrailTypeChip
import com.nyasar.app.ui.components.pressScale
import com.nyasar.app.ui.theme.NyasarControl
import com.nyasar.app.ui.theme.NyasarElevation
import com.nyasar.app.ui.theme.NyasarRadius
import com.nyasar.app.ui.theme.NyasarSpacing
import com.nyasar.app.ui.theme.NyasarStatValue
import android.content.Intent
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * "Jelajah / Explore" — the new Home tab (Fase 2 poin 3, IA rework 2026):
 * browse + search + filter + sort of PUBLIC routes (Fase 2). Read-only over
 * [BrowseRepository]; opening a route goes to [PublicRouteDetailScreen].
 *
 * One trailing detail on purpose: no onBack. This is a root tab — the
 * back gesture must exit the app, not pop the tab.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    viewModel: BrowseViewModel = viewModel(),
    onOpenRoute: (String) -> Unit,
    onRequireSignIn: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val query by viewModel.query.collectAsState()
    val likedIds by viewModel.likedIds.collectAsState()
    val likePending by viewModel.likePending.collectAsState()
    val savedIds by viewModel.savedIds.collectAsState()
    val savePending by viewModel.savePending.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val nearMeEnabled by viewModel.nearMeEnabled.collectAsState()
    val scope = rememberCoroutineScope()

    /**
     * Ambil satu fix GPS lalu aktifkan Jelajah "dekat saya" (bbox ±25 km).
     * Izin dicek pemanggil; di sini hanya soal mendapatkan posisi. Tanpa fix
     * dalam 8 detik (di dalam ruangan, GPS belum lock) pengguna diberi tahu
     * alih-alih melihat daftar kosong tanpa penjelasan.
     */
    fun enableNearMe() {
        scope.launch {
            val fix = withTimeoutOrNull(8_000) {
                com.nyasar.app.location.LocationRepository(context).observeLocation().first()
            }
            if (fix != null) {
                viewModel.enableNearMe(fix.lat, fix.lon)
            } else {
                Toast.makeText(context, R.string.browse_near_me_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableNearMe()
        } else {
            Toast.makeText(context, R.string.browse_near_me_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // "Dekat saya" pindah dari header ke strip status, karena ia KEADAAN
    // (radius menyala/tidak), bukan aksi sekali tekan. Logikanya di-hoist di
    // sini supaya chip di strip bisa memanggilnya tanpa menduplikasi izin.
    val onToggleNearMe: () -> Unit = {
        if (nearMeEnabled) {
            viewModel.disableNearMe()
        } else if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            enableNearMe()
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Filter + sort live in the header as icon-only actions (reference feed
    // anatomy: a title with a row of glyphs, no control bar eating the first
    // 60dp of the list). Filter is green only while it is applied — colour as
    // state, not decoration. Hoisted here because both the header and the
    // sheet need them.
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    val activeFilters by viewModel.appliedCount.collectAsState()
    val currentSort = viewModel.currentSort()

    // Search is a header glyph now: the field is mounted only while the user
    // is searching (or a query is still filtering the list, so the reason the
    // feed is short never disappears). That buys back the full row of height
    // the always-on field used to take, above every single route.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchOpen) {
        // Runs after composition, so the field exists by the time this fires.
        if (searchOpen) searchFocusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            NyasarTabHeader(
                // Brand-first header, like the reference feed: the bottom bar
                // already labels this tab, so repeating "Jelajah Rute" up here
                // only competed with the logo chip. The wordmark makes the top
                // of the app read as the product, not as a page title.
                title = stringResource(R.string.app_name),
                // Subtitle dihapus (feedback user 2026-09): tagline tidak
                // menambah informasi, header cukup judul + aksi saja.
                actions = {
                    // "Dekat saya" TIDAK lagi di header: ia keadaan, bukan
                    // aksi, dan sekarang tinggal di strip status di atas feed.
                    // Search, lalu refine: glyph order mirrors how the feed is
                    // narrowed. Search stays lit while the field is open.
                    IconButton(onClick = {
                        if (searchOpen) {
                            viewModel.onQueryChanged("")
                            searchOpen = false
                        } else {
                            searchOpen = true
                        }
                    }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.browse_search),
                            tint = if (searchOpen || query.isNotEmpty())
                                       MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { showSortSheet = true }) {
                        Icon(
                            Icons.Default.SwapVert,
                            contentDescription = stringResource(R.string.browse_sort_cd),
                            // Same rule as the filter glyph: green means
                            // "not the default state", so an active sort
                            // is visible without a label.
                            tint = if (currentSort != BrowseRepository.SortOrder.NEWEST)
                                       MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { showFilterSheet = true }) {
                        // Angka, bukan hanya warna: badge memberi tahu BERAPA
                        // filter aktif tanpa perlu membuka sheet-nya dulu.
                        BadgedBox(
                            badge = {
                                if (activeFilters > 0) {
                                    Badge { Text(activeFilters.toString()) }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = stringResource(R.string.history_filter_button),
                                tint = if (activeFilters > 0) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // The search field, summoned from the header glyph. The X clears
            // the query AND folds the field away in one tap — the two things
            // you want after finishing a search, instead of two icons.
            if (searchOpen || query.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChanged,
                    placeholder = { Text(stringResource(R.string.browse_search_hint), maxLines = 1) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = {
                            viewModel.onQueryChanged("")
                            searchOpen = false
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.browse_clear_search)
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(NyasarRadius.md),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                        .focusRequester(searchFocusRequester)
                )
            }

            // Summoned from the header's filter glyph. Same draft semantics as
            // before: edits apply on "Terapkan", and the header glyph turns
            // green from the APPLIED count (StateFlow), so it never claims a
            // filter the list isn't actually using.
            if (showFilterSheet) {
                BrowseFilterSheet(
                    viewModel = viewModel,
                    onDismiss = { showFilterSheet = false }
                )
            }

            // Urutan: sheet dengan centang, bukan dropdown buta yang hanya
            // memberi isyarat lewat warna ikon.
            if (showSortSheet) {
                BrowseSortSheet(
                    current = currentSort,
                    onSelect = {
                        viewModel.onSortSelected(it)
                        showSortSheet = false
                    },
                    onDismiss = { showSortSheet = false }
                )
            }

            // Pull-to-refresh (M2 pullRefresh — lihat catatan import):
            // tarik ke bawah re-browse tanpa mengosongkan layar — list tetap
            // terlihat, hanya indicator yang muncul. Gesture hanya aktif saat
            // konten scrollable (LazyColumn) di posisi paling atas; Loading/
            // Error/empty tidak scrollable → tidak bisa ditarik (retry tetap
            // via tombol masing-masing).
            val isRefreshing = (state as? BrowseViewModel.BrowseState.Loaded)?.isRefreshing == true
            val pullState = rememberPullRefreshState(
                refreshing = isRefreshing,
                onRefresh = { viewModel.refresh() }
            )
            Box(Modifier.fillMaxSize().pullRefresh(pullState)) {
                when (val s = state) {
                BrowseViewModel.BrowseState.Idle, BrowseViewModel.BrowseState.Loading -> {
                    BrowseFeedSkeleton()
                }
                is BrowseViewModel.BrowseState.Error -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            stringResource(s.error.messageRes),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = viewModel::retry) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                is BrowseViewModel.BrowseState.Loaded -> {
                    if (s.routes.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.Explore,
                            title = stringResource(
                                if (s.fromSearch) R.string.browse_empty_search else R.string.browse_empty_all
                            ),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        // Strip penyempitan: jumlah hasil + chip keadaan yang
                        // sedang aktif. Sebelumnya filter/urutan/"dekat saya"
                        // hanya dikodekan lewat warna ikon, jadi feed bisa
                        // memendek tanpa sebab yang terbaca — pengguna tidak
                        // tahu apakah datanya sedikit atau filternya menyala.
                        Column(Modifier.fillMaxSize()) {
                        BrowseNarrowingStrip(
                            resultCount = s.routes.size,
                            hasMore = s.hasMore,
                            query = query,
                            nearMeEnabled = nearMeEnabled,
                            onToggleNearMe = onToggleNearMe,
                            activeFilters = activeFilters,
                            sortLabel = currentSort
                                .takeIf { it != BrowseRepository.SortOrder.NEWEST }
                                ?.let { stringResource(it.labelRes) },
                            onClear = {
                                viewModel.clearAllFilters()
                                if (nearMeEnabled) viewModel.disableNearMe()
                                viewModel.onSortSelected(BrowseRepository.SortOrder.NEWEST)
                                if (query.isNotEmpty()) viewModel.onQueryChanged("")
                                searchOpen = false
                            }
                        )
                        // List state dibagikan ke kartu lewat
                        // LocalFeedScrollState: kartu menunggu daftar diam
                        // sebelum mem-boot MapSnapshotter (lihat
                        // StaticMapPreview), jadi renderer native tidak
                        // pernah menyala di tengah gestur scroll.
                        val feedListState = rememberLazyListState()
                        // Infinite scroll: minta halaman berikutnya begitu
                        // tiga kartu terakhir mulai terlihat. derivedStateOf
                        // supaya recomposition hanya terjadi saat ambang itu
                        // berubah, bukan setiap frame scroll.
                        val shouldLoadMore by remember(
                            feedListState, s.routes.size, s.hasMore, s.isLoadingMore
                        ) {
                            derivedStateOf {
                                val lastVisible =
                                    feedListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                                s.hasMore && !s.isLoadingMore && s.routes.isNotEmpty() &&
                                    lastVisible >= s.routes.size - 3
                            }
                        }
                        LaunchedEffect(shouldLoadMore) {
                            if (shouldLoadMore) viewModel.loadMore()
                        }
                        CompositionLocalProvider(LocalFeedScrollState provides feedListState) {
                        LazyColumn(
                            // Full-bleed feed rows: the map and the hairlines
                            // run edge to edge, so the list itself carries no
                            // side padding — only a bottom gap.
                            contentPadding = PaddingValues(bottom = 16.dp),
                            // Filter chips row scrolls its own context; list
                            // items animate in with the shared stagger.
                            state = feedListState,
                            // weight(1f): feed mengisi sisa tinggi SETELAH
                            // strip, jadi strip tetap di tempat saat scroll.
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            // contentType: semua baris di feed ini sejenis, jadi
                            // Compose boleh memakai ulang slot subkomposisinya
                            // saat scroll alih-alih membangun ulang tiap baris.
                            items(s.routes, key = { it.id }, contentType = { "route" }) { route ->
                                PublicRouteCard(
                                    route = route,
                    liked = route.id in likedIds,
                                    likePending = route.id in likePending,
                                    onToggleLike = { viewModel.toggleLike(route) },
                                    saved = route.id in savedIds,
                                    savePending = route.id in savePending,
                                    onToggleSave = { viewModel.toggleSave(route) },
                                    onOpenComments = { onOpenRoute(route.id) },
                                    onRequireSignIn = onRequireSignIn,
                                    onShare = { shareRoute(context, route) },
                                    onClick = { onOpenRoute(route.id) }
                                )
                            }
                            // Footer: satu-satunya penanda bahwa daftar masih
                            // bertambah saat pengguna scroll.
                            if (s.isLoadingMore) {
                                item(key = "loading-more") {
                                    Box(
                                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }
                        }
                        }
                        }
                    }
                }
            }
                PullRefreshIndicator(
                    refreshing = isRefreshing,
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            } // pull-refresh Box
        }
    }
}


/**
 * Baris status di atas feed: "N rute" + satu chip per keadaan yang sedang
 * menyempitkan daftar, lalu "Hapus semua" bila ada yang bisa dihapus.
 *
 * Ada selalu (bukan hanya saat menyempit) karena jumlah hasil sendiri sudah
 * informasi: pengguna tahu feed punya 12 atau 400 rute, dan tahu kapan
 * daftarnya berhenti di halaman pertama (tanda "+").
 */
@Composable
private fun BrowseNarrowingStrip(
    resultCount: Int,
    hasMore: Boolean,
    query: String,
    nearMeEnabled: Boolean,
    onToggleNearMe: () -> Unit,
    activeFilters: Int,
    sortLabel: String?,
    onClear: () -> Unit
) {
    val isNarrowed = query.isNotBlank() || nearMeEnabled || activeFilters > 0 || sortLabel != null
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(
                    start = NyasarSpacing.lg,
                    end = NyasarSpacing.sm,
                    top = NyasarSpacing.sm,
                    bottom = NyasarSpacing.sm
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NyasarSpacing.sm)
        ) {
            Text(
                stringResource(R.string.browse_result_count, resultCount) +
                    if (hasMore) "+" else "",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (query.isNotBlank()) {
                NarrowingChip("\u201C$query\u201D")
            }
            // Toggle, bukan penanda: "Dekat saya" adalah satu-satunya chip
            // yang bisa dinyalakan dari sini, dan karena itu ia satu-satunya
            // yang memakai aksen (primaryContainer) ketika aktif.
            NarrowingChip(
                text = stringResource(R.string.browse_near_me),
                selected = nearMeEnabled,
                icon = Icons.Default.MyLocation,
                onClick = onToggleNearMe
            )
            if (activeFilters > 0) {
                NarrowingChip(
                    stringResource(R.string.browse_filter_filters) + " \u00B7 " + activeFilters
                )
            }
            sortLabel?.let { NarrowingChip(it) }
            if (isNarrowed) {
                TextButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = NyasarSpacing.sm),
                    modifier = Modifier.heightIn(min = 32.dp)
                ) {
                    Text(
                        stringResource(R.string.browse_filter_clear_all),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** Chip keadaan: tenang saat pasif, beraksen saat dipilih (hanya toggle
 *  "Dekat saya" yang bisa dipilih). */
@Composable
private fun NarrowingChip(
    text: String,
    selected: Boolean = false,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(NyasarRadius.pill),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(
            NyasarControl.hairline,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        modifier = if (onClick != null) {
            Modifier
                .pressScale(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        } else Modifier
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** Kerangka kartu saat feed pertama kali dimuat.
 *
 *  Menggantikan spinner di tengah layar kosong: bentuk blok mengikuti anatomi
 *  kartu asli (byline, judul, stat, peta), jadi saat data datang tata letaknya
 *  tidak "melompat" dari kosong ke kartu setinggi ~700dp. */
@Composable
private fun BrowseFeedSkeleton() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "feedSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 900),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "feedSkeletonAlpha"
    )
    Column(Modifier.fillMaxSize()) {
        repeat(3) { FeedSkeletonCard(alpha) }
    }
}

@Composable
private fun FeedSkeletonCard(alpha: Float) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(
            Modifier.padding(NyasarSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkeletonShape(Modifier.size(40.dp), CircleShape, alpha)
            Spacer(Modifier.width(NyasarSpacing.md))
            Column {
                SkeletonShape(Modifier.width(120.dp).height(12.dp), RoundedCornerShape(NyasarRadius.xs), alpha)
                Spacer(Modifier.height(6.dp))
                SkeletonShape(Modifier.width(72.dp).height(10.dp), RoundedCornerShape(NyasarRadius.xs), alpha)
            }
        }
        SkeletonShape(
            Modifier.padding(horizontal = NyasarSpacing.lg).fillMaxWidth().height(20.dp),
            RoundedCornerShape(NyasarRadius.xs),
            alpha
        )
        Spacer(Modifier.height(NyasarSpacing.md))
        Row(
            Modifier.padding(horizontal = NyasarSpacing.lg).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NyasarSpacing.md)
        ) {
            repeat(3) {
                Column(Modifier.weight(1f)) {
                    SkeletonShape(Modifier.width(48.dp).height(9.dp), RoundedCornerShape(NyasarRadius.xs), alpha)
                    Spacer(Modifier.height(6.dp))
                    SkeletonShape(Modifier.fillMaxWidth(0.7f).height(16.dp), RoundedCornerShape(NyasarRadius.xs), alpha)
                }
            }
        }
        Spacer(Modifier.height(NyasarSpacing.md))
        SkeletonShape(
            Modifier.fillMaxWidth().aspectRatio(1080f / 640f),
            RoundedCornerShape(0.dp),
            alpha
        )
        Spacer(Modifier.height(NyasarSpacing.md))
    }
}

@Composable
private fun SkeletonShape(
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    alpha: Float
) {
    Box(
        modifier.background(
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha),
            shape
        )
    )
}

@Composable
/** Shared with the Profile → Saved tab (same card everywhere a bookmarked
 *  route appears). internal, not private, for exactly that cross-package use. */
internal fun PublicRouteCard(
    route: BrowseRepository.PublicRoute,
    liked: Boolean,
    likePending: Boolean,
    onToggleLike: () -> Unit,
    saved: Boolean,
    savePending: Boolean,
    onToggleSave: () -> Unit,
    onOpenComments: () -> Unit,
    onRequireSignIn: () -> Unit,
    onShare: () -> Unit,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    // Feed row, not a card: hairline on top, full-bleed map, no rounded inset
    // frame. The rounded card with 16dp margins made every route look like an
    // equal-weight widget; a feed entry earns its emphasis from the map and
    // the numbers, not from a container.
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Column {
            // ── Reference-feed order: (1) publisher byline, (2) route name,
            // (3) description, (4) stat row, (5) full-bleed map, then the
            // like/comment/save/share action row.
            //
            // (1) Publisher header: monogram + username + date — the exact
            //     anatomy of the reference byline, over our own visual language.
            Row(
                // 12dp on the end, not 16: the badge is a circle, and a 40dp
                // circle pushed in to the text margin leaves its glyph reading
                // ~11dp inside the card's edge — it looked like it had drifted.
                Modifier.padding(
                    start = NyasarSpacing.lg,
                    end = NyasarSpacing.md,
                    top = NyasarSpacing.md,
                    bottom = NyasarSpacing.md
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Identity at the top of the row, same monogram treatment as
                // the Profile header: green-tinted circle + the author's
                // initial. The grey square with a generic person glyph this
                // replaces was indistinguishable from a broken-image state
                // once two cards from the same author stacked up.
                AuthorAvatar(name = route.username)
                Spacer(Modifier.width(NyasarSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        route.username ?: stringResource(R.string.browse_unknown_author),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        feedTimestamp(route.createdAt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                // Save (bookmark) is the only thing at the end of the byline:
                // the byline answers "who and when", and a badge-filled line
                // (chips + sport + toggle) turned it into a metadata soup that
                // also made chip-less cards look empty. The route's own
                // attributes moved down to the name, where they belong.
                SaveBadge(
                    saved = saved,
                    enabled = !savePending,
                    onClick = {
                        if (SupabaseClientProvider.client.auth.currentSessionOrNull() != null) {
                            onToggleSave()
                        } else {
                            onRequireSignIn()
                        }
                    }
                )
            }

            // (2) Route name — full width, on its own line. The badges moved
            //     below it, so long names get the whole line instead of
            //     squeezing chips off-screen.
            Column(Modifier.padding(horizontal = NyasarSpacing.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        route.name,
                        // Semibold, not Bold: at 20sp the extra weight made the
                        // name fight the stat numbers below it. One bold voice
                        // per card — the numbers.
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                // (2b) Route attributes: difficulty, trail shape, sport. They
                //     describe the ROUTE, so they sit with its name instead of
                //     crowding the author byline. Own line also means long
                //     names can no longer push the chips off-screen (the
                //     reason they were moved up to the byline before).
                val diffChip = route.difficulty
                    ?.let { BrowseRepository.DifficultyFilter.fromWire(it) }
                val trailChip = route.trailType
                    ?.let { BrowseRepository.TrailTypeFilter.fromWire(it) }
                // Sport icon only when the publisher actually picked a sport —
                // the old render drew HelpOutline ("?") for the UNSPECIFIED
                // default, which read as a broken glyph on every fresh card.
                val sport = SportType.fromString(route.sportType)
                    .takeIf { it != SportType.UNSPECIFIED }
                val hasMeta = diffChip != null || trailChip != null || sport != null
                if (hasMeta) {
                    Spacer(Modifier.height(NyasarSpacing.sm))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NyasarSpacing.xs)
                    ) {
                        diffChip?.let { chip ->
                            DifficultyChip(label = stringResource(chip.labelRes), wire = chip.wire)
                        }
                        trailChip?.let { chip ->
                            TrailTypeChip(label = stringResource(chip.labelRes))
                        }
                        sport?.let {
                            Icon(
                                imageVector = it.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // (3) Description (Strava shows it right under the name).
                route.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Spacer(Modifier.height(if (hasMeta) NyasarSpacing.sm else NyasarSpacing.xs))
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

            // (4) Stat row — reference feed anatomy: small quiet label above,
            //     numbers much larger and heavier below. Three equal columns,
            //     every one of them left-aligned: pushing the last block to the
            //     right margin made "Time" look like a separate, unrelated
            //     block and left a hole in the middle of the row.
                Spacer(Modifier.height(NyasarSpacing.md))
                // Explicit gutters on top of the equal thirds: with smaller
                // values the columns otherwise read as one run of numbers.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NyasarSpacing.md)
                ) {
                    StatTile(
                        label = stringResource(R.string.stat_distance),
                        value = "%.1f km".format(route.distanceMeters / 1000.0),
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = stringResource(R.string.browse_stat_elev_gain),
                        value = route.elevationGainM?.let { "+${it.roundToInt()} m" } ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        // Short label: "Moving time" is the longest of the
                        // three and ellipsises on a 320dp-wide card.
                        label = stringResource(R.string.browse_stat_time),
                        value = route.movingTimeMs?.takeIf { ms -> ms > 0 }
                            ?.let { ms -> formatCardDuration(ms) } ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(NyasarSpacing.md))

            // (5) Hero map — full-bleed, and sized by the SAME 1080×640 ratio
            //     the History card uses (ActivityCard feeds MapSnapshotHelper a
            //     1080×640 bitmap and draws it into a matching box). The old
            //     fixed 210dp band was shorter and wider than that frame, so the
            //     route got squeezed into a thin strip and the feed map read as
            //     a smaller, narrower map than the identical route in History.
            //     Ratio, not a dp height: 210dp is a different shape on every
            //     phone, while the snapshot's framing never changes.
            //     Tile sources: MapTiler topo when keyed, then OSM standard,
            //     then OpenTopoMap (see StaticMapPreview fallback chain).
            StaticMapPreview(
                polyline = route.trackPolyline,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1080f / 640f)
            )

            // (6) Action row — hairline, then three glyphs evenly spread
            //     across the full width at full-strength onSurface. The old
            //     row huddled 20dp muted icons inside text buttons, so the
            //     footer read as a toolbar instead of a feed footer. Counts
            //     stay beside their glyph so the number is still visible.
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = NyasarSpacing.md, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FeedActionSlot(
                    icon = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = stringResource(if (liked) R.string.browse_liked else R.string.browse_like),
                    onClick = {
                        if (SupabaseClientProvider.client.auth.currentSessionOrNull() != null) {
                            onToggleLike()
                        } else {
                            onRequireSignIn()
                        }
                    },
                    tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    label = route.likesCount.takeIf { it > 0 }?.toString(),
                    enabled = !likePending
                )
                Spacer(Modifier.width(NyasarSpacing.lg))
                FeedActionSlot(
                    icon = Icons.AutoMirrored.Outlined.Chat,
                    contentDescription = stringResource(R.string.browse_comment),
                    onClick = onOpenComments,
                    label = route.commentsCount.takeIf { it > 0 }?.toString()
                )
                // Bagikan didorong ke ujung kanan: ia aksi paling jarang
                // dipakai, jadi tidak lagi mendapat sepertiga lebar yang sama
                // dengan suka. Like dan komentar berbagi sisi kiri sebagai
                // "respons", bagikan berdiri sendiri sebagai "sebarkan".
                Spacer(Modifier.weight(1f))
                FeedActionSlot(
                    icon = Icons.Default.Share,
                    contentDescription = stringResource(R.string.browse_share_route),
                    onClick = onShare
                )
            }
        }
    }
}

/**
 * Feed stat block: quiet label on top, number much larger and heavier below,
 * both starting at the same left edge so the three columns read as one row of
 * equal parts (the reference app's stat row is entirely left-aligned).
 */
@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(
            label,
            // Small and quiet: the label is a caption for the number, not a
            // peer of the description above it. bodyMedium made every line in
            // the card the same size.
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            value,
            style = NyasarStatValue,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Save toggle at the end of the byline. A bare outline glyph here was
 *  indistinguishable from the sport icon that used to sit next to it — both
 *  20dp, both muted — so one looked tappable and the other looked decorative
 *  for no reason. The soft circular well marks it as the card's one control,
 *  and the fill/tint pair reads as state without a label. */
@Composable
private fun SaveBadge(
    saved: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val well = if (saved) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val glyph = if (saved) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(well)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
            contentDescription = stringResource(if (saved) R.string.browse_saved else R.string.browse_save),
            modifier = Modifier.size(18.dp),
            tint = glyph
        )
    }
}

/** One slot of the feed footer: 48dp tall, glyph centred inside its share of
 *  the width, count beside it when there is one. */
@Composable
private fun FeedActionSlot(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    label: String? = null,
    enabled: Boolean = true
) {
    Box(modifier.height(48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
                tint = tint
            )
            if (label != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Timestamp for the card byline: relative while the post is fresh ("3 jam"),
 *  a real date afterwards ("25 Okt") — raw ISO-8601 like "2026-10-25" in a
 *  social feed reads as debug output, and the relative part used to be English
 *  hardcoded strings ("3h") in an app whose copy is Indonesian.
 *
 *  Everything short of the date itself now comes from string resources, so the
 *  Indonesian and English builds say their own version of the same thing. */
@Composable
internal fun feedTimestamp(iso: String): String {
    val context = LocalContext.current
    return remember(iso) { formatFeedTimestamp(iso, context) }
}

private fun formatFeedTimestamp(iso: String, context: Context): String {
    val millis = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { setTimeZone(java.util.TimeZone.getTimeZone("UTC")) }
            .parse(iso.take(19))
            ?.time
    }.getOrNull() ?: return iso.take(10)
    val diffMin = ((System.currentTimeMillis() - millis) / 60000L).coerceAtLeast(0)
    return when {
        diffMin < 1 -> context.getString(R.string.feed_time_now)
        diffMin < 60 -> context.getString(R.string.feed_time_minutes, diffMin.toInt())
        diffMin < 60 * 24 -> context.getString(R.string.feed_time_hours, (diffMin / 60).toInt())
        diffMin < 60 * 24 * 7 -> context.getString(R.string.feed_time_days, (diffMin / (60 * 24)).toInt())
        else -> {
            // Per-app locale, so a phone set to Indonesian but the app switched
            // to English still gets "Oct" instead of "Okt". Older than this year
            // and the year has to show too, or last October reads as this one.
            val locale = context.resources.configuration.locales.get(0)
            val date = java.util.Date(millis)
            val thisYear = java.util.Calendar.getInstance()
                .get(java.util.Calendar.YEAR)
            val postYear = java.util.Calendar.getInstance()
                .apply { time = date }
                .get(java.util.Calendar.YEAR)
            val pattern = if (postYear == thisYear) "d MMM" else "d MMM yyyy"
            java.text.SimpleDateFormat(pattern, locale).format(date)
        }
    }
}

/** System share sheet for a route — plain text deep link style; no server
 *  involvement. Uses the same chooser mechanism as the GPX share. */
internal fun shareRoute(context: android.content.Context, route: BrowseRepository.PublicRoute) {
    val text = "${route.name} — Nyasar\n${com.nyasar.app.AppLinks.routeLink(route.id)}"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, route.name))
}

/** Compact moving-time label for cards — same h/m convention as the
 *  formatDuration helpers in history/navigation screens. */
private fun formatCardDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** Equirectangular mini track sketch (aspect-correct via cos(midLat)), used
 *  by browse cards AND the detail screen's preview. Pure function of the
 *  polyline — no map tiles, no network, safe to draw during scroll. */
fun trackPath(points: List<com.nyasar.app.gpx.model.TrackPoint>, width: Float, height: Float): Path {
    val path = Path()
    if (points.size < 2 || width <= 0f || height <= 0f) return path
    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val minLon = points.minOf { it.lon }
    val maxLon = points.maxOf { it.lon }
    val latSpan = (maxLat - minLat).takeIf { it > 1e-9 } ?: return path
    val lonSpan = (maxLon - minLon).takeIf { it > 1e-9 } ?: return path
    val midLatRad = Math.toRadians((minLat + maxLat) / 2.0)
    // Project lon with the cos(midLat) equirectangular factor so east-west
    // distances aren't stretched, then fit the bounding box with padding.
    val xSpan = lonSpan * cos(midLatRad)
    val scale = minOf(width * 0.86f / xSpan.toFloat(), height * 0.86f / latSpan.toFloat())
    val xMid = (minLon + maxLon) / 2.0
    val yMid = (minLat + maxLat) / 2.0
    points.forEachIndexed { i, p ->
        val x = (width / 2f + ((p.lon - xMid) * cos(midLatRad) * scale).toFloat())
        val y = (height / 2f - ((p.lat - yMid) * scale).toFloat())
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

@Composable
fun MiniTrackPreview(polyline: String, modifier: Modifier = Modifier) {
    val points = remember(polyline) { BrowseRepository.decodeTrack(polyline) }
    val lineColor = MaterialTheme.colorScheme.primary
    // drawWithCache: path hanya dibangun ulang saat ukuran berubah, bukan
    // tiap frame. Signature ini dipakai di list yang di-scroll (dan di sheet
    // pratinjau), jadi biaya O(n) per frame tidak perlu dibayar sama sekali.
    androidx.compose.foundation.Canvas(
        modifier = modifier.drawWithCache {
            val path = trackPath(points, size.width, size.height)
            onDrawBehind {
                drawPath(path, lineColor, style = Stroke(width = 3f))
            }
        }
    ) { }
}

/**
 * Sheet urutan feed. Menggantikan `DropdownMenu` kecil yang hanya memberi
 * isyarat lewat warna ikon: di sini setiap pilihan terlihat, dan yang aktif
 * ditandai centang radio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseSortSheet(
    current: BrowseRepository.SortOrder,
    onSelect: (BrowseRepository.SortOrder) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(bottom = NyasarSpacing.lg)
        ) {
            Text(
                stringResource(R.string.browse_sort_cd),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = NyasarSpacing.sm
                )
            )
            BrowseRepository.SortOrder.entries.forEach { order ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(order) }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = order == current,
                        onClick = { onSelect(order) }
                    )
                    Spacer(Modifier.width(NyasarSpacing.md))
                    Text(
                        stringResource(order.labelRes),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

/**
 * Wikiloc-style Filters bottom sheet (konsep user): sport-type chips,
 * Distance & Elevation-Gain range sliders, multi-select difficulty buttons,
 * and a Loop-trails-only switch — all edited as a DRAFT that only reaches
 * the server when Apply is pressed. Swipe-dismiss with pending edits
 * commits them too (user intent — silently dropping them feels like a
 * lost Apply); back/X dismiss keeps the draft for the next open.
 *
 * Wikiloc's PREMIUM rows ("Only authors you follow", "Recorded") are
 * deliberately NOT reproduced — see the filter draft decision in
 * PROJECT_CONTEXT.md.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BrowseFilterSheet(
    viewModel: BrowseViewModel,
    onDismiss: () -> Unit
) {
    val draft by viewModel.draftFilters.collectAsState()

    ModalBottomSheet(
        onDismissRequest = {
            if (viewModel.isDirty(draft)) viewModel.applyFilters()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(R.string.browse_filter_sport),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(NyasarSpacing.sm))
            // Reuse SportType labels/icons — the exact vocabulary routes are
            // published with (the schema CHECK copies these enum names).
            // FlowRow, bukan Row yang digulir horizontal: dengan empat jenis
            // olahraga di layar 360dp, chip terakhir selalu terpotong di tepi
            // dan pengguna tidak tahu masih ada pilihan di kanan (terlihat di
            // screenshot 2026-11). Membungkus ke baris berikutnya membuat
            // SEMUA pilihan terlihat sekaligus tanpa gestur tersembunyi.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SportType.entries.forEach { sport ->
                    FilterChip(
                        selected = sport in draft.sportTypes,
                        onClick = {
                            viewModel.onDraftChanged(
                                draft.copy(
                                    sportTypes = if (sport in draft.sportTypes) draft.sportTypes - sport
                                    else draft.sportTypes + sport
                                )
                            )
                        },
                        label = { Text(stringResource(sport.labelRes), maxLines = 1) },
                        leadingIcon = {
                            Icon(sport.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }
            Spacer(Modifier.height(NyasarSpacing.md))
            RangeSliderItem(
                title = stringResource(R.string.browse_filter_distance),
                unit = "km",
                value = draft.distanceMinKm..draft.distanceMaxKm,
                max = BrowseRepository.BrowseFilters.MAX_DISTANCE_KM,
                step = 1f,
                onValueChange = {
                    viewModel.onDraftChanged(
                        draft.copy(distanceMinKm = it.start, distanceMaxKm = it.endInclusive)
                    )
                }
            )
            Spacer(Modifier.height(NyasarSpacing.md))
            RangeSliderItem(
                title = stringResource(R.string.browse_filter_gain),
                unit = "m",
                value = draft.gainMinM..draft.gainMaxM,
                max = BrowseRepository.BrowseFilters.MAX_GAIN_M,
                step = 50f,
                onValueChange = {
                    viewModel.onDraftChanged(
                        draft.copy(gainMinM = it.start, gainMaxM = it.endInclusive)
                    )
                }
            )
            Spacer(Modifier.height(NyasarSpacing.md))
            Text(
                stringResource(R.string.browse_filter_difficulty),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(NyasarSpacing.sm))
            // Multi-select buttons (Wikiloc) — wire values are the schema's
            // CHECK set; labels reused from the publish form.
            // Alasan sama dengan chip olahraga: di layar sempit / font besar,
            // "Sangat sulit" terpotong. FlowRow membungkusnya utuh.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrowseRepository.DifficultyFilter.entries.forEach { d ->
                    FilterChip(
                        selected = d in draft.difficulties,
                        onClick = {
                            viewModel.onDraftChanged(
                                draft.copy(
                                    difficulties = if (d in draft.difficulties) draft.difficulties - d
                                    else draft.difficulties + d
                                )
                            )
                        },
                        label = { Text(stringResource(d.labelRes), maxLines = 1) }
                    )
                }
            }
            Spacer(Modifier.height(NyasarSpacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.browse_filter_loop_only),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = draft.loopOnly,
                    onCheckedChange = { viewModel.onDraftChanged(draft.copy(loopOnly = it)) }
                )
            }
            Spacer(Modifier.height(NyasarSpacing.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { viewModel.clearAllFilters() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.browse_filter_clear_all), maxLines = 1)
                }
                Button(
                    onClick = {
                        viewModel.applyFilters()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.browse_filter_apply), maxLines = 1)
                }
            }
        }
    }
}

/** Labeled float range slider with Wikiloc's "0 km … +200 km" end labels —
 *  the upper label gains its "+" exactly at the ceiling because that
 *  position is the OPEN bound (no upper filter is sent from there). */
@Composable
private fun RangeSliderItem(
    title: String,
    unit: String,
    value: ClosedFloatingPointRange<Float>,
    max: Float,
    step: Float,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                "%,d %s".format(value.start.roundToInt(), unit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            val plus = if (value.endInclusive >= max - step / 2) "+" else ""
            Text(
                "%s%,d %s".format(plus, value.endInclusive.roundToInt(), unit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RangeSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..max,
            steps = (max / step).toInt() - 1
        )
    }
}
