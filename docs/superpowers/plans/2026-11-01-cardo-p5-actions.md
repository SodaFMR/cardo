# Cardo P5 — Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `:actions` executes every v1 command on a real Android phone through public APIs and intents. It returns language-free `ActionResult`s (choices, confirmations, permissions, failures, outcomes) and owns Cardo's local reminders, notes and incoming-message store.

**Architecture:** `CardoActionExecutor` implements P1's `ActionExecutor` and dispatches to one small class per domain. Android access goes through thin seams (`Launcher`, `PermissionGate`, `DeviceState`, repositories) so each domain is tested with Robolectric, asserting exact intents and API calls. Reminders and notes use Room. Incoming messages come from a `NotificationListenerService` into an in-memory store.

**Tech Stack:** Kotlin, coroutines, Room 2.8.5 (KSP), AndroidX Core, Robolectric + JUnit4, `:brain` (schema + `EntityMatcher`/parsers when P2 has landed — until then code against P1 schema only and use the matcher through the interface named below).

**Spec:** `docs/superpowers/specs/2026-11-01-cardo-v1-design.md` (sections 3, 7, 9, 10)

## Global Constraints

- Namespace `io.github.sodafmr.cardo.actions`; depends only on `:brain` (schema) and AndroidX. It must not request `INTERNET`.
- Permissions are checked at execution time. A missing one returns `ActionResult.NeedsPermission(<CardoPermission>)`; `:actions` never requests permissions itself (the UI does).
- Executors never return `NeedsUnlock` for lock-screen policy (an `:app` wrapper does that in P7). `Choice.id` alone must identify the resolved entity (phone choice = ContactsContract data row id, which implies the contact; contact choice = contact id; app = package; reminder = id); set `Choice.numberLabel` for number choices. `Failed(NOT_FOUND_CONTACT|NOT_FOUND_APP, detail, suggestion)` carries the closest name (score ≥ 0.6) in `suggestion`. `ReplyMessage.target` (an `IncomingMessage.key`) selects the thread; null = latest replyable.
- Confirmation rules (executors own these): `CallNumber`, `SendMessage`, `ReplyMessage` execute only when `confirmed = true`, else return `NeedsConfirmation(command, preview)`.
- Ambiguity: more than one contact/app match with score ≥ 0.85, or more than one number on the chosen contact without a matching `NumberLabel`, returns `NeedsChoice` (≤ 5 options, best first). `Choice.id` is the contact/data row id or package name.
- Message notifications live in memory only and are dropped after being read or after 24 h. Reminders and notes are stored in app-private Room. Exclude them from backup (P7 adds `dataExtractionRules`).
- Activities are started with `FLAG_ACTIVITY_NEW_TASK` from a non-activity context. A missing handler returns `Failed(NO_HANDLER_APP)`.
- Search fallback engine default: DuckDuckGo (`https://duckduckgo.com/?q=`), opened in the user's browser only after `ACTION_WEB_SEARCH` has no handler.
- Known package map for hand-offs: Spotify `com.spotify.music`, YouTube Music `com.google.android.apps.youtube.music`, YouTube `com.google.android.youtube`, WhatsApp `com.whatsapp` (+ `com.whatsapp.w4b`), Telegram `org.telegram.messenger`, Signal `org.thoughtcrime.securesms`, Google Maps `com.google.android.apps.maps`, OsmAnd `net.osmand` / `net.osmand.plus`.
- Commits: plain messages, no `Co-Authored-By` / "Generated with" trailers. While iterating run `./gradlew :actions:testDebugUnitTest --tests '<class>'`.

## Review Focus

1. A contact with the same number stored twice (formatted differently) must not trigger a "which number?" question.
2. A spoken phone number with a country prefix or spaces (`+34 612 34 56 78`) must dial exactly `+34612345678`.
3. The user revokes a permission between two commands (contacts, calendar, notification access) — the next command must return `NeedsPermission`, not crash with `SecurityException`.
4. Reminders must survive reboot and time-zone changes (rescheduled from `BOOT_COMPLETED` and `TIMEZONE_CHANGED`), and a reminder in the past fires immediately rather than never.
5. Toggling the flashlight when the torch is already in the requested state must answer truthfully (`DeviceChanged(DeviceSetting.FLASHLIGHT, on = true)`), and an emulator/phone without a flash must return `Failed(NOT_SUPPORTED)`.

