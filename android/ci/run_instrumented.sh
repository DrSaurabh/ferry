#!/usr/bin/env bash
# Host side of an emulator suite (plan CP6, §5.2). Runs inside reactivecircus/android-emulator-runner.
#   run_instrumented.sh <phone|tv|api25> [attempt]
# Starts ferry.py 2.0 from a temp copy of pc/ (--no-open --port --discovery-port off --inbox --outbox
# --data-dir) and waits for its FERRY_READY line (Ph1); installs the debug + androidTest APKs (no -g); runs the classes of
# suites/<suite>.txt in order; pulls screenshots, probe results, logcat. Exit 1 if anything failed.
#
# Env: FERRY_APKS (folder with the downloaded ferry-test-apks, default ./apks), FERRY_PYTHON (python3),
#      FERRY_BIG_VIDEO (TV outbox video, default ~/big-video/big.mp4).
set -uo pipefail

suite=${1:?usage: run_instrumented.sh <phone|tv|api25> [attempt]}
attempt=${2:-1}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CI=$ROOT/android/ci
OUT=$ROOT/ci-out/$suite-try$attempt
APKS=${FERRY_APKS:-$ROOT/apks}
PY=${FERRY_PYTHON:-python3}
TMP=${RUNNER_TEMP:-/tmp}/ferry-$suite-try$attempt
PKG=app.ferry
EXT=/sdcard/Android/data/$PKG/files
RESULTS=$OUT/results.txt
rm -rf "$OUT" "$TMP"
mkdir -p "$OUT" "$TMP"

PIDS=()
cleanup() {
  for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done
  for d in "$TMP"/*/; do cp "$d"data/ferry-*.txt "$d"data/ferry-*.log "$OUT/" 2>/dev/null; done
  true
}
trap cleanup EXIT

log() { echo "[$suite] $*"; echo "$*" >> "$RESULTS"; }
adbs() { adb shell "$@" | tr -d '\r'; }

# ---------- host PCs ----------
# start_pc NAME PORT [outbox seed files…]
start_pc() {
  local name=$1 port=$2; shift 2
  local d=$TMP/$name
  mkdir -p "$d/inbox" "$d/outbox"
  cp -r "$ROOT/pc" "$d/pc"
  rm -rf "$d/pc/tests"
  for f in "$@"; do cp "$f" "$d/outbox/"; done
  mkdir -p "$d/data"
  (cd "$d/pc" && exec "$PY" -u ferry.py --no-open --port "$port" --discovery-port off \
      --inbox "$d/inbox" --outbox "$d/outbox" --data-dir "$d/data") > "$OUT/console-$name.txt" 2>&1 &
  PIDS+=($!)
  for _ in $(seq 1 60); do
    if grep -q "^FERRY_READY port=$port " "$OUT/console-$name.txt" 2>/dev/null &&
       curl -sf "http://127.0.0.1:$port/api/v1/info" > /dev/null; then
      log "pc $name ready: $(grep -m1 '^FERRY_READY' "$OUT/console-$name.txt")"; return 0
    fi
    sleep 0.5
  done
  log "pc $name did not answer on :$port"; cat "$OUT/console-$name.txt"; return 1
}

# ---------- host steps usable in suite files ----------
grant() {
  local err
  if err=$(adb shell pm grant "$PKG" "android.permission.$1" 2>&1 | tr -d '\r') && [ -z "$err" ]; then log "granted $1"
  else log "grant $1 refused: $(echo "$err" | grep -m1 -i exception)"; fi
}
push_media() { adb push "$CI/fixtures/$1" "$2" > /dev/null && log "pushed $1 -> $2"; }

# Media shown to Ferry must be created and indexed by the shell user, never by Ferry (plan §4.14).
# Tries, in order: (1) nothing, (2) content call scan_volume, (3) MEDIA_SCANNER_SCAN_FILE broadcast; the
# first that makes the row visible to the shell (with its owner) is recorded. Ph0b: on an emulator booted
# from the AVD snapshot, (1) showed the row to the shell but not to Ferry for > 30 s, so scan_volume
# (synchronous) is always run as well.
media_row() {
  # one string: the device shell must see the SQL quotes
  adbs "content query --uri content://media/external/images/media --projection _display_name:owner_package_name:is_pending --where \"_display_name='$(basename "$1")'\"" 2>&1 | grep -m1 'Row:'
}
scan_media() {
  local path=$1 row="" how=""
  for how in none scan_volume broadcast; do
    case $how in
      scan_volume) adbs content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1 ;;
      broadcast) adbs am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$path" > /dev/null 2>&1 ;;
    esac
    for _ in $(seq 1 20); do
      row=$(media_row "$path")
      [ -n "$row" ] && break 2
      sleep 0.5
    done
  done
  if [ -n "$row" ]; then log "scan_media: $path visible after mechanism '$how': $row"
  else log "scan_media: $path not visible to 'content query' after all three mechanisms"; fi
  if [ "$how" = none ]; then
    adbs content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1
    log "scan_media: then scan_volume: $(media_row "$path")"
  fi
}

# A big shell-owned photo (plan §4.14 class 6): a real JPEG with MB megabytes of padding after its end
# (still a valid image for the media scanner), created by the shell user on the device.
make_big_jpeg() {
  local path=$1 mb=$2
  adb push "$CI/fixtures/photo1.jpg" "$path" > /dev/null &&
    adbs "dd if=/dev/zero bs=1048576 count=$mb >> $path" > /dev/null 2>&1
  log "make_big_jpeg: $path $(adbs ls -l "$path" | awk '{print $5}') bytes"
}

# ---------- host checks after classes (Ph5a, verify_inbox.py) ----------
verify() {
  local out
  if out=$(PYTHONPATH="$ROOT/pc" "$PY" "$CI/verify_inbox.py" "$@" 2>&1); then log "verify_inbox: $out"
  else log "verify_inbox FAILED: $out"; FAILED=1; fi
}
date_modified() {
  adbs "content query --uri content://media/external/images/media --projection date_modified --where \"_display_name='$1'\"" |
    sed -n 's/.*date_modified=\([0-9]*\).*/\1/p' | head -1
}
# B1 negative control: PC-B's copy (sent without location) must not be the GPS original.
check_negative() { verify redacted --inbox "$TMP/B/inbox" --name "$GPS_NAME" --fixture "$CI/fixtures/gps.jpg" --label PC-B; }
# P2: PC-A's copy is the original (bytes, GPS, date); then the PC saw "saved" and the Send-anyway "skipped".
check_original() {
  sleep 1 # the PC writes its request log on a background thread
  verify identical --inbox "$TMP/A/inbox" --name "$GPS_NAME" --fixture "$CI/fixtures/gps.jpg" --mtime-s "$(date_modified "$GPS_NAME")" --label PC-A
  verify uploads --log "$TMP/A/data/ferry-log.txt" --name "$GPS_NAME" --expect saved,skipped --label PC-A
}
check_no_partials() { sleep 2; verify no-partials --inbox "$TMP/$1/inbox" --label "PC-$1"; }

