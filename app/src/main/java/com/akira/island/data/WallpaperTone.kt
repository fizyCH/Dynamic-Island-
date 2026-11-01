package com.akira.island.data

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Тёмные ли обои. По этому признаку остров сам включает серую обводку, чтобы чёрная капсула
 * не растворялась на тёмном фоне.
 *
 * Цвета обоев читаются через [WallpaperManager.getWallpaperColors] — без доступа к самой картинке
 * и без разрешений. Если система ещё не посчитала цвета (или стоит живая обивка), обводка не
 * включается: лучше не подсвечивать светлые обои, чем ошибиться.
 */
object WallpaperTone {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _dark = MutableStateFlow(false)
    val dark: StateFlow<Boolean> = _dark.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    /** Начать следить за сменой обоев. Вызывается, пока живёт служба острова. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        refresh(app)
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh(app)
        }
        runCatching {
            ContextCompat.registerReceiver(
                app,
                r,
                IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess { receiver = r }
    }

    /** Перестать следить за обоями вместе со службой острова. */
    @Synchronized
    fun stop(context: Context) {
        receiver?.let { r -> runCatching { context.applicationContext.unregisterReceiver(r) } }
        receiver = null
    }

    /** Перечитать обои, например после смены темы или когда открыли настройки оформления. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            _dark.value = detectDark(app)
        }
    }

    private fun detectDark(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return false
        val colors = runCatching {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull() ?: return false
        // На Android 12+ система сама говорит, нужна ли тёмная тема под эти обои
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hints = colors.colorHints
            if (hints and WallpaperColors.HINT_SUPPORTS_DARK_THEME != 0) return true
            if (hints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0) return false
        }
        return colors.primaryColor.luminance() < DARK_WALLPAPER_LUMINANCE
    }

    private fun Color.luminance(): Float =
        0.2126f * red() / 255f + 0.7152f * green() / 255f + 0.0722f * blue() / 255f

    /** Ниже этой яркости обои считаются тёмными; порог близок к системному для тёмной темы. */
    private const val DARK_WALLPAPER_LUMINANCE = 0.32f
}
