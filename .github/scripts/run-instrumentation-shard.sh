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
# `rest` hit that same wall at 116 tests and is now split too (P4-049): 57 + 59, measured to cover
# the shard with no overlap and no silent drop. Three hosted occurrences, every one stopping at the
# same test on BOTH attempts with the retry on a freshly rebooted emulator -- 32480422381,
# 32508539772 and 32555585123. Four runs cleared 116 before the last of those failed, so 116 was a
# margin `rest` sat on rather than a wall.
#
# THE FREEZER ASSERTION BELOW IS NOT A FIX FOR THOSE, and an earlier version of this header said it
# was. Android's cached-app freezer really does hang this suite on a desk AVD: measured 2026-08-22
# by dumping thread state at the stall, the app's main thread IDLE in epoll_pwait -- not wedged at
# all -- while `app.trailveil.test` and `com.google.android.permissioncontroller` both sat in
# `do_freezer_trap`, and a controlled A/B took that test from an indefinite hang to 21 seconds. It
# is also the honest explanation for "no in-process timeout can escape it" above: a frozen process
# cannot run its own watchdog, whatever the renderer is doing.
#
# But `do_freezer_trap` has appeared 0 times in every hosted log and always will, because nothing
# here captures process state on a hang. The assertion is kept because it costs nothing and removes
# a confound -- not because any hosted timeout was ever traced to it.
#
# TWO TRAPS, both paid for in full:
#   - The freezer A/B ran a 59-test half and was then shipped on the unsplit 116, where the position
#     that actually failed is unreachable. A fix measured on part of a shard has not been measured
#     on the shard.
#   - `bad color buffer handle` is NOT a hang signature. It appears once or twice in the five GREEN
#     runs on the split -- the same profile as in all three hung runs. What marks a hang is the
#     SILENCE after it, not the marker.
#
# So triage runs in one direction only: the `cached_apps_freezer disabled` gate echoes into the log,
# so a timeout with it passing is provably not a frozen-process hang. To assign a hang POSITIVELY,
# add a `ps` dump on the exit-124 path below -- it does not have one.
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
  # Re-asserted after the reboot rather than trusted to persist, so the gate's echo covers the retry
  # too. A frozen instrumentation process hangs this suite on a desk AVD (P4-049); no hosted timeout
  # has been traced to it, and this line is what makes that statement checkable.
  adb shell settings put global cached_apps_freezer disabled
  adb shell settings get global cached_apps_freezer | grep -Fx disabled
  adb shell cmd overlay enable-exclusive --user 0 --category com.android.internal.systemui.navbar.threebutton
  adb shell cmd overlay list | grep -F '[x] com.android.internal.systemui.navbar.threebutton'
}

diagnostics="${GITHUB_WORKSPACE:-.}/shard-diagnostics"
mkdir -p "$diagnostics"

# Captured on every non-green outcome, because until now a red run left NOTHING to read: no test
# report, no logcat, no process state. Every P4-049 diagnosis so far was reconstructed from
# truncated console output. `ps -A` on the hang path is the instrument that entry names as missing --
# `do_freezer_trap` has never appeared in a hosted log because nothing has ever looked. adb can be
# wedged when the shard is, so each capture is bounded and failure to capture is not fatal.
capture_state() {
  # ART writes every Java thread's stack to logcat when a process receives SIGQUIT, and that is the
  # one thing `ps` cannot supply: it reports that a process sits in S, never which frame it sleeps
  # in. `P4-049` spent two rounds narrowing a blocked frame by elimination and got it wrong, purely
  # because no capture ever held a stack. Signal first, so the traces land in the logcat dump below.
  #
  # `adb root` is required because the shell uid may not signal an app uid. It works on the
  # `google_apis` image this workflow pins and would fail on `google_apis_playstore`. Every step is
  # best effort: this runs on a path that has ALREADY failed, and a capture must never convert a
  # diagnosable failure into a broken one.
  timeout 60 adb root >/dev/null 2>&1 || true
  timeout 60 adb wait-for-device >/dev/null 2>&1 || true
  for process in app.trailveil app.trailveil.test; do
    # awk coerces to a number, which drops adb's trailing carriage return and takes the first pid.
    process_pid=$(timeout 30 adb shell pidof "$process" 2>/dev/null | awk '{print $1+0}')
    [ -n "$process_pid" ] && [ "$process_pid" != "0" ] || continue
    timeout 30 adb shell kill -3 "$process_pid" >/dev/null 2>&1 || true
  done
  # ART writes the dump asynchronously; without this the logcat below races it.
  sleep 5
  timeout 60 adb logcat -d > "$diagnostics/logcat-${shard_name}-$1.txt" 2>&1 || true
  timeout 60 adb shell ps -A -o PID,S,WCHAN,NAME > "$diagnostics/ps-${shard_name}-$1.txt" 2>&1 || true
  timeout 60 adb shell dumpsys activity processes > "$diagnostics/procs-${shard_name}-$1.txt" 2>&1 || true
}

# A retried shard that then fails an assertion exits on the test failure, not 124, so the hang is
# invisible to anything reading exit codes or GitHub's attempt counter. Say so where a human looks.
note() {
  echo "$1"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    echo "- **${shard_name}**: $1" >> "$GITHUB_STEP_SUMMARY"
  fi
}

echo "Shard ${shard_name}: ${shard_args[*]}"
timeout "$shard_timeout" ./gradlew connectedDebugAndroidTest "${shard_args[@]}"
code=$?
if [ "$code" = "0" ]; then
  exit 0
fi
capture_state attempt1
if [ "$code" != "124" ]; then
  note "failed tests on the first attempt (exit $code) and was NOT retried."
  exit "$code"
fi

echo "Shard ${shard_name} hung; rebooting the emulator for one retry"
note "HUNG and was retried on a rebooted emulator. Any result below is from the retry."
adb reboot
restore_device
timeout "$shard_timeout" ./gradlew connectedDebugAndroidTest "${shard_args[@]}"
code=$?
if [ "$code" = "0" ]; then
  note "passed on the retry, so this run still contained a hang."
else
  capture_state attempt2
fi
exit "$code"
