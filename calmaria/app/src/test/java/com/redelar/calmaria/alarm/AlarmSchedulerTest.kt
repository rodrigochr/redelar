package com.redelar.calmaria.alarm

import com.redelar.calmaria.data.AlarmConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmSchedulerTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    // 2026-10-23 is a Friday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone)

    @Test
    fun onceLaterToday() {
        assertEquals(at(23, 7), AlarmScheduler.nextTrigger(AlarmConfig(hour = 7), at(23, 6)))
    }

    @Test
    fun onceTomorrowWhenTimeHasPassed() {
        assertEquals(at(24, 7), AlarmScheduler.nextTrigger(AlarmConfig(hour = 7), at(23, 7)))
    }

    @Test
    fun weekdaysSkipTheWeekend() {
        val weekdays = 0b0011111
        val next = AlarmScheduler.nextTrigger(AlarmConfig(hour = 6, minute = 30, days = weekdays), at(23, 8))
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(at(26, 6, 30), next)
    }

    @Test
    fun weekendOnlyFromFridayNight() {
        val weekend = 0b1100000
        assertEquals(at(24, 9), AlarmScheduler.nextTrigger(AlarmConfig(hour = 9, days = weekend), at(23, 23)))
    }

    @Test
    fun sameWeekdayNextWeekWhenTodayAlreadyRang() {
        val fridays = 1 shl 4
        assertEquals(at(30, 7), AlarmScheduler.nextTrigger(AlarmConfig(hour = 7, days = fridays), at(23, 7, 1)))
    }
}
