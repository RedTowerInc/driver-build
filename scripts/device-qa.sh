#!/usr/bin/env bash
set -eu
collect() {
  mkdir -p qa/screenshots
  adb pull /sdcard/Android/data/dev.geohide.switcher/files/qa qa/screenshots || true
  adb logcat -d > qa/logcat.txt || true
}
trap collect EXIT
gradle :app:connectedDebugAndroidTest --no-daemon --stacktrace
