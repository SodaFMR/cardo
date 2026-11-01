# Cardo P7 — Integration, End-to-End and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the five tracks into one working assistant. "Cardo" heard by the always-on listening service opens the panel over any app or the lock screen; the command is understood, executed, shown and spoken. This is proven end to end on the emulator with injected audio, hardened, documented and released as a signed APK on a now-public GitHub repo.

**Architecture:**
- `CardoGraph` (manual DI in `CardoApp`) builds the brain, voice pipeline, executor and repositories once per process.
- `ListeningService` (microphone foreground service) owns the pipeline while waiting for the wake word and asks `CardoVoiceInteractionService` to `showSession()` on a hit.
- `CardoSession` hosts the Compose `AssistantPanel`. A session-scoped `AssistantController` connects pipeline events → `CardoDialog` → `LockPolicyExecutor(CardoActionExecutor)` → `ResponseWriter` → speech and panel state.

**Tech Stack:** Everything from P1–P6, plus AndroidX test/UiAutomator for instrumented end-to-end tests, the `cardo-api37` emulator, `gh`.

**Spec:** `docs/superpowers/specs/2026-11-01-cardo-v1-design.md` (all sections; §4 runtime flow and listening policy, §9, §10, §11, §12, §15 are the acceptance list)

## Global Constraints

- Every P1–P6 global constraint still holds: no `INTERNET`, no AI attribution trailers, package names, minSdk 31 / targetSdk 37, colours, motion values, confidence and confirmation rules.
- Runtime timings:
  - follow-up listening window: 6 s without wake word
  - the panel folds away 2.5 s after Cardo stops speaking (when not listening for a follow-up)
  - a false wake folds away silently within 4 s
  - end of speech after 600 ms of silence; 8 s capture cap
- Voice feedback `AUTOMATIC`: speak when the session was opened by voice or the assistant gesture; stay silent when typing, or when the ringer is silent/vibrate.
- Listening auto-pause: during calls (`AudioManager` mode `IN_CALL`/`IN_COMMUNICATION`); while another app's recording silences ours (`AudioManager.AudioRecordingCallback` reports Cardo's config as silenced); below 10% battery unless charging (if `lowBatteryPause`). Resume automatically.
- Modes: `ALWAYS`; `SCREEN_ON_OR_CHARGING`; `CHARGING_ONLY`; `OFF` (no service, no notification; the assistant gesture still works).
- Models in memory: wake, VAD, ASR and NLU stay loaded while `ListeningService` runs. TTS loads on first use and stays loaded. Everything is released on `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL or higher)` and reloaded on next use.
- Backup: `android:allowBackup="false"` and `dataExtractionRules` excluding every domain.
- Exported components: only the assistant service, session service, recognition service, notification listener, `MainActivity`, the boot/time receivers, and in debug builds only the audio-injection receiver. All are protected by their system permission where one exists.
- Release signing: keystore at `~/.cardo-keys/cardo-release.jks` with `~/.cardo-keys/keystore.properties`. Both live outside the repo and are never committed or uploaded. Release builds with R8, keeping sherpa-onnx and `ai.onnxruntime` JNI classes.
- The repo becomes public only in the last task, after the final review is clean.

## Review Focus

1. "Cardo" heard while a session is already open, or while Cardo is speaking — ignored; no second session.
2. A phone call starts (or the screen turns off) mid-session — speech stops and the session closes; listening resumes afterwards.
3. The process is killed by the OEM or the system — when Android rebinds the assistant service, listening restarts without user action.
4. Listening mode `OFF` — no foreground service and no notification, yet the hold-power/home gesture still opens a working session that captures from the mic.
5. Locked phone: "read my messages" with the default rule `UNLOCK` → unlock prompt, then the original command runs after unlocking; "set a timer" runs immediately.

---

### Task 1: Merge the tracks

**Files:**
- Modify: whatever conflicts; delete `actions/.../contacts/MatcherShim.kt` if present and switch to `brain`'s `EntityMatcher`; un-`@Ignore` any "needs P2" tests

**Interfaces:**
- Consumes: branches `p2-brain`, `p3-nlu-model`, `p4-voice`, `p5-actions`, `p6-ui` (merged into `main` by the controller before this plan starts; this task fixes the combined build).

