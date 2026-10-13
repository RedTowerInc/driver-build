#!/usr/bin/env bash
set -eu
collect() {
  mkdir -p qa/screenshots
  timeout 10s adb pull /sdcard/Download/GeoHide-QA qa/screenshots || true
  timeout 10s adb logcat -d > qa/logcat.txt || true
}
trap collect EXIT
adb install -r probe/build/outputs/apk/debug/probe-debug.apk
gradle :app:connectedDebugAndroidTest --no-daemon --stacktrace
