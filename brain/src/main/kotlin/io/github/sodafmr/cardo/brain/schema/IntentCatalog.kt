package io.github.sodafmr.cardo.brain.schema

object IntentCatalog {
    /** The 50 intent ids, in the order of the [Command] subclasses. */
    val ids: List<String> =
        listOf(
        "call.contact",
        "call.number",
        "call.last",
        "call.history",
        "message.send",
        "message.read",
        "message.reply",
        "email.compose",
        "alarm.set",
        "alarm.show",
        "alarm.next",
        "timer.set",
        "timer.show",
        "stopwatch.start",
        "stopwatch.stop",
        "reminder.create",
        "reminder.list",
        "reminder.cancel",
        "note.create",
        "note.show",
        "calendar.create",
        "calendar.query",
        "app.open",
        "camera.open",
        "device.flashlight",
        "device.volume",
        "device.ringer",
        "device.dnd",
        "device.brightness",
        "device.panel",
        "device.battery",
        "media.control",
        "media.play",
        "info.time",
        "info.date",
        "calc.evaluate",
        "convert.units",
        "web.search",
        "weather.query",
        "maps.search",
        "maps.directions",
        "cardo.help",
        "cardo.cancel",
        "cardo.yes",
        "cardo.no",
        "cardo.repeat",
        "chitchat.greeting",
        "chitchat.thanks",
        "chitchat.identity",
        "out_of_scope",
        )

    /** The 31 slot types, in spec order. */
    val slotTypes: List<String> =
        listOf(
        "contact",
        "phone_number",
        "number_label",
        "message_body",
        "messaging_app",
        "email_subject",
        "datetime",
        "duration",
        "recurrence",
        "label",
        "task",
        "note_text",
        "event_title",
        "location",
        "app_name",
        "camera_mode",
        "on_off",
        "level",
        "volume_stream",
        "ringer_mode",
        "setting",
        "media_action",
        "media_query",
        "place",
        "expression",
        "quantity",
        "unit_from",
        "unit_to",
        "search_query",
        "destination",
        "travel_mode",
        )

    private val required: Map<String, Set<String>> =
        mapOf(
            "call.contact" to setOf("contact"),
            "call.number" to setOf("phone_number"),
            // a phone_number slot satisfies "contact"
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

    /** Slot types an intent cannot run without; empty for intents that need none. */
    fun requiredSlots(intent: String): Set<String> {
        require(intent in ids) { "unknown intent: $intent" }
        return required[intent] ?: emptySet()
    }
}