- [ ] **Step 1:** `./gradlew build` on the combined tree. Fix compile and test breakages caused by the combination only (no behaviour changes).
- [ ] **Step 2:** Make `GoldenParityTest` mandatory: remove its `assumeTrue` now that `models/nlu/*golden*` exist. It must pass. A mismatch means the Kotlin tokenizer or decoder differs from Python — fix the Kotlin side to match the Python reference Viterbi.
- [ ] **Step 3:** `./gradlew build` → BUILD SUCCESSFUL with zero ignored tests.
- [ ] **Step 4:** Commit `chore: integrate brain, model, voice, actions and UI tracks`.

### Task 2: Application graph and model packaging

**Files:**
- Create: `app/src/main/kotlin/io/github/sodafmr/cardo/CardoGraph.kt`, `app/src/main/kotlin/.../nlu/NluLoader.kt`, `app/src/main/kotlin/.../actions/AndroidActionSeams.kt` (real `DeviceState`, `ActionSettings` from `SettingsRepository`)
- Modify: `CardoApp.kt`, `app/build.gradle.kts` (copy `models/nlu/{model.onnx,vocab.tsv,labels.json}` into generated assets under `nlu/`)
- Test: `app/src/test/kotlin/.../CardoGraphTest.kt`, `app/src/androidTest/kotlin/.../NluOnDeviceTest.kt`

**Interfaces:**
- Produces: `class CardoGraph(app: Application)` with lazy `clock`, `settings: SettingsRepository`, `engines: Engines`, `pipeline: CardoVoicePipeline`, `understanding: Understanding`, `writer: ResponseWriter`, `executor: ActionExecutor` (`LockPolicyExecutor(CardoActionExecutor(...))` from Task 4), `reminders`, `notes`, `contacts`, `apps`; `fun newDialog(): DialogManager`; `fun warmUp()` (loads wake/VAD/ASR/NLU off the main thread); `fun release()`.
- `NluLoader.load(context): Understanding` → `ModelUnderstanding` from `assets/nlu`. If loading fails, log `Cardo` error and return `KeywordUnderstanding`; the startup integrity check (Task 9) surfaces the failure.

- [ ] **Step 1:** Write tests: `CardoGraphTest` (Robolectric) builds the graph and `newDialog()` with a fake engine factory. `NluOnDeviceTest` (emulator) loads the real model from assets: "pon un temporizador de diez minutos" → `SetTimer(10m)` with confidence ≥ 0.85; p95 latency over 100 runs is logged.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(app): application graph with on-device NLU from assets`.

### Task 3: Listening service and policy

**Files:**
- Create: `app/src/main/kotlin/.../listening/ListeningService.kt`, `listening/ListeningPolicy.kt`, `listening/DeviceSignals.kt`, `listening/ListeningNotification.kt`, `assistant/AssistantBridge.kt`
- Modify: `assistant/CardoVoiceInteractionService.kt` (start the service from `onReady()`; `onShutdown()` stops it; expose `showSession` through `AssistantBridge`), manifest (`foregroundServiceType="microphone"`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
- Test: `listening/ListeningPolicyTest.kt` (pure), `listening/ListeningServiceTest.kt` (Robolectric)

**Interfaces:**
- Produces:
  - `data class Signals(val screenOn: Boolean, val charging: Boolean, val batteryPct: Int, val inCall: Boolean, val micSilenced: Boolean)`
  - `object ListeningPolicy { fun decide(mode: ListeningMode, lowBatteryPause: Boolean, s: Signals): Decision }` with `sealed interface Decision { data object Listen; data class Pause(val reason: PauseReason); data object Off }` and `enum class PauseReason { IN_CALL, MIC_BUSY, LOW_BATTERY, SCREEN_OFF, NOT_CHARGING }`.
  - `class ListeningService : Service`. It starts in the foreground with type microphone and a low-importance notification ("Listening for "Cardo"" / "Escuchando «Cardo»", with a Pause/Resume action). It observes `Signals` and prefs, and drives `pipeline.start/pause/resume/stop`. On `WakeDetected` it calls `AssistantBridge.showSession(wake = true)`.
  - `object AssistantBridge { fun attach(vis: CardoVoiceInteractionService); fun detach(); fun showSession(wake: Boolean): Boolean; var sessionOpen: Boolean }`

- [ ] **Step 1:** Write `ListeningPolicyTest` covering every mode × signal combination that matters, including Review Focus 4 (`OFF` → `Off`). Write `ListeningServiceTest`: start posts the foreground notification with the microphone type; a `Pause` decision calls `pipeline.pause()`; the notification Pause action toggles prefs; a wake event with `sessionOpen = true` is ignored (Review Focus 1).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. The service is started from `onReady()` — legal from the background because Cardo provides the `VoiceInteractionService`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(app): always-on listening service with policy and auto-pause`.

