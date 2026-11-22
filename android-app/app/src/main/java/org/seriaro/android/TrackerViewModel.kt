package org.seriaro.android

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.seriaro.core.CoreException
import org.seriaro.core.Dashboard
import org.seriaro.core.EpisodeKey
import org.seriaro.core.EpisodeState
import org.seriaro.core.FollowingFilter
import org.seriaro.core.SeriesProgress
import org.seriaro.core.Series
import org.seriaro.core.Settings
import org.seriaro.core.accentUsesDarkText
import org.seriaro.core.filterFollowing
import java.io.ByteArrayOutputStream
import java.io.IOException

internal data class CredentialsSummary(
    val webDavUrl: String = "",
    val webDavUsername: String = "",
)

internal class UiMessage private constructor(
    @StringRes val text: Int,
    val autoDismiss: Boolean,
) {
    companion object {
        fun status(@StringRes text: Int) = UiMessage(text, autoDismiss = true)
        fun error(@StringRes text: Int) = UiMessage(text, autoDismiss = false)
    }
}

internal data class ScreenState(
    val dashboard: Dashboard? = null,
    val darkAccentText: Boolean = true,
    val search: List<Series>? = null,
    val lastQuery: String = "",
    val detail: Series? = null,
    val episodes: List<EpisodeState> = emptyList(),
    val credentials: CredentialsSummary = CredentialsSummary(),
    val busy: Boolean = false,
    val message: UiMessage? = null,
)

