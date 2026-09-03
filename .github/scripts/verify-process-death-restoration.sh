#!/usr/bin/env bash
# `V02-005` stage 9: real process death on the googlePoc launcher, driven from the host.
#
# WHY THIS IS A SCRIPT AND NOT AN INSTRUMENTATION TEST. Instrumentation runs inside the target
# package's process, and ActivityManager force-stops that package when an instrumentation run ends
# (or when the instrumented process dies) -- and a force-stop removes the task together with the
# framework's saved Activity state. So no `am instrument` run can plant state, die, and come back to
# find the icicle: the plant phase's own exit erases what the verify phase needs. The only honest
# way to exercise the kill-then-relaunch path the design names is to drive the plain app from the
# host with adb and never start instrumentation at all.
#
# WHAT IS OBSERVED. The googlePoc surface logs one coordinate-free breadcrumb per map delivery:
#   TrailVeilMapReady: map-ready restored=<bool> keys=<n> cameraDefault=<bool>
# restored      -- a provider-tagged saved-state envelope reached this MapView
# keys          -- how many SDK keys the envelope's payload carried (0 = nothing to restore)
# cameraDefault -- the camera at map-ready was still the SDK default
# Under the entry screen's opaque fog a screenshot cannot show the camera, so the breadcrumb is the
# host-observable signal; in-process instrumentation (GoogleMapProcessDeathRestorationTest) proves
# the other half, that a real captured envelope restores the camera field by field.
#
# PROTOCOL (everything printed is a boolean, a count, a pid or a duration; nothing positional):
#   1. force-stop, cold launch: the breadcrumb must read restored=false keys=0.
#      A fresh install shows the privacy disclosure; its acknowledge button is located by its
#      localized text in a uiautomator dump and tapped (the position is computed, never printed).
#   2. pan the camera with one fixed swipe so the camera is no longer the SDK default.
#   3. HOME (the Activity stops and the framework collects the envelope), `am kill` the package,
#      prove the pid is gone.
#   4. relaunch through the launcher intent, which resumes the saved task: the breadcrumb must read
#      restored=true keys>0 -- the framework handed the provider-tagged envelope back into a new
#      process and its SDK payload was not empty (an empty payload is exactly the destroyed-before-
#      save defect the ON_STOP snapshot fixes).
#   5. control: force-stop (which wipes the task), cold launch: restored=false keys=0. The
#      restoration claim means something only because a genuinely fresh start reads differently.
#
# cameraDefault is printed on every launch but never gated: measured on API 36, the SDK reports a
# non-default camera at map-ready on a warm renderer even with nothing moved, so it cannot tell a
# pan apart from a warm start. What the envelope restores the camera TO is proven in-process by
# the envelope replay test; this script proves the framework round trip across a real kill.
#
# Requires: adb on PATH (or ADB=...), python3, the googlePoc APK installed on the attached device.
set -euo pipefail
# Git Bash on Windows rewrites arguments that look like POSIX paths (`/sdcard/...`) into Windows
# paths before adb sees them; this disables that for the whole script and is inert elsewhere.
export MSYS_NO_PATHCONV=1

ADB="${ADB:-adb}"
PKG="${PKG:-app.trailveil}"
ACTIVITY="${ACTIVITY:-.MainActivity}"
LOG_TAG="${LOG_TAG:-TrailVeilMapReady}"
DISCLOSURE_TEXT="${DISCLOSURE_TEXT:-Got it}"   # the disclosure's acknowledge button, values/strings.xml
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-60}"
SETTLE_SECONDS="${SETTLE_SECONDS:-5}"
OUT="${OUT:-$(mktemp -d)}"

say() { printf 'process-death: %s\n' "$*"; }
fail() { say "FAIL: $*"; exit 1; }

# A caller-supplied OUT may not exist yet; without it the uiautomator dump fails silently and the
# disclosure is never acknowledged, so create it before anything writes there.
mkdir -p "$OUT" || fail "could not create OUT=$OUT"

command -v python3 >/dev/null || fail "python3 is required"
"$ADB" get-state >/dev/null 2>&1 || fail "no device attached"

# On a Windows host (Git Bash) the python3 on PATH is a native interpreter that cannot open POSIX
# paths such as /tmp/...; hand it Windows paths when cygpath is available, and the path unchanged
# elsewhere.
hostpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

screen_size() {
  "$ADB" shell wm size | sed -n 's/.*: *\([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1
}
read -r WIDTH HEIGHT < <(screen_size)
[ -n "${WIDTH:-}" ] && [ -n "${HEIGHT:-}" ] || fail "could not read the screen size"
CENTRE_X=$((WIDTH / 2))

launch() {
  "$ADB" shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
    -n "$PKG/$ACTIVITY" >/dev/null
}

