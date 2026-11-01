# Cardo P1 — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A building, testable Gradle project with all four modules, the shared command schema every later track codes against, pinned third-party artifacts, a privacy check that fails the build on `INTERNET`, an assistant shell Android can bind on the API 37 emulator, CI, and a private GitHub repo.

**Architecture:** `:brain` is pure Kotlin/JVM and owns every shared type (commands, outcomes, responses, dialog contracts). `:voice` and `:actions` are Android libraries; `:app` is the application. Third-party binaries (sherpa-onnx AAR) are fetched at settings time from `artifacts.lock.json` with SHA-256 checks.

**Tech Stack:** Gradle 9.8.2 (Kotlin DSL, version catalog), AGP 9.5.0, Kotlin 2.4.21, Compose BOM 2026.09.00, KSP 2.3.13, Room 2.8.5, JUnit 5, Robolectric, onnxruntime(-android) 1.31.1, sherpa-onnx 2.0.1 static-link AAR, JDK 17, Android SDK platform 37.0 / build-tools 37.0.0.

**Spec:** `docs/superpowers/specs/2026-11-01-cardo-v1-design.md`

## Global Constraints

- Package / applicationId: `io.github.sodafmr.cardo`; module namespaces `io.github.sodafmr.cardo.voice`, `io.github.sodafmr.cardo.actions`; `:brain` Kotlin package root `io.github.sodafmr.cardo.brain`.
- minSdk 31, compileSdk 37, targetSdk 37; JVM toolchain 17.
- Release ABI: `arm64-v8a` only. Debug ABIs: `arm64-v8a`, `x86_64`.
- The merged manifest of every `:app` variant must not contain `android.permission.INTERNET`.
- No analytics, crash reporting, ads, Google Play Services or Firebase dependencies.
- Licence: Apache-2.0.
- Commits: plain messages, **no** `Co-Authored-By` or "Generated with" trailers. Author is the repo's configured identity.
- Android SDK at `~/Android/Sdk` (write `sdk.dir` into git-ignored `local.properties`).
- `org.gradle.jvmargs=-Xmx3g` — the dev machine has ~10 GB free RAM shared with other work; never raise it.

## Review Focus

1. A clean checkout with no `third_party/` directory must build: the fetcher downloads, verifies and caches; a second build must not re-download.
2. A truncated or tampered download (checksum mismatch) must fail the build with a message naming the file, and must not leave the bad file in place.
3. A dependency that adds `INTERNET` transitively must still fail the check — the check reads the **merged** manifest, not `src/main`.
4. Android silently ignores a `VoiceInteractionService` whose XML lacks `android:recognitionService`; the shell must declare one so Cardo appears as a selectable digital assistant.
5. `:brain` must stay free of Android imports — a test asserts it compiles and runs on a plain JVM.

---

