package io.github.sodafmr.cardo.brain.schema

/** One class per intent id; nullable = may be missing, the dialog asks for it. */
sealed interface Command {
    val intent: String
}

data class CallContact(val contact: String?, val label: NumberLabel?, val speaker: Boolean) : Command { override val intent = "call.contact" }

data class CallNumber(val digits: String?, val speaker: Boolean) : Command { override val intent = "call.number" }

data class CallLast(val kind: CallLastKind) : Command { override val intent = "call.last" }

data class CallHistory(val missedOnly: Boolean) : Command { override val intent = "call.history" }

data class SendMessage(val recipient: Recipient?, val body: String?, val app: MessagingApp) : Command { override val intent = "message.send" }

data class ReadMessages(val contact: String?, val app: MessagingApp) : Command { override val intent = "message.read" }

data class ReplyMessage(val body: String?) : Command { override val intent = "message.reply" }

data class ComposeEmail(val recipient: String?, val subject: String?, val body: String?) : Command { override val intent = "email.compose" }

data class SetAlarm(val time: java.time.LocalTime?, val date: java.time.LocalDate?, val repeat: Recurrence, val label: String?) : Command { override val intent = "alarm.set" }

data object ShowAlarms : Command { override val intent = "alarm.show" }

data object NextAlarm : Command { override val intent = "alarm.next" }

data class SetTimer(val duration: java.time.Duration?, val label: String?) : Command { override val intent = "timer.set" }

data object ShowTimers : Command { override val intent = "timer.show" }

data object StartStopwatch : Command { override val intent = "stopwatch.start" }

data object StopStopwatch : Command { override val intent = "stopwatch.stop" }

data class CreateReminder(val task: String?, val at: java.time.LocalDateTime?, val repeat: Recurrence) : Command { override val intent = "reminder.create" }

data class ListReminders(val range: DateRange?) : Command { override val intent = "reminder.list" }

data class CancelReminder(val query: String?) : Command { override val intent = "reminder.cancel" }

data class CreateNote(val text: String?) : Command { override val intent = "note.create" }

data object ShowNotes : Command { override val intent = "note.show" }

data class CreateEvent(val title: String?, val start: java.time.LocalDateTime?, val duration: java.time.Duration?, val allDay: Boolean, val location: String?) : Command { override val intent = "calendar.create" }

data class QueryCalendar(val range: DateRange?, val nextOnly: Boolean) : Command { override val intent = "calendar.query" }

data class OpenApp(val name: String?) : Command { override val intent = "app.open" }

data class OpenCamera(val mode: CameraMode) : Command { override val intent = "camera.open" }

data class Flashlight(val state: Toggle) : Command { override val intent = "device.flashlight" }

data class Volume(val stream: VolumeStream, val action: VolumeAction, val percent: Int?) : Command { override val intent = "device.volume" }

data class Ringer(val mode: RingerMode) : Command { override val intent = "device.ringer" }

data class DoNotDisturb(val state: Toggle, val duration: java.time.Duration?) : Command { override val intent = "device.dnd" }

data class Brightness(val action: LevelAction, val percent: Int?) : Command { override val intent = "device.brightness" }

data class OpenPanel(val setting: SettingPanel, val desired: Toggle?) : Command { override val intent = "device.panel" }

data object BatteryLevel : Command { override val intent = "device.battery" }

data class ControlMedia(val action: MediaAction) : Command { override val intent = "media.control" }

data class PlayMedia(val query: String?, val app: String?) : Command { override val intent = "media.play" }

data class TellTime(val place: String?) : Command { override val intent = "info.time" }

data class TellDate(val date: java.time.LocalDate?) : Command { override val intent = "info.date" }

data class Calculate(val expression: String?) : Command { override val intent = "calc.evaluate" }

data class ConvertUnits(val value: Double?, val from: String?, val to: String?) : Command { override val intent = "convert.units" }

data class WebSearch(val query: String?) : Command { override val intent = "web.search" }

data class Weather(val place: String?, val date: java.time.LocalDate?) : Command { override val intent = "weather.query" }

data class MapsSearch(val query: String?) : Command { override val intent = "maps.search" }

data class Directions(val destination: String?, val mode: TravelMode) : Command { override val intent = "maps.directions" }

data object Help : Command { override val intent = "cardo.help" }

data object Cancel : Command { override val intent = "cardo.cancel" }

data object Yes : Command { override val intent = "cardo.yes" }

data object No : Command { override val intent = "cardo.no" }

data object Repeat : Command { override val intent = "cardo.repeat" }

data object Greeting : Command { override val intent = "chitchat.greeting" }

data object Thanks : Command { override val intent = "chitchat.thanks" }

data object Identity : Command { override val intent = "chitchat.identity" }

data class OutOfScope(val text: String) : Command { override val intent = "out_of_scope" }
