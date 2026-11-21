package org.seriestracker.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.seriestracker.core.EpisodeState
import org.seriestracker.core.FollowingFilter
import org.seriestracker.core.Premiere
import org.seriestracker.core.Series
import org.seriestracker.core.SeriesProgress
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal val screenPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
internal val screenSpacing = Arrangement.spacedBy(12.dp)

@Composable
internal fun ActionButton(label: Int, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    SquareButton(stringResource(label), enabled, primary, onClick)
}

@Composable
internal fun SquareButton(label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    if (primary) {
        Button(
            onClick, enabled = enabled, shape = RectangleShape, elevation = null,
            border = ButtonDefaults.outlinedButtonBorder(enabled),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) { Text(label) }
    } else {
        OutlinedButton(
            onClick, enabled = enabled, shape = RectangleShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) { Text(label) }
    }
}

@Composable
internal fun SectionHeading(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
}

@Composable
internal fun BorderedPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
internal fun Subtle(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FollowingPage(
    series: List<SeriesProgress>,
    query: String,
    changeQuery: (String) -> Unit,
    filter: FollowingFilter,
    changeFilter: (FollowingFilter) -> Unit,
    model: TrackerViewModel,
    enabled: Boolean,
    open: (Series) -> Unit,
    onSearch: () -> Unit,
) {
    val visible by produceState<List<SeriesProgress>?>(null, series, query, filter) {
        value = model.filteredFollowing(series, query, filter)
    }
    LazyColumn(contentPadding = screenPadding, verticalArrangement = screenSpacing) {
        item { SectionHeading(R.string.nav_following) }
        if (series.isEmpty()) {
            item {
                BorderedPanel {
                    Subtle(stringResource(R.string.following_empty))
                    ActionButton(R.string.nav_search, primary = true, onClick = onSearch)
                }
            }
        } else {
            item {
                OutlinedTextField(
                    query,
                    changeQuery,
                    Modifier.fillMaxWidth(),
                    shape = RectangleShape,
                    singleLine = true,
                    label = { Text(stringResource(R.string.following_filter_title)) },
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FollowingFilter.entries.forEach { choice ->
                        ActionButton(choice.label, primary = filter == choice) { changeFilter(choice) }
                    }
                }
            }
            if (visible?.isEmpty() == true) item {
                val empty = if (filter == FollowingFilter.ARCHIVED && query.isBlank()) {
                    R.string.following_archive_empty
                } else {
                    R.string.following_filter_empty
                }
                BorderedPanel { Subtle(stringResource(empty)) }
            }
        }
        items(visible.orEmpty(), key = { it.series.id.toLong() }) { progress ->
            BorderedPanel(Modifier.clickable(enabled = enabled) { open(progress.series) }) {
                SeriesPosterRow(progress.series, model::loadPoster) {
                    Text(progress.series.name, style = MaterialTheme.typography.titleLarge)
                    progress.series.firstAirDate?.let { Subtle(dateLabel(it)) }
                    if (progress.archived) Subtle(stringResource(R.string.archived))
                    val count = progress.watchedCount.toInt()
                    Subtle(pluralStringResource(R.plurals.watched_count, count, count, progress.episodeCount.toInt()))
                    val fraction = if (progress.episodeCount == 0u) 0f
                        else (progress.watchedCount.toFloat() / progress.episodeCount.toFloat()).coerceIn(0f, 1f)
                    Box(
                        Modifier.fillMaxWidth().height(4.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
                    ) {
                        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}

private val FollowingFilter.label: Int
    get() = when (this) {
        FollowingFilter.ACTIVE -> R.string.following_active
        FollowingFilter.IN_PROGRESS -> R.string.following_in_progress
        FollowingFilter.COMPLETED -> R.string.following_completed
        FollowingFilter.ARCHIVED -> R.string.following_archive
        FollowingFilter.ALL -> R.string.following_all
    }

@Composable
private fun SeriesPosterRow(
    series: Series,
    loadPoster: suspend (Series) -> ImageBitmap?,
    posterWidth: Dp = 64.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        key(series.id, series.posterUrl) {
            val poster by produceState<ImageBitmap?>(null) { value = loadPoster(series) }
            Box(
                Modifier.width(posterWidth).height(posterWidth * 1.5f)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                contentAlignment = Alignment.Center,
            ) {
                val image = poster
                if (image != null) {
                    Image(
                        image,
                        contentDescription = null,
                        modifier = Modifier.matchParentSize().padding(1.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        painterResource(R.drawable.ic_nav_following),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
internal fun SearchScreen(
    state: ScreenState,
    model: TrackerViewModel,
    legacy: Series?,
    open: (Series) -> Unit,
    follow: (Series) -> Unit,
    pair: (Series) -> Unit,
    cancelPairing: () -> Unit,
) {
    var query by rememberSaveable(legacy?.id?.toLong()) { mutableStateOf(legacy?.name ?: state.lastQuery) }
    val focus = LocalFocusManager.current
    val enabled = !state.busy && query.isNotBlank()
    val submit = {
        if (enabled) {
            focus.clearFocus()
            model.search(query)
        }
    }
    val result = state.search
    val following = state.dashboard?.following.orEmpty().map { it.series.id }.toSet()
    LazyColumn(contentPadding = screenPadding, verticalArrangement = screenSpacing) {
        item {
            SectionHeading(R.string.nav_search)
            if (legacy != null) {
                Subtle(stringResource(R.string.pair_search_hint, legacy.name))
                ActionButton(R.string.cancel, !state.busy, onClick = cancelPairing)
            }
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RectangleShape,
                singleLine = true,
                label = { Text(stringResource(R.string.search_label)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )
            ActionButton(R.string.search_submit, enabled, primary = true, onClick = submit)
            if (!state.busy && result != null && result.isEmpty()) Subtle(stringResource(R.string.search_empty))
        }
        items(result.orEmpty(), key = { it.id.toLong() }) { series ->
            val isFollowing = series.id in following
            BorderedPanel {
                SeriesPosterRow(series, model::loadPoster) {
                    Text(series.name, style = MaterialTheme.typography.titleLarge)
                    series.firstAirDate?.let { Subtle(dateLabel(it)) }
                    if (series.overview.isNotBlank()) {
                        Text(series.overview, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                    val action = when {
                        legacy != null -> R.string.pair_series
                        isFollowing -> R.string.open_series
                        else -> R.string.follow
                    }
                    ActionButton(action, !state.busy) {
                        when {
                            legacy != null -> pair(series)
                            isFollowing -> open(series)
                            else -> follow(series)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SeriesDetail(
    series: Series,
    episodes: List<EpisodeState>,
    selectedSeason: UInt?,
    selectSeason: (UInt) -> Unit,
    enabled: Boolean,
    archived: Boolean,
    loadPoster: suspend (Series) -> ImageBitmap?,
    onBack: () -> Unit,
    onUnfollow: () -> Unit,
    onRefresh: () -> Unit,
    onPair: () -> Unit,
    onWatch: (EpisodeState) -> Unit,
    onWatchAll: () -> Unit,
    onArchive: (Boolean) -> Unit,
) {
    val grouped = episodes.groupBy { it.episode.season }
    val season = selectedSeason?.takeIf { it in grouped }
        ?: grouped.keys.firstOrNull { it > 0u }
        ?: grouped.keys.firstOrNull()
    LazyColumn(contentPadding = screenPadding, verticalArrangement = screenSpacing) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(R.string.back, enabled, onClick = onBack)
                if (series.catalogUrl == null) {
                    ActionButton(R.string.pair_series, enabled, onClick = onPair)
                } else {
                    ActionButton(R.string.refresh, enabled, onClick = onRefresh)
                }
                ActionButton(R.string.unfollow, enabled, onClick = onUnfollow)
            }
            SeriesPosterRow(series, loadPoster, posterWidth = 92.dp) {
                Text(series.name, style = MaterialTheme.typography.headlineMedium)
                series.firstAirDate?.let { Subtle(dateLabel(it)) }
                if (archived) Subtle(stringResource(R.string.archived))
            }
            if (series.overview.isNotBlank()) Text(series.overview, Modifier.padding(top = 8.dp))
            val catalogUrl = series.catalogUrl
            if (catalogUrl != null) {
                val uri = LocalUriHandler.current
                ActionButton(R.string.tvmaze_show) { uri.openUri(catalogUrl) }
            } else {
                Subtle(stringResource(R.string.legacy_series))
            }
        }
        val allWatched = episodes.isNotEmpty() && episodes.all { it.watched }
        if (episodes.isNotEmpty() || archived) item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (episodes.isNotEmpty() && !allWatched) {
                    ActionButton(R.string.mark_all_watched, enabled, onClick = onWatchAll)
                }
                if (archived || allWatched) {
                    ActionButton(if (archived) R.string.unarchive else R.string.archive, enabled) {
                        onArchive(!archived)
                    }
                }
            }
        }
        if (grouped.isEmpty()) item { Subtle(stringResource(R.string.episodes_empty)) }
        if (grouped.isNotEmpty()) item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                grouped.keys.forEach { number ->
                    val label = if (number == 0u) stringResource(R.string.specials)
                        else stringResource(R.string.season_n, number.toInt())
                    SquareButton(label, enabled, primary = number == season) { selectSeason(number) }
                }
            }
        }
        items(grouped[season].orEmpty(), key = { "${it.episode.season}-${it.episode.number}" }) { item ->
            val ep = item.episode
            BorderedPanel {
                EpisodeCode(ep.season, ep.number)
                Text(ep.name.ifBlank { stringResource(R.string.untitled_episode) }, style = MaterialTheme.typography.titleLarge)
                ep.airDate?.let { Subtle(dateLabel(it)) }
                if (ep.overview.isNotBlank()) Text(ep.overview)
                ActionButton(if (item.watched) R.string.mark_unwatched else R.string.mark_watched, enabled, primary = item.watched) {
                    onWatch(item)
                }
            }
        }
    }
}

@Composable
internal fun CalendarScreen(premieres: List<Premiere>, enabled: Boolean, refresh: () -> Unit, open: (UInt) -> Unit) {
    LazyColumn(contentPadding = screenPadding, verticalArrangement = screenSpacing) {
        item {
            SectionHeading(R.string.nav_calendar)
            Subtle(pluralStringResource(R.plurals.calendar_range, 90, 90))
            ActionButton(R.string.refresh, enabled, onClick = refresh)
        }
        if (premieres.isEmpty()) item {
            BorderedPanel { Subtle(stringResource(R.string.calendar_empty)) }
        }
        premieres.groupBy { it.episode.airDate }.forEach { (date, entries) ->
            item {
                if (date != null) {
                    Text(dateLabel(date), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }
            items(entries, key = { "${it.episode.seriesId}-${it.episode.season}-${it.episode.number}" }) { entry ->
                val ep = entry.episode
                BorderedPanel(Modifier.clickable(enabled = enabled) { open(ep.seriesId) }) {
                    Text(entry.seriesName, style = MaterialTheme.typography.titleLarge)
                    EpisodeCode(ep.season, ep.number)
                    Text(ep.name.ifBlank { stringResource(R.string.untitled_episode) })
                    if (entry.watched) Subtle(stringResource(R.string.watched))
                }
            }
        }
    }
}

@Composable
private fun EpisodeCode(season: UInt, number: UInt) {
    Text(
        stringResource(R.string.episode_code, season.toInt(), number.toInt()),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun dateLabel(date: String): String {
    val locale = LocalConfiguration.current.locales[0]
    if (locale.language == "tlh") return date
    return LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
}