# ---------- one test class ----------
# Pass rule (plan §4.14 [PA16]): INSTRUMENTATION_CODE: -1 and an "OK (" line, and none of the failure marks.
judge() {
  grep -q 'INSTRUMENTATION_CODE: -1' "$1" && grep -q '^OK (' "$1" &&
    ! grep -qE 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2|INSTRUMENTATION_FAILED|Process crashed' "$1"
}

FAILED=0
run_class() {
  local cls=$1 before=$2 after=$3
  local raw=$OUT/am-${cls##*.}.txt
  adb shell am force-stop "$PKG"
  if [ -n "$before" ]; then eval "$before"; fi
  log "=== $cls"
  adb shell am instrument -w -r -e class "$cls" "${INST_ARGS[@]}" \
    "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' > "$raw" 2>&1
  if judge "$raw"; then log "PASS $cls"
  else
    log "FAIL $cls"; FAILED=1
    grep -E -A45 'INSTRUMENTATION_STATUS: stack=|Process crashed' "$raw" | grep -vE 'at android\.view\.(View|ViewGroup)\.' | head -70
  fi
  if [ -n "$after" ]; then eval "$after"; fi
}

# ---------- main ----------
adb wait-for-device
SDK=$(adbs getprop ro.build.version.sdk)
{
  echo "suite=$suite attempt=$attempt"
  echo "sdk=$SDK"
  echo "characteristics=$(adbs getprop ro.build.characteristics)"
  echo "abi=$(adbs getprop ro.product.cpu.abi)"
  echo "leanback: $(adbs pm list features | grep -i leanback | tr '\n' ' ')"
  echo "network agents:"; adbs dumpsys connectivity | grep -i -m5 NetworkAgentInfo
} | tee "$OUT/device.txt"
cat "$OUT/device.txt" >> "$RESULTS"

case $suite in
  tv) start_pc T 18765 "${FERRY_BIG_VIDEO:-$HOME/big-video/big.mp4}" "$CI/fixtures/small.mp4" || exit 1 ;;
  phone) { start_pc A 18765 && start_pc B 18766; } || exit 1 ;;
  *)  start_pc A 18765 || exit 1 ;;
