package org.seriaro.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.seriaro.core.CoreException
import org.seriaro.core.EpisodeKey
import java.util.concurrent.TimeUnit

internal open class PremiereWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            checkPremieres()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun checkPremieres(): Result {
        val app = applicationContext as TrackerApplication
        val tracker = app.tracker
        val today = localDate()
        var retryRefresh = false
        try {
            tracker.refreshDue(today)
        } catch (_: CoreException.Network) {
            retryRefresh = true
        } catch (_: CoreException.Remote) {
            // Other available series were already refreshed by Rust.
        } catch (_: CoreException.InvalidItem) {
            // Use the local snapshot when the catalog returns incomplete metadata.
        }
        synchronized(app.premiereNotificationLock) { postPremieres(app, today) }
        return if (retryRefresh) Result.retry() else Result.success()
    }

    private fun postPremieres(app: TrackerApplication, today: String) {
        val tracker = app.tracker
        val settings = tracker.settings()
        if (!settings.notificationsEnabled) return
        val context = app.localized(settings.language)
        val manager = app.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE ||
            (Build.VERSION.SDK_INT >= 33 &&
                app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        ) {
            return
        }
        val launch = PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        for (premiere in tracker.checkTodaysPremieres(today)) {
            val episode = premiere.episode
            val key = EpisodeKey(episode.seriesId, episode.season, episode.number)
            val code = context.getString(R.string.episode_code, episode.season.toInt(), episode.number.toInt())
            val episodeName = episode.name.ifBlank { context.getString(R.string.untitled_episode) }
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_premiere)
                .setContentTitle(context.getString(R.string.notification_title, premiere.seriesName))
                .setContentText(context.getString(R.string.notification_episode, code, episodeName))
                .setContentIntent(launch)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build()
            manager.notify("$today-${key.seriesId}-${key.season}-${key.number}", 0, notification)
            tracker.markNotified(key, today)
        }
    }

    companion object {
        private const val CHANNEL_ID = "episode_premieres"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "regular-premieres",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PremiereWorker>(6, TimeUnit.HOURS).build(),
            )
        }

        fun checkSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "check-premieres-now",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PremiereWorker>().build(),
            )
        }

        fun applyNotificationPreference(app: TrackerApplication, enabled: Boolean) {
            if (enabled) {
                checkSoon(app)
            } else {
                synchronized(app.premiereNotificationLock) {
                    app.getSystemService(NotificationManager::class.java).cancelAll()
                }
            }
        }
    }
}
