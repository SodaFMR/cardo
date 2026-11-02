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

    data class DeviceChanged(val setting: DeviceSetting, val on: Boolean? = null, val percent: Int? = null, val ringer: RingerMode? = null) : Outcome

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

    /** [suggestion] is the closest match (e.g. a contact name), when there is one. */
    data class Failed(val reason: FailReason, val detail: String? = null, val suggestion: String? = null) : ActionResult
}

/**
 * `Choice.id` must fully identify what was resolved: for a phone-number choice it is the ContactsContract data
 * row id (which implies the contact); for a contact choice the contact id; for an app the package name; for a
 * reminder its id. Executors re-resolve everything from the latest `chosen` alone.
 *
 * [label] is a proper name (contact/app/reminder text, language-neutral); language-dependent words come from
 * [numberLabel], which Brain localizes.
 */
data class Choice(val id: String, val label: String, val detail: String?, val numberLabel: NumberLabel? = null)

enum class CardoPermission { MICROPHONE, CONTACTS, CALL_PHONE, CALL_LOG, SMS, CALENDAR, NOTIFICATIONS_POST, NOTIFICATION_ACCESS, DND_ACCESS, WRITE_SETTINGS, EXACT_ALARMS }

enum class FailReason { NOT_FOUND_CONTACT, NOT_FOUND_APP, NO_HANDLER_APP, NO_NUMBER, NOTHING_TO_READ, NOTHING_TO_REPLY, NOT_SUPPORTED, INVALID_VALUE, ERROR }

/**
 * `Choice.id` must fully identify what was resolved: for a phone-number choice it is the ContactsContract data
 * row id (which implies the contact); for a contact choice the contact id; for an app the package name; for a
 * reminder its id. Executors re-resolve everything from the latest `chosen` alone.
 *
 * Executors own `NeedsConfirmation` (they hold the resolved readback: recipient name, number) for CallNumber,
 * SendMessage, ReplyMessage. Executors never return `NeedsUnlock`; the lock-screen policy is applied by a
 * wrapper executor in `:app`. Missing or unstated values (e.g. an event without a time) are asked by the
 * dialog before `Run`, never confirmed by executors.
 */
interface ActionExecutor {
    suspend fun execute(command: Command, chosen: Choice? = null, confirmed: Boolean = false): ActionResult
}
