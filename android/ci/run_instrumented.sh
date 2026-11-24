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
  for d in "$TMP"/*/; do
    cp "$d"data/ferry-*.txt "$d"data/ferry-*.log "$OUT/" 2>/dev/null
    # 2.1 (CP110, CP134): the PC log and the shipped phone/TV logs live in data/logs/
    if [ -d "$d"data/logs ]; then mkdir -p "$OUT/logs-$(basename "$d")" && cp -r "$d"data/logs/. "$OUT/logs-$(basename "$d")/" 2>/dev/null; fi
  done
  true
}
trap cleanup EXIT

log() { echo "[$suite] $*"; echo "$*" >> "$RESULTS"; }
adbs() { adb shell "$@" | tr -d '\r'; }
# Stage 7: is the emulator still there? Run 44030175847: the phone emulator crashed after a class ("adb: device
# offline"), the next classes failed at once, then `adb logcat -d` waited for the device until the job's 45-minute
# limit cancelled it, so attempt 2 never ran. Now the suite stops at the first offline check and no adb call can wait
# for ever, so the job goes on to attempt 2 on a new emulator.
alive() { [ "$(timeout 15 adb get-state 2>/dev/null | tr -d '\r')" = device ]; }

# ---------- host PCs ----------
# start_pc NAME PORT [outbox seed files…]   (env APPDIR: the PC's --app-dir, Ph7 update test)
start_pc() {
  local name=$1 port=$2; shift 2
  local d=$TMP/$name extra=()
  mkdir -p "$d/inbox" "$d/outbox"
  cp -r "$ROOT/pc" "$d/pc"
  rm -rf "$d/pc/tests"
  for f in "$@"; do cp "$f" "$d/outbox/"; done
  mkdir -p "$d/data"
  if [ -n "${APPDIR:-}" ]; then extra=(--app-dir "$APPDIR"); fi
  (cd "$d/pc" && exec "$PY" -u ferry.py --no-open --port "$port" --discovery-port off \
      --inbox "$d/inbox" --outbox "$d/outbox" --data-dir "$d/data" "${extra[@]}") > "$OUT/console-$name.txt" 2>&1 &
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

# An --app-dir with the debug APK as "Ferry 9.9.9" (version_code 2000000000), so the app offers an update.
make_app_dir() {
  local a=$1
  mkdir -p "$a"
  cp "$APKS/app-debug.apk" "$a/Ferry.apk" &&
    "$PY" "$CI/make_apk_json.py" "$a/Ferry.apk" 2000000000 9.9.9 > "$a/Ferry.apk.json" || return 1
  log "app bundle $a: $(tr -d '\n ' < "$a/Ferry.apk.json")"
}
# PC-U (plan §4.14, phone): only the update bundle.
start_pc_update() { make_app_dir "$TMP/U-app" && APPDIR=$TMP/U-app start_pc U 18767; }
# PC-T (plan §4.14, TV, Ph8a): update bundle [PB20]; outbox big.mp4, small.mp4, 5 photos + broken.jpg,
# Test.apk (copy of the debug APK), notes.txt, old.avi (kind "video": Other files [A12]).
# start_pc_tv [PORT]: 18765 in the TV suite; the API 25 suite runs it next to PC-A on 18769 (Stage 7, audit O6).
start_pc_tv() {
  local s=$TMP/T-seed f port=${1:-18765}
  mkdir -p "$s"
  make_app_dir "$TMP/T-app" || return 1
  cp "$APKS/app-debug.apk" "$s/Test.apk" || return 1
  printf 'Ferry TV test notes\n' > "$s/notes.txt"
  head -c 200000 "$CI/fixtures/small.mp4" > "$s/old.avi"
  set -- "${FERRY_BIG_VIDEO:-$HOME/big-video/big.mp4}" "$CI/fixtures/small.mp4" "$s/Test.apk" "$s/notes.txt" "$s/old.avi"
  for f in photo1 photo2 photo3 photo4 photo5 broken; do set -- "$@" "$CI/fixtures/$f.jpg"; done
  APPDIR=$TMP/T-app start_pc T "$port" "$@"
}

# The emulator's Wi-Fi network must have a route before the app can reach 10.0.2.2 on it (Ph6: once, on a cold-boot
# retry, every class failed with "PC not found" and ENETUNREACH). Waits up to 45 s for a wlan0 route; if there is
# none, Wi-Fi is switched off and on once and waited for again. Only logs; the classes judge.
wait_wifi_route() {
  local r=""
  for _ in $(seq 1 45); do
    r=$(adbs ip route show table all 2>/dev/null | grep 'dev wlan0' | head -3)
    [ -n "$r" ] && { log "wifi route: $(echo "$r" | tr '\n' ';')"; return 0; }
    sleep 1
  done
  log "wifi route: none for wlan0 after 45 s; switching Wi-Fi off and on"
  adbs svc wifi disable > /dev/null 2>&1; sleep 2; adbs svc wifi enable > /dev/null 2>&1
  for _ in $(seq 1 30); do
    r=$(adbs ip route show table all 2>/dev/null | grep 'dev wlan0' | head -3)
    [ -n "$r" ] && { log "wifi route after toggle: $(echo "$r" | tr '\n' ';')"; return 0; }
    sleep 1
  done
  log "wifi route: still none ($(adbs ip -4 addr show wlan0 2>&1 | grep inet | tr -s ' '))"
}

# ---------- host steps usable in suite files ----------
# Ph7: an empty Download/Ferry (a retry attempt or an earlier run may have left files there).
clear_downloads() {
  adbs rm -rf /sdcard/Download/Ferry > /dev/null 2>&1
  adbs content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1
  log "clear_downloads: /sdcard/Download/Ferry removed, volume rescanned"
}
grant() {
  local err
  if err=$(adb shell pm grant "$PKG" "android.permission.$1" 2>&1 | tr -d '\r') && [ -z "$err" ]; then log "granted $1"
  else log "grant $1 refused: $(echo "$err" | grep -m1 -i exception)"; fi
}
# Ph8a (B72): what the user does in "Install unknown apps"; on API 30 it kills the app, so it runs between classes.
allow_installs() { adbs appops set "$PKG" REQUEST_INSTALL_PACKAGES allow > /dev/null 2>&1; log "allow_installs: $(adbs appops get "$PKG" REQUEST_INSTALL_PACKAGES)"; }
# Stage 7 (audit O6): the next class runs in the TV layout (debug UI override) against PC-T on 18769. Used by the
# API 25 suite, whose image is a phone image: Fire OS 6 is API 25, and it only ever runs the TV layout.
CLASS_ARGS=()
tv_mode() { CLASS_ARGS=(-e app.ferry.debug.UI tv -e pcA 10.0.2.2:18769); log "tv_mode: next class in the TV layout, PC-T on 18769"; }
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

# Ph6 picker test: six shell-owned photos in two albums (DCIM/AlbumA, Pictures/AlbumB), dated now so they are the
# newest in Recent; one scan_volume after all pushes (it rescans the whole volume).
push_pick_photos() {
  local f
  for f in "DCIM/AlbumA/PICK_A1.jpg photo1.jpg" "DCIM/AlbumA/PICK_A2.jpg photo2.jpg" "DCIM/AlbumA/PICK_A3.jpg photo3.jpg" \
           "Pictures/AlbumB/PICK_B1.jpg photo4.jpg" "Pictures/AlbumB/PICK_B2.jpg photo5.jpg" "Pictures/AlbumB/PICK_B3.jpg photo1.jpg"; do
    set -- $f
    adbs mkdir -p "/sdcard/$(dirname "$1")" > /dev/null
    push_media "$2" "/sdcard/$1"
    adbs touch "/sdcard/$1" > /dev/null
  done
  scan_media /sdcard/Pictures/AlbumB/PICK_B3.jpg
}

# Ph9 cast loopback test: a 60 s video in Movies/ and three photos in Pictures/Cast, made by the shell and touched
# (newest in the picker), one scan_volume; the video row is logged (media_row only looks at images).
push_cast_media() {
  local f
  adbs mkdir -p /sdcard/Movies /sdcard/Pictures/Cast > /dev/null
  for f in "Movies/CAST_60s.mp4 cast.mp4" "Pictures/Cast/CAST_1.jpg photo2.jpg" "Pictures/Cast/CAST_2.jpg photo3.jpg" \
           "Pictures/Cast/CAST_3.jpg photo4.jpg"; do
    set -- $f
    push_media "$2" "/sdcard/$1"
    adbs touch "/sdcard/$1" > /dev/null
  done
  scan_media /sdcard/Pictures/Cast/CAST_3.jpg
  log "push_cast_media: video row: $(adbs "content query --uri content://media/external/video/media --projection _display_name:owner_package_name:is_pending --where \"_display_name='CAST_60s.mp4'\"" 2>&1 | grep -m1 'Row:')"
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
  verify uploads --log "$TMP/A/data/logs/ferry-log.txt" --name "$GPS_NAME" --expect saved,skipped --label PC-A
  # 2.1 (Ph12, L5): the PC's readable line read the photo's GPS itself
  verify readable-line --log "$TMP/A/data/logs/ferry-log.txt" --name "$GPS_NAME" --expect "GPS yes" --label PC-A
}
# Stage 7 (audit O1): the photo shared with "Allow" reached PC-U as the original.
check_share_original() {
  sleep 1
  verify identical --inbox "$TMP/U/inbox" --name "$SHARE_GPS_NAME" --fixture "$CI/fixtures/gps.jpg" --mtime-s "$(date_modified "$SHARE_GPS_NAME")" --label PC-U
}
# 2.1 (Ph11a, used from Ph11b): PC NAME holds a shipped device log with these events (comma list), e.g.
#   check_device_log A send.done     check_device_log T device_name
check_device_log() { sleep 2; verify device-log --logs "$TMP/$1/data/logs" --expect "$2" --label "PC-$1"; }
check_no_partials() { sleep 2; verify no-partials --inbox "$TMP/$1/inbox" --label "PC-$1"; }
# Ph5b: the three files the share helper sent (android/sharehelper ShareActivity.FILES) arrived byte-identical.
check_share() {
  sleep 1
  verify pattern --inbox "$TMP/A/inbox" --name ferry-share-big.bin --index 1 --size $((30 * 1048576)) --label PC-A
  verify pattern --inbox "$TMP/A/inbox" --name ferry-share-notes.txt --index 2 --size 20000 --label PC-A
  verify pattern --inbox "$TMP/A/inbox" --name ferry-share-small.bin --index 3 --size 300000 --label PC-A
}
# Ph6: PC-A received exactly the three picked photos (names as on the phone).
check_picker() { sleep 1; verify names --inbox "$TMP/A/inbox" --prefix PICK_ --expect PICK_A1.jpg,PICK_A2.jpg,PICK_B1.jpg --label PC-A; }
# Ph5b: the screen-off upload has the size of the phone's file.
check_screen_off() {
  local n=SCREENOFF_280MB.jpg size
  size=$(adbs stat -c %s "/sdcard/DCIM/Camera/$n")
  verify size --inbox "$TMP/A/inbox" --name "$n" --size "$size" --label PC-A
}

# ---------- one test class ----------
# Pass rule (plan §4.14 [PA16]): INSTRUMENTATION_CODE: -1 and an "OK (" line, and none of the failure marks.
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
# Ph13 (CP142, B135): SetupFlowTest needs a fresh install. The APK is reinstalled (uninstall + install, no -g): that
# resets runtime permissions, app-ops and the battery exemption, which `pm clear` does not promise. The app's files
# (screenshots, probe results of the classes so far) are saved first; the probe results are merged at the end.
fresh_install() {
  pull_files screenshots "$OUT" > /dev/null && log "fresh_install: screenshots so far saved"
  mkdir -p "$TMP/pre-fresh" && pull_files probe "$TMP/pre-fresh" > /dev/null && log "fresh_install: probe results so far saved"
  adb uninstall "$PKG" > /dev/null 2>&1
  adb shell rm -rf "/sdcard/Android/data/$PKG" > /dev/null 2>&1
  if adb install -r "$APKS/app-debug.apk" > /dev/null 2>&1; then log "fresh_install: app reinstalled (no permissions)"
  else log "fresh_install: reinstall FAILED"; FAILED=1; fi
}
# Battery-optimisation allow-list (setup step 3; Ph15's staged job uses "on").
idle_whitelist() {
  case $1 in on) adbs dumpsys deviceidle whitelist "+$PKG" > /dev/null 2>&1 ;; *) adbs dumpsys deviceidle whitelist "-$PKG" > /dev/null 2>&1 ;; esac
  log "idle_whitelist $1"
}
judge() {
  grep -q 'INSTRUMENTATION_CODE: -1' "$1" && grep -q '^OK (' "$1" &&
    ! grep -qE 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2|INSTRUMENTATION_FAILED|Process crashed' "$1"
}

FAILED=0
run_class() {
  local cls=$1 before=$2 after=$3
  local raw=$OUT/am-${cls##*.}.txt
  adb shell am force-stop "$PKG"
  # Ph7 B67: a cold-booted image once showed "System UI isn't responding" over every class; close system dialogs.
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
  if [ -n "$before" ]; then eval "$before"; fi
  log "=== $cls"
  timeout 900 adb shell am instrument -w -r -e class "$cls" "${INST_ARGS[@]}" "${CLASS_ARGS[@]}" \
    "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' > "$raw" 2>&1
  if judge "$raw"; then log "PASS $cls"
  else
    log "FAIL $cls"; FAILED=1
    grep -E -A45 'INSTRUMENTATION_STATUS: stack=|Process crashed' "$raw" | grep -vE 'at android\.view\.(View|ViewGroup)\.' | head -70
  fi
  CLASS_ARGS=()
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
  tv) { start_pc_tv && start_pc E 18768; } || exit 1 ;;   # PC-E: empty outbox (Ph10 tour: empty TV home)
  phone) { start_pc A 18765 &&
           start_pc B 18766 "$CI/fixtures/report.pdf" "$CI/fixtures/small.mp4" "$CI/fixtures/photo1.jpg" &&
           start_pc_update; } || exit 1 ;;
  *)  { start_pc A 18765 "$CI/fixtures/report.pdf" && start_pc_tv 18769; } || exit 1 ;;   # PC-T: TV layout on API 25 (O6)
esac

GPS_NAME=IMG_20250101_120000.jpg
SHARE_GPS_NAME=IMG_20250102_130000.jpg
GPS_SHA=$(sha256sum "$CI/fixtures/gps.jpg" | cut -d' ' -f1)
# Where the app keeps screenshots/probe files [CP16]: the shell can't read Android/data on API 30 TV (Ph0b).
case $suite in tv) STORE=internal ;; *) STORE=external ;; esac
INST_ARGS=(-e pcA 10.0.2.2:18765 -e pcB 10.0.2.2:18766 -e pcU 10.0.2.2:18767 -e pcE 10.0.2.2:18768 -e suite "$suite" -e store "$STORE" -e gpsName "$GPS_NAME" -e shareGpsName "$SHARE_GPS_NAME" -e gpsSha "$GPS_SHA" -e bigName BIG_300MB.jpg -e screenOffName SCREENOFF_280MB.jpg)
# API 25 image: its only network is reported as cellular, so the app uses the debug NET=any override [PR2].
if [ "$suite" = api25 ]; then INST_ARGS+=(-e app.ferry.debug.NET any); fi

adb uninstall "$PKG" > /dev/null 2>&1
adb uninstall "$PKG.test" > /dev/null 2>&1
# A retry reinstalls under a new UID: files the first attempt left in Android/data would be EACCES (Ph5a B49).
adb shell rm -rf "/sdcard/Android/data/$PKG" > /dev/null 2>&1
adb install -r "$APKS/app-debug.apk" || exit 1
adb install -r "$APKS/app-debug-androidTest.apk" || exit 1
# Phone: the share helper app (another UID) for ShareToFerryTest [CP21].
if [ "$suite" = phone ]; then
  adb uninstall "$PKG.sharehelper" > /dev/null 2>&1
  adb install -r "$APKS/sharehelper-debug.apk" || exit 1
fi
if [ "$SDK" -ge 33 ]; then grant POST_NOTIFICATIONS; fi
if [ "$suite" = phone ]; then wait_wifi_route; fi
# Ph7 B67: no ANR/crash dialogs of other apps (System UI on a cold boot) on top of the screen under test.
adbs settings put global hide_error_dialogs 1 > /dev/null 2>&1
log "hide_error_dialogs=$(adbs settings get global hide_error_dialogs)"
adb logcat -c

trim() { local s=$1; s=${s#"${s%%[![:space:]]*}"}; s=${s%"${s##*[![:space:]]}"}; printf '%s' "$s"; }
# Suite file: one line per class, "Class | host steps before | host check after" (# comments) [PA10].
# Read on fd 3: adb reads stdin and would swallow the rest of the file.
while IFS='|' read -r cls before after <&3; do
  cls=$(trim "$cls")
  case $cls in ''|\#*) continue ;; esac
  run_class "$cls" "$(trim "$before")" "$(trim "$after")"
  if ! alive; then
    log "emulator offline after $cls: suite stopped (the next attempt starts a new emulator)"
    FAILED=1; OFFLINE=1; break
  fi
done 3< "$CI/suites/$suite.txt"

# ---------- collect ----------
if [ "${OFFLINE:-0}" = 1 ]; then
  log "screenshots, probe files and logcat not collected: the emulator is offline"
elif how=$(pull_files screenshots "$OUT"); then
  log "screenshots: $how OK ($(find "$OUT/screenshots" -name '*.png' | wc -l) png)"
else
  log "screenshots: every pull way FAILED"
  FAILED=1
fi
if [ "${OFFLINE:-0}" != 1 ]; then
  if how=$(pull_files probe "$OUT"); then cp "$OUT/probe/results.txt" "$OUT/probe-results.txt" 2>/dev/null; fi
  # fresh_install (Ph13): the probe results written before the reinstall come first.
  if [ -f "$TMP/pre-fresh/probe/results.txt" ]; then
    cat "$TMP/pre-fresh/probe/results.txt" "$OUT/probe-results.txt" > "$OUT/probe-merged.txt" 2>/dev/null &&
      mv "$OUT/probe-merged.txt" "$OUT/probe-results.txt"
  fi
  timeout 120 adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
fi
echo "----- app log lines (tag Ferry) in logcat ($suite) -----"
grep -E ' (I|W|E) Ferry *:' "$OUT/logcat.txt" | tail -80
echo "----- app crashes in logcat ($suite) -----"
grep -A40 'FATAL EXCEPTION' "$OUT/logcat.txt" | grep -E 'AndroidRuntime' | head -120
echo "----- FERRY_PROBE lines in logcat ($suite) -----"
grep 'FERRY_PROBE' "$OUT/logcat.txt" | sed 's/^.*FERRY_PROBE[^:]*: //'
# Artifacts can't be downloaded into the agent workspace (proxy), so screenshots also go into the job
# log as base64 between markers; android/ci/shots_from_log.py turns a saved job log back into PNGs.
find "$OUT" -name '*.png' | sort | while read -r png; do
  echo "FERRY_PNG_BEGIN ${png#$OUT/}"; base64 -w 0 "$png"; echo; echo "FERRY_PNG_END"
done

# 2.1 (CP134 [PS9]): every PC's logs folder (its ferry-log.txt and the phone/TV app-log.txt it received) goes
# into the job log too, gzip + base64 between "FERRY_LOG_BEGIN <PC>/logs/<path>" and "FERRY_LOG_END";
# `shots_from_log.py --logs JOB.log OUTDIR` turns them back into files.
print_logs() {
  local d name f
  for d in "$TMP"/*/; do
    name=$(basename "$d")
    [ -d "$d"data/logs ] || continue
    find "$d"data/logs -type f -name '*.txt' | sort | while read -r f; do
      echo "FERRY_LOG_BEGIN $name/logs/${f#"$d"data/logs/}"; gzip -c "$f" | base64 -w 0; echo; echo "FERRY_LOG_END"
    done
  done
}
print_logs

echo "----- probe results ($suite) -----"
cat "$OUT/probe-results.txt" 2>/dev/null
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  { echo "### $suite (attempt $attempt)"; echo '```'; cat "$RESULTS" "$OUT/probe-results.txt" 2>/dev/null; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
fi
[ "$FAILED" = 0 ] && log "suite $suite: PASS" || log "suite $suite: FAIL"
exit "$FAILED"
