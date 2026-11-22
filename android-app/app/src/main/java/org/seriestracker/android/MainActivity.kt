package org.seriestracker.android

import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.seriestracker.core.FollowingFilter
import org.seriestracker.core.Language
import org.seriestracker.core.NavigationTab
import org.seriestracker.core.Series
import org.seriestracker.core.ThemeMode
import org.seriestracker.core.ThemeSettings

class MainActivity : ComponentActivity() {
    private val model by viewModels<TrackerViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PremiereWorker.schedule(this)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val settings = state.dashboard?.settings
            val baseContext = LocalContext.current
            val baseConfiguration = LocalConfiguration.current
            val localized = remember(baseContext, baseConfiguration, settings?.language) {
                val configured = baseContext.localized(settings?.language ?: Language.EN)
                object : ContextWrapper(baseContext) {
                    override fun getResources() = configured.resources
                }
            }
            val theme = settings?.theme ?: ThemeSettings(ThemeMode.SYSTEM, "#F97316")
            val dark = useDarkTheme(theme.mode)
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
            ) {
                SeriesTheme(theme, state.darkAccentText) {
                    TrackerScreen(state, model)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.updateFromLocal()
    }
}

internal val NavigationTab.label: Int
    get() = when (this) {
        NavigationTab.CALENDAR -> R.string.nav_calendar
        NavigationTab.SEARCH -> R.string.nav_search
        NavigationTab.FOLLOWING -> R.string.nav_following
        NavigationTab.SETTINGS -> R.string.nav_settings
    }

private val NavigationTab.icon: Int
    get() = when (this) {
        NavigationTab.CALENDAR -> R.drawable.ic_nav_calendar
        NavigationTab.SEARCH -> R.drawable.ic_nav_search
        NavigationTab.FOLLOWING -> R.drawable.ic_nav_following
        NavigationTab.SETTINGS -> R.drawable.ic_nav_settings
    }

private enum class PendingAction(val message: Int) {
    REPLACE_FILE(R.string.confirm_replace),
    REPLACE_DAV(R.string.confirm_replace),
    UPLOAD_DAV(R.string.confirm_upload),
    UNFOLLOW(R.string.confirm_unfollow),
    MARK_ALL_WATCHED(R.string.confirm_mark_all_watched),
    MARK_SEASON_WATCHED(R.string.confirm_mark_season_watched),
}

