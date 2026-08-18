#!/usr/bin/env bash
# Runs ONE instrumentation shard on this job's own emulator, with one retry that only a hang can
# trigger.
#
# The starved SwiftShader renderer wedges the app's main thread while a real-Activity test creates
# the SurfaceView map ("bad color buffer"), which hangs the whole suite with zero test failures; no
# in-process timeout can escape it. The corruption is cumulative: after roughly 75 heavy map tests
# the hosted emulator tips over deterministically, so one emulator cannot carry the full suite even
# with a reboot retry (both attempts of run 31765193970 hung at exactly 75/142). Sharding is
# therefore a correctness requirement before it is a speed one, and every shard must stay well
# inside that budget.
#
# Shards used to run in sequence inside a single job, with a reboot between them for clean GL. They
# are now separate jobs, so each starts on a genuinely fresh emulator and they run in parallel: the
# map package alone was 40 of the 51 minutes a full run took, split here into two halves by the
# runner's own numShards/shardIndex. That split is deliberately not a hand-written class list — a
# new map test would silently miss such a list, and this suite has already paid for one silent drop.
#
# timeout(1) exits 124 only for a hang, so a genuine test failure propagates immediately and is
# never retried -- flakes cannot hide behind this. A retried shard gets a rebooted emulator because
# the broken GL state outlives the app process.
#
# This lives in a script file because the emulator-runner executes its `script:` input line by line
# and fails on the first nonzero line, which makes multi-line shell control flow there impossible --
# the first version of this retry never ran for exactly that reason.
set -u

shard_name="${SHARD_NAME:?SHARD_NAME must name the shard}"
shard_timeout="${SHARD_TIMEOUT:-40m}"
read -r -a shard_args <<< "${SHARD_ARGS:?SHARD_ARGS must carry the runner filter}"

restore_device() {
  adb wait-for-device
  sleep 90
  adb shell cmd overlay enable-exclusive --user 0 --category com.android.internal.systemui.navbar.threebutton
  adb shell cmd overlay list | grep -F '[x] com.android.internal.systemui.navbar.threebutton'
}

echo "Shard ${shard_name}: ${shard_args[*]}"
timeout "$shard_timeout" ./gradlew connectedDebugAndroidTest "${shard_args[@]}"
code=$?
if [ "$code" = "0" ]; then
  exit 0
fi
if [ "$code" != "124" ]; then
  exit "$code"
fi

echo "Shard ${shard_name} hung; rebooting the emulator for one retry"
adb reboot
restore_device
timeout "$shard_timeout" ./gradlew connectedDebugAndroidTest "${shard_args[@]}"
