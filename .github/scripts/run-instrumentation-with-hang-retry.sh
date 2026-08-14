#!/usr/bin/env bash
# Runs the instrumentation suite in two emulator-fresh shards, each with one retry that only a
# hang can trigger.
#
# The starved SwiftShader renderer wedges the app's main thread while a real-Activity test
# creates the SurfaceView map ("bad color buffer"), which hangs the whole suite with zero test
# failures; no in-process timeout can escape it. The corruption is cumulative: after roughly 75
# heavy map tests the hosted emulator tips over deterministically, so one emulator cannot carry
# the full suite even with a reboot retry (both attempts of run 31765193970 hung at exactly
# 75/142). Sharding at the app.trailveil.map package boundary keeps each shard inside that
# budget, and the reboot between shards hands the second one a clean GL state.
#
# timeout(1) exits 124 only for a hang, so a genuine test failure propagates immediately and is
# never retried -- flakes cannot hide behind this. A retried shard gets a rebooted emulator
# because the broken GL state outlives the app process.
#
# This lives in a script file because the emulator-runner executes its `script:` input line by
# line and fails on the first nonzero line, which makes multi-line shell control flow there
# impossible -- the first version of this retry never ran for exactly that reason.
set -u

restore_device() {
  adb wait-for-device
  sleep 90
  adb shell cmd overlay enable-exclusive --user 0 --category com.android.internal.systemui.navbar.threebutton
  adb shell cmd overlay list | grep -F '[x] com.android.internal.systemui.navbar.threebutton'
}

run_shard() {
  shard_name="$1"
  shard_timeout="$2"
  shift 2
  timeout "$shard_timeout" ./gradlew connectedDebugAndroidTest "$@"
  code=$?
  if [ "$code" = "0" ]; then
    return 0
  fi
  if [ "$code" != "124" ]; then
    return "$code"
  fi
  echo "Shard ${shard_name} hung; rebooting the emulator for one retry"
  adb reboot
  restore_device
  timeout "$shard_timeout" ./gradlew connectedDebugAndroidTest "$@"
}

run_shard map "${MAP_SHARD_TIMEOUT:-45m}" \
  -Pandroid.testInstrumentationRunnerArguments.package=app.trailveil.map
map_code=$?
if [ "$map_code" != "0" ]; then
  exit "$map_code"
fi

echo "Map shard green; rebooting the emulator for a clean-GL second shard"
adb reboot
restore_device

run_shard rest "${REST_SHARD_TIMEOUT:-35m}" \
  -Pandroid.testInstrumentationRunnerArguments.notPackage=app.trailveil.map
