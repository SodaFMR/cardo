package io.github.sodafmr.cardo.brain.schema

import io.github.sodafmr.cardo.brain.Lang

/** What the user sees and hears. */
data class Response(val display: String, val speech: String, val lang: Lang, val card: Card? = null, val buttons: List<UiAction> = emptyList())

sealed interface Card {
    data class Timer(val duration: java.time.Duration, val label: String?) : Card

    data class Alarm(val time: java.time.LocalTime, val date: java.time.LocalDate? = null, val repeat: Recurrence, val label: String?) : Card

    data class Contact(val name: String, val digits: String?, val label: NumberLabel?) : Card

    data class Message(val recipient: String, val body: String, val app: MessagingApp) : Card

    data class Messages(val items: List<IncomingMessage>) : Card

    data class Events(val range: DateRange, val items: List<EventItem>) : Card

    data class Reminders(val items: List<ReminderItem>) : Card

    data class Calls(val entries: List<CallEntry>) : Card

    data class Reminder(val task: String, val at: java.time.LocalDateTime) : Card

    data class Note(val text: String) : Card

    data class Calc(val expression: String, val result: String) : Card

    data class Info(val title: String, val value: String) : Card

    data class Handoff(val appLabel: String, val query: String?) : Card

    data class Choices(val options: List<Choice>) : Card
}

sealed interface UiAction {
    data object Confirm : UiAction

    data object CancelAction : UiAction

    data class Pick(val choice: Choice) : UiAction

    data class Grant(val permission: CardoPermission) : UiAction

    data object Unlock : UiAction

    data object Type : UiAction
}
