# Cardo

A private, fully on-device voice assistant for Android. Say "Cardo", get things done, and nothing ever leaves your phone.

[![CI](https://github.com/SodaFMR/cardo/actions/workflows/ci.yml/badge.svg)](https://github.com/SodaFMR/cardo/actions/workflows/ci.yml)
[![Licence: Apache-2.0](https://img.shields.io/badge/licence-Apache--2.0-blue.svg)](LICENSE)
![No INTERNET permission](https://img.shields.io/badge/permissions-no%20INTERNET-brightgreen)

> **Status: in development, not usable yet.** The foundation is done; the voice, understanding, actions and UI tracks are being built and integrated. Nothing below is claimed to work until the table says "Done".

## What Cardo is

Cardo aims to match iPhone 13-era (iOS 15) Siri for everyday phone tasks, then go further on privacy and speed:

- Wake it by saying "Cardo" (also "Hey Cardo" or "Oye Cardo"), or with the assistant gesture, or type to it.
- A panel rises from the bottom of the screen with an animated cardo (thistle) flower.
- It understands natural English and Spanish, detected per sentence, and answers in the language it heard.
- Calls, messages, alarms and timers, reminders, notes, calendar, apps, music and device settings.
- Everything (wake word, speech recognition, understanding, speech output) runs on the phone.

Requires Android 12 or newer (minSdk 31). Release builds target `arm64-v8a`.

## Privacy promise

- **No `INTERNET` permission.** The build enforces it: a Gradle check on every variant's merged manifest fails the build if a network permission (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, and similar) ever appears. CI runs it.
- **No analytics, telemetry, crash reporting or ads.** The same check also fails the build if any merged component looks like telemetry or analytics (for example ONNX Runtime's telemetry provider, which Cardo strips), and it flags any dependency that asked for a network permission and was silently stripped unless it is explicitly allowlisted.
- **No audio is stored.** Microphone audio lives in a short in-memory buffer that is overwritten.
- **What is on the device:** speech and understanding models, your reminders and notes (app-private, excluded from cloud backup), and settings. Message notifications, if you enable that feature, are held in memory only.
- No Google Play Services and no proprietary SDKs.

Details: [spec section 9](docs/superpowers/specs/2026-11-01-cardo-v1-design.md).

## Features

Every capability is planned in English and Spanish. "Hand-off" means Cardo opens your own app with the request filled in, because the answer needs the internet.

| Capability | Status |
| --- | --- |
| Project foundation: Gradle modules, shared command schema, privacy gate, assistant shell, CI | Done |
| Wake word "Cardo" (always listening, optional charging-only modes) | In progress |
| Offline speech recognition and spoken replies | In progress |
| Understanding: small on-device intent model plus exact rules for times, dates and numbers | In progress |
| Calls: call contact or number, speakerphone, redial, "who called me?" | In progress |
| Messages: dictate and send SMS, read incoming messages from other apps, WhatsApp/Telegram prefill, email hand-off | In progress |
| Clock: alarms, timers, stopwatch (through the phone's Clock app) | In progress |
| Reminders and notes (stored on the phone) | In progress |
| Calendar: create events, "what's on today?" | In progress |
| Apps and camera: open any app, photo/selfie/video | In progress |
| Device: flashlight, volume, silent/vibrate, Do Not Disturb, brightness, battery | In progress |
| Settings panels (Wi-Fi, Bluetooth, data, airplane mode, and so on: Android forbids toggling these, so Cardo opens the exact panel) | In progress |
| Media control, "play X on Spotify" | In progress |
| Local answers: time, date, time in another city, maths, unit conversions | In progress |
| Hand-offs: web search, weather, maps, directions | In progress |
| Conversation: clarifying questions, confirmations, follow-ups without the wake word | In progress |
| Panel UI: flower animation, onboarding, home, settings, reminders, notes | In progress |
| Works over the lock screen with per-feature policy | Planned v1 |
| Assistant gesture and type-to-Cardo | Planned v1 |
| Location reminders ("when I get home") | Later (v1.1) |
| Personal shortcuts ("Cardo, good night") | Later (v1.1) |
| Offline English-Spanish translation | Later (v1.1) |
| "Share this" for the current screen | Later (v1.1) |
| F-Droid release | Later (v1.1) |

Full scope and examples: [spec section 3](docs/superpowers/specs/2026-11-01-cardo-v1-design.md).

## How it works

```
wake word -> speech-to-text -> understanding -> actions -> spoken + on-screen reply
```

1. A foreground microphone service runs a low-power keyword spotter on a short in-memory audio buffer.
2. On a hit, the panel opens and voice activity detection finds the end of your sentence. Speech recognition transcribes it, including the wake word, which is checked a second time to reject false wakes.
3. A small trained intent and slot model (ONNX, a few tens of MB) plus exact rules for times, dates, numbers and contact/app matching turn the text into a command. This is how iOS 15 Siri worked, not a chatbot.
4. A dialog manager asks one short question if something is missing and confirms risky actions. An executor for each domain runs the command.

Keyword spotting, voice activity detection, speech recognition and speech synthesis use [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx); the understanding model runs on ONNX Runtime.

| Module | Role |
| --- | --- |
| `:brain` | Pure Kotlin: command schema, understanding, rules, entity matching, dialog |
| `:voice` | Audio source, wake word, VAD, speech recognition, speech synthesis, pipeline |
| `:actions` | One executor per domain (calls, messages, clock, calendar, ...), reminders and notes storage |
| `:app` | Assistant service, listening service, session panel, onboarding, settings, design system |
| `ml/` | Python tooling for training and benchmarks (not shipped) |

## Download & updates

There is no release yet. When there is, it will be here: **https://github.com/SodaFMR/cardo/releases/latest**. (The repository is private until the v1 release.)

### Install

1. Download `cardo-<version>.apk` from the latest release and open it. Android will ask you to allow installs from your browser or file manager ("unknown sources").
2. Open Cardo and choose it as your **Digital assistant app** (Settings, Apps, Default apps).
3. Set Cardo's battery usage to **Unrestricted** so the wake word keeps listening with the screen off.

### Verify before installing

Each release lists the APK's SHA-256 and the signing certificate's SHA-256 in its notes, and attaches a `SHA256SUMS` file.

```
sha256sum -c SHA256SUMS
apksigner verify --print-certs cardo-<version>.apk
```

The signing-certificate fingerprint is the same in every release. If it differs from the one published in the notes, do not install. (The first public release's notes will be the reference.)

### Updates with Obtainium

Cardo has no internet permission, so it cannot check for or download updates itself, and it never will: that would break the promise above. To get updates automatically, use [Obtainium](https://github.com/ImranR98/Obtainium):

1. In Obtainium choose **Add App** and enter `https://github.com/SodaFMR/cardo`.
2. Obtainium fetches each new GitHub release and installs it over the old one. All APKs are signed with the same key, so your data is kept.

Or just download the new APK yourself and install it over the old one. An F-Droid listing is planned for v1.1.

## Status / roadmap

Milestones from the [design spec](docs/superpowers/specs/2026-11-01-cardo-v1-design.md):

| Milestone | State |
| --- | --- |
| 1. Foundation: SDK and emulator, Gradle modules and schema, CI, manifest gate, sherpa-onnx plus ONNX Runtime packaged together | Done |
| 2. Parallel tracks: brain rules and dialog, understanding model, voice bake-off and `:voice`, actions, UI | In progress (on separate branches) |
| 3. Integration: assistant service, listening service, session wiring, lock screen, emulator end-to-end suite | Not started |
| 4. Polish and release: performance, accessibility, copy in both languages, docs, signed APK on GitHub Releases, repository made public | Not started |

No physical phone has been used yet; verification so far is emulator plus host-side benchmarks.

## Building from source

Requirements:

- JDK 17 (the build uses a JVM 17 toolchain)
- Android SDK with platform `android-37.0` and build-tools `37.0.0`. Point Gradle at it with `sdk.dir=/path/to/Android/Sdk` in `local.properties` (git-ignored) or by setting `ANDROID_HOME`.

```
./gradlew build
```

The first build downloads the pinned sherpa-onnx AAR (checksum-verified, cached in `~/.cache/cardo-artifacts`). `build` runs the unit tests and the check that fails any variant whose merged manifest requests `INTERNET`.

Helper scripts:

- `scripts/emulator.sh start|stop`: create and run the headless API 37 emulator.
- `scripts/set-assistant.sh`: make Cardo the default assistant on the connected device or emulator.
- `scripts/check-apk-libs.sh <apk>`: list an APK's native libraries and fail on duplicates.

CI builds a debug and an unsigned release APK on every push and keeps them as workflow artifacts for 14 days.

### Releasing (maintainers)

```
scripts/release.sh 0.1.0             # dry run: build, sign, verify, print fingerprints
scripts/release.sh 0.1.0 --publish   # also tag, push and create the GitHub release
```

Run from a clean `main`. Publishing needs `docs/release-notes/<version>.md` (start from `docs/release-notes/TEMPLATE.md`) and an authenticated `gh`. The script creates the signing key in `~/.cardo-keys/` on first use. **Back that directory up**: if the key is lost, nobody can update Cardo in place. Keys are never committed. Versions below 1.0.0 are published as pre-releases.

## Project docs

- [v1 design spec](docs/superpowers/specs/2026-11-01-cardo-v1-design.md)
- [Implementation plans](docs/superpowers/plans/) for each track
- Benchmarks (`docs/benchmarks/`): added when the bake-off lands

## Contributing

Issues and pull requests are welcome once the repository is public. Please keep to the privacy rules: no network permission, no analytics, no proprietary SDKs. Run `./gradlew build` before opening a pull request.

## Licence

[Apache-2.0](LICENSE).