### Task 1: Gradle skeleton with four modules

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, Gradle wrapper (9.8.2), `LICENSE` (Apache-2.0 text), `.gitignore` (add `local.properties`, `third_party/`, `.kotlin/`, `*.keystore`, `/captures`)
- Create: `brain/build.gradle.kts`, `brain/src/main/kotlin/io/github/sodafmr/cardo/brain/Lang.kt`, `brain/src/test/kotlin/io/github/sodafmr/cardo/brain/LangTest.kt`
- Create: `voice/build.gradle.kts`, `voice/src/main/AndroidManifest.xml`
- Create: `actions/build.gradle.kts`, `actions/src/main/AndroidManifest.xml`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/io/github/sodafmr/cardo/CardoApp.kt`, `app/src/main/kotlin/io/github/sodafmr/cardo/MainActivity.kt` (Compose "Cardo" text placeholder)

**Interfaces:**
- Produces: modules `:brain` (Kotlin JVM, JUnit 5), `:voice`, `:actions` (Android libraries with JUnit 4 + Robolectric for tests), `:app` (application, Compose enabled). Dependency direction: `:app → :voice, :actions, :brain`; `:actions → :brain`; `:voice` depends on nothing internal.
- Produces: `enum class Lang { EN, ES }` in `io.github.sodafmr.cardo.brain`.

- [ ] **Step 1:** Write `LangTest.fromTag()` asserting `Lang.fromTag("es-ES") == Lang.ES`, `Lang.fromTag("en") == Lang.EN`, `Lang.fromTag("fr") == null`.
- [ ] **Step 2:** Generate the wrapper (`gradle wrapper --gradle-version 9.8.2` via a downloaded Gradle distribution if `gradle` is absent), write the version catalog with the versions in Tech Stack, and the module build files. AGP 9 provides built-in Kotlin for Android modules; use `org.jetbrains.kotlin.jvm` for `:brain` and `org.jetbrains.kotlin.plugin.compose` for `:app`. Verify KSP 2.3.13 resolves with Kotlin 2.4.21; if not, pick the newest KSP that does and record it in the catalog.
- [ ] **Step 3:** Run `./gradlew :brain:test` → FAIL (`fromTag` unresolved).
- [ ] **Step 4:** Implement `Lang.fromTag(tag: String): Lang?` (match on the language subtag, case-insensitive).
- [ ] **Step 5:** Write `local.properties` with `sdk.dir=/home/soda/Android/Sdk`. Run `./gradlew build` → BUILD SUCCESSFUL, `LangTest` passes, `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 6:** Commit `build: gradle skeleton with brain, voice, actions, app modules`.

### Task 2: Pinned third-party artifacts and ONNX packaging

**Files:**
- Create: `artifacts.lock.json`, `gradle/fetch-artifacts.settings.gradle.kts` (applied from `settings.gradle.kts`)
- Modify: `voice/build.gradle.kts` (depend on `files("$rootDir/third_party/sherpa-onnx-static-2.0.1.aar")`), `app/build.gradle.kts` (add `com.microsoft.onnxruntime:onnxruntime-android:1.31.1`), `brain/build.gradle.kts` (`compileOnly` + `testImplementation` `com.microsoft.onnxruntime:onnxruntime:1.31.1`)
- Create: `scripts/check-apk-libs.sh`

**Interfaces:**
- Produces: `artifacts.lock.json` schema, reused later for model files:
  ```json
  { "artifacts": [ { "name": "sherpa-onnx-static", "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar",
      "sha256": "<computed once and pinned>", "dest": "third_party/sherpa-onnx-static-2.0.1.aar", "license": "Apache-2.0" } ] }
  ```
  `dest` is relative to the repo root. Cache directory: `~/.cache/cardo-artifacts/<sha256>`.
- Produces: `scripts/check-apk-libs.sh <apk>` prints the `lib/<abi>/*.so` list and exits non-zero if any `.so` name appears twice within one ABI.

- [ ] **Step 1:** Download the AAR once, compute its SHA-256 and pin it. List its `jni/*/` contents and record which ABIs it ships in the commit message.
- [ ] **Step 2:** Implement the settings-time fetcher. For each artifact: if `dest` exists with the right hash, do nothing; else copy from the cache or download to a temp file, verify the hash, then move it into place. On a mismatch, delete the temp file and throw `GradleException("Checksum mismatch for <name>: expected <x>, got <y>")`.
- [ ] **Step 3:** Verify Review Focus 1–2: `rm -rf third_party && ./gradlew help` downloads once; running again prints no download. Corrupt the cached file (`truncate -s 100`) and remove `third_party/` → build fails with the checksum message and no file is left at `dest`. Restore.
- [ ] **Step 4:** `./gradlew :app:assembleDebug` then `scripts/check-apk-libs.sh app/build/outputs/apk/debug/app-debug.apk`. Expected: `libsherpa-onnx-jni.so` and `libonnxruntime.so` (+ `libonnxruntime4j_jni.so`) present for `arm64-v8a` and `x86_64`, no duplicates, exit 0. If AGP reports a duplicate `.so` merge conflict, resolve it with `packaging.jniLibs.pickFirsts` only for files that come from the **same** library and document why.
- [ ] **Step 5:** Commit `build: pin sherpa-onnx static AAR and onnxruntime`.

