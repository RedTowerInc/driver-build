#!/usr/bin/env bash
set -eu
collect() {
  mkdir -p qa/screenshots
  timeout 10s adb pull /sdcard/Android/data/dev.geohide.switcher/files/qa qa/screenshots || true
  timeout 10s adb logcat -d > qa/logcat.txt || true
}
trap collect EXIT
gradle :app:connectedDebugAndroidTest --no-daemon --stacktrace