---

### Task 1: Executor skeleton, seams and manifest

**Files:**
- Create under `actions/src/main/kotlin/io/github/sodafmr/cardo/actions/`: `CardoActionExecutor.kt`, `core/Launcher.kt`, `core/PermissionGate.kt`, `core/DeviceState.kt`, `core/ActionSettings.kt`, `core/Notifications.kt`
- Modify: `actions/src/main/AndroidManifest.xml`, `actions/build.gradle.kts`
- Test: `actions/src/test/kotlin/.../CardoActionExecutorTest.kt`, `core/LauncherTest.kt`

**Interfaces:**
- Produces:
  - `interface Launcher { fun start(intent: Intent): Boolean }` and `class ContextLauncher(context: Context) : Launcher`. It resolves the activity first, returns `false` if nothing handles it, and adds `FLAG_ACTIVITY_NEW_TASK`.
  - `interface PermissionGate { fun has(p: CardoPermission): Boolean }` and `class AndroidPermissionGate(context: Context) : PermissionGate`. It maps each `CardoPermission` to the runtime permission or special-access check (notification listener enabled, `isNotificationPolicyAccessGranted`, `Settings.System.canWrite`, `AlarmManager.canScheduleExactAlarms`).
  - `interface DeviceState { fun isLocked(): Boolean; fun hasFlash(): Boolean }`
  - `interface ActionSettings { val calendarId: Long?; val searchUrlPrefix: String }`
  - `object Notifications { const val CHANNEL_REMINDERS = "reminders"; const val CHANNEL_STOPWATCH = "stopwatch"; fun ensureChannels(context: Context) }`
  - `class CardoActionExecutor(context: Context, clock: java.time.Clock, launcher: Launcher, permissions: PermissionGate, device: DeviceState, settings: ActionSettings) : ActionExecutor`. It dispatches with a `when` on the command type to the domain classes of Tasks 2–10. Meta commands (`Help`, `Cancel`, `Yes`, `No`, `Repeat`, `Greeting`, `Thanks`, `Identity`, `OutOfScope`) return `Failed(NOT_SUPPORTED)` — the dialog never sends them.
- Manifest:
  - Permissions: `CALL_PHONE`, `READ_CONTACTS`, `READ_CALL_LOG`, `SEND_SMS`, `READ_CALENDAR`, `WRITE_CALENDAR`, `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `com.android.alarm.permission.SET_ALARM`, `ACCESS_NOTIFICATION_POLICY`, `WRITE_SETTINGS` (with `tools:ignore="ProtectedPermissions"`).
  - `<queries>` for: launcher `MAIN/LAUNCHER`, `ACTION_WEB_SEARCH`, `ACTION_VIEW` `geo:`/`https:`, `ACTION_SENDTO` `mailto:`/`smsto:`, `ACTION_DIAL`, `MEDIA_PLAY_FROM_SEARCH`, the still/video camera actions, `AlarmClock` actions, and the known packages in Global Constraints.

- [ ] **Step 1:** Write tests: `ContextLauncher.start` returns false and starts nothing when no activity resolves (Robolectric `ShadowPackageManager`); `CardoActionExecutor.execute(Help)` → `Failed(NOT_SUPPORTED)`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. Domain methods not yet built return `Failed(NOT_SUPPORTED)` until their task lands (Task 11 proves none remain).
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(actions): executor skeleton, Android seams and manifest`.

### Task 2: Contacts

**Files:**
- Create: `actions/src/main/kotlin/.../contacts/ContactsRepository.kt`, `contacts/ContactResolver.kt`
- Test: `contacts/ContactResolverTest.kt` (fake repository), `contacts/ContactsRepositoryTest.kt` (Robolectric with a fake `ContactsContract` provider)

