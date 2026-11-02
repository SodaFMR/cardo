# Cardo v1 Design

Date: 2026-11-01
Status: Approved direction (owner delegated remaining decisions)
Supersedes: [2026-06-26-cardo-mvp-design.md](2026-06-26-cardo-mvp-design.md)

## 1. Goal

Cardo is a private voice assistant for Android that handles everyday phone tasks by voice, fast and fully private. It wakes when you say "Cardo", opens a panel from the bottom of the screen with an animated cardo (thistle) flower, understands natural English and Spanish, acts in well under a second, and never sends anything off the phone.

The public promise, in one line: **Cardo has no internet permission. Your voice, contacts and requests never leave your phone.**

## 2. Decisions and why

| Decision | Choice | Why |
| --- | --- | --- |
| Distribution | Open source on GitHub first (Apache-2.0), F-Droid next, Google Play maybe later | Owner's choice; open code makes the privacy claim checkable |
| Platform | Native Kotlin, Jetpack Compose, minSdk 31 (Android 12), targetSdk = latest stable, `arm64-v8a` release (plus `x86_64` in debug for the emulator) | Modern APIs (cross-window blur, splash screen, notification and foreground-service rules), covers the large majority of active phones |
| Package | `io.github.sodafmr.cardo` | F-Droid friendly; needs no domain |
| Wake word | "Cardo", with "Hey Cardo" and "Oye Cardo" also accepted | Owner's choice |
| Wake path | Cardo runs its own low-power keyword detector inside a microphone foreground service | Android's DSP hotword APIs (`AlwaysOnHotwordDetector`, `HotwordDetectionService`) are reserved for privileged, preinstalled assistants. Android explicitly allows "an app which provides the `VoiceInteractionService`" to start a microphone foreground service from the background ([docs](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions)) |
| Default listening | Always, including screen off; user can switch to "screen on + charging", "charging only" or "off" | Owner's choice; most hands-free |
| Speech engine | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) for keyword spotting, voice activity detection, speech recognition and speech synthesis; exact models chosen by the bake-off in section 6 | One Apache-2.0 offline runtime; models are swappable; lets the app ship without `INTERNET` |
| Understanding | A small trained intent + slot model (task-specific, about 30–40 MB, ONNX) plus exact rules for times/dates/numbers, personal entity matching and scripted dialog flows | iOS 15 Siri used task-specific ML understanding, not a chatbot. A chatbot LLM would add 400 MB+, 1–2 s per command and invented details |
| Languages | English and Spanish, detected per utterance; Cardo answers in the language it heard | Owner is bilingual; no language switching needed |
| Timers and alarms | Delegated to the phone's Clock app through `AlarmClock` intents | Ringing reliability matters more than voice-querying the remaining time |
| Reminders and notes | Owned by Cardo, stored on the phone | Works offline and fully under Cardo's control |
| Verification | Emulator plus host-side benchmarks now; real-device checklist recorded for later | No physical phone available yet |
| Repository | `github.com/SodaFMR/cardo`, private until the v1 release, then public | Avoids publishing half-built code; flipping to public is one command |

The June 2026 MVP doc assumed the OS hotword path and "no AI". Both are replaced: the OS path is not available to third-party apps, and natural-language understanding needs a small trained model.

## 3. Capability scope

Every capability works in English and Spanish. "Hand-off" means Cardo opens the user's own app with the request filled in, because the answer needs the internet.

### v1 (launch)

