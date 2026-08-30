package io.github.jiangyuyi.lightnovel

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.github.jiangyuyi.lightnovel.core.cache.CachedDataSource
import io.github.jiangyuyi.lightnovel.core.cache.SqliteCacheStore
import io.github.jiangyuyi.lightnovel.core.data.LightNovelRepository
import io.github.jiangyuyi.lightnovel.core.network.CronetImageFetcher
import io.github.jiangyuyi.lightnovel.core.network.LightNovelApi
import io.github.jiangyuyi.lightnovel.core.offline.OfflineLibrary
import io.github.jiangyuyi.lightnovel.core.preferences.ReaderPreferencesStore
import io.github.jiangyuyi.lightnovel.core.preferences.AppPreferencesStore
import io.github.jiangyuyi.lightnovel.core.reader.ChapterFontRepository
import io.github.jiangyuyi.lightnovel.core.session.SessionStore
import io.github.jiangyuyi.lightnovel.core.source.AggregateSearchCoordinator
import io.github.jiangyuyi.lightnovel.core.source.SourceRegistry
import io.github.jiangyuyi.lightnovel.core.updates.SourceUpdateSnapshotStore
import io.github.jiangyuyi.lightnovel.core.updates.SourceUpdateNotificationSnapshotStore
import io.github.jiangyuyi.lightnovel.core.updates.UpdateNotificationSettings
import io.github.jiangyuyi.lightnovel.source.lightnovelkingdom.LightNovelKingdomSource
import io.github.jiangyuyi.lightnovel.source.lightnovelshelf.LightNovelShelfSource

class LightNovelApplication : Application(), ImageLoaderFactory {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(true)
        .components { add(CronetImageFetcher.Factory(container.api)) }
        .build()
}

class AppContainer(application: Application) {
    // Keep the first composition lightweight. Keystore, WorkManager and source
    // adapters are only needed after the shell or onboarding is visible.
    val sessionStore: SessionStore by lazy { SessionStore(application) }
    val readerPreferences: ReaderPreferencesStore by lazy { ReaderPreferencesStore(application) }
    val appPreferences: AppPreferencesStore by lazy { AppPreferencesStore(application) }
    val sourceUpdateSnapshots: SourceUpdateSnapshotStore by lazy { SourceUpdateSnapshotStore(application) }
    val sourceUpdateNotifications: SourceUpdateNotificationSnapshotStore by lazy {
        SourceUpdateNotificationSnapshotStore(application)
    }
    val updateNotifications: UpdateNotificationSettings by lazy { UpdateNotificationSettings(application) }
    val api: LightNovelApi by lazy { LightNovelApi(application) }
    private val cacheStore: SqliteCacheStore by lazy { SqliteCacheStore(application) }
    private val cachedDataSource: CachedDataSource by lazy { CachedDataSource(cacheStore) }
    val repository: LightNovelRepository by lazy {
        LightNovelRepository(api, sessionStore, cachedDataSource)
    }
    val sourceRegistry: SourceRegistry by lazy {
        SourceRegistry(
            listOf(
                LightNovelKingdomSource.from(repository),
                LightNovelShelfSource.create(application),
            ),
        )
    }
    val aggregateSearch: AggregateSearchCoordinator by lazy { AggregateSearchCoordinator(sourceRegistry) }
    val chapterFonts: ChapterFontRepository by lazy { ChapterFontRepository(application) }
    val offlineLibrary: OfflineLibrary by lazy {
        OfflineLibrary(
            application,
            sourceRegistry,
            chapterFonts,
            coverFetcher = { url -> runCatching { api.getBytes(url) }.getOrNull() },
        )
    }
}
