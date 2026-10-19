package com.threadmaster.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 用户可调设置。默认值按"记录准确、开销可控"来定。 */
data class AppSettings(
    val intervalMs: Long = 500,
    val fpsEnabled: Boolean = true,
    val recordThreads: Boolean = true,
    val foregroundOnly: Boolean = true,
    val hudEnabled: Boolean = false,
    val hudAlpha: Float = 0.94f,
    val themeMode: Int = THEME_SYSTEM,
    val lastPackage: String = "",
    val lastLabel: String = "",
) {
    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }
}

/** 用 SharedPreferences 存设置：量小、无需迁移、启动即可读。 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("tm_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun read(): AppSettings = AppSettings(
        intervalMs = prefs.getLong(KEY_INTERVAL, 500).coerceIn(250, 4000),
        fpsEnabled = prefs.getBoolean(KEY_FPS, true),
        recordThreads = prefs.getBoolean(KEY_THREADS, true),
        foregroundOnly = prefs.getBoolean(KEY_FG_ONLY, true),
        hudEnabled = prefs.getBoolean(KEY_HUD, false),
        hudAlpha = prefs.getFloat(KEY_HUD_ALPHA, 0.94f),
        themeMode = prefs.getInt(KEY_THEME, AppSettings.THEME_SYSTEM),
        lastPackage = prefs.getString(KEY_LAST_PKG, "").orEmpty(),
        lastLabel = prefs.getString(KEY_LAST_LABEL, "").orEmpty(),
    )

    fun setInterval(ms: Long) = update(KEY_INTERVAL, ms.coerceIn(250, 4000))

    fun setFpsEnabled(enabled: Boolean) = update(KEY_FPS, enabled)

    fun setRecordThreads(enabled: Boolean) = update(KEY_THREADS, enabled)

    fun setForegroundOnly(enabled: Boolean) = update(KEY_FG_ONLY, enabled)

    fun setHudEnabled(enabled: Boolean) = update(KEY_HUD, enabled)

    fun setHudAlpha(alpha: Float) = update(KEY_HUD_ALPHA, alpha.coerceIn(0.4f, 1f))

    fun setThemeMode(mode: Int) = update(KEY_THEME, mode.coerceIn(0, 2))

    fun rememberTarget(packageName: String, label: String) {
        prefs.edit().putString(KEY_LAST_PKG, packageName).putString(KEY_LAST_LABEL, label).apply()
        _state.value = _state.value.copy(lastPackage = packageName, lastLabel = label)
    }

    private fun update(key: String, value: Any) {
        prefs.edit().also { editor ->
            when (value) {
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
            }
        }.apply()
        _state.value = read()
    }

    companion object {
        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_FPS = "fps_enabled"
        private const val KEY_THREADS = "record_threads"
        private const val KEY_FG_ONLY = "foreground_only"
        private const val KEY_HUD = "hud_enabled"
        private const val KEY_HUD_ALPHA = "hud_alpha"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LAST_PKG = "last_pkg"
        private const val KEY_LAST_LABEL = "last_label"
    }
}