| Area | Capabilities | Example (EN / ES) |
| --- | --- | --- |
| Calls | Call contact (choose mobile/work/home), call number (confirm first), speakerphone, call back / redial, "who called me?" | "Call mom on speaker" / "Llama a mamá al móvil" |
| Messages | Dictate SMS, read back, confirm, send. Read new messages from any app (SMS, WhatsApp, Telegram, Signal…) and reply by voice through the notification (opt-in notification access). Start a WhatsApp/Telegram message with text prefilled. Compose email (hand-off to mail app) | "Text Ana I'm running late" / "Lee mis mensajes" |
| Clock | Set alarm (one-off, recurring, labelled), show alarms, "when is my next alarm?", set timer, show timers, stopwatch | "Wake me up at 7 tomorrow" / "Pon un temporizador de diez minutos" |
| Reminders | Time-based reminders with notification, list, cancel, done/snooze from notification | "Remind me at 5 to buy milk" / "Recuérdame mañana llamar a Pedro" |
| Notes | Create a note, open notes | "Note that the wifi code is 1234" / "Apunta que…" |
| Calendar | Create event, "what's on today/tomorrow?", "when's my next meeting?" | "What do I have tomorrow?" / "¿Qué tengo hoy?" |
| Apps | Open any installed app by name (fuzzy, accent-insensitive); take photo/selfie/video | "Open Spotify" / "Hazme un selfie" |
| Device | Flashlight, volume (media/ring/alarm; up/down/set/mute/max), silent/vibrate/normal, Do Not Disturb, brightness, battery level | "Turn on the flashlight" / "Pon el móvil en silencio" |
| Settings panels | Wi-Fi, Bluetooth, mobile data, airplane mode, dark mode, battery saver, location, NFC: Android forbids apps from toggling these, so Cardo opens the exact panel and says so | "Turn off Wi-Fi" → opens the Wi-Fi panel |
| Media | Play/pause/next/previous/stop, "play X (on Spotify)" | "Pause the music" / "Pon Rosalía en Spotify" |
| Local answers | Time, date, day of week, time in another city, maths (percentages, roots, powers), unit conversions | "What time is it in Tokyo?" / "¿Cuánto es el 15% de 80?" |
| Hand-offs | Web search, facts, weather, sports, currency, maps search, directions (driving/walking/transit) | "What's the weather in Madrid?" → opens weather/search with the query |
| Conversation | One short clarifying question when needed, confirmation for risky actions, follow-ups without repeating the wake word, "cancel/never mind", "say that again", "what can you do?", a handful of witty canned replies (who are you, thank you, hello) | "Never mind" / "Déjalo" |
| Input | Wake word, assistant gesture (hold power/home), type to Cardo | |
| Lock screen | Works over the lock screen with per-feature "allowed when locked" policy | |

### v1.1 (after launch)

Location reminders ("when I get home"), personal shortcuts ("Cardo, good night" → DND + alarm), offline English↔Spanish translation, "share this" for the current screen, optional on-device history of missed phrases to improve the model, F-Droid release.

### Out of scope

FaceTime, HomeKit, Find My, Apple Pay equivalents; general chat or knowledge answers generated on the phone; anything that needs Cardo itself to use the network.

## 4. Architecture

### Modules

| Module | Type | Responsibility | Depends on |
| --- | --- | --- | --- |
| `:brain` | Pure Kotlin/JVM | Command schema, understanding (model runner interface, tokenizer, decoding), rules for time/date/duration/number/phone/expressions, entity matching (contacts, apps, cities), dialog manager | nothing Android |
| `:voice` | Android library | `AudioSource` (microphone, WAV file), wake word detector, VAD, speech recognizer, speech synthesizer, `VoicePipeline` state machine | sherpa-onnx |
| `:actions` | Android library | One executor per domain, repositories for contacts, apps, call log, calendar, message notifications; reminders/notes storage (Room) | `:brain` schema |
| `:app` | Android app | `CardoVoiceInteractionService`, session service and session UI, `ListeningService` (foreground, microphone), `MessageListenerService` (notification listener), onboarding, home, settings, reminders, notes, design system, flower animation | all of the above |
| `ml/` | Python tooling (not shipped) | Dataset generation, model training/export, tokenizer golden files, wake/ASR/TTS bake-off, audio test-fixture synthesis | — |

The understanding model runs through ONNX Runtime (`onnxruntime-android`; the desktop `onnxruntime` jar in JVM tests, same `ai.onnxruntime` API). The voice engine uses sherpa-onnx's static-link AAR, which carries its own ONNX Runtime inside `libsherpa-onnx-jni.so`, so the two never clash over `libonnxruntime.so` (about 15 MB extra, accepted).

### Key interfaces (in `:brain` unless noted)

```kotlin
sealed interface Command          // one data class per intent, e.g. Command.SetTimer(duration, label)
data class Interpretation(val command: Command?, val intent: String, val confidence: Float,
                          val alternatives: List<Pair<String, Float>>, val language: Lang)
interface Understanding { fun interpret(text: String, ctx: DialogContext): Interpretation }
interface DialogManager { fun onUtterance(text: String): Turn; fun onActionResult(r: ActionResult): Turn }
sealed interface Turn { /* Execute(command) | Ask(prompt, expecting) | Confirm(prompt, command) | Say(response) | End */ }
interface ActionExecutor { suspend fun execute(c: Command): ActionResult }   // :actions
interface VoicePipeline { val state: StateFlow<VoiceState>; val level: StateFlow<Float> } // :voice
```