### Task 3: Shared command schema in `:brain`

**Files:**
- Create under `brain/src/main/kotlin/io/github/sodafmr/cardo/brain/`: `schema/Command.kt`, `schema/Values.kt`, `schema/IntentCatalog.kt`, `schema/Outcome.kt`, `schema/Response.kt`, `dialog/DialogContracts.kt`, `nlu/UnderstandingContracts.kt`, `nlu/ModelContract.kt`
- Test: `brain/src/test/kotlin/io/github/sodafmr/cardo/brain/schema/IntentCatalogTest.kt`, `brain/src/test/kotlin/io/github/sodafmr/cardo/brain/NoAndroidTest.kt`

**Interfaces (Produces — every later plan codes against these exact names):**

```kotlin
// schema/Values.kt — all time types are java.time; "now" always comes from an injected java.time.Clock
enum class NumberLabel { MOBILE, HOME, WORK, OTHER }
enum class CallLastKind { REDIAL, CALL_BACK }            // REDIAL = last outgoing, CALL_BACK = last incoming or missed
enum class MessagingApp { SMS, WHATSAPP, TELEGRAM, SIGNAL, ANY }
sealed interface Recipient { data class Contact(val name: String) : Recipient; data class Number(val digits: String) : Recipient }
data class Recurrence(val days: Set<java.time.DayOfWeek>)  // empty = one-off; all 7 = daily
data class DateRange(val start: java.time.LocalDate, val endInclusive: java.time.LocalDate)
enum class Toggle { ON, OFF, TOGGLE }
enum class CameraMode { PHOTO, SELFIE, VIDEO }
enum class VolumeStream { MEDIA, RING, ALARM, CALL }
enum class VolumeAction { UP, DOWN, SET, MUTE, UNMUTE, MAX }
enum class LevelAction { UP, DOWN, SET, MAX, MIN }
enum class RingerMode { SILENT, VIBRATE, NORMAL }
enum class SettingPanel { WIFI, BLUETOOTH, MOBILE_DATA, AIRPLANE, DARK_MODE, BATTERY_SAVER, LOCATION, NFC, HOTSPOT }
enum class MediaAction { PLAY, PAUSE, RESUME, NEXT, PREVIOUS, STOP }
enum class TravelMode { DRIVING, WALKING, TRANSIT, CYCLING }

// schema/Command.kt — one class per intent id; nullable = may be missing, the dialog asks for it
sealed interface Command { val intent: String }
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

// schema/IntentCatalog.kt
object IntentCatalog {
    val ids: List<String>                       // the 50 ids above, in the order listed
    val slotTypes: List<String>                 // the 31 slot types from spec §5, in spec order
    fun requiredSlots(intent: String): Set<String>  // e.g. "call.contact" -> {"contact"}, "timer.set" -> {"duration"}
}

// schema/Outcome.kt — what an executor did, language-free; Brain turns it into words
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
    data class DeviceChanged(val what: String, val newValue: String?) : Outcome   // what: "flashlight","volume","ringer","dnd","brightness"
    data class PanelOpened(val setting: SettingPanel) : Outcome
    data class Battery(val percent: Int, val charging: Boolean) : Outcome
    data class MediaDone(val action: MediaAction) : Outcome
    data class TimeIs(val time: java.time.ZonedDateTime, val place: String?) : Outcome
    data class DateIs(val date: java.time.LocalDate) : Outcome
    data class Computed(val expression: String, val result: Double) : Outcome
    data class Converted(val value: Double, val from: String, val result: Double, val to: String) : Outcome
    data class HandedOff(val appLabel: String, val query: String?) : Outcome
    data object Screen : Outcome                // something was opened on screen (alarms list, notes, camera…)
}
data class CallEntry(val name: String?, val digits: String, val at: java.time.LocalDateTime, val missed: Boolean)
data class IncomingMessage(val key: String, val app: MessagingApp, val appLabel: String, val sender: String, val text: String, val at: java.time.LocalDateTime, val canReply: Boolean)
data class ReminderItem(val id: Long, val task: String, val at: java.time.LocalDateTime, val repeat: Recurrence)
data class EventItem(val title: String, val start: java.time.LocalDateTime, val end: java.time.LocalDateTime, val allDay: Boolean, val location: String?)

sealed interface ActionResult {
    data class Done(val outcome: Outcome) : ActionResult
    data class NeedsChoice(val slot: String, val options: List<Choice>) : ActionResult          // which contact / number / app
    data class NeedsConfirmation(val command: Command, val preview: Outcome?) : ActionResult   // read back before acting
    data class NeedsPermission(val permission: CardoPermission) : ActionResult
    data class NeedsUnlock(val command: Command) : ActionResult
    data class Failed(val reason: FailReason, val detail: String? = null) : ActionResult
}
data class Choice(val id: String, val label: String, val detail: String?)   // id is opaque to Brain; label is spoken/displayed
enum class CardoPermission { MICROPHONE, CONTACTS, CALL_PHONE, CALL_LOG, SMS, CALENDAR, NOTIFICATIONS_POST, NOTIFICATION_ACCESS, DND_ACCESS, WRITE_SETTINGS, EXACT_ALARMS }
enum class FailReason { NOT_FOUND_CONTACT, NOT_FOUND_APP, NO_HANDLER_APP, NO_NUMBER, NOTHING_TO_READ, NOTHING_TO_REPLY, NOT_SUPPORTED, INVALID_VALUE, ERROR }
interface ActionExecutor { suspend fun execute(command: Command, chosen: Choice? = null, confirmed: Boolean = false): ActionResult }

// schema/Response.kt — what the user sees and hears
data class Response(val display: String, val speech: String, val lang: Lang, val card: Card? = null, val buttons: List<UiAction> = emptyList())
sealed interface Card {
    data class Timer(val duration: java.time.Duration, val label: String?) : Card
    data class Alarm(val time: java.time.LocalTime, val repeat: Recurrence, val label: String?) : Card
    data class Contact(val name: String, val digits: String?, val label: NumberLabel?) : Card
    data class Message(val recipient: String, val body: String, val app: MessagingApp) : Card
    data class Messages(val items: List<IncomingMessage>) : Card
    data class Events(val range: DateRange, val items: List<EventItem>) : Card
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

// dialog/DialogContracts.kt
data class SessionInfo(val wokenByVoice: Boolean, val locked: Boolean, val langHint: Lang)
sealed interface Turn {
    data class Run(val command: Command, val chosen: Choice? = null, val confirmed: Boolean = false) : Turn  // caller executes, then calls onResult
    data class Reply(val response: Response, val listen: Boolean) : Turn      // listen = open a follow-up window without wake word
    data object Close : Turn
}
interface DialogManager {
    fun start(session: SessionInfo)
    fun onUtterance(text: String): Turn
    fun onUiAction(action: UiAction): Turn
    fun onResult(result: ActionResult): Turn
}

// nlu/UnderstandingContracts.kt
data class SlotSpan(val type: String, val text: String, val start: Int, val end: Int)   // offsets into normalized text
data class Interpretation(val intent: String, val confidence: Float, val alternatives: List<Pair<String, Float>>,
                          val slots: List<SlotSpan>, val command: Command?, val lang: Lang)
interface Understanding { fun interpret(text: String, langHint: Lang): Interpretation }

// nlu/ModelContract.kt — the artifact P3 produces and P2 loads
const val NLU_MAX_TOKENS = 48
data class ModelOutput(val intentLogits: FloatArray, val slotLogits: Array<FloatArray>, val langLogits: FloatArray) // slotLogits[token][tag]
interface IntentModel { fun run(inputIds: LongArray, attentionMask: LongArray): ModelOutput }
```

