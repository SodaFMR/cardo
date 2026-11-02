package io.github.sodafmr.cardo.brain.schema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows

class IntentCatalogTest {
    /** One hand-written instance per [Command] subclass, so the test reads each `intent` property. */
    private val samples: List<Command> = listOf(
        CallContact(null, null, false),
        CallNumber(null, false),
        CallLast(CallLastKind.REDIAL),
        CallHistory(false),
        SendMessage(null, null, MessagingApp.ANY),
        ReadMessages(null, MessagingApp.ANY),
        ReplyMessage(null, null),
        ComposeEmail(null, null, null),
        SetAlarm(null, null, Recurrence(emptySet()), null),
        ShowAlarms,
        NextAlarm,
        SetTimer(null, null),
        ShowTimers,
        StartStopwatch,
        StopStopwatch,
        CreateReminder(null, null, Recurrence(emptySet())),
        ListReminders(null),
        CancelReminder(null),
        CreateNote(null),
        ShowNotes,
        CreateEvent(null, null, null, false, null),
        QueryCalendar(null, false),
        OpenApp(null),
        OpenCamera(CameraMode.PHOTO),
        Flashlight(Toggle.ON),
        Volume(VolumeStream.MEDIA, VolumeAction.UP, null),
        Ringer(RingerMode.NORMAL),
        DoNotDisturb(Toggle.ON, null),
        Brightness(LevelAction.UP, null),
        OpenPanel(SettingPanel.WIFI, null),
        BatteryLevel,
        ControlMedia(MediaAction.PLAY),
        PlayMedia(null, null),
        TellTime(null),
        TellDate(null),
        Calculate(null),
        ConvertUnits(null, null, null),
        WebSearch(null),
        Weather(null, null),
        MapsSearch(null),
        Directions(null, TravelMode.DRIVING),
        Help,
        Cancel,
        Yes,
        No,
        Repeat,
        Greeting,
        Thanks,
        Identity,
        OutOfScope(""),
    )

    @Test
    fun `ids has 50 unique entries`() {
        assertEquals(50, IntentCatalog.ids.size)
        assertEquals(50, IntentCatalog.ids.toSet().size)
    }

    @Test
    fun `every id is the intent of exactly one Command subclass`() {
        assertEquals(IntentCatalog.ids, samples.map { it.intent })
    }

    @Test
    fun `samples cover every sealed subclass of Command`() {
        assertEquals(Command::class.sealedSubclasses.toSet(), samples.map { it::class }.toSet())
        assertEquals(samples.size, samples.map { it::class }.toSet().size)
    }

    @Test
    fun `slotTypes has 31 unique entries`() {
        assertEquals(31, IntentCatalog.slotTypes.size)
        assertEquals(31, IntentCatalog.slotTypes.toSet().size)
    }

    @Test
    fun `requiredSlots is defined for every id and names only known slot types`() {
        for (id in IntentCatalog.ids) {
            val required = IntentCatalog.requiredSlots(id)
            assertTrue(IntentCatalog.slotTypes.containsAll(required), "$id requires unknown slot in $required")
        }
    }

    @Test
    fun `requiredSlots values match the spec`() {
        val expected = mapOf(
            "call.contact" to setOf("contact"),
            "call.number" to setOf("phone_number"),
            "message.send" to setOf("contact", "message_body"),
            "message.reply" to setOf("message_body"),
            "alarm.set" to setOf("datetime"),
            "timer.set" to setOf("duration"),
            "reminder.create" to setOf("task", "datetime"),
            "note.create" to setOf("note_text"),
            "calendar.create" to setOf("event_title", "datetime"),
            "app.open" to setOf("app_name"),
            "calc.evaluate" to setOf("expression"),
            "convert.units" to setOf("quantity", "unit_from", "unit_to"),
            "web.search" to setOf("search_query"),
            "maps.search" to setOf("search_query"),
            "maps.directions" to setOf("destination"),
        )
        for (id in IntentCatalog.ids) {
            assertEquals(expected[id] ?: emptySet<String>(), IntentCatalog.requiredSlots(id), id)
        }
    }

    @Test
    fun `requiredSlots rejects unknown intents`() {
        assertThrows(IllegalArgumentException::class.java) { IntentCatalog.requiredSlots("no.such.intent") }
    }
}