**Interfaces:**
- Produces:
  - `data class ContactRecord(val id: Long, val displayName: String, val nicknames: List<String>, val relations: Set<Relation>, val phones: List<PhoneRecord>, val emails: List<String>)`
  - `data class PhoneRecord(val dataId: Long, val digits: String, val label: NumberLabel, val isPrimary: Boolean)`. `digits` is normalized E.164-ish: keep a leading `+` and digits only.
  - `interface ContactsRepository { fun all(): List<ContactRecord> }`. The Android implementation reads display name, nickname, relation rows (mapped to `Relation`) and phone/email data, with a `ContentObserver`-invalidated cache.
  - `class ContactResolver(repo: ContactsRepository) { fun resolve(query: String, lang: Lang): ContactMatch; fun number(contact: ContactRecord, label: NumberLabel?): NumberMatch }`
  - `sealed interface ContactMatch { data class One(val c: ContactRecord); data class Many(val options: List<ContactRecord>); data object None }`
  - `sealed interface NumberMatch { data class One(val p: PhoneRecord); data class Many(val options: List<PhoneRecord>); data object None }`
  - Phones are de-duplicated by `digits` (Review Focus 1). The primary number wins when no label is given and one is marked primary.
- Uses `EntityMatcher` and `Relations` from `:brain` (P2 Task 5). If P2 has not merged when this task runs, define the same API locally in `contacts/MatcherShim.kt` with exact-and-prefix matching only, and leave a `// replace with brain EntityMatcher` marker. P7 removes it.

- [ ] **Step 1:** Write tests:
  - "Ana" with 2 contacts → `Many`
  - "Ana López" → `One`
  - relation MOTHER resolves "mamá"
  - duplicate numbers collapse to one
  - label WORK picks the work number
  - no label + primary set → primary
  - a repository reading from the fake provider maps TYPE_MOBILE → MOBILE
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(actions): contact repository and resolver`.

### Task 3: Phone

**Files:**
- Create: `actions/src/main/kotlin/.../phone/PhoneActions.kt`, `phone/CallLogRepository.kt`
- Test: `phone/PhoneActionsTest.kt`

**Interfaces:**
- Produces: `class PhoneActions(context, permissions, contacts: ContactResolver, callLog: CallLogRepository, telecom: TelecomManager)` with `suspend fun call(c: CallContact, chosen: Choice?)`, `callNumber(c: CallNumber, confirmed: Boolean)`, `callLast(c: CallLast)`, `history(c: CallHistory): ActionResult`.
  - Calls go through `TelecomManager.placeCall(Uri.fromParts("tel", digits, null), extras)`, with `EXTRA_START_CALL_WITH_SPEAKERPHONE` when `speaker`.
  - `CallLast(REDIAL)` = last outgoing; `CALL_BACK` = last incoming or missed.
  - `history` returns up to 5 recent entries (missed only if asked).
- Missing `CALL_PHONE` → `NeedsPermission(CALL_PHONE)`; missing contacts or call log → its permission.

- [ ] **Step 1:** Write tests (Robolectric `ShadowTelecomManager`):
  - `CallNumber("+34612345678", confirmed=false)` → `NeedsConfirmation`
  - confirmed → a placed call to exactly `tel:+34612345678` (Review Focus 2)
  - `CallContact` with 2 numbers and no label → `NeedsChoice(slot="number_label")`
  - `chosen` = second → call to that number
  - speaker extra set
  - no permission → `NeedsPermission`
  - `CallLast(CALL_BACK)` picks the latest incoming/missed row
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(actions): calls, redial, call back and call history`.

### Task 4: Messages and email

**Files:**
- Create: `actions/src/main/kotlin/.../messages/MessageActions.kt`, `messages/MessageStore.kt`, `messages/MessageListenerService.kt`, `messages/SmsSender.kt`
- Modify: `actions/src/main/AndroidManifest.xml` (listener service with `BIND_NOTIFICATION_LISTENER_SERVICE`)
- Test: `messages/MessageActionsTest.kt`, `messages/MessageStoreTest.kt`

