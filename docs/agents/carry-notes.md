# Coordinator carry-over notes for later phases
## For Ph10 (wording / screenshot tour)
- TV "not found" message says "this phone" on the TV (Ph4).
- Toast "Sending 1 files" — singular (Ph5b).
- Location-choice dialog is wired but no instrumented test drives it; include it in the screenshot tour (Ph5b).
- New strings from B46/B53 need the wording pass.
## For Ph7
- Storage failure mid-download is treated as a dropped connection (3 tries → connection_kept_dropping); free-space check before each download is the guard (Ph3a).
- Update banner tap is a no-op until Ph7 (Ph4).
## For Ph8a
- TV focus memory (Ph4).
- TV APK cards + install dialog: APK folder files/apk and the install flow (ApkInstaller) are ready (Ph7, B59).
- Picker + permission strings (Ph6) need the wording pass; system permission dialogs belong in the screenshot tour (Ph6).
## For Ph9
- Update banner 'busy' rule must also count casting (Ph7).
- Picker's two cast modes exist but Play on TV button shows "Coming in this build" (Ph6).
## CI reliability (any phase touching run_instrumented.sh; else Stage 7)
- Ph6: on main run 63 the 2nd (cold-boot) phone attempt had no Wi-Fi route to 10.0.2.2 → every class "PC not found". Fix idea: wait for a Wi-Fi route before the first class. Also an earlier run's 2nd attempt failed ScreenOffUploadTest/CancelAndWaitingTest (1st attempt passed).
- CI: Ph7 added a Wi-Fi-route wait + dismissal of system/ANR dialogs in run_instrumented.sh.
- Ph10 wording: B60 strings.
## Added after Ph8a
- Ph8b: the temporary player screen (B68) is to be replaced; Photo and Slideshow cards show "Coming in this build".
- Ph9: a cast must keep the TV screen on (Ph8a keeps it on for 30 min after a key press and while the player is showing).
- Ph10: on the TV, the "Allow Ferry to install apps" dialog focuses Cancel first; `tv_storage_full` (B71) needs the wording pass. B72: on Fire OS 8, allowing unknown-app installs kills Ferry's process; the README should say "press OK on the APK again".
- CI flake: FromPcDownloadTest failed once on attempt 1 (10 s text wait); the build log has a suggested fix if it recurs.
## Added after Ph8b
- Ph9: player and viewer keep the TV screen on while showing; a cast must too. Update banner busy rule must count casting.
- Ph10: wording pass for B75 strings (`cant_play`, `resume_from`, `start_over`) and "Can't show this photo"; screenshot tour's "paused" shot of small.mp4 is at the very end — pause mid-video instead.
- Tests: the test tool's Back press can take several seconds during playback; read positions from what the app recorded, not from before the key press.
## Added after Ph9
- Ph10 wording: B81 strings; Remote pause/stop symbols render as colour emoji on Android 14 (use icons); casting notification uses the upload-arrow icon.
- Ph10 screenshot tour: Play on TV with several TVs / no TV, Remote "Starting…", each way a cast ends.
- Ph10 DEVICE-CHECKLIST (real devices, Stage 7 can't test): TV discovery by broadcast on the real LAN; Fire TV with UDP 8765 taken by another app (fallback to last-used TV); casting from the vivo with screen off; Fire TV playback of vivo HEVC/HDR video and HEIC photos on Fire OS 6/7.
- Tests: UiAutomator's view of Compose text can lag (B57, B82) — assert on app state; use text only for screenshots.
## Done in Ph10 (all Ph10 items above)
- Wording pass (B83), TV "this TV", plurals, install dialog focus, Remote icons, casting icon, screenshot tour (B84), README + DEVICE-CHECKLIST (B85), handoff `docs/v2-handoff.md`.
## For Stage 7 (code audit) / later
- CI flake: `ScreenOffUploadTest` "notification with MB/s" fails on some cold-boot attempt-2 runs (Ph6, Ph10 run 2); attempt 1 passes. Not investigated in Ph10 (run_instrumented.sh control flow untouched).
- UiAutomator's view of Compose text/buttons lags (B57, B82, B86): any remaining `SendHelpers.tap("<text>")`/`waitText` on a just-changed screen is a flake candidate.
- Test-only hooks in product code: `ConnectionManager.showForTest`, `Casting.showForTest`, `InstallFlow.mainFocused` (B84).
- Real-device items: `pc/DEVICE-CHECKLIST.txt` (user) and `docs/v2-handoff.md` §6.


# 2.1
## From Ph11a (for Ph11b and later)
- `EventLog` exists in `:core` but nothing writes through it yet: `AppGraph` still creates `RingLog` on
  `files/ferry-app-log.txt` (2.0 line format; rotation `.txt.1`). Ph11b: `EventLog(filesDir, …,
  LogStateStore(File(filesDir, EventLog.STATE_FILE_NAME)))` takes over the same file name; old 2.0 lines in it have
  no `#seq` and are skipped by `LogReader`/the PC; `SettingsScreen` share-log uses `ringLog.previousFile` (2.0
  name `ferry-app-log.txt.1`) → switch to `EventLog.previousFile` (`ferry-app-log.1.txt`). Decide `RingLog`'s fate (B106).
- `FerryHttp.newClient(…, deviceId, deviceType)`: `NetworkBinder` must pass `Prefs.installId` (4 hex) and
  `"phone"`/`"tv"` once Ph11b adds `installId`.
- Ship with `LogReader(filesDir).linesAfter(eventLog.shipped)` → `api.postLogs(chunk.fromSeq, chunk.body)` →
  `eventLog.markShipped(reply.lastSeq)`; `FerryException.LogsUnsupported` → off for the process; log
  `clock peer=pc offset_ms=` from `reply.timeMs` / `PcInfo.timeMs`.
- Host check for LogShippingTest / TV: suite "after" step `check_device_log A send.done` / `check_device_log T device_name`
  (wraps `verify_inbox.py device-log`, B104). Logs come out of CI with `python3 android/ci/shots_from_log.py --logs job.log OUT`.
- `EventLog` tail keeps `diag` as `diag <tag> <msg>` (B107), so 2.0 androidTests that search `ringLog.snapshot()`
  keep matching once the shim is in.
