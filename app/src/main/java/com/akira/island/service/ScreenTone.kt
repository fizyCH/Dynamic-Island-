package com.akira.island.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import kotlin.math.floor

/**
 * Цвет фона вокруг острова. Пока включена настройка «По цвету на экране», служба раз в пару
 * секунд снимает экран и смотрит только полоски сразу под островом и по бокам от него.
 * Если фон в этой зоне тёмный — остров получает серую обводку; светлый — обводки нет.
 *
 * Тёмный считается по двум признакам: медиане яркости и доле тёмных пикселей. Так чёрный и
 * тёмно-серый не теряются из-за белого текста, иконок или одной светлой полосы в кадре.
 * Между включением и выключением есть запас, чтобы обводка не мигала на границе.
 *
 * Снимок целиком остаётся в памяти телефона: наружу уходят только вычисленные яркости.
 */
object ScreenTone {
    private const val BINS = 32
    /** Всё, что темнее этого (до тёмно-серого), считаем тёмным пикселем. */
    private const val DARK_PIXEL_LIMIT = 0.42f
    /** Если тёмных пикселей больше этой доли — фон тёмный. */
    private const val DARK_SHARE = 0.55f
    /** Или если типичная (медианная) яркость ниже этой. */
    private const val DARK_MEDIAN = 0.30f
    /** Запас на выключение: фон должен стать заметно светлее. */
    private const val LIGHT_SHARE = 0.40f
    private const val LIGHT_MEDIAN = 0.36f
    private const val MIN_INTERVAL_MS = 650L

    private val _dark = MutableStateFlow(false)
    val dark: StateFlow<Boolean> = _dark.asStateFlow()

    private data class Tone(val median: Float, val darkShare: Float)

    // Отдельный поток: снимок и разбор пикселей не должны трогать главный поток острова
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "island-screen-tone").apply { isDaemon = true }
    }

    private var busy = false
    private var lastRequest = 0L

    /** Попросить новый замер. Частые вызовы схлопываются: не чаще раза в [MIN_INTERVAL_MS]. */
    fun sample(service: AccessibilityService, regions: List<Rect>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || regions.isEmpty()) {
            clear()
            return
        }
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (busy || now - lastRequest < MIN_INTERVAL_MS) return
            busy = true
            lastRequest = now
        }
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val dark = runCatching { measure(result, regions) }.getOrDefault(false)
                _dark.value = dark
                synchronized(this@ScreenTone) { busy = false }
            }

            override fun onFailure(errorCode: Int) {
                _dark.value = false
                synchronized(this@ScreenTone) { busy = false }
            }
        }
        runCatching {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
        }.onFailure {
            _dark.value = false
            synchronized(this) { busy = false }
        }
    }

    /** Нет свежего замера — обводка не нужна. */
    fun clear() {
        _dark.value = false
    }

    private fun measure(result: AccessibilityService.ScreenshotResult, regions: List<Rect>): Boolean {
        val buffer = result.hardwareBuffer
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return false
            val bitmap = try {
                hardware.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                hardware.recycle()
            } ?: return false
            try {
                val tone = tone(bitmap, regions)
                // С запасом: уже включённая обводка гаснет только на заметно более светлом фоне
                return if (_dark.value) {
                    tone.darkShare >= LIGHT_SHARE || tone.median <= LIGHT_MEDIAN
                } else {
                    tone.darkShare >= DARK_SHARE || tone.median <= DARK_MEDIAN
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            buffer.close()
        }
    }

    /** Медиана яркости и доля тёмных пикселей: белый текст и иконки не делают тёмный фон светлым. */
    private fun tone(bitmap: Bitmap, regions: List<Rect>): Tone {
        val histogram = IntArray(BINS)
        val darkBin = (DARK_PIXEL_LIMIT * BINS).toInt().coerceIn(0, BINS - 1)
        var count = 0
        var darkCount = 0
        for (r in regions) {
            val x0 = floor(r.left).toInt().coerceIn(0, bitmap.width - 1)
            val y0 = floor(r.top).toInt().coerceIn(0, bitmap.height - 1)
            val x1 = floor(r.right).toInt().coerceIn(x0 + 1, bitmap.width)
            val y1 = floor(r.bottom).toInt().coerceIn(y0 + 1, bitmap.height)
            // Даже широкую полосу разбираем примерно по 40×10 точкам — этого хватает для фона
            val stepX = ((x1 - x0) / 40).coerceAtLeast(1)
            val stepY = ((y1 - y0) / 10).coerceAtLeast(1)
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val c = bitmap.getPixel(x, y)
                    val luminance = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
                    val bin = (luminance * BINS).toInt().coerceIn(0, BINS - 1)
                    histogram[bin]++
                    if (bin <= darkBin) darkCount++
                    count++
                    x += stepX
                }
                y += stepY
            }
        }
        if (count == 0) return Tone(1f, 0f)
        var seen = 0
        var median = 1f
        for (i in histogram.indices) {
            seen += histogram[i]
            if (seen * 2 >= count) {
                median = (i + 0.5f) / BINS
                break
            }
        }
        return Tone(median, darkCount.toFloat() / count)
    }
}