**Interfaces:**
- Produces:
  - `object MessageStore { fun put(m: StoredMessage); fun unread(contact: String?, app: MessagingApp): List<StoredMessage>; fun markRead(keys: List<String>); fun latestReplyable(): StoredMessage?; fun clear() }`, with `data class StoredMessage(val message: IncomingMessage, val reply: android.app.Notification.Action?, val postedAt: java.time.Instant)`. Entries expire after 24 h.
  - `MessageListenerService` parses `MessagingStyle` messages (or title/text fallback), maps known packages to `MessagingApp`, finds the action with a `RemoteInput` for replies, and ignores group summaries and Cardo's own notifications.
  - `class MessageActions(context, permissions, contacts, launcher, sms: SmsSender, clock)`:
    - `send(c: SendMessage, chosen, confirmed)`. SMS: resolve the recipient → `NeedsConfirmation(preview = Outcome.MessageSent(name, SMS))` → on confirm, `SmsManager.divideMessage` + `sendMultipartTextMessage`. WhatsApp/Telegram/Signal: if a replyable stored message from that contact exists, reply through its `RemoteInput`; otherwise open the app with the text prefilled (WhatsApp `https://wa.me/<digits>?text=<encoded>` with the package set; Telegram/Signal `ACTION_SEND` text targeted to the package) → `Done(HandedOff(appLabel, body))`.
    - `read(c: ReadMessages)` → `Done(MessagesRead(list))` marking them read, or `Failed(NOTHING_TO_READ)`. Without notification access → `NeedsPermission(NOTIFICATION_ACCESS)`.
    - `reply(c: ReplyMessage, confirmed)` replies to the stored message whose key is `c.target`, else `latestReplyable()`.
    - `email(c: ComposeEmail)` → `ACTION_SENDTO mailto:` with subject/body → `Done(HandedOff(…))`.

- [ ] **Step 1:** Write tests:
  - SMS unconfirmed → `NeedsConfirmation`; confirmed → `ShadowSmsManager` last multipart params have the right destination and body parts
  - a 300-char body splits into multiple parts
  - WhatsApp with no stored thread → started intent targets `com.whatsapp` with the encoded text
  - WhatsApp with a stored replyable message → the `RemoteInput` pending intent is sent with the body
  - `read` with nothing stored → `Failed(NOTHING_TO_READ)`
  - the store expires a 25 h-old entry
  - email → `mailto:` intent with subject and body extras
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(actions): SMS, messaging-app replies and hand-offs, reading messages, email`.

### Task 5: Clock

**Files:**
- Create: `actions/src/main/kotlin/.../clock/ClockActions.kt`, `clock/Stopwatch.kt`
- Test: `clock/ClockActionsTest.kt`, `clock/StopwatchTest.kt`

**Interfaces:**
- Produces: `class ClockActions(context, launcher, alarmManager: AlarmManager, stopwatch: Stopwatch, clock)` with `setAlarm`, `showAlarms`, `nextAlarm`, `setTimer`, `showTimers`, `startStopwatch`, `stopStopwatch`.
  - `SetAlarm` → `AlarmClock.ACTION_SET_ALARM`: `EXTRA_HOUR`, `EXTRA_MINUTES`, `EXTRA_DAYS` (Calendar day ints) when repeating, `EXTRA_MESSAGE` = label, `EXTRA_SKIP_UI = true`. A missing time → `Failed(INVALID_VALUE)` (the dialog should have asked).
  - A one-off alarm dated beyond tomorrow → `Failed(NOT_SUPPORTED, "date")`: the Clock API can't schedule it, and the response suggests a reminder instead.
  - `SetTimer` → `ACTION_SET_TIMER` with `EXTRA_LENGTH` seconds (1..86400) and `EXTRA_SKIP_UI`.
  - `NextAlarm` → `AlarmManager.nextAlarmClock` → `NextAlarmIs`.
  - `class Stopwatch(context, clock) { fun start(): java.time.Instant; fun stop(): java.time.Duration?; val running: Boolean }` shows an ongoing `CHANNEL_STOPWATCH` notification with `setUsesChronometer(true)` and a Stop action. Stopping when not running → `Failed(INVALID_VALUE)`.

- [ ] **Step 1:** Write tests:
  - alarm 07:30 weekdays → the intent carries hour 7, minutes 30, days [2,3,4,5,6], skip UI
  - timer of 10 min → length 600
  - a 25 h timer → `Failed(INVALID_VALUE)`
  - no clock app → `Failed(NO_HANDLER_APP)`
  - next alarm from a shadowed `AlarmManager`
  - stopwatch start → ongoing notification with chronometer; stop returns the elapsed time from the fixed clock
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(actions): alarms, timers and stopwatch`.

### Task 6: Reminders and notes

