#!/usr/bin/env bash
set -eu
collect() {
  mkdir -p qa/screenshots
  timeout 10s adb pull /sdcard/Download/GeoHide-QA qa/screenshots || true
  timeout 10s adb logcat -d > qa/logcat.txt || true
}
trap collect EXIT
adb install -r probe/build/outputs/apk/debug/probe-debug.apk
qa_status=0
gradle :app:connectedDebugAndroidTest --no-daemon --stacktrace || qa_status=$?
python3 - <<'QA'
from pathlib import Path
import xml.etree.ElementTree as E
for p in Path('app/build/outputs/androidTest-results').rglob('TEST-*.xml'):
    root=E.fromstring(p.read_text())
    for c in root.iter('testcase'):
        for failure in c.findall('failure')+c.findall('error'):
            print('TEST FAILURE:',c.attrib.get('name'),failure.text,flush=True)
QA
exit "$qa_status"