pid_of() { "$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r\n' || true; }

clear_log() { "$ADB" logcat -c >/dev/null 2>&1 || "$ADB" logcat -c >/dev/null 2>&1 || true; }

# Waits for the newest breadcrumb since the last clear_log and leaves it in READY_LINE.
wait_ready() {
  local label="$1" started line
  started=$(date +%s)
  while :; do
    line=$("$ADB" logcat -d -s "$LOG_TAG:I" 2>/dev/null \
      | grep -o 'map-ready restored=[a-z]* keys=[0-9]* cameraDefault=[a-z]*' | tail -1 || true)
    if [ -n "$line" ]; then
      say "$label: $line after $(( $(date +%s) - started )) s"
      READY_LINE="$line"
      return 0
    fi
    if [ $(( $(date +%s) - started )) -ge "$READY_TIMEOUT_SECONDS" ]; then
      fail "$label: no map-ready breadcrumb within $READY_TIMEOUT_SECONDS s (googlePoc build with a key installed?)"
    fi
    sleep 1
  done
}

# expect_ready <label> <restored> <zero|nonzero> <cameraDefault|any>
expect_ready() {
  local label="$1" restored="$2" keys="$3" camera="$4" r k c
  r=$(printf '%s' "$READY_LINE" | sed -n 's/.*restored=\([a-z]*\).*/\1/p')
  k=$(printf '%s' "$READY_LINE" | sed -n 's/.*keys=\([0-9]*\).*/\1/p')
  c=$(printf '%s' "$READY_LINE" | sed -n 's/.*cameraDefault=\([a-z]*\).*/\1/p')
  [ "$r" = "$restored" ] || fail "$label: expected restored=$restored, got restored=$r"
  case "$keys" in
    nonzero) [ "${k:-0}" -gt 0 ] || fail "$label: expected a non-empty SDK payload, got keys=$k" ;;
    zero) [ "${k:-0}" -eq 0 ] || fail "$label: expected no payload, got keys=$k" ;;
  esac
  [ "$camera" = any ] || [ "$c" = "$camera" ] || fail "$label: expected cameraDefault=$camera, got cameraDefault=$c"
}

# The first launch of a fresh install shows the privacy disclosure, a modal dialog. Its acknowledge
# button is found by its localized text in a uiautomator dump and tapped at the centre of its
# bounds; the position is computed here and never printed.
dismiss_disclosure_if_shown() {
  local attempt tap
  for attempt in 1 2 3 4 5 6 7 8; do
    "$ADB" shell uiautomator dump /sdcard/trailveil-ui.xml >/dev/null 2>&1 || true
    "$ADB" exec-out cat /sdcard/trailveil-ui.xml > "$OUT/ui.xml" 2>/dev/null || true
    tap=$(python3 - "$(hostpath "$OUT/ui.xml")" "$DISCLOSURE_TEXT" <<'PY'
import re, sys
try:
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
except OSError:
    sys.exit(0)
for node in re.finditer(r"<node [^>]*>", xml):
    attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', node.group(0)))
    if attrs.get("text") == sys.argv[2] or attrs.get("content-desc") == sys.argv[2]:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attrs.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
)
    if [ -n "$tap" ]; then
      # shellcheck disable=SC2086  # two integers, intentionally split
      "$ADB" shell input tap $tap
      say "disclosure acknowledged on attempt $attempt"
      sleep 1
      return 0
    fi
    sleep 1
  done
  say "no disclosure shown"
}

say "screen ${WIDTH}x${HEIGHT}; workdir $OUT"
"$ADB" shell settings put global window_animation_scale 0 >/dev/null
"$ADB" shell settings put global transition_animation_scale 0 >/dev/null
"$ADB" shell settings put global animator_duration_scale 0 >/dev/null
"$ADB" shell svc power stayon usb >/dev/null || true
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null || true

# ---- 1. cold launch ---------------------------------------------------------------------------------
"$ADB" shell am force-stop "$PKG"
clear_log
launch
wait_ready "cold launch"
expect_ready "cold launch" false zero any
dismiss_disclosure_if_shown
sleep "$SETTLE_SECONDS"

# ---- 2. pan ------------------------------------------------------------------------------------------
"$ADB" shell input swipe "$CENTRE_X" $((HEIGHT * 65 / 100)) "$CENTRE_X" $((HEIGHT * 30 / 100)) 400
sleep "$SETTLE_SECONDS"
PID_BEFORE=$(pid_of); [ -n "$PID_BEFORE" ] || fail "app pid missing before kill"

# ---- 3. kill -----------------------------------------------------------------------------------------
"$ADB" shell input keyevent KEYCODE_HOME
sleep 3
for attempt in 1 2 3 4 5; do
  "$ADB" shell am kill "$PKG" || true
  sleep 2
  [ -z "$(pid_of)" ] && break
  say "kill attempt $attempt: process still alive, retrying"
done
[ -z "$(pid_of)" ] || fail "am kill never removed the process (pid $PID_BEFORE); is the app still foreground?"
say "process killed (previous pid $PID_BEFORE)"

# ---- 4. relaunch through the existing task -------------------------------------------------------------
clear_log
launch
PID_AFTER=$(pid_of); [ -n "$PID_AFTER" ] || fail "app did not start after kill"
[ "$PID_AFTER" != "$PID_BEFORE" ] || fail "same pid after kill: the process never died"
wait_ready "relaunch"
expect_ready "relaunch" true nonzero any
say "relaunch restored the envelope into a new process (pid $PID_AFTER)"

# ---- 5. control: a genuinely fresh start reads differently --------------------------------------------
"$ADB" shell am force-stop "$PKG"
clear_log
launch
wait_ready "fresh launch (control)"
expect_ready "fresh launch (control)" false zero any

say "PASS relaunch after am kill: restored=true payload non-empty; fresh start: restored=false payload empty"