internal class TrackerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TrackerApplication
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()

    init {
        reload()
    }

    fun reload() = operation {
        val snapshot = snapshot()
        try {
            snapshot.copy(credentials = credentials())
        } catch (_: Exception) {
            snapshot.copy(message = UiMessage.error(R.string.error_credentials))
        }
    }

    fun search(query: String) = operation {
        val cleared = copy(search = null, lastQuery = query, detail = null, episodes = emptyList())
        try {
            cleared.copy(search = app.tracker.search(query))
        } catch (error: CoreException) {
            cleared.copy(message = UiMessage.error(errorMessage(error)))
        }
    }

    fun follow(series: Series) = operation {
        app.tracker.follow(series.id, localDate())
        snapshot(series).copy(message = UiMessage.status(R.string.status_following))
    }

    fun relink(legacy: Series, selected: Series) = operation {
        app.tracker.relink(legacy.id, selected.id, localDate())
        snapshot(selected).copy(message = UiMessage.status(R.string.status_linked))
    }

    fun show(series: Series) = operation { snapshot(series) }

    fun closeDetail() = mutable.update { it.copy(detail = null, episodes = emptyList()) }

    fun unfollow(series: Series) = operation {
        app.tracker.unfollow(series.id)
        snapshot(null).copy(message = UiMessage.status(R.string.status_unfollowed))
    }

    fun watched(state: EpisodeState) = operation {
        val episode = state.episode
        app.tracker.setWatched(EpisodeKey(episode.seriesId, episode.season, episode.number), !state.watched)
        snapshot()
    }

    fun markAllWatched(series: Series) = operation {
        app.tracker.markAllWatched(series.id)
        snapshot().copy(message = UiMessage.status(R.string.status_marked_all_watched))
    }

    fun markSeasonWatched(series: Series, season: UInt) = operation {
        app.tracker.markSeasonWatched(series.id, season)
        snapshot().copy(message = UiMessage.status(R.string.status_marked_season_watched))
    }

    fun setArchived(series: Series, archived: Boolean) = operation {
        app.tracker.setArchived(series.id, archived)
        snapshot().copy(message = UiMessage.status(if (archived) R.string.status_archived else R.string.status_unarchived))
    }

    suspend fun filteredFollowing(entries: List<SeriesProgress>, query: String, filter: FollowingFilter) =
        withContext(Dispatchers.Default) { filterFollowing(entries, query, filter) }

    suspend fun loadPoster(series: Series): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val bytes = app.tracker.posterForSeries(series.id, series.posterUrl) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: CoreException) {
            null
        }
    }

    fun refresh(series: Series) = operation {
        app.tracker.refresh(series.id, localDate())
        snapshot().copy(message = UiMessage.status(R.string.status_refreshed))
    }

    fun refreshCalendar() = operation {
        try {
            app.tracker.refreshDue(localDate())
            snapshot().copy(message = UiMessage.status(R.string.status_refreshed))
        } catch (error: CoreException) {
            snapshot().copy(message = UiMessage.error(errorMessage(error)))
        }
    }

    fun applySettings(settings: Settings) = operation {
        app.tracker.setSettings(settings)
        if (dashboard?.settings?.notificationsEnabled != settings.notificationsEnabled) {
            PremiereWorker.applyNotificationPreference(app, settings.notificationsEnabled)
        }
        snapshot().copy(message = UiMessage.status(R.string.status_saved))
    }

    fun saveWebDav(url: String, username: String, password: String) = operation {
        app.secrets.saveWebDav(url, username, password)
        copy(credentials = credentials(), message = UiMessage.status(R.string.status_saved))
    }

    fun clearWebDav() = operation {
        app.secrets.clearWebDav()
        copy(credentials = credentials(), message = UiMessage.status(R.string.status_saved))
    }

    fun exportDocument(uri: Uri) = operation {
        val data = app.tracker.exportBackup()
        val stream = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException()
        stream.use { it.write(data) }
        copy(message = UiMessage.status(R.string.status_exported))
    }

    fun importDocument(uri: Uri, merge: Boolean) = operation {
        val stream = app.contentResolver.openInputStream(uri) ?: throw IOException()
        val data = stream.use { input ->
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BACKUP_BYTES) return@operation copy(message = UiMessage.error(R.string.error_too_large))
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        app.tracker.importBackup(data, merge)
        snapshotAfterImport()
    }

    fun uploadWebDav() = operation {
        val config = app.secrets.readWebDav() ?: return@operation copy(message = UiMessage.error(R.string.error_webdav_missing))
        app.tracker.uploadWebdav(config.url, config.username, config.password)
        copy(message = UiMessage.status(R.string.status_exported))
    }

    fun downloadWebDav(merge: Boolean) = operation {
        val config = app.secrets.readWebDav() ?: return@operation copy(message = UiMessage.error(R.string.error_webdav_missing))
        app.tracker.downloadWebdav(config.url, config.username, config.password, merge)
        snapshotAfterImport()
    }

    private fun ScreenState.snapshotAfterImport(): ScreenState {
        val updated = snapshot(null)
        updated.dashboard?.settings?.let {
            PremiereWorker.applyNotificationPreference(app, it.notificationsEnabled)
        }
        return updated.copy(message = UiMessage.status(R.string.status_imported))
    }

    fun dismissMessage(message: UiMessage) = mutable.update {
        if (it.message === message) it.copy(message = null) else it
    }

    fun updateFromLocal() {
        if (state.value.dashboard != null) operation(clearMessage = false) { snapshot() }
    }

    private fun operation(clearMessage: Boolean = true, block: ScreenState.() -> ScreenState) {
        val initial = mutable.value
        if (initial.busy) return
        val working = initial.copy(busy = true, message = if (clearMessage) null else initial.message)
        mutable.value = working
        viewModelScope.launch {
            val updated = try {
                withContext(Dispatchers.IO) { working.block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                working.copy(message = UiMessage.error(errorMessage(error)))
            }
            mutable.update { current ->
                val message = if (updated.message === working.message) current.message else updated.message
                updated.copy(busy = false, message = message)
            }
        }
    }

    private fun ScreenState.snapshot(preferredDetail: Series? = detail): ScreenState {
        val updated = app.tracker.dashboard(localDate())
        val updatedDetail = updated.following.firstOrNull { it.series.id == preferredDetail?.id }?.series
        return copy(
            dashboard = updated,
            darkAccentText = accentUsesDarkText(updated.settings.theme.accentHex),
            detail = updatedDetail,
            episodes = if (updatedDetail == null) emptyList() else app.tracker.episodes(updatedDetail.id),
        )
    }

    private fun credentials(): CredentialsSummary {
        val config = app.secrets.readWebDav()
        return CredentialsSummary(config?.url.orEmpty(), config?.username.orEmpty())
    }

    private companion object {
        const val MAX_BACKUP_BYTES = 32 * 1024 * 1024

        fun errorMessage(error: Exception): Int = when (error) {
            is CoreException.Database, is CoreException.DatabaseVersion -> R.string.error_database
            is CoreException.InvalidItem, is CoreException.InvalidDate -> R.string.error_item
            is CoreException.IncompleteSeries -> R.string.error_archive_incomplete
            is CoreException.InvalidAccent -> R.string.error_accent
            is CoreException.InvalidBackup -> R.string.error_backup
            is CoreException.BackupVersion -> R.string.error_backup_version
            is CoreException.Unauthorized -> R.string.error_auth
            is CoreException.Network -> R.string.error_network
            is CoreException.Remote -> R.string.error_remote
            is CoreException.InvalidWebDavUrl -> R.string.error_webdav_url
            is CoreException.FileTooLarge -> R.string.error_too_large
            is IOException -> R.string.error_file
            is java.security.GeneralSecurityException -> R.string.error_credentials
            else -> R.string.error_general
        }
    }
}
