#!/usr/bin/env bash
# Makes Cardo the default digital assistant on the connected device/emulator and prints the active service.
#
# Verified on the API 37 emulator (cardo-api37, google_apis):
#   adb shell cmd role add-role-holder --user 0 android.app.role.ASSISTANT io.github.sodafmr.cardo
# On API 37 the role manager also sets secure settings `assistant` and `voice_interaction_service`
# to io.github.sodafmr.cardo/.assistant.CardoVoiceInteractionService, so no manual `settings put` is needed.
#
# Showing a session (verified; logcat tag Cardo logs "session shown"):
#   adb shell input keyevent KEYCODE_ASSIST
# `adb shell cmd voiceinteraction show` does NOT work from the shell user: it is denied with
# "requires: android.permission.ACCESS_VOICE_INTERACTION_SERVICE".
set -euo pipefail

ADB=${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}
PKG=io.github.sodafmr.cardo

"$ADB" shell cmd role add-role-holder --user 0 android.app.role.ASSISTANT "$PKG"

# The VoiceInteractionManagerService switches services asynchronously; wait for it.
for _ in $(seq 1 15); do
    if "$ADB" shell dumpsys voiceinteraction | grep -q "mComponent=$PKG/"; then
        break
    fi
    sleep 1
done
"$ADB" shell dumpsys voiceinteraction | grep -E 'mComponent=|Session service='
