package com.akira.island.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Build
import android.os.SystemClock
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.akira.island.core.CallInfo
import com.akira.island.core.ChronoInfo
import com.akira.island.core.HotspotInfo
import com.akira.island.core.IslandBus
import com.akira.island.core.LiveKind
import com.akira.island.core.ProgressInfo
import com.akira.island.data.SettingsRepo

/**
 * Доступ к уведомлениям даёт две вещи: медиасессии всех плееров
 * и уведомления с прогрессом или секундомером (загрузки, Часы, звонки).
 */
class NotifListener : NotificationListenerService() {
    private var media: MediaMonitor? = null

    override fun onListenerConnected() {
        SettingsRepo.init(this)
        media = MediaMonitor(applicationContext).also { it.start() }
        // Уже висящие уведомления разбираем без показа сообщений — они не новые
        runCatching { activeNotifications?.forEach { NotificationParser.onPosted(this, it, initial = true) } }
    }

    override fun onListenerDisconnected() {
        media?.stop()
        media = null
        IslandBus.progress.value = emptyMap()
        IslandBus.chrono.value = emptyMap()
        IslandBus.calls.value = emptyMap()
        IslandBus.live.value = emptyMap()
        IslandBus.ringingAlarms.value = emptySet()
        NotificationParser.clearRecordings()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationParser.onPosted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationParser.onRemoved(sbn.key)
    }
}

object NotificationParser {
    private val icons = HashMap<String, ImageBitmap?>()
    private val RecordingWords = Regex("record|recording|dictaphone|screen.?record|voice.?record|запис|диктофон")
    private val HotspotWords = Regex("hotspot|tether|access point|portable hotspot|точка доступа|раздач|модем")
    /** Уже опознанные записи: обновление уведомления не должно гасить статус. */
    private val recordingKeys = HashSet<String>()
    /** Ключ «пакет|канал» → до какого момента этот канал считается записью. */
    private val recordingChannels = HashMap<String, Long>()
    private val hotspotEntries = HashMap<String, HotspotInfo>()

