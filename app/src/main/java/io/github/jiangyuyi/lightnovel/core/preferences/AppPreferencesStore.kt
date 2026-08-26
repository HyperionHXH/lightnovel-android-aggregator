package io.github.jiangyuyi.lightnovel.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class AppThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

enum class AppScale(val label: String, val factor: Float) {
    SMALL("小", 0.9f),
    NORMAL("标准", 1f),
    LARGE("大", 1.12f),
}

data class AppPreferences(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val uiScale: AppScale = AppScale.NORMAL,
    val iconScale: AppScale = AppScale.NORMAL,
)

interface AppPreferencesAccess {
    val preferences: Flow<AppPreferences>
    suspend fun update(value: AppPreferences)
}

private val Context.appPreferencesDataStore by preferencesDataStore(name = "app_preferences")

class AppPreferencesStore(private val context: Context) : AppPreferencesAccess {
    override val preferences: Flow<AppPreferences> = context.appPreferencesDataStore.data.map { values ->
        AppPreferences(
            themeMode = enumValueOrDefault(values[THEME], AppThemeMode.SYSTEM),
            uiScale = enumValueOrDefault(values[UI_SCALE], AppScale.NORMAL),
            iconScale = enumValueOrDefault(values[ICON_SCALE], AppScale.NORMAL),
        )
    }

    override suspend fun update(value: AppPreferences) {
        context.appPreferencesDataStore.edit { values ->
            values[THEME] = value.themeMode.name
            values[UI_SCALE] = value.uiScale.name
            values[ICON_SCALE] = value.iconScale.name
        }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val UI_SCALE = stringPreferencesKey("ui_scale")
        val ICON_SCALE = stringPreferencesKey("icon_scale")

        inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
            value?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } } ?: default
    }
}
