package com.akira.island.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.palette.graphics.Palette
import com.akira.island.core.Controls
import com.akira.island.core.IslandBus
import com.akira.island.core.Lyrics
import com.akira.island.core.LyricsStatus
import com.akira.island.core.MediaInfo
import com.akira.island.core.PlayerAction
import com.akira.island.core.Tints
import com.akira.island.data.SettingsRepo
import com.akira.island.lyrics.LyricsRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Следит за системными медиасессиями (любой плеер) и выбирает ту, что играет. */
class MediaMonitor(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, NotifListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val bound = ArrayList<Pair<MediaController, MediaController.Callback>>()
    private var current: MediaController? = null
    /** Отпечаток последней обложки (см. [artPrint]); null — обложки нет */
    private var lastArt: Long? = null
    private var artJob: Job? = null
    private var lyricsJob: Job? = null
    private var hideJob: Job? = null
    private var hiddenKey: String? = null
    private var lyricsKey: String? = null
    private var pendingHideKey: String? = null
    /** Плеер на миг убирает метаданные при переключении трека — не прячем карточку сразу. */
    private var blankSince = 0L
    private var blankJob: Job? = null
    private val musicFindReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MUSICFIND_STATE_ACTION) return
            IslandBus.musicFindFavorite.value = intent.getBooleanExtra(MUSICFIND_EXTRA_FAVORITE, false)
            IslandBus.musicFindRepeat.value = intent.getIntExtra(MUSICFIND_EXTRA_REPEAT, 0)
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bind(list.orEmpty())
    }

    fun start() {
        // MusicFind additionally sends its favorite/repeat state explicitly: some controllers
        // don't expose custom media actions, and the island must still show the right star/loop.
        runCatching {
            ContextCompat.registerReceiver(
                context,
                musicFindReceiver,
                IntentFilter(MUSICFIND_STATE_ACTION),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        scope.launch {
            combine(IslandBus.musicFindFavorite, IslandBus.musicFindRepeat) { _, _ -> Unit }
                .collect { refresh() }
        }
        try {
            manager?.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            bind(manager?.getActiveSessions(component).orEmpty())
        } catch (_: SecurityException) {
            // Доступ к уведомлениям ещё не выдан
        }
        // Включили текст песен в настройках — подгрузим для текущего трека
        scope.launch {
            SettingsRepo.state.map { it.lyricsEnabled }.distinctUntilChanged().collect { enabled ->
                val m = IslandBus.media.value
                if (m != null && (enabled && IslandBus.lyrics.value?.key != m.key || !enabled)) loadLyrics(m)
            }
        }
    }

    fun stop() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        runCatching { context.unregisterReceiver(musicFindReceiver) }
        unbindAll()
        scope.cancel()
        Controls.mediaController = null
        IslandBus.media.value = null
        IslandBus.lyrics.value = null
    }

    private fun bind(list: List<MediaController>) {
        unbindAll()
        list.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onSessionDestroyed() = refresh()
            }
            c.registerCallback(cb, handler)
            bound += c to cb
        }
        refresh()
    }

    private fun unbindAll() {
        bound.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        bound.clear()
    }

    private fun isPlaying(c: MediaController): Boolean {
        val st = c.playbackState?.state
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
    }

    private fun refresh() {
        val controllers = bound.map { it.first }
        val chosen = controllers.firstOrNull { isPlaying(it) }
            ?: current?.let { cur -> controllers.firstOrNull { it.sessionToken == cur.sessionToken } }
            ?: controllers.firstOrNull { it.metadata != null }
        current = chosen
        Controls.mediaController = chosen

        val meta = chosen?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (chosen == null || meta == null || title.isNullOrBlank()) {
            if (keepThroughBlank()) return
            publish(null)
            return
        }
        blankSince = 0L
        blankJob?.cancel()
        blankJob = null

        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val state = chosen.playbackState
        val playing = isPlaying(chosen)
        val key = "${chosen.packageName}|$title|$artist"
        val prev = IslandBus.media.value
        val sameTrack = prev?.key == key

        val (parsedLike, dislike) = rates(state)
        val parsedRepeat = repeat(state)
        // MusicFind умеет лайк и повтор, но старые прошивки/контроллеры иногда не показывают
        // кастомные команды в PlaybackState. Для него кнопки показываем всегда — команды известны.
        val musicFind = chosen.packageName == MUSICFIND_PACKAGE
        val extras = state?.extras
        val favoriteExtra = IslandBus.musicFindFavorite.value
            ?: if (musicFind && extras?.containsKey(MUSICFIND_EXTRA_FAVORITE) == true) {
                extras.getBoolean(MUSICFIND_EXTRA_FAVORITE)
            } else {
                null
            }
        val repeatExtra = IslandBus.musicFindRepeat.value
            ?: if (musicFind && extras?.containsKey(MUSICFIND_EXTRA_REPEAT) == true) {
                extras.getInt(MUSICFIND_EXTRA_REPEAT, -1)
            } else {
                null
            }
        val like = when {
            favoriteExtra != null -> PlayerAction(MUSICFIND_FAVORITE, favoriteExtra)
            parsedLike != null -> parsedLike
            musicFind -> PlayerAction(MUSICFIND_FAVORITE, false)
            else -> null
        }
        val repeat = when {
            repeatExtra != null && repeatExtra >= 0 ->
                PlayerAction(MUSICFIND_REPEAT, repeatExtra != 0, repeatExtra == 2)
            parsedRepeat != null -> parsedRepeat
            musicFind -> PlayerAction(MUSICFIND_REPEAT, false)
            else -> null
        }
        val info = MediaInfo(
            key = key,
            pkg = chosen.packageName,
            title = title,
            artist = artist,
            album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM),
            art = if (sameTrack) prev?.art else null,
            accent = if (sameTrack && prev != null) prev.accent else Tints.Lavender,
            durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = state?.position ?: 0,
            positionTime = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            playing = playing,
            canSeek = (state?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L,
            like = like,
            dislike = dislike,
            repeat = repeat,
        )

        // На паузе карточка держится ещё немного и уходит, как на iPhone
        if (playing) {
            hideJob?.cancel()
            hiddenKey = null
        } else {
            if (hiddenKey == key) {
                publish(null)
                return
            }
            if (hideJob?.isActive != true || pendingHideKey != key) {
                hideJob?.cancel()
                pendingHideKey = key
                hideJob = scope.launch {
                    delay(PAUSE_KEEP_MS)
                    hiddenKey = key
                    publish(null)
                }
            }
        }

        publish(info)
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        // Плеер при каждой паузе и перемотке отдаёт обложку новым объектом, хотя картинка та же, — её не пересчитываем.
        // Сравниваем не по размеру, а по самой картинке: обложки треков обычно одного размера, а при смене трека
        // плеер часто сперва присылает новое название со старой обложкой и только следом — новую обложку
        val print = art?.let(::artPrint)
        if (!sameTrack || print != lastArt) loadArt(key, art, print)
        // Текст ищем один раз на трек, а не после каждого скрытия карточки на паузе
        if (key != lyricsKey) {
            lyricsKey = key
            loadLyrics(info)
        }
    }

    /**
     * «Нравится» или «Не нравится» среди своих кнопок плеера: Яндекс Музыка, YouTube Music, Spotify кладут их
     * в медиасессию рядом с паузой. Уже лайкнутый трек плеер подписывает «Убрать…», «Remove…», «Unlike».
     */
    private fun rates(state: PlaybackState?): Pair<PlayerAction?, PlayerAction?> {
        var like: PlayerAction? = null
        var dislike: PlayerAction? = null
        for (a in state?.customActions.orEmpty()) {
            val t = "${a.action} ${a.name}".lowercase()
            val favoriteExtra = a.extras?.takeIf { it.containsKey(MUSICFIND_EXTRA_FAVORITE) }
                ?.getBoolean(MUSICFIND_EXTRA_FAVORITE)
            when {
                DislikeWords.containsMatchIn(t) -> if (dislike == null) dislike = PlayerAction(a.action, UndoWords.containsMatchIn(t))
                LikeWords.containsMatchIn(t) -> if (like == null) {
                    like = PlayerAction(a.action, favoriteExtra ?: UndoWords.containsMatchIn(t))
                }
            }
        }
        return like to dislike
    }

    /**
     * Повтор из медиасессии. MusicFind отдаёт команду «Повтор», «Повтор всех» или «Повтор одного»;
     * по подписи понимаем, включён ли повтор и какой именно.
     */
    private fun repeat(state: PlaybackState?): PlayerAction? {
        for (a in state?.customActions.orEmpty()) {
            val all = "${a.action} ${a.name}".lowercase()
            if (!RepeatWords.containsMatchIn(all)) continue
            val mode = a.extras?.takeIf { it.containsKey(MUSICFIND_EXTRA_REPEAT) }
                ?.getInt(MUSICFIND_EXTRA_REPEAT, -1) ?: -1
            if (mode >= 0) return PlayerAction(a.action, mode != 0, mode == 2)
            val name = a.name?.toString().orEmpty().lowercase()
            val one = OneWords.containsMatchIn(name)
            val on = one || AllWords.containsMatchIn(name)
            return PlayerAction(a.action, on, one)
        }
        return null
    }

    /**
     * MusicFind на секунду останавливает старый трек и готовит новый; метаданных в этот момент нет.
     * Держим прежнюю карточку ещё немного, чтобы остров не мигал и не сворачивался при переключении.
     */
    private fun keepThroughBlank(): Boolean {
        if (IslandBus.media.value == null) return false
        val now = SystemClock.elapsedRealtime()
        if (blankSince == 0L) {
            blankSince = now
            blankJob?.cancel()
            blankJob = scope.launch {
                delay(TRACK_SWITCH_GRACE_MS)
                if (blankSince != 0L) {
                    blankSince = 0L
                    publish(null)
                }
            }
            return true
        }
        return now - blankSince < TRACK_SWITCH_GRACE_MS
    }

    private fun publish(info: MediaInfo?) {
        IslandBus.media.value = info
        if (info == null) {
            lastArt = null
        }
    }

    private fun loadArt(key: String, art: Bitmap?, print: Long?) {
        lastArt = print
        artJob?.cancel()
        if (art == null) return
        artJob = scope.launch {
            val (image, accent) = withContext(Dispatchers.Default) {
                val small = if (art.width > 300 || art.height > 300) art.scale(300, 300 * art.height / art.width.coerceAtLeast(1)) else art
                val palette = Palette.from(small).maximumColorCount(16).generate()
                val rgb = palette.getVibrantColor(palette.getLightVibrantColor(palette.getDominantColor(0xFFC6C3F7.toInt())))
                small.asImageBitmap() to Color(rgb)
            }
            val m = IslandBus.media.value
            if (m?.key == key) IslandBus.media.value = m.copy(art = image, accent = accent)
        }
    }

    private fun loadLyrics(m: MediaInfo) {
        lyricsJob?.cancel()
        IslandBus.lyrics.value = null
        if (!SettingsRepo.current.lyricsEnabled) {
            IslandBus.lyricsStatus.value = null
            return
        }
        IslandBus.lyricsStatus.value = m.key to LyricsStatus.LOADING
        lyricsJob = scope.launch {
            // Телефон в кармане — текст никто не увидит: ищем, когда включат экран, и только для текущего трека
            if (!IslandBus.screenOn.value) IslandBus.screenOn.first { it }
            val found = LyricsRepo.find(m.title, m.artist, m.album, m.durationMs)
            if (lyricsKey != m.key) return@launch
            if (found != null && found.lines.isNotEmpty()) {
                IslandBus.lyrics.value = Lyrics(m.key, found.lines, found.synced)
                IslandBus.lyricsStatus.value = m.key to LyricsStatus.FOUND
            } else {
                IslandBus.lyricsStatus.value = m.key to LyricsStatus.MISSING
            }
        }
    }

    private companion object {
        const val PAUSE_KEEP_MS = 45_000L
        const val TRACK_SWITCH_GRACE_MS = 3_000L
        const val MUSICFIND_PACKAGE = "com.musicfind.app"
        const val MUSICFIND_STATE_ACTION = "com.musicfind.app.STATE"
        const val MUSICFIND_EXTRA_FAVORITE = "com.musicfind.app.extra.FAVORITE"
        const val MUSICFIND_EXTRA_REPEAT = "com.musicfind.app.extra.REPEAT"
        const val MUSICFIND_FAVORITE = "com.musicfind.app.action.TOGGLE_FAVORITE"
        const val MUSICFIND_REPEAT = "com.musicfind.app.action.CYCLE_REPEAT"
        val DislikeWords = Regex("dislike|не нрав|thumbs?.?down|ban|block")
        val LikeWords = Regex("like|нрав|favou?rite|heart|thumbs?.?up|любим|избран")
        val UndoWords = Regex("unlike|un_like|undo|remove|убрать|удалить|отмен")
        val RepeatWords = Regex("repeat|повтор|зацикл|loop")
        val AllWords = Regex("all|всех|весь|playlist|список")
        val OneWords = Regex("one|одного|один|трек|track")
    }
}

/**
 * Отпечаток картинки: размер и 25 точек сеткой 5×5. Дёшево (не весь битмап) и отличает разные обложки одного размера.
 * Аппаратный битмап читать нельзя — тогда каждый раз «новая», как было до отпечатков.
 */
private fun artPrint(b: Bitmap): Long = runCatching {
    var h = b.width * 31L + b.height
    for (i in 1..5) for (j in 1..5) h = h * 31 + b.getPixel(b.width * i / 6, b.height * j / 6)
    h
}.getOrDefault(System.nanoTime())