### Task 4: Session, controller and lock policy

**Files:**
- Create: `app/src/main/kotlin/.../assistant/AssistantController.kt`, `assistant/LockPolicyExecutor.kt`, `assistant/SessionWindow.kt`, `assistant/SessionLifecycleOwner.kt`, `assistant/Earcons.kt`, `assistant/UnlockActivity.kt`, `assistant/PermissionActivity.kt`
- Modify: `assistant/CardoSession.kt`, `assistant/CardoSessionService.kt`, manifest (two translucent, `showWhenLocked` helper activities, not exported)
- Test: `assistant/AssistantControllerTest.kt` (fakes + virtual time), `assistant/LockPolicyExecutorTest.kt`

**Interfaces:**
- Produces:
  - `class AssistantController(graph: CardoGraph, speaker: SpeechSynthesizer, signals: () -> Signals, prefs: () -> CardoPrefs, scope: CoroutineScope)` with `val ui: StateFlow<PanelUiState>`, `val level: StateFlow<Float>`, `fun open(source: OpenSource)` (`enum class OpenSource { WAKE, GESTURE, TYPED }`), `fun onTyped(text: String)`, `fun onUiAction(a: UiAction)`, `fun close()`.
  - Behaviour:
    - `WAKE` waits for the pipeline's `Transcribed(includesWake=true)`. If `WakeWord.leading(normalized)` is null → silent false-wake close.
    - `GESTURE` calls `pipeline.captureNow()` (if `ListeningService` isn't running, it opens a `MicAudioSource` itself — the session window is visible, so mic access is allowed).
    - The panel phase follows the pipeline state.
    - Turns go to the dialog: `Turn.Run` → executor (UI `Thinking`) → `dialog.onResult` → `Turn.Reply` → UI `Result` + speech per voice-feedback rules.
    - Wake detection is paused while speaking (`pipeline.pause()`).
    - `listen=true` → `captureNow()` with the 6 s window; else close 2.5 s after speech ends.
    - `close()` stops speech and resumes the pipeline if policy says `Listen`.
    - Success outcomes bump `successPulse`.
  - `class LockPolicyExecutor(delegate: ActionExecutor, prefs: () -> CardoPrefs, keyguard: () -> Boolean) : ActionExecutor`. Intent → `LockGroup`:
    - `call.*` → CALLS
    - `message.*`, `email.*` → MESSAGES
    - `calendar.*` → CALENDAR
    - `reminder.*`, `note.*` → REMINDERS_NOTES
    - `app.open`, `camera.open` (unless `PHOTO`/`SELFIE`, which use the secure camera), `maps.*`, `web.search`, `weather.query`, `media.play` → APPS
    - everything else → always allowed.

    Locked + `UNLOCK` → `NeedsUnlock(command)`; locked + `CONFIRM` → `NeedsConfirmation` unless confirmed.
  - `UiAction.Unlock` → `UnlockActivity` (`requestDismissKeyguard`); on success the controller re-runs the pending command (Review Focus 5). `UiAction.Grant(p)` → `PermissionActivity` (a runtime request, or the exact special-access settings screen); on return the controller retries the command.
  - `CardoSession`:
    - Builds the window: full-screen, transparent, edge-to-edge, show over keyguard, turn screen on. Uses `setBackgroundBlurRadius` + `FLAG_BLUR_BEHIND` when `WindowManager.isCrossWindowBlurEnabled` (sets `blurBehind`).
    - Hosts `PanelTheme { AssistantPanel(...) }` in a `ComposeView` with lifecycle/saved-state owners.
    - Plays earcons (wake/done) and haptics per prefs.
    - Closes on `onHide`, screen-off and call start (Review Focus 2).

- [ ] **Step 1:** Write `AssistantControllerTest` scenarios:
  - wake → transcript "cardo pon un temporizador de diez minutos" → executor receives `SetTimer(10m)` → UI `Result` with a `Timer` card → speaks ES → closes after 2.5 s
  - wake → transcript "ricardo…" → closes silently, no executor call
  - a confirm flow keeps listening 6 s and closes on silence
  - typed input never speaks
  - a second wake while open is ignored
  - a call starting closes the session

  Write `LockPolicyExecutorTest`: the mapping table and the three rules.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(app): assistant session, controller and lock-screen policy`.

### Task 5: Main app wiring

**Files:**
- Create: `app/src/main/kotlin/.../ui/AppUiDeps.kt`
- Modify: `MainActivity.kt`, manifest
- Test: `app/src/androidTest/kotlin/.../OnboardingFlowTest.kt` (emulator, UiAutomator for system dialogs)

**Interfaces:**
- Produces: `AppUiDeps : UiDeps` backed by `CardoGraph`:
  - assistant-role status via `RoleManager.isRoleHeld(ROLE_ASSISTANT)`
  - open assistant settings: `Settings.ACTION_VOICE_INPUT_SETTINGS`, falling back to `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS`
  - battery: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, falling back to the app details screen
  - "try it" runs a foreground pipeline on a `MicAudioSource` while the onboarding screen is visible
  - settings, reminders and notes flows; voices from `ModelCatalog`; calendars from `CalendarRepository`
  - "Test wake" in settings opens a session via `AssistantBridge`

- [ ] **Step 1:** Write `OnboardingFlowTest`: fresh install → onboarding; grant mic via the system dialog; the assistant step's button opens settings (assert the settings package is in the foreground); finishing lands on home.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(app): wire onboarding, home, settings, reminders and notes to the engine`.

### Task 6: End-to-end on the emulator

**Files:**
- Create: `app/src/debug/kotlin/.../debug/InjectAudioReceiver.kt`, `app/src/debug/AndroidManifest.xml`, `app/src/main/kotlin/.../E2eLog.kt` (structured `CardoE2E` log lines, a no-op in release), `scripts/e2e/run.sh`, `scripts/e2e/scenarios.tsv`, `scripts/e2e/fixtures/` (WAVs synthesized with the held-back bake-off voices)
- Create: `docs/benchmarks/e2e.md`

**Interfaces:**
- `InjectAudioReceiver` (debug only, exported with `android:permission="android.permission.DUMP"` so only adb can send it) feeds `files/inject/<name>.wav` into the live pipeline through `WavAudioSource(realtime=true)`, replacing the mic until the file ends.
- `E2eLog` emits `CardoE2E {"event":"wake"|"transcript"|"interpretation"|"run"|"result"|"reply"|"close", ...}`.
- `scripts/e2e/run.sh` steps:
  1. Boot the emulator; install debug; set the assistant role.
  2. `pm grant` runtime permissions; enable notification access with `cmd notification allow_listener`.
  3. Insert test contacts with `content insert`; set the clock zone.
  4. For each scenario row (`name<TAB>wav<TAB>expected_event_regex<TAB>side_effect_check`): inject, wait for `close`, assert the log regex and the side effect.
- Side-effect checks:
  - Clock: the `com.android.deskclock`/Clock activity resumed with the alarm/timer extras.
  - `dumpsys alarm`: a Cardo reminder alarm is present.
  - Dialer: `ACTION_CALL` to the expected number.
  - SMS: a confirmation card on screen (`uiautomator dump`), then a "yes" injection → the SMS shows in the emulator's outbox.
  - App open: the target package is in the foreground.
  - Web search: a hand-off intent fired.
  - Calc: the reply log contains the result.
  - False wake ("Ricardo") produces `close` with no `run`.
- Scenarios cover, in EN and ES: alarm, timer, reminder, call contact (2 numbers → choose), call number (confirm), SMS (confirm), open app, web search, weather hand-off, directions, calculation, conversion, time in city, battery, flashlight (expects `NOT_SUPPORTED` on the emulator), media pause, note, calendar create/query, read messages (posted via `cmd notification post`), false wake, wake-only "Cardo" → "Yes?", follow-up answer.
- `docs/benchmarks/e2e.md` records:
  - pass/fail per scenario
  - latency per stage from the log timestamps (wake→panel, end-of-speech→transcript, transcript→run, run→reply)
  - re-measured end-to-end intent accuracy on the full synthesized fixture set with the chosen ASR, compared with the bake-off runner-up. If the runner-up wins by > 2 points, swap it in `artifacts.lock.json` and re-run.

- [ ] **Step 1:** Write `scenarios.tsv` and `run.sh`; run → failures expected first.
- [ ] **Step 2:** Fix integration bugs until every scenario passes. Each fix gets a unit/instrumented test in the module that owned the bug.
- [ ] **Step 3:** Write `docs/benchmarks/e2e.md`.
- [ ] **Step 4:** Commit `test(e2e): emulator end-to-end suite with injected audio`.

### Task 7: Robustness and privacy hardening

**Files:**
- Modify: manifest (`allowBackup=false`, `dataExtractionRules`), `CardoGraph.kt` (`onTrimMemory` release), `CardoApp.kt` (startup integrity check of model asset sizes and sha256 against a generated `assets/models.sha256`)
- Create: `app/src/test/kotlin/.../ManifestAuditTest.kt` (parses the merged release manifest from the build output: exported set equals the allowed list; no INTERNET; `allowBackup=false`), `app/src/androidTest/kotlin/.../ProcessDeathTest.kt`

- [ ] **Step 1:** Write `ManifestAuditTest` and `ProcessDeathTest` (`am kill` the app process → within 20 s the assistant service is rebound and `ListeningService` runs again; Review Focus 3).
- [ ] **Step 2:** Run → FAIL where unimplemented.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `fix(app): backup exclusion, manifest audit, memory release and restart resilience`.

### Task 8: Accessibility and copy pass

**Files:**
- Modify: any UI strings and composables flagged

- [ ] **Step 1:** On the emulator with TalkBack enabled (`settings put secure enabled_accessibility_services …`), walk onboarding, home, settings and the panel with `uiautomator dump`. Every actionable node must have a label, and focus order must follow visual order. Record the findings in `docs/benchmarks/a11y.md`.
- [ ] **Step 2:** Read every EN and ES string (app + Phrasebook) in one pass for tone, consistency ("Cardo" capitalization, «» quotes in ES, “” in EN) and length. Fix as needed.
- [ ] **Step 3:** Re-run `./gradlew build` and the e2e suite → green.
- [ ] **Step 4:** Commit `polish: accessibility labels and bilingual copy pass`.

### Task 9: Docs and store metadata

**Files:**
- Create/Modify: `README.md`, `PRIVACY.md`, `CONTRIBUTING.md`, `docs/device-test-checklist.md`, `models/LICENSES.md`, `app/src/main/assets/licenses/index.json`, `fastlane/metadata/android/{en-US,es-ES}/{title.txt,short_description.txt,full_description.txt,images/phoneScreenshots/*.png}`

- [ ] **Step 1:** Capture screenshots from the emulator: home, panel listening, panel with timer card, message confirmation, onboarding privacy, settings — in EN and ES.
- [ ] **Step 2:** Write the docs.
  - `README`: what Cardo is, the privacy promise, feature list (spec §3 v1), install from Releases, how to set Cardo as the assistant, battery tips per OEM, an honest "verified on" table (emulator API 37 only so far), build instructions, licence.
  - `PRIVACY`: every permission and why, what is stored where, what never happens.
  - `device-test-checklist`: spec §11 real-device items as checkboxes per OEM.
- [ ] **Step 3:** Commit `docs: README, privacy, contributing, device checklist and store metadata`.

### Task 10: Release

**Files:**
- Modify: `app/build.gradle.kts` (signing from `~/.cardo-keys/keystore.properties` when present; R8 with keep rules), `app/proguard-rules.pro`, `.github/workflows/ci.yml` (also build the unsigned release and report APK size)

- [ ] **Step 1:** Generate the keystore if absent: `keytool -genkeypair -v -keystore ~/.cardo-keys/cardo-release.jks -alias cardo -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Cardo"`, with a random password written to `~/.cardo-keys/keystore.properties` (mode 600).
- [ ] **Step 2:** `./gradlew :app:assembleRelease`. Check that the APK is ≤ 200 MB, `scripts/check-apk-libs.sh` passes, `apksigner verify` is OK, and `aapt2 dump permissions` lacks INTERNET. Install it on the emulator and re-run the e2e smoke subset (timer, call confirm, open app) on the release build.
- [ ] **Step 3:** Commit `build: signed, minified release build`.
- [ ] **Step 4:** (The controller does this after the final review is clean.) Tag `v1.0.0` and `git push --tags`. Then `gh release create v1.0.0 app-release.apk SHA256SUMS --title "Cardo 1.0" --notes-file docs/release-notes/1.0.0.md`, then `gh repo edit SodaFMR/cardo --visibility public --accept-visibility-change-consequences`.
