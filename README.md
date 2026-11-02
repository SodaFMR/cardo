# Cardo

Cardo is a private voice assistant for Android. Say "Cardo" and it handles calls, messages, alarms, timers, reminders, calendar, apps, music and device settings in English or Spanish — like iPhone 13-era Siri, but entirely on your phone.

**Cardo has no internet permission.** Speech recognition, understanding and its voice all run on the device. Nothing you say is sent anywhere or used for training.

Status: in development. Design: [docs/superpowers/specs/2026-11-01-cardo-v1-design.md](docs/superpowers/specs/2026-11-01-cardo-v1-design.md).

## Building

Requirements:

- JDK 17 (the build uses a JVM 17 toolchain)
- Android SDK with platform `android-37.0` and build-tools `37.0.0`. Point Gradle at it with `sdk.dir=/path/to/Android/Sdk` in `local.properties` (git-ignored) or by setting `ANDROID_HOME`.

```
./gradlew build
```

The first build downloads the pinned sherpa-onnx AAR (checksum-verified, cached in `~/.cache/cardo-artifacts`). `build` runs the unit tests and the check that fails any variant whose merged manifest requests `INTERNET`.