esac

GPS_NAME=IMG_20250101_120000.jpg
GPS_SHA=$(sha256sum "$CI/fixtures/gps.jpg" | cut -d' ' -f1)
# Where the app keeps screenshots/probe files [CP16]: the shell can't read Android/data on API 30 TV (Ph0b).
case $suite in tv) STORE=internal ;; *) STORE=external ;; esac
INST_ARGS=(-e pcA 10.0.2.2:18765 -e pcB 10.0.2.2:18766 -e suite "$suite" -e store "$STORE" -e gpsName "$GPS_NAME" -e gpsSha "$GPS_SHA" -e bigName BIG_300MB.jpg)
# API 25 image: its only network is reported as cellular, so the app uses the debug NET=any override [PR2].
if [ "$suite" = api25 ]; then INST_ARGS+=(-e app.ferry.debug.NET any); fi

adb uninstall "$PKG" > /dev/null 2>&1
adb uninstall "$PKG.test" > /dev/null 2>&1
# A retry reinstalls under a new UID: files the first attempt left in Android/data would be EACCES (Ph5a B49).
adb shell rm -rf "/sdcard/Android/data/$PKG" > /dev/null 2>&1
adb install -r "$APKS/app-debug.apk" || exit 1
adb install -r "$APKS/app-debug-androidTest.apk" || exit 1
if [ "$SDK" -ge 33 ]; then grant POST_NOTIFICATIONS; fi
adb logcat -c

trim() { local s=$1; s=${s#"${s%%[![:space:]]*}"}; s=${s%"${s##*[![:space:]]}"}; printf '%s' "$s"; }
# Suite file: one line per class, "Class | host steps before | host check after" (# comments) [PA10].
# Read on fd 3: adb reads stdin and would swallow the rest of the file.
while IFS='|' read -r cls before after <&3; do
  cls=$(trim "$cls")
  case $cls in ''|\#*) continue ;; esac
  run_class "$cls" "$(trim "$before")" "$(trim "$after")"
done 3< "$CI/suites/$suite.txt"

# ---------- collect ----------
# pull_files SUBDIR DEST: copy the app's SUBDIR (screenshots, probe) to DEST/SUBDIR [CP16].
# STORE=external: `adb pull` from the external files dir. STORE=internal (images where the shell may not
# read Android/data, e.g. API 30 TV): `run-as` + tar from filesDir (debug APK).
pull_files() {
  local sub=$1 dest=$2 err
  if [ "$STORE" = external ]; then
    err=$(adb pull "$EXT/$sub" "$dest/" 2>&1) && { echo "adb pull $EXT/$sub"; return 0; }
    echo "  (adb pull $EXT/$sub: $err)" >&2; return 1
  fi
  adb exec-out run-as "$PKG" tar -cf - -C files "$sub" | tar -xf - -C "$dest" && [ -e "$dest/$sub" ] &&
    { echo "adb exec-out run-as $PKG tar -cf - -C files $sub"; return 0; }
  return 1
}
if how=$(pull_files screenshots "$OUT"); then
  log "screenshots: $how OK ($(find "$OUT/screenshots" -name '*.png' | wc -l) png)"
else
  log "screenshots: every pull way FAILED"
  FAILED=1
fi
if how=$(pull_files probe "$OUT"); then cp "$OUT/probe/results.txt" "$OUT/probe-results.txt" 2>/dev/null; fi
adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
echo "----- app log lines (tag Ferry) in logcat ($suite) -----"
grep -E ' (I|W|E) Ferry *:' "$OUT/logcat.txt" | tail -80
echo "----- FERRY_PROBE lines in logcat ($suite) -----"
grep 'FERRY_PROBE' "$OUT/logcat.txt" | sed 's/^.*FERRY_PROBE[^:]*: //'
# Artifacts can't be downloaded into the agent workspace (proxy), so screenshots also go into the job
# log as base64 between markers; android/ci/shots_from_log.py turns a saved job log back into PNGs.
find "$OUT" -name '*.png' | sort | while read -r png; do
  echo "FERRY_PNG_BEGIN ${png#$OUT/}"; base64 -w 0 "$png"; echo; echo "FERRY_PNG_END"
done

echo "----- probe results ($suite) -----"
cat "$OUT/probe-results.txt" 2>/dev/null
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  { echo "### $suite (attempt $attempt)"; echo '```'; cat "$RESULTS" "$OUT/probe-results.txt" 2>/dev/null; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
fi
[ "$FAILED" = 0 ] && log "suite $suite: PASS" || log "suite $suite: FAIL"
exit "$FAILED"