**Files:**
- Create under `actions/src/main/kotlin/.../store/`: `CardoDatabase.kt`, `ReminderEntity.kt`, `NoteEntity.kt`, `RemindersRepository.kt`, `NotesRepository.kt`; under `.../reminders/`: `ReminderScheduler.kt`, `ReminderReceiver.kt`, `RescheduleReceiver.kt`, `ReminderActions.kt`; `.../notes/NoteActions.kt`
- Modify: manifest (receivers: `ReminderReceiver` not exported; `RescheduleReceiver` for `BOOT_COMPLETED`, `TIMEZONE_CHANGED`, `TIME_SET`, `MY_PACKAGE_REPLACED`)
- Test: `reminders/ReminderSchedulerTest.kt`, `reminders/ReminderActionsTest.kt`, `store/RemindersRepositoryTest.kt` (in-memory Room), `notes/NoteActionsTest.kt`

**Interfaces:**
- Produces (P6 screens and P7 consume these):
  - `interface RemindersRepository { fun observeUpcoming(): Flow<List<ReminderItem>>; suspend fun create(task: String, at: java.time.LocalDateTime, repeat: Recurrence): ReminderItem; suspend fun complete(id: Long); suspend fun snooze(id: Long, by: java.time.Duration); suspend fun delete(id: Long); suspend fun find(query: String): List<ReminderItem>; suspend fun inRange(range: DateRange): List<ReminderItem> }`
  - `data class NoteRow(val id: Long, val text: String, val createdAt: java.time.Instant)`
  - `interface NotesRepository { fun observeAll(): Flow<List<NoteRow>>; suspend fun create(text: String): NoteRow; suspend fun update(id: Long, text: String); suspend fun delete(id: Long) }`
  - `class ReminderScheduler(context, alarmManager, clock) { fun schedule(r: ReminderItem); fun cancel(id: Long); suspend fun rescheduleAll(repo: RemindersRepository) }`. It uses `setExactAndAllowWhileIdle` when `canScheduleExactAlarms()`, else `setWindow(at, 60_000)`. Past due → fire within 5 s (Review Focus 4). Repeating reminders schedule the next matching day after firing or completion.
  - `ReminderReceiver` posts a `CHANNEL_REMINDERS` notification (title = task, Done and "Snooze 10 min" actions; text in the device language — strings in `actions/src/main/res/values{,-es}/strings.xml`).
  - `ReminderActions` / `NoteActions`: `create` → `Done(ReminderCreated/NoteCreated)`; `list` → `RemindersListed`; `cancel(query)` → resolves via `find` (≥ 2 matches → `NeedsChoice(slot="task")`) → `ReminderCancelled`; `ShowNotes` → launches the app's notes screen intent (`io.github.sodafmr.cardo.action.SHOW_NOTES`, declared by P6/P7) → `Done(Screen)`.

- [ ] **Step 1:** Write tests:
  - create → row stored and alarm scheduled at the exact epoch millis (`ShadowAlarmManager`)
  - no exact-alarm permission → a window alarm
  - a past time → scheduled ≤ 5 s ahead
  - `rescheduleAll` re-registers every upcoming reminder
  - a weekdays reminder completed on Friday schedules Monday
  - cancel with 2 matches → `NeedsChoice`
  - the receiver posts a notification with 2 actions
  - notes CRUD round-trip through the `Flow`
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(actions): local reminders with exact scheduling, and notes`.

### Task 7: Calendar

**Files:**
- Create: `actions/src/main/kotlin/.../calendar/CalendarRepository.kt`, `calendar/CalendarActions.kt`
- Test: `calendar/CalendarActionsTest.kt` (fake `CalendarContract` provider)

**Interfaces:**
- Produces:
  - `data class CalendarInfo(val id: Long, val name: String, val account: String, val writable: Boolean, val primary: Boolean)`
  - `interface CalendarRepository { fun calendars(): List<CalendarInfo>; fun insert(calendarId: Long, title: String, start: java.time.LocalDateTime, end: java.time.LocalDateTime, allDay: Boolean, location: String?, zone: java.time.ZoneId): Long; fun instances(range: DateRange, zone: java.time.ZoneId): List<EventItem> }`
  - `class CalendarActions(repo, permissions, settings, clock)`:
    - `create`: target = `settings.calendarId`, else the first writable visible primary calendar, else the first writable one; none → `Failed(NOT_SUPPORTED, "no writable calendar")`. Duration defaults to 1 h; all-day when no time and `allDay`.
    - `query(range|nextOnly)`: `EventsListed` sorted by start; `nextOnly` = first event after now within 7 days.

- [ ] **Step 1:** Write tests:
  - insert uses the primary writable calendar and UTC millis for `DTSTART`/`DTEND` in the right zone
  - a settings override wins
  - today's query returns 2 events in order
  - `nextOnly` skips past events
  - no permission → `NeedsPermission(CALENDAR)`
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(actions): calendar create and query`.

