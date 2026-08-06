package io.github.jiangyuyi.lightnovel

import android.app.Application
import io.github.jiangyuyi.lightnovel.core.data.LightNovelRepository
import io.github.jiangyuyi.lightnovel.core.network.LightNovelApi
import io.github.jiangyuyi.lightnovel.core.preferences.ReaderPreferencesStore
import io.github.jiangyuyi.lightnovel.core.session.SessionStore

class LightNovelApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    val sessionStore = SessionStore(application)
    val readerPreferences = ReaderPreferencesStore(application)
    val repository = LightNovelRepository(LightNovelApi(), sessionStore)
}

