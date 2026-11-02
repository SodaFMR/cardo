package io.github.sodafmr.cardo.brain.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SchemaShapesTest {
    @Test
    fun `Choice carries an optional number label`() {
        assertNull(Choice("42", "Ana", null).numberLabel)
        assertEquals(NumberLabel.MOBILE, Choice("42", "Ana", null, NumberLabel.MOBILE).numberLabel)
    }

    @Test
    fun `Failed carries an optional suggestion after detail`() {
        val failed = ActionResult.Failed(FailReason.NOT_FOUND_CONTACT, "ana", "Anna")
        assertEquals("ana", failed.detail)
        assertEquals("Anna", failed.suggestion)
        assertNull(ActionResult.Failed(FailReason.ERROR).suggestion)
    }

    @Test
    fun `ReplyMessage target defaults to latest replyable`() {
        assertNull(ReplyMessage("ok").target)
        assertEquals("k1", ReplyMessage("ok", "k1").target)
    }

    @Test
    fun `DeviceChanged is typed per setting`() {
        assertEquals(
            DeviceSetting.FLASHLIGHT,
            Outcome.DeviceChanged(DeviceSetting.FLASHLIGHT, on = true).setting,
        )
        assertEquals(30, Outcome.DeviceChanged(DeviceSetting.VOLUME, percent = 30).percent)
        assertEquals(RingerMode.VIBRATE, Outcome.DeviceChanged(DeviceSetting.RINGER, ringer = RingerMode.VIBRATE).ringer)
        assertEquals(
            setOf("FLASHLIGHT", "VOLUME", "RINGER", "DND", "BRIGHTNESS"),
            DeviceSetting.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `list cards and dated alarm card exist`() {
        val at = LocalDateTime.of(2026, 11, 2, 7, 0)
        val reminders = Card.Reminders(listOf(ReminderItem(1, "pan", at, Recurrence(emptySet()))))
        val calls = Card.Calls(listOf(CallEntry("Ana", "600", at, missed = true)))
        assertEquals(1, reminders.items.size)
        assertEquals(1, calls.entries.size)
        val alarm = Card.Alarm(LocalTime.of(7, 0), LocalDate.of(2026, 11, 3), Recurrence(emptySet()), null)
        assertEquals(LocalDate.of(2026, 11, 3), alarm.date)
        assertNull(Card.Alarm(LocalTime.of(7, 0), repeat = Recurrence(emptySet()), label = null).date)
    }
}