### Task 8: Apps and camera

**Files:**
- Create: `actions/src/main/kotlin/.../apps/AppsRepository.kt`, `apps/AppActions.kt`
- Test: `apps/AppActionsTest.kt`

**Interfaces:**
- Produces:
  - `data class AppEntry(val packageName: String, val label: String, val component: ComponentName)`
  - `interface AppsRepository { fun launchable(): List<AppEntry> }` (cached; invalidated on `PACKAGE_ADDED/REMOVED/CHANGED`)
  - `class AppActions(repo, launcher, device)`:
    - `open(c: OpenApp, chosen)`: fuzzy match; ≥ 2 matches at ≥ 0.85 → `NeedsChoice(slot="app_name")`; none → `Failed(NOT_FOUND_APP, name)`.
    - `camera(c: OpenCamera)`: `INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE` when `device.isLocked()`, else `INTENT_ACTION_STILL_IMAGE_CAMERA`. Selfie adds `android.intent.extras.CAMERA_FACING=1`, `android.intent.extras.LENS_FACING_FRONT=1`, `android.intent.extra.USE_FRONT_CAMERA=true`. Video → `INTENT_ACTION_VIDEO_CAMERA`.

- [ ] **Step 1:** Write tests:
  - "spotifai" opens Spotify's component
  - "notas" with "Notas" and "Google Keep Notas" → `NeedsChoice`
  - an unknown app → `Failed(NOT_FOUND_APP)`
  - locked → secure camera action
  - selfie extras present
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(actions): open apps and camera`.

### Task 9: Device controls

**Files:**
- Create: `actions/src/main/kotlin/.../device/DeviceActions.kt`, `device/Torch.kt`
- Test: `device/DeviceActionsTest.kt`

**Interfaces:**
- Produces: `class DeviceActions(context, permissions, device, launcher, audio: AudioManager, torch: Torch)` with `flashlight`, `volume`, `ringer`, `dnd`, `brightness`, `panel`, `battery`.
  - `Torch` tracks state through `CameraManager.TorchCallback`; `TOGGLE` uses the tracked state; no flash → `Failed(NOT_SUPPORTED)` (Review Focus 5).
  - Volume: `percent` maps to a stream index; `MUTE`/`UNMUTE` use `adjustStreamVolume(ADJUST_MUTE/UNMUTE)`. Changing ring volume while DND blocks it → `NeedsPermission(DND_ACCESS)`.
  - Ringer `SILENT` needs DND access; `VIBRATE`/`NORMAL` via `setRingerMode`.
  - DND: `setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY | ALL)`. With a duration, also schedule a Cardo alarm that restores it — reuse the `ReminderScheduler` alarm channel with a distinct request code.
  - Brightness: `WRITE_SETTINGS` → `Settings.System.SCREEN_BRIGHTNESS` (0–255 from percent), also setting `SCREEN_BRIGHTNESS_MODE` manual.
  - Panels: WIFI → `Settings.Panel.ACTION_WIFI`; MOBILE_DATA → `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`; NFC → `Settings.Panel.ACTION_NFC`; BLUETOOTH → `Settings.ACTION_BLUETOOTH_SETTINGS`; AIRPLANE → `ACTION_AIRPLANE_MODE_SETTINGS`; DARK_MODE → `ACTION_DISPLAY_SETTINGS`; BATTERY_SAVER → `ACTION_BATTERY_SAVER_SETTINGS`; LOCATION → `ACTION_LOCATION_SOURCE_SETTINGS`; HOTSPOT → `ACTION_WIRELESS_SETTINGS`. All return `Done(PanelOpened)`.
  - Battery: `BatteryManager.BATTERY_PROPERTY_CAPACITY` and `isCharging`.

- [ ] **Step 1:** Write tests:
  - flashlight ON when already on → `DeviceChanged(FLASHLIGHT, on = true)` and no second `setTorchMode`
  - no flash → `NOT_SUPPORTED`
  - media volume 50% on a 0..15 stream → index 7 or 8 (round half up = 8)
  - SILENT without DND access → `NeedsPermission(DND_ACCESS)`
  - brightness 80% → 204
  - each panel maps to its exact action string
  - battery reads the shadowed capacity
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(actions): flashlight, volume, ringer, DND, brightness, panels and battery`.

