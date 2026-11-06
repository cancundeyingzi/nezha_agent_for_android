#!/usr/bin/env bash
# Run inside android-emulator-runner, against its single disposable emulator.
set -euo pipefail

mapfile -t attached_devices < <(adb devices | tr -d '\r' | awk 'NR > 1 && NF >= 2 {print $1 " " $2}')
if [ "${#attached_devices[@]}" -ne 1 ]; then
  echo 'Device regressions require exactly one connected emulator; refusing package changes.' >&2
  exit 2
fi
if [[ "${attached_devices[0]}" =~ ^(emulator-[0-9]+)[[:space:]]device$ ]]; then
  device_serial="${BASH_REMATCH[1]}"
else
  echo 'Device regressions accept only an online emulator-* device; refusing package changes.' >&2
  exit 2
fi
export ANDROID_SERIAL="$device_serial"
device_adb() { adb -s "$device_serial" "$@"; }

evidence=build/device-evidence
mkdir -p "$evidence"
# Only these disposable packages are touched. This also prevents a previous test's screenshot from
# being mistaken for evidence from this run when a local emulator is deliberately reused.
device_adb uninstall com.nezhahq.agent.r1test.test >/dev/null 2>&1 || true
device_adb uninstall com.nezhahq.agent.r1test >/dev/null 2>&1 || true
{
  git rev-parse HEAD
  printf 'API: '
  device_adb shell getprop ro.build.version.sdk
  printf 'Build: '
  device_adb shell getprop ro.build.fingerprint
} > "$evidence/run.txt"

collect_evidence() {
  result=$?
  trap - EXIT
  device_adb pull /sdcard/Android/data/com.nezhahq.agent.r1test/files/device-evidence "$evidence/" >/dev/null 2>&1 || true
  if [ "$result" -ne 0 ]; then
    device_adb logcat -d -v threadtime > "$evidence/logcat.txt" 2>&1 || true
    device_adb exec-out screencap -p > "$evidence/failure.png" 2>/dev/null || true
  fi
  # UTP leaves the two packages installed until their external evidence has been pulled above.
  device_adb uninstall com.nezhahq.agent.r1test.test >/dev/null 2>&1 || true
  device_adb uninstall com.nezhahq.agent.r1test >/dev/null 2>&1 || true
  exit "$result"
}
trap collect_evidence EXIT

device_adb logcat -c
# JUnit also has a 60-second per-test deadline. This host deadline covers runner startup/UTP failures
# before JUnit begins, which otherwise can inherit an effectively unbounded adb instrumentation wait.
timeout --signal=TERM --kill-after=30s 15m ./gradlew :app:connectedR1testAndroidTest --stacktrace \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
