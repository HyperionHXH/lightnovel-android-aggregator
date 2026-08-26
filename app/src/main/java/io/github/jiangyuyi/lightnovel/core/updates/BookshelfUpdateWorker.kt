package io.github.jiangyuyi.lightnovel.core.updates

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.jiangyuyi.lightnovel.LightNovelApplication
import io.github.jiangyuyi.lightnovel.MainActivity
import io.github.jiangyuyi.lightnovel.core.source.NovelSummary
import io.github.jiangyuyi.lightnovel.core.source.ShelfProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope

class BookshelfUpdateWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? LightNovelApplication ?: return Result.failure()
        val container = application.container
        if (!container.updateNotifications.isEnabled()) return Result.success()
        if (!canPostNotifications()) return Result.success()

        val previous = container.sourceUpdateSnapshots.snapshots.first().associateBy { it.novelKey }
        val notified = container.sourceUpdateNotifications.snapshots.first().associateBy { it.novelKey }
        val shelves = supervisorScope {
            container.sourceRegistry.shelfProviders().map { provider ->
                async { loadShelf(provider) }
            }.mapNotNull { deferred -> deferred.await() }
        }
        if (shelves.isEmpty()) return Result.success()

        val now = System.currentTimeMillis()
        container.sourceUpdateSnapshots.saveAll(
            shelves.flatMap { (provider, books) ->
                books.map { novel ->
                    val old = previous[novel.key]
                    SourceUpdateSnapshot(
                        novelKey = novel.key,
                        chapterCount = novel.chapterCount.coerceAtLeast(0),
                        unreadChapterCount = novel.unreadChapterCount?.takeIf { it >= 0 },
                        acknowledgedChapterCount = old?.acknowledgedChapterCount,
                        acknowledgedUnreadChapterCount = old?.acknowledgedUnreadChapterCount,
                        observedAtEpochMillis = now,
                    )
                }
            },
        )

        val updates = shelves.flatMap { (provider, books) ->
            books.filter { novel ->
                previous[novel.key] != null &&
                    novel.isUpdatedComparedTo(previous[novel.key]) &&
                    novel.hasNewerContentThan(
                        previousChapterCount = notified[novel.key]?.chapterCount,
                        previousUnreadChapterCount = notified[novel.key]?.unreadChapterCount,
                    )
            }.map { novel -> provider.descriptor.displayName to novel }
        }
        if (updates.isEmpty()) return Result.success()

        container.sourceUpdateNotifications.saveAll(
            updates.map { (_, novel) ->
                SourceUpdateNotificationSnapshot(
                    novelKey = novel.key,
                    chapterCount = novel.chapterCount.coerceAtLeast(0),
                    unreadChapterCount = novel.unreadChapterCount?.takeIf { it >= 0 },
                )
            },
        )
        postNotification(updates)
        return Result.success()
    }

    private suspend fun loadShelf(provider: ShelfProvider): Pair<ShelfProvider, List<NovelSummary>>? =
        runCatching { provider to provider.getRemoteShelf() }.getOrNull()

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun postNotification(updates: List<Pair<String, NovelSummary>>) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "书架更新",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val bySource = updates.groupingBy { it.first }.eachCount()
        val summary = bySource.entries.joinToString("、") { (source, count) -> "$source $count 本" }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle("书架有 ${updates.size} 本书更新")
                    .setContentText(summary)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "bookshelf_updates"
        private const val NOTIFICATION_ID = 0x5348
    }
}