NLU artifact contract (documented in KDoc of `ModelContract.kt` and in `ml/README.md` later): directory `models/nlu/` containing `model.onnx` (inputs `input_ids` int64 `[1,48]`, `attention_mask` int64 `[1,48]`; outputs `intent_logits` `[1,50]`, `slot_logits` `[1,48,2*31+1]`, `lang_logits` `[1,2]`), `vocab.tsv` (`piece<TAB>score` per line, line index = token id; ids 0–3 = `<pad>`, `<s>`, `</s>`, `<unk>`), `labels.json` (`{"intents":[…IntentCatalog.ids], "slots":["O","B-contact","I-contact",…], "langs":["EN","ES"]}`).

- [ ] **Step 1:** Write `IntentCatalogTest`:
  - `ids` has 50 unique entries.
  - Every id equals the `intent` of exactly one `Command` subclass (reflect over `Command::class.sealedSubclasses`, instantiate data objects and read the property; for data classes compare against a hand-written map of class → id).
  - `slotTypes.size == 31`.
  - `requiredSlots` is defined for every id and only names slot types in `slotTypes`.
- [ ] **Step 2:** Write `NoAndroidTest`: every class on `:brain`'s runtime classpath under `io.github.sodafmr.cardo.brain` loads without any `android.*` class.
- [ ] **Step 3:** Run `./gradlew :brain:test` → FAIL.
- [ ] **Step 4:** Implement the files exactly as in the Interfaces block. `requiredSlots` values:
  - `call.contact` {contact}, `call.number` {phone_number}
  - `message.send` {contact, message_body} (a `phone_number` slot satisfies `contact`), `message.reply` {message_body}
  - `alarm.set` {datetime}, `timer.set` {duration}
  - `reminder.create` {task, datetime}, `note.create` {note_text}
  - `calendar.create` {event_title, datetime}
  - `app.open` {app_name}
  - `calc.evaluate` {expression}, `convert.units` {quantity, unit_from, unit_to}
  - `web.search` {search_query}, `maps.search` {search_query}, `maps.directions` {destination}
  - every other intent: empty.