@Composable
private fun TrackerScreen(state: ScreenState, model: TrackerViewModel) {
    var page by rememberSaveable { mutableStateOf(NavigationTab.FOLLOWING) }
    var pairingOrigin by rememberSaveable { mutableStateOf(NavigationTab.FOLLOWING) }
    var followingQuery by rememberSaveable { mutableStateOf("") }
    var followingFilter by rememberSaveable { mutableStateOf(FollowingFilter.ACTIVE) }
    var action by rememberSaveable { mutableStateOf<PendingAction?>(null) }
    var mergeFile by rememberSaveable { mutableStateOf(true) }
    var legacyId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pairingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val legacy = state.dashboard?.following?.firstOrNull { it.series.id.toLong() == legacyId }?.series
    val selected = state.search?.firstOrNull { it.id.toLong() == pairingId }
    var showDetail by rememberSaveable { mutableStateOf(false) }
    var selectedSeason by rememberSaveable { mutableStateOf<Int?>(null) }
    val closeDetail = {
        showDetail = false
        model.closeDetail()
    }
    val openSeries: (Series) -> Unit = {
        selectedSeason = null
        showDetail = true
        model.show(it)
    }
    val cancelPairing = {
        page = pairingOrigin
        legacyId = null
        pairingId = null
        if (legacy != null && !state.busy) openSeries(legacy)
    }
    val hasInternalBack = action != null || pairingId != null || showDetail ||
        legacy != null || page != NavigationTab.FOLLOWING
    BackHandler(enabled = hasInternalBack) {
        when {
            action != null -> action = null
            pairingId != null -> pairingId = null
            showDetail -> closeDetail()
            legacy != null -> cancelPairing()
            page != NavigationTab.FOLLOWING -> page = NavigationTab.FOLLOWING
        }
    }
    val context = LocalContext.current
    val exportName = stringResource(R.string.backup_filename)
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importDocument(uri, mergeFile)
    }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.exportDocument(uri)
    }
    val notificationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        PremiereWorker.checkSoon(context)
    }

    action?.let { pending ->
        AlertDialog(
            onDismissRequest = { action = null },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(stringResource(pending.message)) },
            shape = RectangleShape,
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 0.dp,
            confirmButton = {
                ActionButton(R.string.confirm, !state.busy, primary = true) {
                    action = null
                    when (pending) {
                        PendingAction.REPLACE_FILE -> {
                            mergeFile = false
                            openBackup.launch(arrayOf("*/*"))
                        }
                        PendingAction.REPLACE_DAV -> model.downloadWebDav(merge = false)
                        PendingAction.UPLOAD_DAV -> model.uploadWebDav()
                        PendingAction.MARK_ALL_WATCHED -> state.detail?.let(model::markAllWatched)
                        PendingAction.MARK_SEASON_WATCHED -> {
                            val series = state.detail
                            val season = selectedSeason?.toUInt()
                            if (series != null && season != null) model.markSeasonWatched(series, season)
                        }
                        PendingAction.UNFOLLOW -> {
                            state.detail?.let(model::unfollow)
                            showDetail = false
                        }
                    }
                }
            },
            dismissButton = { ActionButton(R.string.cancel) { action = null } },
        )
    }

    if (legacy != null && selected != null) {
        AlertDialog(
            onDismissRequest = { pairingId = null },
            title = { Text(stringResource(R.string.pair_series)) },
            text = { Text(stringResource(R.string.pair_confirm, legacy.name, selected.name)) },
            shape = RectangleShape,
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 0.dp,
            confirmButton = {
                ActionButton(R.string.confirm, !state.busy, primary = true) {
                    pairingId = null
                    selectedSeason = null
                    showDetail = true
                    model.relink(legacy, selected)
                }
            },
            dismissButton = { ActionButton(R.string.cancel) { pairingId = null } },
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val order = state.dashboard?.settings?.navigationOrder ?: NavigationTab.entries
                    order.forEach { candidate ->
                        val active = page == candidate
                        val colors = MaterialTheme.colorScheme
                        Box(
                            Modifier.weight(1f).height(48.dp)
                                .border(1.dp, colors.outline)
                                .background(if (active) colors.primary else colors.surface)
                                .selectable(selected = active, role = Role.Tab) {
                                    page = candidate
                                    showDetail = false
                                    legacyId = null
                                    pairingId = null
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(candidate.icon),
                                contentDescription = stringResource(candidate.label),
                                tint = if (active) colors.onPrimary else colors.onSurface,
                            )
                        }
                    }
                }
            }
            if (state.busy) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.primary))
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            state.message?.let { message ->
                StatusMessageBanner(message, onDismiss = model::dismissMessage)
            }

            val dashboard = state.dashboard
            val detail = state.detail
            val canAct = !state.busy
            val followSeries: (Series) -> Unit = {
                selectedSeason = null
                showDetail = true
                model.follow(it)
            }
            when {
                dashboard == null -> Column(Modifier.padding(16.dp)) {
                    if (state.busy) Text(stringResource(R.string.loading))
                    else ActionButton(R.string.retry, onClick = model::reload)
                }
                showDetail && detail != null -> SeriesDetail(
                    detail, state.episodes, selectedSeason?.toUInt(), { selectedSeason = it.toInt() }, canAct,
                    archived = dashboard.following.any { it.series.id == detail.id && it.archived },
                    loadPoster = model::loadPoster,
                    onBack = closeDetail,
                    onUnfollow = { action = PendingAction.UNFOLLOW },
                    onRefresh = { model.refresh(detail) },
                    onPair = {
                        pairingOrigin = page
                        legacyId = detail.id.toLong()
                        page = NavigationTab.SEARCH
                        showDetail = false
                        model.search(detail.name)
                    },
                    onWatch = model::watched,
                    onWatchAll = { action = PendingAction.MARK_ALL_WATCHED },
                    onWatchSeason = { season ->
                        selectedSeason = season.toInt()
                        action = PendingAction.MARK_SEASON_WATCHED
                    },
                    onArchive = { model.setArchived(detail, it) },
                )
                page == NavigationTab.FOLLOWING -> FollowingPage(
                    series = dashboard.following,
                    query = followingQuery,
                    changeQuery = { followingQuery = it },
                    filter = followingFilter,
                    changeFilter = { followingFilter = it },
                    model = model,
                    enabled = canAct,
                    open = openSeries,
                    onSearch = { page = NavigationTab.SEARCH; showDetail = false },
                )
                page == NavigationTab.SEARCH -> SearchScreen(
                    state, model, legacy, openSeries, followSeries,
                    pair = { pairingId = it.id.toLong() },
                    cancelPairing = cancelPairing,
                )
                page == NavigationTab.CALENDAR -> CalendarScreen(dashboard.upcoming, canAct, model::refreshCalendar) { id ->
                    dashboard.following.find { it.series.id == id }?.series?.let(openSeries)
                }
                page == NavigationTab.SETTINGS -> SettingsScreen(
                    settings = dashboard.settings,
                    state = state,
                    model = model,
                    onExport = { createBackup.launch(exportName) },
                    onImport = { merge ->
                        if (merge) {
                            mergeFile = true
                            openBackup.launch(arrayOf("*/*"))
                        } else {
                            action = PendingAction.REPLACE_FILE
                        }
                    },
                    onUpload = { action = PendingAction.UPLOAD_DAV },
                    onDownload = { merge ->
                        if (merge) model.downloadWebDav(merge = true) else action = PendingAction.REPLACE_DAV
                    },
                    onNotifications = {
                        notificationSettings.launch(
                            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                )
            }
        }
    }
}

private const val STATUS_MESSAGE_TIMEOUT_MILLIS = 5_000L

@Composable
private fun StatusMessageBanner(message: UiMessage, onDismiss: (UiMessage) -> Unit) {
    val accessibility = LocalAccessibilityManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message, accessibility, lifecycle) {
        if (!message.autoDismiss) return@LaunchedEffect
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            STATUS_MESSAGE_TIMEOUT_MILLIS,
            containsText = true,
            containsControls = true,
        ) ?: STATUS_MESSAGE_TIMEOUT_MILLIS
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(timeout)
            dismiss(message)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(message.text),
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        ActionButton(R.string.dismiss) { onDismiss(message) }
    }
}
