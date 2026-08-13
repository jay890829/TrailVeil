#!/usr/bin/env bash
# Runs the unfiltered instrumentation suite with one retry that only a hang can trigger.
#
# The starved SwiftShader renderer intermittently wedges the app's main thread while a
# real-Activity test creates the SurfaceView map ("bad color buffer"), which hangs the whole
# suite with zero test failures; no in-process timeout can escape it. timeout(1) exits 124 only
# for that hang, so a genuine test failure propagates immediately and is never retried — flakes
# cannot hide behind this. The retry gets a rebooted emulator because the broken GL state
# outlives the app process.
#
# This lives in a script file because the emulator-runner executes its `script:` input line by
# line and fails on the first nonzero line, which makes multi-line shell control flow there
# impossible — the first version of this retry never ran for exactly that reason.
set -u

timeout "${SUITE_TIMEOUT:-55m}" ./gradlew connectedDebugAndroidTest
code=$?
if [ "$code" != "124" ]; then
  exit "$code"
fi

echo "Instrumentation hung; rebooting the emulator for one retry"
adb reboot
adb wait-for-device
sleep 90
adb shell cmd overlay enable-exclusive --user 0 --category com.android.internal.systemui.navbar.threebutton
adb shell cmd overlay list | grep -F '[x] com.android.internal.systemui.navbar.threebutton'
exec ./gradlew connectedDebugAndroidTest