    fun onPosted(context: Context, sbn: StatusBarNotification, initial: Boolean = false) {
        if (sbn.packageName == context.packageName) return
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val isMedia = n.category == Notification.CATEGORY_TRANSPORT || ex.containsKey(Notification.EXTRA_MEDIA_SESSION)
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: appLabel(context, sbn.packageName)

        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val value = ex.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val hasProgress = !isMedia && ((max > 0 && value < max) || (indeterminate && ongoing))
        IslandBus.progress.value = IslandBus.progress.value.toMutableMap().apply {
            if (hasProgress) {
                put(sbn.key, ProgressInfo(sbn.key, sbn.packageName, title, icon(context, sbn.packageName), value, max, indeterminate, sbn.postTime))
            } else {
                remove(sbn.key)
            }
        }

        val call = if (n.category == Notification.CATEGORY_CALL && !isMedia) parseCall(context, sbn, title) else null
        // Какой канал присылает всплывающую плашку звонка или будильника — для экрана «Системные плашки»
        SystemBanners.onPosted(sbn, incomingCall = call?.incoming == true)
        // Звонящий будильник или таймер «Часов» — у него полноэкранный сигнал; остров включит экран сам,
        // даже если всплывающие плашки «Часов» выключены
        val ringing = n.category == Notification.CATEGORY_ALARM && n.fullScreenIntent != null
        if (ringing != (sbn.key in IslandBus.ringingAlarms.value)) {
            IslandBus.ringingAlarms.value = if (ringing) IslandBus.ringingAlarms.value + sbn.key else IslandBus.ringingAlarms.value - sbn.key
        }
        IslandBus.calls.value = IslandBus.calls.value.toMutableMap().apply {
            if (call != null) put(sbn.key, call) else remove(sbn.key)
        }

        // Точка доступа: постоянное уведомление с числом подключённых устройств
        val hotspot = hotspotInfo(sbn, n, ex, title, ongoing)
        if (hotspot != null) {
            hotspotEntries[sbn.key] = hotspot
            IslandBus.hotspot.value = hotspotEntries.values.maxByOrNull { it.clients }
        } else if (hotspotEntries.remove(sbn.key) != null) {
            IslandBus.hotspot.value = hotspotEntries.values.maxByOrNull { it.clients }
        }

        val ticking = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0
        val previousChrono = IslandBus.chrono.value[sbn.key]
        // Некоторые прошивки (в том числе OriginOS) не дают узнать «Часы» по пакету или кладут
        // название только в канал уведомления. Тогда распознаём по каналу/заголовку и прошлой карточке.
        val channelLower = n.channelId.orEmpty().lowercase()
        val titleLower = title.lowercase()
        val clockHint = listOf("timer", "stopwatch", "таймер", "секундомер").any {
            it in channelLower || it in titleLower
        } || previousChrono?.let { !it.recording && (it.pause != null || it.resume != null) } == true
        // Кнопки «Часов» (пауза, продолжить, сброс) — их нажимают кнопки острова
        val clock = ClockApps.isClock(context, sbn.packageName) || clockHint
        val buttons = if (clock) ClockWords.buttons(n.actions) else emptyMap()
        // Запись экрана или голоса без chronometer-extra: считаем время от начала уведомления.
        // Статус «липкий»: если запись уже опознали, её обновления не должны её выключать.
        val nowElapsed = SystemClock.elapsedRealtime()
        val channelKey = "${sbn.packageName}|${n.channelId.orEmpty()}"
        val knownRecording = sbn.key in recordingKeys
        val recentChannel = (recordingChannels[channelKey] ?: 0L) > nowElapsed
        val recText = listOfNotNull(
            title,
            n.tickerText?.toString(),
            ex.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        ).joinToString(" ").lowercase()
        val recPausedText = listOf("pause", "пауза", "приостанов").any { it in recText }
        val recTime = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT)
            .firstNotNullOfOrNull { ClockScreenParser.find(ex.getCharSequence(it)) }
        val recFinishedText = listOf(
            "saved", "saving", "ready", "сохран", "записан", "готов", "finished", "stopped", "останов", "заверш", "обработ",
        ).any { it in recText }
        val recordingCandidate = knownRecording || isRecordingNotification(sbn, n, ex, title) ||
            (recentChannel && ongoing)
        val recording = recordingCandidate && !recFinishedText && (ongoing || recPausedText)
        // Некоторые прошивки (OriginOS и др.) не дают время в extras: Chronometer живёт в RemoteViews.
        val uiChrono = if (recording || clock) notificationChronometer(context, n) else null
        if (recording) {
            recordingKeys += sbn.key
            recordingChannels[channelKey] = nowElapsed + 15_000L
        } else {
            recordingKeys -= sbn.key
        }
        val recordingBase = n.`when`.takeIf { it in 1..System.currentTimeMillis() } ?: sbn.postTime
        // Google Часы и AOSP кладут время таймера в свой RemoteViews, а не в стандартный chronometer-extra.
        // У звонящего будильника/таймера время уже не считается — его не трогаем.
        val remoteAny = if ((clock && !ringing) || recording) ClockRemote.parse(n) else null
        val remote = if (clock && !ringing) remoteAny else null
        val remotePausedMs = remote?.pausedMs(System.currentTimeMillis())
        // Если время есть только текстом (некоторые часы так делают), берём его при явных кнопках таймера
        val textTime = if (clock && remote == null && !ticking && call == null && !isMedia && !ringing &&
            ClockAction.PAUSE in buttons && ClockAction.START !in buttons
        ) {
            listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT)
                .firstNotNullOfOrNull { ClockScreenParser.find(ex.getCharSequence(it)) }?.takeIf { it > 0 }
        } else {
            null
        }
        val textBase = textTime?.let { time ->
            val now = System.currentTimeMillis()
            if (looksLikeTimer(n, title)) now + time else now - time
        }
        // Таймер или секундомер «Часов» на паузе: счёт стоит, время только в тексте, а в кнопках — «Продолжить»
        // Google Часы добавляют «(Пауза)» / «(Paused)» к названию, а время могут оставить только в тексте.
        val titlePaused = recPausedText
        val pausedFromText = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT)
            .firstNotNullOfOrNull { ClockScreenParser.find(ex.getCharSequence(it)) }
        val pausedMs = when {
            remote != null -> remotePausedMs ?: if (clock && titlePaused) {
                previousChrono?.pausedMs ?: previousChrono?.elapsed(System.currentTimeMillis()) ?: pausedFromText
            } else {
                null
            }
            clock && !ticking && call == null && !isMedia &&
                ClockAction.START in buttons && ClockAction.PAUSE !in buttons ->
                previousChrono?.pausedMs
                    ?: previousChrono?.elapsed(System.currentTimeMillis())
                    ?: pausedFromText
                    ?: if (!looksLikeTimer(n, title)) (System.currentTimeMillis() - n.`when`).coerceAtLeast(0) else null
            clock && titlePaused ->
                previousChrono?.pausedMs ?: previousChrono?.elapsed(System.currentTimeMillis()) ?: pausedFromText
            clock && uiChrono?.first == true ->
                uiChrono.second
                    ?: previousChrono?.pausedMs
                    ?: previousChrono?.elapsed(System.currentTimeMillis())
                    ?: pausedFromText
            else -> null
        }
        val chrono = call == null && !isMedia &&
            (ticking || remote != null || textTime != null || pausedMs != null || recording)
        if (clock && chrono) ClockControl.reader?.onNotificationChrono(sbn.packageName)
        IslandBus.chrono.value = IslandBus.chrono.value.toMutableMap().apply {
            if (chrono) {
                val recPrev = previousChrono?.takeIf { it.recording }
                val recPause = if (recording) actionByWords(n, listOf("пауза", "pause")) else null
                val recResume = if (recording) {
                    actionByWords(n, listOf("продолж", "возобнов", "resume"))
                } else {
                    null
                }
                val recPaused = recording && when {
                    recPausedText || (recPrev != null && recResume != null && recPause == null) -> true
                    // Индикатор Chronometer в RemoteViews — точнее, чем «пропал ли extra»:
                    // он снова true после продолжения записи.
                    uiChrono != null -> uiChrono.first
                    else -> (recPrev?.chronometer == true && !ticking) ||
                        (recPrev?.pausedMs != null && !ticking && recResume != null && recPause == null) ||
                        (remoteAny != null && !remoteAny.running && recPause == null)
                }
                val nowWall = System.currentTimeMillis()
                val base = when {
                    // При паузе диктофон иногда обновляет notification.when; не начинаем отсчёт с нуля.
                    // На возобновлении сдвигаем базу на застывшее время, чтобы пауза не попала в отсчёт.
                    recording && recPaused -> recPrev?.base ?: recordingBase
                    recording && recPrev?.pausedMs != null && !recPaused ->
                        nowWall - (recPrev.pausedMs ?: 0L)
                    recording -> recPrev?.base ?: recordingBase
                    remote != null -> remote.base
                    textTime != null -> textBase ?: n.`when`
                    else -> n.`when`
                }
                val recPausedMs = if (recording && recPaused) {
                    uiChrono?.second
                        ?: recPrev?.pausedMs
                        ?: remoteAny?.pausedMs(nowWall)
                        ?: recTime
                        ?: (nowWall - base).coerceAtLeast(0L)
                } else {
                    null
                }
                val down = when {
                    recording -> false
                    remote != null -> remote.countDown
                    textTime != null -> looksLikeTimer(n, title)
                    pausedMs != null -> looksLikeTimer(n, title)
                    else -> ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
                }
                val stop = stopAction(n, fallbackLast = recording)
                put(sbn.key, ChronoInfo(sbn.key, sbn.packageName, title, icon(context, sbn.packageName), base, down, sbn.postTime, stop,
                    pausedMs = pausedMs ?: recPausedMs, pause = if (recording) recPause else buttons[ClockAction.PAUSE],
                    resume = if (recording) recResume else buttons[ClockAction.START],
                    reset = buttons[ClockAction.RESET], recording = recording, chronometer = ticking))
            } else {
                remove(sbn.key)
            }
        }

        val live = if (call == null && !isMedia) LiveParser.parse(context, sbn, title) else null
        val liveWasKnown = sbn.key in IslandBus.live.value
        IslandBus.live.value = IslandBus.live.value.toMutableMap().apply {
            if (live != null) put(sbn.key, live) else remove(sbn.key)
        }
        // Новая «живая активность» сначала показывается широкой плашкой, потом сворачивается в капсулу
        if (live != null && !liveWasKnown && !initial && SettingsRepo.current.cardLive) {
            LiveParser.postBannerLater(context, sbn.key)
        }
        // Навигатор и такси показываются своей карточкой, а не полоской прогресса или таймером
        if (live != null) {
            IslandBus.progress.value = IslandBus.progress.value - sbn.key
            IslandBus.chrono.value = IslandBus.chrono.value - sbn.key
        }

        val service = context as? NotificationListenerService
        if (!initial && service != null && !isMedia && call == null && !chrono && !hasProgress && live == null) {
            // Уведомление об оплате из банка — «Оплачено» с галочкой, а не карточка сообщения
            if (PayMonitor.onNotification(context, sbn)) return
            Messages.maybeShow(service, sbn, appLabel(context, sbn.packageName))
        }
    }

    /**
     * Chronometer из RemoteViews: многие прошивки не кладут время/паузу в extras.
     * Возвращает «стоит ли на паузе» и показанное время.
     */
    private fun notificationChronometer(context: Context, n: Notification): Pair<Boolean, Long?>? {
        val remote = listOfNotNull(n.contentView, n.bigContentView, n.headsUpContentView).firstOrNull() ?: return null
        val root = runCatching { remote.apply(context, FrameLayout(context)) }.getOrNull() ?: return null
        val chrono = findChronometer(root) ?: return null
        val shown = ClockScreenParser.find(chrono.text)
        return !chronometerStarted(chrono) to shown
    }

    /** isStarted() у Chronometer скрыт в SDK — читаем аккуратно, иначе считаем, что идёт. */
    private fun chronometerStarted(chrono: Chronometer): Boolean {
        runCatching {
            return chrono.javaClass.getMethod("isStarted").invoke(chrono) as Boolean
        }
        runCatching {
            val field = chrono.javaClass.getDeclaredField("mStarted")
            field.isAccessible = true
            return field.getBoolean(chrono)
        }
        return true
    }

    private fun findChronometer(view: View): Chronometer? {
        if (view is Chronometer) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findChronometer(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** Действие уведомления по словам в подписи — для паузы и продолжения записи. */
    private fun actionByWords(n: Notification, words: List<String>): PendingIntent? =
        n.actions.orEmpty().firstOrNull { a ->
            a.actionIntent != null && words.any { it in a.title?.toString()?.lowercase().orEmpty() }
        }?.actionIntent

    /** Кнопка «Стоп» в записи/таймере: по подписи, а для записи — крайняя кнопка, если подписи нет. */
    private fun stopAction(n: Notification, fallbackLast: Boolean): PendingIntent? {
        val actions = n.actions.orEmpty().filter { it.actionIntent != null }
        val words = listOf("стоп", "останов", "заверш", "готов", "stop", "finish", "end", "done", "отмен", "cancel", "удал", "delete")
        val found = actions.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            words.any { it in t }
        }?.actionIntent
        if (found != null) return found
        // Без подписи «Стоп» не жмём последнюю кнопку наугад: у записи это может быть «Поделиться»
        // или «Открыть приложение». Пусть служба попробует найти кнопку в шторке.
        return if (fallbackLast && actions.size == 1 && actions.first().title.isNullOrBlank()) {
            actions.first().actionIntent
        } else {
            null
        }
    }

    private fun parseCall(context: Context, sbn: StatusBarNotification, title: String): CallInfo? {
        val n = sbn.notification
        val ex = n.extras
        val ongoing = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0
        // CallStyle (Android 12+) кладёт кнопки в extras, у остальных звонилок ищем по подписи на языке телефона
        var answer = pending(ex, "android.answerIntent")
        var decline = pending(ex, "android.declineIntent")
        var hangUp = pending(ex, "android.hangUpIntent")
        n.actions?.forEach { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            when {
                answer == null && AnswerWords.any { it in t } -> answer = a.actionIntent
                decline == null && DeclineWords.any { it in t } -> decline = a.actionIntent
                hangUp == null && HangUpWords.any { it in t } -> hangUp = a.actionIntent
            }
        }
        val incoming = !ongoing && answer != null
        if (!incoming && !ongoing) return null

        val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            runCatching { ex.getParcelable<Person>(Notification.EXTRA_CALL_PERSON) }.getOrNull()
        } else {
            null
        }
        val name = person?.name?.toString()?.takeIf { it.isNotBlank() } ?: title
        val avatar = runCatching {
            val icon = person?.icon ?: n.getLargeIcon()
            icon?.loadDrawable(context)?.toBitmap(128, 128)?.asImageBitmap()
        }.getOrNull()
        return CallInfo(
            key = sbn.key,
            pkg = sbn.packageName,
            name = name,
            avatar = avatar,
            incoming = incoming,
            base = n.`when`,
            postTime = sbn.postTime,
            answer = answer,
            decline = decline ?: hangUp,
            hangUp = hangUp ?: decline,
            open = n.contentIntent,
        )
    }

    /** Запись экрана или голоса: постоянное уведомление с «record/запись/диктофон» в пакете, канале или тексте. */
    private fun isRecordingNotification(sbn: StatusBarNotification, n: Notification, ex: android.os.Bundle, title: String): Boolean {
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_TRANSPORT) return false
        if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return false
        val pkg = sbn.packageName.lowercase()
        val channel = n.channelId.orEmpty().lowercase()
        val bits = listOfNotNull(
            title,
            n.tickerText?.toString(),
            ex.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        ).joinToString(" ").lowercase()
        val pkgHint = pkg.contains("record") || pkg.contains("dictaphone") ||
            pkg.contains("voicenote") || (pkg.contains("voice") && (pkg.contains("rec") || pkg.contains("memo")))
        val channelHint = channel.contains("record") || channel.contains("запис") ||
            channel.contains("dictaphone") || channel.contains("voicerec")
        val screenWords = (bits.contains("screen") || bits.contains("экран")) &&
            (bits.contains("record") || bits.contains("запис"))
        val systemScreen = pkg == "com.android.systemui" && (channelHint || screenWords)
        val words = "$title $channel $bits"
        val known = pkgHint || channelHint || systemScreen || screenWords ||
            (RecordingWords.containsMatchIn(words) && (pkg.contains("screen") || pkg.contains("voice")))
        return known
    }

    /** Точка доступа: постоянное уведомление с «hotspot/точка доступа» и счётчиком устройств. */
    private fun hotspotInfo(sbn: StatusBarNotification, n: Notification, ex: android.os.Bundle, title: String, ongoing: Boolean): HotspotInfo? {
        if (!ongoing) return null
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_TRANSPORT) return null
        if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        val pkg = sbn.packageName.lowercase()
        val channel = n.channelId.orEmpty().lowercase()
        val bits = listOfNotNull(
            title,
            n.tickerText?.toString(),
            ex.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        ).joinToString(" ").lowercase()
        if (!HotspotWords.containsMatchIn("$pkg $channel $bits")) return null
        val stop = n.actions?.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            listOf("отключ", "выключ", "останов", "заверш", "stop", "turn off", "disable", "end").any { it in t }
        }?.actionIntent
        return HotspotInfo(hotspotCount("$title $bits"), stop)
    }

    private fun hotspotCount(text: String): Int {
        val t = text.lowercase()
        if ("нет устройств" in t || "no device" in t) return 0
        val after = Regex("""(\d+)\s*(?:устройств|device|client|подключ|connected)""").find(t)
        val before = Regex("""(?:подключ|connected|clients?)[^\d]{0,12}(\d+)""").find(t)
        return (after ?: before)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** Таймер на паузе или секундомер — по заголовку и каналу; по умолчанию таймер. */
    private fun looksLikeTimer(n: Notification, title: String): Boolean {
        val t = (title + " " + n.channelId.orEmpty()).lowercase()
        return when {
            "секундомер" in t || "stopwatch" in t -> false
            else -> true
        }
    }

    @Suppress("DEPRECATION")
    private fun pending(ex: android.os.Bundle, key: String): PendingIntent? =
        runCatching { ex.getParcelable<PendingIntent>(key) }.getOrNull()

    fun clearRecordings() {
        recordingKeys.clear()
        recordingChannels.clear()
        hotspotEntries.clear()
        IslandBus.hotspot.value = null
    }

    fun onRemoved(key: String) {
        Messages.forget(key)
        LiveParser.forget(key)
        ClockControl.forget(key)
        LiveDebug.forget(key)
        recordingKeys.remove(key)
        if (hotspotEntries.remove(key) != null) {
            IslandBus.hotspot.value = hotspotEntries.values.maxByOrNull { it.clients }
        }
        if (key in IslandBus.calls.value) IslandBus.calls.value = IslandBus.calls.value - key
        if (key in IslandBus.progress.value) IslandBus.progress.value = IslandBus.progress.value - key
        IslandBus.chrono.value[key]?.let { c ->
            // Пауза таймера/секундомера «Часов» может пересоздать уведомление: не убираем карточку,
            // чтобы остров не закрылся и можно было нажать «Продолжить».
            if (c.pausedMs == null || c.recording) {
                IslandBus.chrono.value = IslandBus.chrono.value - key
            }
        }
        if (key in IslandBus.live.value) IslandBus.live.value = IslandBus.live.value - key
        if (key in IslandBus.ringingAlarms.value) IslandBus.ringingAlarms.value = IslandBus.ringingAlarms.value - key
    }

    fun icon(context: Context, pkg: String): ImageBitmap? = icons.getOrPut(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }

    fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

/** Приложения, чьи постоянные уведомления остров показывает как «живые активности». */
object LiveApps {
    private val nav = setOf(
        "com.google.android.apps.maps",
        "ru.yandex.yandexmaps",
        "ru.yandex.yandexnavi",
        "ru.dublgis.dgismobile",
        "com.waze",
        "net.osmand",
        "net.osmand.plus",
        "com.here.app.maps",
        "cz.seznam.mapy",
        "app.organicmaps",
        "com.mapswithme.maps.pro",
    )
    private val ride = setOf(
        "ru.yandex.taxi",
        "com.yandex.go",
        "com.ubercab",
        "ee.mtakso.client",
        "ru.citymobil",
        "sinet.startup.indriver",
        "com.lyft.android",
    )
    private val delivery = setOf(
        "ru.yandex.eda",
        "ru.foodfox.client",
        "ru.samokat.app",
        "ru.instamart",
        "ru.sbermarket",
        "com.deliveryclub",
        "com.ubercab.eats",
        "ru.ozon.app.android",
        "com.wildberries.ru",
        "ru.beru.android",
    )

    fun kind(pkg: String, category: String?): LiveKind? = when {
        pkg in nav || category == Notification.CATEGORY_NAVIGATION -> LiveKind.NAV
        pkg in ride -> LiveKind.RIDE
        pkg in delivery -> LiveKind.DELIVERY
        else -> null
    }
}

/**
 * Подписи кнопок звонка на разных языках: остров смотрят во Вьетнаме, Индонезии, Таиланде, Бразилии,
 * Латинской Америке и не только — без них ответить и сбросить из острова там не получалось.
 */
private val AnswerWords = listOf(
    "ответ", "принять", "answer", "accept", "trả lời", "nghe", "jawab", "terima", "angkat", "contestar", "responder",
    "atender", "aceptar", "aceitar", "รับสาย", "cevapla", "yanıtla", "kabul", "annehmen", "répondre", "rispondi", "accetta",
    "odbierz", "відповісти", "прийняти", "sagutin", "接听", "接聽", "応答", "응답", "받기",
)
private val DeclineWords = listOf(
    "отклон", "сброс", "decline", "reject", "dismiss", "từ chối", "tolak", "rechazar", "recusar", "rejeitar", "ปฏิเสธ",
    "reddet", "ablehnen", "refuser", "rifiuta", "odrzuć", "відхилити", "tanggihan", "拒接", "拒絕", "拒否", "거절",
)
private val HangUpWords = listOf(
    "заверш", "положить", "hang", "end call", "kết thúc", "gác máy", "akhiri", "colgar", "finalizar", "desligar",
    "encerrar", "วางสาย", "kapat", "auflegen", "beenden", "raccrocher", "riaggancia", "zakończ", "завершити", "ibaba",
    "挂断", "掛斷", "終了", "종료",
)
