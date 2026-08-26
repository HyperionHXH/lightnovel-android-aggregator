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
import io.github.jiangyuyi.lightnovel.core.local.LocalLibraryStore
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
    val sessionStore = SessionStore(application)
    val readerPreferences = ReaderPreferencesStore(application)
    val appPreferences = AppPreferencesStore(application)
    val sourceUpdateSnapshots = SourceUpdateSnapshotStore(application)
    val sourceUpdateNotifications = SourceUpdateNotificationSnapshotStore(application)
    val updateNotifications = UpdateNotificationSettings(application)
    val api = LightNovelApi(application)
    private val cacheStore = SqliteCacheStore(application)
    private val cachedDataSource = CachedDataSource(cacheStore)
    val repository = LightNovelRepository(api, sessionStore, cachedDataSource)
    val sourceRegistry = SourceRegistry(
        listOf(
            LightNovelKingdomSource.from(repository),
            LightNovelShelfSource.create(application),
        ),
    )
    val aggregateSearch = AggregateSearchCoordinator(sourceRegistry)
    val chapterFonts = ChapterFontRepository(application)
    val offlineLibrary = OfflineLibrary(
        application,
        sourceRegistry,
        chapterFonts,
        coverFetcher = { url -> runCatching { api.getBytes(url) }.getOrNull() },
    )
    val localLibrary = LocalLibraryStore(application)
}