- [ ] **Step 5:** Run `./gradlew :brain:test` → PASS.
- [ ] **Step 6:** Commit `feat(brain): shared command, outcome, response and dialog schema`.

### Task 4: Privacy check on the merged manifest

**Files:**
- Create: `gradle/privacy-check.gradle.kts` (applied in `app/build.gradle.kts`)
- Modify: `app/src/main/AndroidManifest.xml` (add `<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>`)

**Interfaces:**
- Produces: task `:app:verifyNoInternet<Variant>` for every variant, wired into `check` and into `assemble<Variant>` (finalizedBy is not enough — the APK task must depend on it).

- [ ] **Step 1:** Implement the task using AGP's variant API (`androidComponents.onVariants`), reading the variant's merged manifest artifact (`SingleArtifact.MERGED_MANIFEST`). Fail with `"INTERNET permission found in merged manifest of <variant>"` if it contains `android.permission.INTERNET` (outside a `tools:node="remove"` line, which never survives merging anyway).
- [ ] **Step 2:** Verify Review Focus 3: temporarily delete the `tools:node="remove"` line from the app manifest and add `<uses-permission android:name="android.permission.INTERNET"/>` to `voice/src/main/AndroidManifest.xml` (simulating a library that requests it). `./gradlew :app:assembleDebug` → FAIL with the message. Revert both → BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit `build: fail any build whose merged manifest requests INTERNET`.

### Task 5: Assistant shell Android will bind