### Task 10: Media, local answers and hand-offs

**Files:**
- Create: `actions/src/main/kotlin/.../media/MediaActions.kt`, `info/InfoActions.kt`, `handoff/HandoffActions.kt`
- Test: `media/MediaActionsTest.kt`, `info/InfoActionsTest.kt`, `handoff/HandoffActionsTest.kt`

**Interfaces:**
- Produces:
  - `MediaActions.control(c: ControlMedia)`: `AudioManager.dispatchMediaKeyEvent` down+up for `KEYCODE_MEDIA_PLAY|PAUSE|NEXT|PREVIOUS|STOP` (`RESUME` = PLAY).
  - `MediaActions.play(c: PlayMedia)`: `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` with `SearchManager.QUERY` and `EXTRA_MEDIA_FOCUS="vnd.android.cursor.item/*"`, targeted to the named app's package when given and installed. A named app that isn't installed → `Failed(NO_HANDLER_APP, app)`.
  - `InfoActions` (pure, no Android needed except zone):
    - `TellTime(place)` → `TimeIs(ZonedDateTime.now(clock.withZone(CityTimeZones.resolve(place) ?: systemZone)))`; an unknown place → `Failed(INVALID_VALUE, place)`.
    - `TellDate` → `DateIs`.
    - `Calculate` → `MathParser` → `Computed`, or `Failed(INVALID_VALUE)`.
    - `ConvertUnits` → `Units` → `Converted`, or `Failed(INVALID_VALUE)`.
  - Commands carry no language, so `InfoActions` parses with English first and falls back to Spanish (`MathParser.evaluate(e, Lang.EN) ?: MathParser.evaluate(e, Lang.ES)`, and the same for `Units.resolve`).
  - `HandoffActions`:
    - `WebSearch`/`Weather`: `ACTION_WEB_SEARCH` with `SearchManager.QUERY` (weather query text: "weather in <place> <date>" / "tiempo en <place> <date>"); no handler → `ACTION_VIEW` of `settings.searchUrlPrefix + encoded`; neither → `Failed(NO_HANDLER_APP)`.
    - `MapsSearch` → `geo:0,0?q=<encoded>`.
    - `Directions` → `google.navigation:q=<encoded>&mode=<d|w|r|b>` when Google Maps is installed, else `geo:0,0?q=<encoded>`.
    - All return `Done(HandedOff(appLabel, query))`, with `appLabel` from the resolved activity's label.

- [ ] **Step 1:** Write tests:
  - pause dispatches `KEYCODE_MEDIA_PAUSE` down and up
  - "play Rosalía on Spotify" targets `com.spotify.music` with the query
  - `TellTime("Tokio")` returns the Tokyo zone
  - `Calculate("el 15 por ciento de 80")` → 12.0
  - weather with no search app falls back to the DuckDuckGo URL
  - directions with walking and Google Maps installed → `mode=w`
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. Until P2 merges, `MathParser`/`Units`/`CityTimeZones` may be missing: write these tests against the brain API names and mark them `@Ignore("needs P2")` only if P2 is absent at that moment. Record it in the report so P7 un-ignores them.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(actions): media control, local answers and app hand-offs`.

### Task 11: Coverage gate

**Files:**
- Test: `actions/src/test/kotlin/.../CommandCoverageTest.kt`

- [ ] **Step 1:** Write `CommandCoverageTest`. For every `Command` sealed subclass except the 9 meta ones, build a representative instance and execute it against the real `CardoActionExecutor` with permissive fakes. Assert the result is not `Failed(NOT_SUPPORTED)` unless the fixture intentionally lacks hardware (flash). Separately assert all 9 meta commands return `Failed(NOT_SUPPORTED)`.
- [ ] **Step 2:** Run → PASS (fix any gap it reveals).
- [ ] **Step 3:** Run the full `./gradlew :actions:testDebugUnitTest` → all green.
- [ ] **Step 4:** Commit `test(actions): every v1 command has an executor`.
