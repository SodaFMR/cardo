# Cardo MVP Design

> **Superseded** by [2026-11-01-cardo-v1-design.md](2026-11-01-cardo-v1-design.md). Kept for history: its OS-hotword and no-AI assumptions were replaced.

Date: 2026-06-26

## Goal

Cardo is a private, low-power Android voice assistant for Samsung, Google Pixel, and Xiaomi phones. It replaces the user's current Android assistant when selected, wakes on "Hey Cardo" in English or "Oye Cardo" in Spanish where Android/OEM hotword support allows it, and performs old-Siri-style structured phone tasks without AI, a backend, telemetry, or app-owned internet access.

## Non-Negotiables

- No AI/LLM behavior.
- No backend.
- No telemetry.
- No `INTERNET` permission in Cardo.
- English first, Spanish second, both in the first usable release.
- Wake phrase is mandatory for the product goal.
- Cardo uses the Android assistant role; users must explicitly choose Cardo as their assistant.
- Power use must rely on Android's assistant/hotword path, not a custom always-running microphone loop.
- All command parsing happens on the phone with fixed grammars and deterministic clarification flows.

## Sources Checked

- Apple iPhone User Guide, Siri overview and app usage: https://support.apple.com/guide/iphone/use-siri-with-apps-iph0193a9d54/ios
- Apple iPhone User Guide, information with Siri: https://support.apple.com/guide/iphone/get-information-with-siri-iphdd7fb5e11/ios
- Apple iPhone User Guide, Siri activation: https://support.apple.com/guide/iphone/how-to-use-siri-ipha48873ed6/ios
- Apple WWDC21 privacy session, on-device Siri speech recognition in iOS 15: https://developer.apple.com/videos/play/wwdc2021/10085/
- Android `VoiceInteractionService`: https://developer.android.com/reference/android/service/voice/VoiceInteractionService
- Android `RoleManager`: https://developer.android.com/reference/android/app/role/RoleManager
- Android `SpeechRecognizer`: https://developer.android.com/reference/android/speech/SpeechRecognizer
- Android common intents: https://developer.android.com/guide/components/intents-common
- Android `Intent.ACTION_WEB_SEARCH`: https://developer.android.com/reference/android/content/Intent#ACTION_WEB_SEARCH
- Android `LauncherApps`: https://developer.android.com/reference/android/content/pm/LauncherApps
- Android package visibility: https://developer.android.com/training/package-visibility
- AOSP voice interaction hotword guide: https://source.android.com/docs/automotive/voice/voice_interaction_guide/app_development

## Siri-Class Capability Target

The target is iPhone 13-era Siri behavior before general AI chat: structured voice commands, short clarifications, offline device actions, and handoffs to apps. Apple moved Siri automatic speech recognition on-device in iOS 15; Cardo mirrors that privacy stance on Android by keeping recognition and parsing local and using app handoffs for internet-backed results.

| Siri-style area | Cardo target | MVP status |
| --- | --- | --- |
| Wake phrase | "Hey Cardo" / "Oye Cardo" through assistant role hotword path | Must prove on devices first |
| Calls | Call contact, call phone number, choose contact number | MVP |
| Messages | Compose SMS or hand off to messaging app | MVP compose; read/reply later behind permission |
| Email | Compose email via mail app | MVP handoff |
| Alarms | Set/show/cancel alarms where clock app supports intents | MVP set alarm |
| Timers/chronos | Start timer | MVP |
| Reminders | Create reminder as calendar event/task-style entry | MVP |
| Calendar | Create calendar event | MVP |
| Open apps | Launch installed app by spoken name | MVP |
| Web information | Send query to browser/search app with `ACTION_WEB_SEARCH` | MVP handoff |
| Maps/directions | Open maps with destination/directions query | MVP handoff |
| Weather | Open weather/search app for forecast query | MVP handoff |
| Music/media | Play/search media and pause/resume/next via Android media APIs | MVP basic |
| Device controls | Volume, flashlight, Wi-Fi/settings panels where Android allows | MVP small set |
| Calculations | Arithmetic and unit conversions locally | MVP small set |
| Contacts | Look up contact phone/email before action | MVP |
| Clarifications | Ask follow-up when contact, time, app, or command is ambiguous | MVP |
| Confirmations | Confirm calls to raw numbers and other risky actions | MVP |
| App shortcuts | Launch common app actions if exposed by installed apps | Later |
| Notifications | Read/reply from notifications | Later, optional notification-listener permission |
| Smart home | Handoff to installed home apps; direct control only if local APIs exist | Later |
| Health/fitness | Health Connect only with explicit opt-in | Later |
| Car mode | Android Auto support | Later |
| Passwords | Open Android password settings only | Later handoff |

## Architecture

Cardo is a native Android Kotlin app with one minimal setup activity and one assistant service.

