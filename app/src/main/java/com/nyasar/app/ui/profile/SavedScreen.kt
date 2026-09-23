package com.nyasar.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.R
import com.nyasar.app.data.supabase.BrowseRepository
import com.nyasar.app.ui.browse.PublicRouteCard
import com.nyasar.app.ui.browse.shareRoute
import com.nyasar.app.ui.theme.NyasarSpacing

/**
 * Profile → Saved tab: the user's private bookmark list (saved_routes,
 * Fase 4). Cards are the EXACT browse [PublicRouteCard] — same byline,
 * stats, map snapshot and like/comment/share/bookmark row — so a route
 * looks identical everywhere it appears. The only behavioral difference:
 * toggling a bookmark OFF removes the card from this list.
 *
 * Embedded variant (no Scaffold/TopAppBar) — the Profile host already
 * renders the page header + sub-tab row, mirroring how
 * [com.nyasar.app.ui.history.ActivityHistoryEmbedded] is hosted.
 */
@Composable
fun SavedEmbedded(
    viewModel: SavedViewModel = viewModel(),
    onOpenRoute: (String) -> Unit,
    onRequireSignIn: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val likedIds by viewModel.likedIds.collectAsState()
    val likePending by viewModel.likePending.collectAsState()
    val savedIds by viewModel.savedIds.collectAsState()
    val savePending by viewModel.savePending.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Realtime guarantee: re-sync EVERY time this pane becomes visible.
    // A route saved on Browse while the app was on another tab joins the
    // list the instant Saved is opened — no restart, no manual refresh.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            is SavedViewModel.SavedState.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
            is SavedViewModel.SavedState.Error -> Text(
                stringResource(s.error.messageRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(NyasarSpacing.lg)
            )
            is SavedViewModel.SavedState.Loaded -> {
                if (s.routes.isEmpty()) {
                    SavedEmptyState(Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(NyasarSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(NyasarSpacing.md),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(s.routes, key = { it.id }) { route ->
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
                    }
                }
            }
        }
    }
}

/** Empty state mirrors the History empty state's visual language:
 *  tinted circle icon + short copy, centered. */
@Composable
private fun SavedEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(NyasarSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(96.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
        Text(
            stringResource(R.string.saved_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = NyasarSpacing.lg)
        )
    }
}
