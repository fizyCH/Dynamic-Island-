package com.akira.island.service

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.akira.island.R
import com.akira.island.core.AlarmInfo
import com.akira.island.core.IslandBus
import com.akira.island.core.Notice
import com.akira.island.core.NoticeStyle
import com.akira.island.core.Tints
import com.akira.island.core.tr
import com.akira.island.data.SettingsRepo
import com.akira.island.island.Ios
import java.util.Calendar

/**
 * Ближайший будильник. Его знает сама система (AlarmManager.getNextAlarmClock) — уведомления «Часов»
 * для этого не нужны. Поставили новый будильник — остров показывает плашку со временем подъёма;
 * следующий по расписанию (прошлый прозвенел или отложен) — молча.
 * Будильником системы пользуются и календари, напоминалки, трекеры сна — их «будильник» человек не ставил.
 * Если автор известен, берём только приложения-будильники («Часы», Alarmy и т. п.); если Android скрывает
 * автора, оставляем обычное время, но отсекаем служебную полночь.
 */
class AlarmMonitor(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = refresh(announce = true)
    }

    fun start() {
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        refresh(announce = false)
        // Некоторые прошивки не присылают ACTION_NEXT_ALARM_CLOCK_CHANGED — подстраховываемся раз в минуту
        scope.launch {
            while (isActive) {
                delay(60_000)
                refresh(announce = false)
            }
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        scope.cancel()
        IslandBus.nextAlarm.value = null
    }

    private fun refresh(announce: Boolean) {
        val now = System.currentTimeMillis()
        val info = runCatching { alarms?.nextAlarmClock }.getOrNull()
        val creator = info?.showIntent?.creatorPackage
        // Android 14+ иногда скрывает автора будильника, пока до звонка не осталось пары минут.
        // Неизвестный автор — ещё не повод прятать настоящий будильник: на Vivo/Android 14 иначе
        // пропадают все будильники. Служебные AlarmClockInfo без автора обычно стоят ровно на 00:00,
        // их и отсекаем; известные приложения-будильники принимаем всегда.
        val known = creator != null && ClockApps.isAlarmApp(context, creator)
        val allowed = info != null &&
            (known || (creator == null && !isMidnight(info.triggerTime)))
        val next = info?.takeIf { allowed && it.triggerTime > now }
            ?.let { AlarmInfo(it.triggerTime, creator, it.showIntent) }
        val prev = IslandBus.nextAlarm.value
        IslandBus.nextAlarm.value = next
        if (!announce || next == null || prev?.triggerAt == next.triggerAt) return
        // Прошлый будильник уже прозвенел или его отложили — это не новый, а очередной по расписанию
        if (prev != null && prev.triggerAt <= now + 60_000) return
        val s = SettingsRepo.current
        if (!s.enabled || !s.evAlarmSet) return
        IslandBus.post(
            Notice(
                title = tr("Будильник", "Alarm"),
                icon = Ios.load(context, R.drawable.ios_alarm),
                tint = Tints.Orange,
                style = NoticeStyle.ALARM,
                alarm = next,
                extraMs = 2_000,
            ),
        )
    }

    /** Служебные «будильники» календарей и прошивки часто приходят без автора ровно на полночь. */
    private fun isMidnight(triggerAt: Long): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = triggerAt }
        return c.get(Calendar.HOUR_OF_DAY) == 0 && c.get(Calendar.MINUTE) == 0
    }
}