**Files:**
- Create under `app/src/main/kotlin/io/github/sodafmr/cardo/assistant/`: `CardoVoiceInteractionService.kt`, `CardoSessionService.kt`, `CardoSession.kt`, `CardoRecognitionService.kt`
- Create: `app/src/main/res/xml/cardo_voice_interaction.xml`, `app/src/main/res/xml/cardo_recognition.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `scripts/emulator.sh`, `scripts/set-assistant.sh`

**Interfaces:**
- Produces: component names `io.github.sodafmr.cardo/.assistant.CardoVoiceInteractionService`, `.assistant.CardoSessionService`, `.assistant.CardoRecognitionService`; log tag `Cardo`; `onReady()` logs `"assistant ready"`, `CardoSession.onShow()` logs `"session shown"`.
- Produces: `scripts/emulator.sh [start|stop]` — creates AVD `cardo-api37` from `system-images;android-37.0;google_apis;x86_64` if missing (`hw.audioInput=yes`, 4 GB RAM, 2 cores), starts it headless (`-no-window -no-snapshot-save -gpu swiftshader_indirect`) and waits for `sys.boot_completed`.
- Produces: `scripts/set-assistant.sh` — `adb shell cmd role add-role-holder android.app.role.ASSISTANT io.github.sodafmr.cardo` then prints `dumpsys voiceinteraction` lines naming the active service.

- [ ] **Step 1:** Declare the services in the manifest:
  - VIS: permission `android.permission.BIND_VOICE_INTERACTION`, intent action `android.service.voice.VoiceInteractionService`, meta-data `android.voice_interaction` → `cardo_voice_interaction.xml`. That XML sets `sessionService`, `recognitionService`, `settingsActivity=.MainActivity`, `supportsAssist=true`, `supportsLaunchVoiceAssistFromKeyguard=true`.
  - Session service: permission `BIND_VOICE_INTERACTION`.
  - Recognition service: intent action `android.speech.RecognitionService`, meta-data `android.speech` → `cardo_recognition.xml`.
  - Declare `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`.
  - The recognition service stub returns `SpeechRecognizer.ERROR_CLIENT` for now (P7 replaces it).
- [ ] **Step 2:** Start the emulator (`scripts/emulator.sh start`), `./gradlew :app:installDebug`, run `scripts/set-assistant.sh`. Expected: dumpsys shows `io.github.sodafmr.cardo/.assistant.CardoVoiceInteractionService` as the active service and `adb logcat -d -s Cardo` contains `assistant ready`.
- [ ] **Step 3:** `adb shell cmd voiceinteraction show` (or `adb shell input keyevent KEYCODE_ASSIST` if that subcommand is unavailable) → logcat contains `session shown`. Record the exact working command in `scripts/set-assistant.sh` comments.
- [ ] **Step 4:** Commit `feat(app): assistant shell bindable as default digital assistant`.

### Task 6: CI and GitHub repository

**Files:**
- Create: `.github/workflows/ci.yml`
- Modify: `README.md` (build instructions: JDK 17, Android SDK path, `./gradlew build`)

**Interfaces:**
- Produces: workflow `ci` on push/PR to `main`: JDK 17 (temurin), `android-actions/setup-android`, cache `~/.cache/cardo-artifacts` and Gradle, run `./gradlew build --stacktrace` (includes `check`, the privacy task, unit tests).

- [ ] **Step 1:** Write the workflow.
- [ ] **Step 2:** `gh repo create SodaFMR/cardo --private --source . --remote origin --description "Private, offline voice assistant for Android — like iPhone 13 Siri, nothing leaves your phone"` then `git push -u origin main`.
- [ ] **Step 3:** `gh run watch` on the triggered run → success. If it fails only due to CI environment differences, fix and push; record any CI-only workaround in a workflow comment.
- [ ] **Step 4:** Commit (if anything changed) `ci: build and test on push`.