### Runtime flow

1. When the user selects Cardo as the default digital assistant, Android binds `CardoVoiceInteractionService`. In `onReady()` it starts `ListeningService` if wake listening is enabled for the current conditions. The same path runs after every reboot, because Android rebinds the assistant.
2. `ListeningService` owns one `AudioRecord` (16 kHz mono). Audio flows into a 2-second ring buffer and the keyword spotter. Nothing is written to disk.
3. On a keyword hit, the service asks the assistant service to `showSession()`. The session window appears over the current app or the lock screen, the screen turns on, and the flower blooms. The audio stream continues without a gap.
4. VAD finds the end of speech (about 600 ms of silence; 8 s hard cap). The ASR decodes the window that starts about 1 s **before** the keyword hit, so the transcript includes the wake word.
5. Second-stage wake check: the transcript must start with "cardo", "hey cardo" or "oye cardo" (fuzzy, accent-insensitive, and never "ricardo"). If it doesn't, it was a false wake: no action, and the panel folds away silently.
6. The wake word is stripped, `DialogManager` interprets the rest and returns a `Turn`. Executors run commands; results produce a spoken and displayed response in the detected language.
7. After a question, Cardo listens for the answer without a wake word (6 s window). Otherwise the panel folds away 2.5 s after it stops speaking, or immediately on "cancel".
8. The assistant gesture opens the same session and skips steps 2–5. Typing opens the keyboard inside the panel.

### Listening policy

- Modes: Always (default), Screen on + charging, Charging only, Off.
- Auto-pause: during phone calls, while another app holds the microphone, and below 10% battery unless charging (setting, default on). It resumes on its own.
- Sensitivity: Low / Normal / High, mapped to keyword thresholds calibrated in the bake-off.
- A persistent, low-importance notification shows "Listening for 'Cardo'" with a Pause action. Android requires it and it doubles as an honest indicator.

## 5. Understanding ("Brain")

### Intent catalog (v1)

`call.contact`, `call.number`, `call.last`, `call.history`, `message.send`, `message.read`, `message.reply`, `email.compose`, `alarm.set`, `alarm.show`, `alarm.next`, `timer.set`, `timer.show`, `stopwatch.start`, `stopwatch.stop`, `reminder.create`, `reminder.list`, `reminder.cancel`, `note.create`, `note.show`, `calendar.create`, `calendar.query`, `app.open`, `camera.open`, `device.flashlight`, `device.volume`, `device.ringer`, `device.dnd`, `device.brightness`, `device.panel`, `device.battery`, `media.control`, `media.play`, `info.time`, `info.date`, `calc.evaluate`, `convert.units`, `web.search`, `weather.query`, `maps.search`, `maps.directions`, `cardo.help`, `cardo.cancel`, `cardo.yes`, `cardo.no`, `cardo.repeat`, `chitchat.greeting`, `chitchat.thanks`, `chitchat.identity`, `out_of_scope`.

Slot types: `contact`, `phone_number`, `number_label`, `message_body`, `messaging_app`, `email_subject`, `datetime`, `duration`, `recurrence`, `label`, `task`, `note_text`, `event_title`, `location`, `app_name`, `camera_mode`, `on_off`, `level`, `volume_stream`, `ringer_mode`, `setting`, `media_action`, `media_query`, `place`, `expression`, `quantity`, `unit_from`, `unit_to`, `search_query`, `destination`, `travel_mode`.

### Model

- Joint intent classifier + BIO slot tagger on a small multilingual transformer encoder (start from `intfloat/multilingual-e5-small` or `paraphrase-multilingual-MiniLM-L12-v2`, whichever wins on the held-out set). The vocabulary is trimmed to English and Spanish tokens, then the model is exported to ONNX with int8 quantization. Targets: ≤ 40 MB and ≤ 30 ms per utterance on a mid-range phone CPU (host proxy: ≤ 8 ms single thread).
- The SentencePiece tokenizer is implemented in Kotlin and checked against golden token ids generated by the Python tokenizer for at least 2,000 sentences.
- Training data comes from `ml/` generators: template banks per intent with wide paraphrase variety (formal, casual, regional Spain/Latin America/UK/US), slot value lists, and ASR-style noise (lowercase, no punctuation, digits vs number words, fillers like "um"/"eh"/"porfa", dropped articles), plus out-of-scope negatives.
- The held-out evaluation set is written independently of the generator (separate author, at least 60 sentences per intent per language, phrasing the way real people talk) and never used for training.
- Targets on held-out: intent accuracy ≥ 95% per language, slot exact-match ≥ 90%.

