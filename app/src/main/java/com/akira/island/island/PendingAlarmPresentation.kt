package com.akira.island.island

/**
 * A scheduled alarm occupies the main card when there is no other activity (the old fork behavior),
 * or after an explicit selection. With other activities it stays available in the secondary bubble.
 */
internal fun primaryWithPendingAlarm(acts: List<Act>, expanded: Boolean, focus: String?): Act =
    acts.firstOrNull { it !is Act.AlarmA || expanded && focus == it.id }
        ?: acts.firstOrNull { it is Act.AlarmA }
        ?: Act.Idle

internal fun secondaryWithPendingAlarm(acts: List<Act>, primary: Act): Act? =
    acts.firstOrNull { !it.transient && it.id != primary.id }
