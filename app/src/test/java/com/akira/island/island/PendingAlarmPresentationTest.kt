package com.akira.island.island

import com.akira.island.core.AlarmInfo
import org.junit.Assert.*
import org.junit.Test

class PendingAlarmPresentationTest {
    private val alarm = Act.AlarmA(AlarmInfo(123456L, null, null))
    private val other = Act.InboxA(emptyList())

    @Test fun alarmAloneUsesTheMainCapsuleLikeTheOldFork() {
        assertEquals(alarm, primaryWithPendingAlarm(listOf(alarm), false, null))
        assertEquals(null, secondaryWithPendingAlarm(listOf(alarm), alarm))
    }
    @Test fun explicitAlarmSelectionStillOpensItsCard() {
        assertEquals(alarm, primaryWithPendingAlarm(listOf(alarm), true, "alarm"))
        assertEquals(alarm, primaryWithPendingAlarm(listOf(alarm), false, "alarm"))
    }
    @Test fun otherActivitiesKeepTheirPriorityAndAreNotDuplicated() {
        assertEquals(other, primaryWithPendingAlarm(listOf(alarm, other), false, null))
        assertEquals(alarm, secondaryWithPendingAlarm(listOf(alarm, other), other))
        assertNull(secondaryWithPendingAlarm(listOf(other), other))
    }
    @Test fun noAlarmPreservesTheExistingFallback() {
        assertEquals(Act.Idle, primaryWithPendingAlarm(emptyList(), false, null))
        assertEquals(other, primaryWithPendingAlarm(listOf(other), false, null))
        assertEquals(other, secondaryWithPendingAlarm(listOf(alarm, other), alarm))
    }
}
