#!/usr/bin/env bash
# Usage: emulator.sh [start|stop]
# Creates AVD cardo-api37 if missing and runs it headless; `start` blocks until sys.boot_completed.
set -euo pipefail

ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
# avdmanager and the emulator disagree on the default AVD home (XDG vs ~/.android), so pin it.
export ANDROID_HOME ANDROID_SDK_ROOT=$ANDROID_HOME ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-$HOME/.android/avd}
mkdir -p "$ANDROID_AVD_HOME"
AVD=cardo-api37
IMAGE='system-images;android-37.0;google_apis;x86_64'
ADB=$ANDROID_HOME/platform-tools/adb
EMULATOR=$ANDROID_HOME/emulator/emulator
AVDMANAGER=$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager
LOG=${TMPDIR:-/tmp}/cardo-emulator.log

create_avd() {
    if ! "$AVDMANAGER" list avd -c 2>/dev/null | grep -qx "$AVD"; then
        echo no | "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" --device pixel_6
    fi
    local cfg=$ANDROID_AVD_HOME/$AVD.avd/config.ini
    # config.ini already carries some of these keys; drop them before appending ours.
    sed -i -E '/^(hw\.audioInput|hw\.ramSize|hw\.cpu\.ncore)=/d' "$cfg"
    printf '%s\n' hw.audioInput=yes hw.ramSize=4096 hw.cpu.ncore=2 >> "$cfg"
}

start() {
    if "$ADB" devices | grep -q '^emulator-.*device$'; then
        echo "emulator already running"
        return
    fi
    create_avd
    nohup "$EMULATOR" -avd "$AVD" -no-window -no-snapshot-save -no-boot-anim \
        -gpu swiftshader_indirect >"$LOG" 2>&1 &
    "$ADB" wait-for-device
    until [[ $("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do
        sleep 2
    done
    echo "emulator booted"
}

stop() {
    "$ADB" emu kill || true
}

case ${1:-start} in
    start) start ;;
    stop) stop ;;
    *) echo "usage: $0 [start|stop]" >&2; exit 2 ;;
esac