### Rules and resolution

- Normalizers turn slot text into values: times ("half past seven", "las siete y media", "mediodía", "7 pm", "19:30"), relative dates ("pasado mañana", "next Monday", "el lunes que viene"), durations, recurrence, cardinal/ordinal numbers in words or digits, phone numbers in digit groups, spoken maths expressions, units. These are deterministic and table-tested.
- Entity matching: contacts by display name, nickname and relationship words ("mom", "mamá", "mi jefe" when a contact field or user-set alias matches), using accent-insensitive normalization plus a phonetic key (EN Double Metaphone; ES phonetic rules) plus edit distance. The same approach covers app labels and a bundled offline city → time-zone table (≈ 600 cities with EN/ES names).
- Confidence policy: ≥ 0.85 acts (subject to confirmation rules); 0.50–0.85 asks between the top two ("Set a timer or an alarm?"); < 0.50 says what Cardo can't do and offers a web search.

### Dialog rules

- Ask one short question when a required slot is missing or ambiguous (which contact, which number, what time, what message, which app).
- Confirm before: calling a raw number, sending any message (read back recipient and body), creating an event with an inferred time, actions while locked that the policy marks as confirm-first.
- "Cancel / never mind / déjalo / olvídalo" ends any turn. "Say that again / repite" repeats the last response.

## 6. Voice engine and bake-off

The bake-off runs on the host with sherpa-onnx's Python package and produces `docs/benchmarks/voice-bakeoff.md`.

**Test audio.** Synthesized with many distinct TTS speakers in English and Spanish, with added noise (café, street, car, TV at 20/10/5 dB SNR) and speed/pitch variation:
- Positives: "Cardo…", "Hey Cardo…", "Oye Cardo…", each followed by commands.
- Hard negatives: "Ricardo", "cardio", "car door", "cardado", "guardo", "Leonardo", "card", "cuadro", "cardinal".
- General negative speech: at least 10 hours, assembled from openly licensed corpora plus synthesized conversation.

**Wake word.**
- Candidates: sherpa-onnx open-vocabulary keyword spotting (English and zh-en zipformer KWS models, keywords tokenized for "CARDO"/"HEY CARDO"/"OYE CARDO"). If needed, a custom openWakeWord-style "cardo" model trained on synthetic speech.
- Targets: recall ≥ 95% at 10 dB SNR, and ≤ 1 false accept per 10 hours of negative speech after the second-stage check.
- CPU cost is measured and recorded.

**Speech recognition.**
- Candidates: every sherpa-onnx model available at bake-off time that covers both English and Spanish (at least Whisper tiny/base/small int8, and Parakeet-TDT multilingual if size allows), plus Vosk small EN/ES as a baseline.
- Scoring: WER, end-to-end intent accuracy (ASR output → Brain), latency for a 4 s utterance on 2 host threads, and model size.
- Selection: the smallest model whose end-to-end intent accuracy is within 2 points of the best, with a host latency of ≤ 250 ms (estimated ≤ 700 ms on a mid-range phone).

**Speech synthesis.**
- Piper/VITS voices through sherpa-onnx, with one natural voice per language at launch (a second voice optional). Voices are chosen on naturalness, permissive licence (CC0/CC-BY/MIT/Apache), and time to first audio ≤ 150 ms on the host.
- Kokoro is evaluated as a higher-quality alternative if its phone latency is acceptable.

**Selection outcome.** Models are pinned in `models/models.lock.json` (URL, SHA-256, licence, size). A Gradle task downloads and verifies them into the build's assets; nothing is fetched at runtime. Cardo's own trained understanding model is committed (or attached to a release if it exceeds 50 MB).

## 7. Actions (Android implementation)