1. `MainActivity` shows assistant-role status, requests `RoleManager.ROLE_ASSISTANT`, and exposes language/status diagnostics.
2. `CardoVoiceInteractionService` registers the assistant service and owns hotword/session entry.
3. `CardoVoiceInteractionSession` handles the active interaction after wake or assistant gesture.
4. `SpeechRecognizer` runs on-device recognition when available. If unavailable, Cardo reports the device limitation instead of silently using network recognition.
5. `CommandParser` maps English/Spanish text to deterministic command objects.
6. `CommandExecutor` performs local Android actions through public APIs, providers, and intents.

No command requires Cardo-owned network access. Internet searches, maps, weather, media providers, and email happen by launching the user's chosen apps.

## Command Model

Commands are plain data objects:

- `SetAlarm(time, label?)`
- `StartTimer(duration, label?)`
- `CreateReminder(text, dateTime?)`
- `CreateCalendarEvent(title, dateTime, duration?, location?)`
- `CallContact(name, numberType?)`
- `CallNumber(number)`
- `ComposeMessage(contactOrNumber, body?)`
- `ComposeEmail(contactOrAddress, subject?, body?)`
- `OpenApp(name)`
- `SearchWeb(query)`
- `OpenMaps(query)`
- `PlayMedia(query?)`
- `MediaControl(action)`
- `DeviceControl(action, value?)`
- `Calculate(expression)`

Unknown commands get a short local fallback: "I can help with calls, messages, alarms, timers, reminders, calendar, apps, search, maps, media, and simple calculations."

## English and Spanish Grammar

The parser starts with fixed patterns, not natural-language generalization.

Examples:

- "Hey Cardo, set an alarm for seven tomorrow"
- "Hey Cardo, start a ten minute timer"
- "Hey Cardo, remind me tomorrow at nine to call Ana"
- "Hey Cardo, call Mom mobile"
- "Hey Cardo, open Spotify"
- "Hey Cardo, search the web for the weather in Madrid"
- "Oye Cardo, pon una alarma a las siete"
- "Oye Cardo, inicia un temporizador de diez minutos"
- "Oye Cardo, recuérdame mañana a las nueve llamar a Ana"
- "Oye Cardo, llama a Mamá al móvil"
- "Oye Cardo, abre Spotify"
- "Oye Cardo, busca en internet el tiempo en Madrid"

## Clarification and Safety

Cardo asks one short follow-up when needed:

- Multiple contacts match: "Which Ana?"
- Contact has multiple numbers: "Mobile or work?"
- Missing time: "What time?"
- Multiple apps match: "Which app?"

Cardo confirms risky actions:

- Calling raw phone numbers.
- Sending messages if body and recipient are both inferred.
- Actions that may cost money or disclose private information.

## Device/OEM Strategy

The first implementation must be tested on one Samsung, one Pixel, and one Xiaomi phone. The same APK should use public Android APIs only. OEM-specific behavior is recorded as device notes, not hidden code paths, unless a public API requires different handling.

The hotword gate is explicit:

- If assistant-role hotword works on all three target OEMs, continue to broaden commands.
- If an OEM blocks third-party hotwords, document the exact model, Android version, and failure mode before adding fallback activation.
- Do not add a custom background microphone listener; it is worse for battery and less reliable.

## Power and Privacy

Cardo stays idle unless Android wakes the assistant service or the user invokes the assistant manually. After wake, it records one short command, parses it, executes, and stops.

Privacy checks:

- No `INTERNET` permission.
- No analytics SDK.
- No crash reporting SDK in MVP.
- No command history stored by default.
- Contacts, calendar, phone, SMS, notification, and health permissions are requested only when a command needs them.

## MVP Definition

The first usable MVP is done when:

1. Cardo can be selected as the Android assistant.
2. "Hey Cardo" and "Oye Cardo" wake Cardo on tested Samsung, Pixel, and Xiaomi phones, or any OEM blocker is documented with proof.
3. On-device speech recognition captures one command in English and Spanish.
4. Parser tests pass for alarms, timers, reminders, calendar events, calls, app launch, web search, maps, media, device controls, and calculations.
5. Real-device manual tests pass for:
   - Set alarm.
   - Start timer.
   - Create reminder/calendar entry.
   - Call contact.
   - Call number with confirmation.
   - Compose SMS.
   - Open app.
   - Search web through browser/search app.
   - Open maps query.
   - Play/pause media.
   - Calculate locally.
6. APK manifest has no `INTERNET` permission.

## Out of Scope for MVP

- General AI answers.
- Cloud speech recognition controlled by Cardo.
- Background microphone service outside Android assistant/hotword APIs.
- Arbitrary automation of every installed app.
- Direct smart-home control across all vendors.
- Reading messages/notifications without explicit opt-in.
- Full Apple ecosystem equivalents such as FaceTime, HomeKit, Find My, or Apple Passwords.

## Open Technical Risk

The largest risk is third-party custom hotword support across Samsung, Pixel, and Xiaomi. This must be proven before polishing the command catalog.
