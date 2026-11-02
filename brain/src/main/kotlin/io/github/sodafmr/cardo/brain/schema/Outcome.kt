package io.github.sodafmr.cardo.brain.schema

/** What an executor did, language-free; Brain turns it into words. */
sealed interface Outcome {
    data class CallStarted(val name: String?, val digits: String, val label: NumberLabel?) : Outcome

    data class CallHistoryListed(val entries: List<CallEntry>) : Outcome

    data class MessageSent(val recipientName: String?, val app: MessagingApp) : Outcome

    data class MessagesRead(val messages: List<IncomingMessage>) : Outcome

    data class AlarmSet(val time: java.time.LocalTime, val date: java.time.LocalDate?, val repeat: Recurrence, val label: String?) : Outcome

    data class NextAlarmIs(val at: java.time.LocalDateTime?) : Outcome

    data class TimerSet(val duration: java.time.Duration, val label: String?) : Outcome

    data class StopwatchStarted(val at: java.time.Instant) : Outcome

    data class StopwatchStopped(val elapsed: java.time.Duration) : Outcome

    data class ReminderCreated(val id: Long, val task: String, val at: java.time.LocalDateTime, val repeat: Recurrence) : Outcome

    data class RemindersListed(val reminders: List<ReminderItem>) : Outcome

    data class ReminderCancelled(val task: String) : Outcome

    data class NoteCreated(val id: Long, val text: String) : Outcome

    data class EventCreated(val title: String, val start: java.time.LocalDateTime, val end: java.time.LocalDateTime, val calendarName: String) : Outcome

    data class EventsListed(val range: DateRange, val events: List<EventItem>) : Outcome

    data class AppOpened(val label: String) : Outcome

    /** [what] is one of "flashlight", "volume", "ringer", "dnd", "brightness". */
    data class DeviceChanged(val what: String, val newValue: String?) : Outcome

    data class PanelOpened(val setting: SettingPanel) : Outcome

    data class Battery(val percent: Int, val charging: Boolean) : Outcome

    data class MediaDone(val action: MediaAction) : Outcome

    data class TimeIs(val time: java.time.ZonedDateTime, val place: String?) : Outcome

    data class DateIs(val date: java.time.LocalDate) : Outcome

    data class Computed(val expression: String, val result: Double) : Outcome

    data class Converted(val value: Double, val from: String, val result: Double, val to: String) : Outcome

    data class HandedOff(val appLabel: String, val query: String?) : Outcome

    /** Something was opened on screen (alarms list, notes, camera...). */
    data object Screen : Outcome
}

data class CallEntry(val name: String?, val digits: String, val at: java.time.LocalDateTime, val missed: Boolean)

data class IncomingMessage(val key: String, val app: MessagingApp, val appLabel: String, val sender: String, val text: String, val at: java.time.LocalDateTime, val canReply: Boolean)

data class ReminderItem(val id: Long, val task: String, val at: java.time.LocalDateTime, val repeat: Recurrence)

data class EventItem(val title: String, val start: java.time.LocalDateTime, val end: java.time.LocalDateTime, val allDay: Boolean, val location: String?)

sealed interface ActionResult {
    data class Done(val outcome: Outcome) : ActionResult

    /** Which contact / number / app. */
    data class NeedsChoice(val slot: String, val options: List<Choice>) : ActionResult

    /** Read back before acting. */
    data class NeedsConfirmation(val command: Command, val preview: Outcome?) : ActionResult

    data class NeedsPermission(val permission: CardoPermission) : ActionResult

    data class NeedsUnlock(val command: Command) : ActionResult

    data class Failed(val reason: FailReason, val detail: String? = null) : ActionResult
}

/** [id] is opaque to Brain; [label] is spoken/displayed. */
data class Choice(val id: String, val label: String, val detail: String?)

enum class CardoPermission { MICROPHONE, CONTACTS, CALL_PHONE, CALL_LOG, SMS, CALENDAR, NOTIFICATIONS_POST, NOTIFICATION_ACCESS, DND_ACCESS, WRITE_SETTINGS, EXACT_ALARMS }

enum class FailReason { NOT_FOUND_CONTACT, NOT_FOUND_APP, NO_HANDLER_APP, NO_NUMBER, NOTHING_TO_READ, NOTHING_TO_REPLY, NOT_SUPPORTED, INVALID_VALUE, ERROR }

interface ActionExecutor {
    suspend fun execute(command: Command, chosen: Choice? = null, confirmed: Boolean = false): ActionResult
}