| Domain | Mechanism | Permission |
| --- | --- | --- |
| Calls | `TelecomManager.placeCall` (speaker extra), contact lookup via `ContactsContract` | `CALL_PHONE`, `READ_CONTACTS` |
| Call history | `CallLog.Calls` | `READ_CALL_LOG` |
| SMS | `SmsManager` multipart send after confirmation | `SEND_SMS` |
| Read/reply messages | `NotificationListenerService`, `MessagingStyle` parsing, reply via `RemoteInput`; kept in memory only | Notification access (opt-in) |
| WhatsApp/Telegram start | Reply through the existing notification thread if present, else deep link with prefilled text | — |
| Email | `ACTION_SENDTO mailto:` | — |
| Alarms/timers | `AlarmClock.ACTION_SET_ALARM` / `ACTION_SET_TIMER` with `EXTRA_SKIP_UI`, `ACTION_SHOW_ALARMS` / `ACTION_SHOW_TIMERS`, `AlarmManager.getNextAlarmClock()` | `SET_ALARM` |
| Stopwatch | Cardo ongoing notification with chronometer | `POST_NOTIFICATIONS` |
| Reminders | Room + `AlarmManager.setExactAndAllowWhileIdle` (falls back to a 1-minute window if exact alarms aren't granted), Done/Snooze actions, rescheduled on boot | `SCHEDULE_EXACT_ALARM`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` |
| Notes | Room | — |
| Calendar | `CalendarContract` insert into the chosen writable calendar; `Instances` query | `READ_CALENDAR`, `WRITE_CALENDAR` |
| Apps | Launcher activities via `PackageManager` with `<queries>` for `MAIN/LAUNCHER` | — |
| Camera | `INTENT_ACTION_STILL_IMAGE_CAMERA(_SECURE)`, front-camera extras, `INTENT_ACTION_VIDEO_CAMERA` | — |
| Flashlight | `CameraManager.setTorchMode` | — |
| Volume/ringer/DND | `AudioManager`, `NotificationManager.setInterruptionFilter` | Notification policy access (opt-in) |
| Brightness | `Settings.System.SCREEN_BRIGHTNESS` | `WRITE_SETTINGS` (opt-in) |
| Panels | `Settings.Panel.*` and specific `Settings.ACTION_*` screens | — |
| Battery | `BatteryManager` | — |
| Media | `AudioManager.dispatchMediaKeyEvent`; `MEDIA_PLAY_FROM_SEARCH` with optional target package | — |
| Search/weather/facts | `Intent.ACTION_WEB_SEARCH`; fallback opens the browser on the user's chosen search engine (DuckDuckGo default) | — |
| Maps | `geo:` URI; Google Maps/OsmAnd navigation URIs when installed | — |

Permissions are requested in context the first time a feature needs them, never in bulk. Every denial has a spoken, one-line explanation and a button that opens the right settings screen.

## 8. Look and motion

### The panel

- A bottom-anchored session window. Where the device supports cross-window blur it uses real frosted glass (`FLAG_BLUR_BEHIND`); otherwise a deep gradient scrim fades from transparent at about 55% height to night violet at the bottom edge.
- The flower is centred at the bottom edge and appears to grow out of the screen bezel.
- The transcript sits above it in large type (28 sp), revealed word by word. Cardo's reply follows (20 sp, softer colour).
- Result cards spring up between text and flower: timer, alarm, contact (with number label), message preview with Send/Cancel, event list, reminder, calculation, hand-off chip ("Opening Weather…").
- A keyboard button (type to Cardo) sits bottom-left and a close button bottom-right. Tapping outside the panel dismisses it.

### The flower (procedural Compose Canvas, one composable reused for panel, home hero and onboarding)

- Anatomy: a bulbous base of overlapping spiky bracts in silvery sage; a dense crown of 60–90 curved filaments in thistle violet → magenta with lighter tips; a soft radial glow behind.
- Each state has its own motion:

| State | Motion |
| --- | --- |
| Appear | The bud rises from below the edge on a spring (damping 0.72, stiffness 380). The bracts unfold over 250 ms, then the filaments bloom in a stagger over 450 ms. Light haptic tick. |
| Listening | Filament length follows smoothed microphone level (60 ms attack, 150 ms release, up to +35%), with per-filament noise phases so it moves like a living waveform, plus a ±3° idle sway. |
| Thinking | A hue wave travels around the crown (about 1.2 rev/s) and the glow breathes. |
| Speaking | Glow and filament pulse follow the speech output level. |
| Success | A short brightening, then 6–12 thistle-down seeds float up and fade over 900 ms. |
| Error | Colour drops by 30% and the filaments droop slightly for 400 ms. |
| Dismiss | Filaments retract over 220 ms and the bud sinks on a spring. |

- Performance: geometry is precomputed, nothing is allocated in the draw loop, draw time stays under 4 ms, and it's ready for 120 Hz.
- Reduced motion (animator scale 0 or the accessibility setting): crossfades and a static flower only.

### Design tokens

- Colours: Thistle Violet `#7B4BD1`, Bloom Magenta `#C24BC9`, Lilac `#B9A2F0`, Bract Sage `#8FA89A`, Night `#0E0B14`, Petal White `#F6F2FB`.
- The panel is always dark. App screens follow system light/dark.
- Type: Figtree (OFL), bundled variable font.
- Motion: spring-first everywhere, with no linear tweens on interactive elements. Shared-element transitions between app screens.
- Sound: a soft two-note wake chime and a confirm chime, synthesized by a script in `ml/` (no licensing issues). They're silent when the phone is on silent.
- Icon: an adaptive icon from the flower silhouette, with a monochrome themed variant.

### App screens

- **Onboarding, six steps:**
  1. Welcome
  2. Privacy promise (No internet permission badge)
  3. Microphone permission
  4. Set Cardo as the digital assistant (deep link to Default apps → Digital assistant)
  5. Battery: unrestricted, plus guides for the device's maker (Xiaomi autostart/no restrictions, Samsung never-sleeping apps, Pixel unrestricted)
  6. Try it: "Say 'Cardo'", with a live flower that reacts
- **Home:** the flower hero, listening status with a toggle, a "Try saying…" carousel in the current language, and shortcuts to Reminders, Notes and Settings.
- **Settings:** Wake word (mode, sensitivity, test), Language (auto, primary), Voice (voice, speed, voice feedback: automatic / always / never), Lock screen (per-feature allow / confirm / unlock), Messages (notification access), Calendar (target calendar), Sounds and haptics, Privacy (no internet badge, history off, clear data), About (open-source licences, model licences, version).
- **Reminders and Notes:** clean lists with swipe actions and empty states that suggest a voice command.

## 9. Privacy and security

- The `INTERNET` permission is absent. A Gradle check on the merged manifest fails the build if it ever appears, and CI runs it.
- No analytics, crash reporting or ads; no proprietary SDKs or Google Play Services.
- No audio is ever persisted. The ring buffer is overwritten in memory.
- History is off by default.
- Reminders and notes live in app-private storage and are excluded from cloud backup and device transfer (`dataExtractionRules`).
- Message notifications are held in memory only and cleared after they're read or after 24 h.
- Exported components are limited to what Android requires (assistant service, session service, notification listener, boot receiver), each protected by the matching system permission.

## 10. Error handling

| Situation | Behaviour |
| --- | --- |
| False wake (no speech, or second-stage reject) | Panel folds away silently within 4 s; no sound |
| Speech heard but not understood | "Sorry, I didn't catch that" once; a second miss offers typing |
| Low confidence | Ask between the top two intents |
| Out of scope | Say so and offer a web search |
| Missing permission | One-line explanation and a "Grant" button opening the exact settings screen |
| No app for a hand-off | "You don't have a maps app installed" |
| Contact or app not found | "I couldn't find Ana in your contacts", with the closest suggestion if similarity is high |
| Microphone busy or a call active | Listening pauses and the notification says why; it resumes automatically |
| Model files missing or corrupt | Startup integrity check; clear error screen (should never happen in release builds) |
| Executor exception | Logged locally (logcat only), user hears "Something went wrong with that" |

## 11. Testing and verification

- **`:brain`:** JUnit 5 on the JVM.
  - Normalizer tables of at least 1,000 cases across times, dates, numbers, maths and units in both languages.
  - Dialog state-machine tests.
  - Tokenizer golden tests.
  - Model inference through ONNX Runtime for Java, evaluated on the held-out set; the build fails if accuracy drops below targets.
- **`:voice`:** pipeline tests with WAV fixtures (wake → VAD → ASR → transcript), on the emulator and the host.
- **`:actions`:** Robolectric tests asserting the exact intents and API calls per command; repository tests with fake content providers.
- **`:app`:** Compose UI tests for onboarding, settings and the panel states; screenshot tests for the flower states.
- **Emulator end-to-end:**
  - A debug-only broadcast injects a WAV file into the live pipeline. Scripted runs speak (synthesized) commands and assert the outcome: timer and alarm intents reach the Clock app, a reminder row exists and its notification fires, a call intent reaches the dialler, the SMS confirmation card appears, an app opens, the web-search hand-off fires, a calculation is answered.
  - Screen recordings of the panel are captured for visual review.
- **Static checks:** Android Lint (warnings as errors on new code), ktlint, the no-`INTERNET` manifest check, and an APK size report (target ≤ 200 MB arm64).
- **Real-device checklist** (`docs/device-test-checklist.md`) for what the emulator cannot prove: battery cost of always-on listening, wake accuracy in real rooms, OEM process killing, screen-on from lock, torch, real call/SMS.

## 12. Build, repository and release

- Gradle Kotlin DSL with a version catalog, the latest stable AGP/Kotlin/Compose, and JDK 17.
- Development uses feature branches merged to `main`. Commits carry no AI attribution trailers.
- GitHub Actions run build, unit tests, Lint, the manifest check and the size report on every push. Tagged releases build the signed release APK and attach it to a GitHub Release.
- Signing: a release keystore is generated locally, kept outside the repo, and supplied to CI as encrypted secrets.
- Docs:
  - `README.md` (what, privacy promise, install, supported phones)
  - `PRIVACY.md`
  - `docs/benchmarks/`
  - `docs/device-test-checklist.md`
  - `models/LICENSES.md`
  - `CONTRIBUTING.md`
- F-Droid submission follows in v1.1, once native libraries can be built from source in their pipeline.

## 13. Delivery plan (milestones)

1. **Foundation:**
   - Android SDK, emulator and system image on the dev machine.
   - Gradle project with all modules and the shared interfaces and command schema.
   - CI, manifest check, private GitHub repo.
   - sherpa-onnx (static-link AAR) and ONNX Runtime packaged together without conflicts.
2. **Parallel tracks** (separate worktrees):
   - Brain rules and dialog
   - Understanding model (data, training, export, Kotlin inference)
   - Voice bake-off and `:voice` integration
   - Actions
   - UI (design system, flower, panel, onboarding, home, settings, reminders, notes)
3. **Integration:**
   - Assistant service, listening service, session wiring, lock screen.
   - Emulator end-to-end suite.
4. **Polish and release:**
   - Performance, accessibility (TalkBack labels, contrast, reduced motion) and copy in both languages.
   - Docs.
   - Signed APK on GitHub Releases; repo made public.

## 14. Risks

| Risk | Mitigation |
| --- | --- |
| Open-vocabulary KWS weak on Spanish-accented "Cardo" | Bake-off gate; fallback to a custom-trained wake model; second-stage transcript check |
| Battery cost of always-on listening | Light KWS model, listening modes, auto-pause rules; measured on real devices before public release |
| OEMs killing the listening service | System-bound assistant service keeps the process alive; onboarding battery guides; service restarts from `onReady()` |
| Screen-on and showing over the lock screen from a background wake | Verified on the emulator in Integration; fallback is a full-screen-intent notification that opens the session |
| Understanding model overfits synthetic data | Independent held-out set; error-analysis loop; rules and clarification as safety net |
| Synthetic test audio flatters ASR accuracy | Noise and voice variety in fixtures; real-device checklist; models are swappable via the lock file |
| APK size | int8 models, arm64-only release, one voice per language at launch |
| Emulator-only verification | Real-device checklist published; "verified on" table in README kept honest |

## 15. Definition of done (v1)

1. The release APK installs on Android 12+ and its merged manifest has no `INTERNET` permission.
2. Cardo can be selected as the default digital assistant, and wake listening starts on its own after selection and after reboot.
3. The bake-off report exists and the chosen models meet the wake and ASR targets in section 6.
4. Held-out understanding accuracy meets section 5 targets in both languages.
5. All unit, Robolectric, Compose and emulator end-to-end suites pass in CI and locally.
6. Every v1 capability in section 3 is implemented in both languages and covered by tests.
7. The panel, flower states, onboarding, home, settings, reminders and notes match section 8 and pass the accessibility checks.
8. README, PRIVACY, model licences and the real-device checklist are published; the signed APK is attached to a GitHub Release, and the repo is public.
