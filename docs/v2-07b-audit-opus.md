# Ferry v2 — Stage 7 code audit (reviewer: Opus)

**Scope:** repo `/home/claude/ferry`, branch `main` @ c6269ac. Focus: (1) reliability on the real devices (vivo X200 Pro, Fire TV Stick Fire OS 6/7/8, Windows 10 PC), (2) TV / casting / player, (3) UX for a non-developer incl. README / DEVICE-CHECKLIST accuracy, (4) requirement coverage, (5) maintainability/tests/CI. Upload/download file-safety and PC file handling are covered by the other reviewer and are not repeated here.

Read: v2-audit-brief, v2-01-requirements, v2-02-design (skimmed), v2-decisions-log, v2-06-build-log (B1–B86, skimmed). Android code cannot be built here: anything not proven by experiment is marked **uncertain**.

## Findings


### O1 — Medium — "Allow" for photo location from Share / "Send other files" can loop forever
**Where:** `android/app/src/main/java/app/ferry/ui/common/SendFlow.kt:96-110` (`askLocation` / `onAllow`), `ui/common/PermissionPlanner.kt:46` (`locationPermission` = ACCESS_MEDIA_LOCATION only), `AndroidManifest.xml:51` (`noHistory` on the share activity).
**Problem:** The share activity and "Send other files" ask for `ACCESS_MEDIA_LOCATION` **alone**. The user who shares from the vivo Gallery before ever using "Send photos & videos" (H23: media permission is only asked there) holds no `READ_MEDIA_IMAGES`. (a) On recent Android the permission controller does not grant photo-location without photo access (it shows no dialog and returns "denied") — uncertain, could not run an emulator here. (b) Certain from the code: whatever the reason for "denied", the callback just prepares again, which shows the **same** "Allow / Send without location" dialog; after two refusals Android auto-denies without any dialog, so "Allow" becomes a button that does nothing visible, for ever. Unlike `MediaGate` there is no "Open Settings" fallback. The only exit is "Send without location", which is exactly what H19 wants to avoid (acceptance 3 / checklist C5 expects the location to arrive). (c) Also untested: no androidTest taps **Allow** in the share activity (`GpsNegativeControlTest` covers only "Send without location"); the activity is `noHistory`, whose docs say `onActivityResult` is not delivered once it is no longer visible — probably fine for the dialog-styled permission screen, but unverified on vivo (uncertain).
**Fix:** In the share/documents path request `PermissionPlanner.mediaPermissions(sdk)` (photos + location together, same as the picker) instead of location alone; if the result is still "no location", switch the dialog to "Open Settings → Permissions → Photos and videos → Allow all" (as `MediaGate.Ask.Settings`) rather than re-showing the same choice. Add one phone test: share a media URI with no media permission → Allow → grant → GPS intact.

### O2 — Medium — Play on TV has no way in when broadcast discovery of the TV fails
**Where:** `ui/phone/CastViewModel.kt:49-72` (`find`), `ui/phone/PlayOnTvScreen.kt` (no address entry), `ui/tv/TvHomeScreen.kt:160-163` (idle line shows the TV name, not its address); `pc/DEVICE-CHECKLIST.txt` G5.
**Problem:** The phone finds a TV only by (1) the remembered last TV or (2) a UDP broadcast answered by the TV. The first time, (1) does not exist, so if the broadcast doesn't get through (router "AP isolation"/broadcast filtering, the TV's UDP 8765 taken by another app — the case `CastReceiverHost` itself anticipates and checklist G5 mentions — or the TV's discovery socket bound to a stale network, see O9), Play on TV is a dead end: "No TV found … tap Find again" for ever. G5 says "Ferry then uses the TV it played on last time", which is not true for a first use, and a DHCP address change of the TV puts the user back there later. For the PC the requirement's fallback (P8, H4) exists on both phone and TV; design §3.5 says "TVs are found the same way", but the manual step was not built for TVs.
**Fix:** Show the TV's own address on the TV home idle line ("Ready for your phone · this TV is 192.168.1.30"), and add "Enter TV address" on Play on TV (number keyboard prefilled with the phone's first three octets, verified with `GET /api/v1/state`, stored as `lastTv`). Correct G5.

### O3 — Medium — TV "Enter address": the cursor starts before the prefilled "192.168.1."
**Where:** `ui/tv/TvPcNotFound.kt:74-78, 90-106`.
**Problem:** The field is a `String`-overload `OutlinedTextField`; it is composed for the first time with the prefilled text already set. That overload keeps its own `TextFieldValue(text = value)` whose selection defaults to `TextRange.Zero`, i.e. the cursor sits at the **start**. Typing the last number on the Fire TV keyboard then gives "5192.168.1." → "Type the PC's address…" error. A remote-only user cannot easily move a text cursor. Also, pressing **Enter address** leaves the focus on that button; the user has to discover that Down reaches the field and OK opens the keyboard. `TvPcNotFoundTest` only checks that the prefill text exists, not typing. (Compose behaviour from source knowledge; not run here — uncertain.)
**Fix:** Use the `TextFieldValue` overload with `selection = TextRange(prefill.length)`; on "Enter address" move focus into the field (`FocusRequester`) and show the keyboard (`LocalSoftwareKeyboardController.show()`). Extend the test to type a digit and assert the parsed address.

### O4 — Medium — A paused or finished video / photo keeps the TV awake indefinitely
**Where:** `ui/tv/KeepScreenOn.kt:47-56` (`Pinned`), used by `TvPlayerScreen.kt:101` and `TvViewerScreen.kt:136`.
**Problem:** `FLAG_KEEP_SCREEN_ON` is held for as long as the Player or Viewer is on screen, whatever its state. A video that ended (last frame frozen), a video paused from the phone and forgotten, or the single-photo viewer (which opens **paused**, B77) keeps the Fire TV from its screensaver and sleep all night — a static image on an OLED TV for hours. This follows design §5.1, but contradicts the stated aim of H24 ("a forgotten TV still goes to sleep"), so it is flagged as real harm.
**Fix:** Pin only while something is actually advancing (`player.isPlaying` / slideshow `playing`); when paused or ended, fall back to the Home rule (`poke` → off 30 min later).

### O5 — Medium — "Retry" in the Results notification silently does nothing once the process has gone
**Where:** `service/Notifications.kt:73-75` (Retry action), `service/Transfers.kt:78` (`retry` on a null engine), `service/TransferService.kt:81-84`.
**Problem:** The Results notification (e.g. "Couldn't reach … for 30 minutes. 12 files were not sent. Tap Retry when the PC is on.") is often acted on much later. By then vivo has usually killed the idle process; the queue is in memory (H16), so `engine` is null, `retry()` is a no-op, the service starts, finds nothing to do and stops. The notification is auto-cancelled; nothing is shown. A non-developer believes it is sending.
**Fix:** When `ACTION_RETRY` arrives and there is no engine (or nothing retryable), open the Transfer screen / Home with a one-line "Ferry was closed since then. Send the files again — files already on the PC are skipped." (or open the picker). Cheap and removes a silent dead end.

### O6 — Medium — The Fire OS 6 (API 25) TV layout is never run in CI
**Where:** `android/ci/suites/api25.txt` (only `Api25LaunchTest` in **phone** layout + `HostReachProbe`), `android/app/src/androidTest/java/app/ferry/Api25LaunchTest.kt`.
**Problem:** API 25 exists in the requirements only because of older Fire TV Sticks (H5), i.e. the **TV** layout. The API 25 job proves `:core`, the phone Home and the From PC list on API 25, but none of what a Fire OS 6 stick actually runs: TV home + D-pad focus, the Media3 player over the Wi-Fi-bound OkHttp client, the cast receiver (`CastReceiverHost`: UDP 8765 responder, `MulticastLock`, `ServerSocket` 8766), the APK download through `TransferService` (foreground type 0 on < 29) and the API 25 install branch (`Settings.Secure.INSTALL_NON_MARKET_APPS`, steps text, FileProvider → installer). Lint `NewApi` (H34) catches missing methods, not behaviour. The `app.ferry.debug.UI=tv` override already exists, so this is cheap.
**Fix:** Add to `suites/api25.txt` the existing TV classes with `UI=tv` (at least `TvLaunchAndFocusTest`, `TvResumeTest`/a short play, `TvCastReceiveTest`, and the install dialog up to the steps text). Until then, list "TV layout on Fire OS 6" under "What CI does not prove" in the handoff/checklist.

### O7 — Low — On the TV the update banner needs "Up, then OK", but the README says "press OK"
**Where:** `ui/tv/TvHomeScreen.kt:109-127` (focus always goes to the first card, never to the banner), `ui/common/UpdateBanner.kt`; `pc/README.txt` §6 step 4 ("Tap it (on the TV press OK)").
**Problem:** When the banner appears, focus is on the first video card; pressing OK as the README says **starts that video** instead of the update. The user has to know to press Up first.
**Fix:** On TV, give the banner the initial focus when it is present (it is the most important action at that moment), or change the README to "press Up to the yellow bar, then OK".

### O8 — Low — TV slideshow interval of outbox photos is fixed at 5 s
**Where:** `ui/tv/TvViewerScreen.kt:79` reads `AppGraph.prefs.slideshowSeconds`, which only the **phone's** Settings can change (`ui/phone/SettingsScreen.kt:120-124`); the TV has no setting and its own prefs.
**Problem:** H6 says "default 5 s (3/5/10 selectable)". For PC-outbox slideshows on the TV (T3) the choice does not exist; only phone casts can change it (Remote).
**Fix:** In the TV slideshow, let Up/Down cycle 3/5/10 s (shown in the overlay) and store it in the TV's prefs. Small, no new screen.

### O9 — Low (uncertain) — TV discovery socket stays bound to the network it started on
**Where:** `ui/tv/CastReceiverHost.kt:62-71` (`DatagramTransport(…, binder = AppGraph.network::bindDatagram)` once per `onStart`).
**Problem:** The responder's socket is bound (`Network.bindSocket`) to the Wi-Fi `Network` present at `onStart`. If the TV's Wi-Fi reconnects while Ferry stays open (router restart), Android creates a new `Network`; replies sent from the old binding fail (ENONET) and the loop only logs them, so the phone no longer finds the TV until Ferry is reopened. Not verified (needs a real network change).
**Fix:** In `CastReceiverHost`, collect `AppGraph.network.chosen` and restart the responder when the network changes (or don't bind the responder at all: it only answers unicast to the asker, and a TV has no mobile data).

### O10 — Low — Share-only users never get the notification permission prompt
**Where:** `AskNotificationsOnce()` is called only in `ui/phone/TransferScreen.kt:74`, `RemoteScreen.kt:65`, `FromPcScreen.kt:197`; `share/ShareReceiverActivity.kt` finishes without opening the Transfer screen.
**Problem:** P4 wants progress and the final summary in the notification. If the user's first sends are Share → Ferry (common from the Gallery), Android 13+ never shows the notification prompt, so uploads run with no visible progress or result, and the Results notification's Retry is never seen.
**Fix:** Ask once from the share activity too (it is visible; one system dialog before the toast), or open the Transfer screen once for the very first share.

### O11 — Low — Test-only state objects are load-bearing product state; some lists grow without bound
**Where:** `ui/tv/TvPlayerScreen.kt:209-220` (`TvPlayerState`, "for tests") is what `TvCastScreen.kt:57-67, 70-84` uses to execute the phone's pause/seek and report state; `TvViewerScreen.kt:233-277` (`TvViewerState`, "for tests") feeds the cast state at `TvCastScreen.kt:96-104`; `CastSinkImpl.received` (`CastSinkImpl.kt:48-49`) and `TvViewerState.moves` are `CopyOnWriteArrayList`s that are only cleared per viewer open / never. Plus `ConnectionManager.showForTest`, `Casting.showForTest`, `InstallFlow.mainFocused` in product classes.
**Problem:** A future session "removing test hooks" would break casting control; the lists grow for as long as the TV app stays open (every cast, control and photo change), each add copying the whole array.
**Fix:** Rename/document `TvPlayerState.host` and `TvViewerState.index/name/playing/intervalMs` as the cast bridge (product), keep pure test counters separate; cap `received`/`moves` (e.g. last 200). The `showForTest` hooks are acceptable as they are (`@VisibleForTesting`), but a lint `VisibleForTests` error level would keep them test-only.

### O12 — Low — TV thumbnail disk cache holds full originals, not 320 px thumbnails
**Where:** `ui/tv/TvThumbnails.kt:24-31` (Coil `diskCache` 100 MB, `diskCacheKey = name|size|mtime`).
**Problem:** Coil's disk cache stores the **network response** (the 5–15 MB original; vivo 50/200 MP photos are larger), not the decoded 320 px bitmap. 100 MB therefore holds only ~7–15 photos, so the "cached on disk, the refresh never reloads" intent (design §5.1 [A11]) mostly doesn't hold beyond the memory cache, while 100 MB of a Fire TV Stick's small storage is used (and competes with `files/apk` installs, which check free space).
**Fix:** Drop the disk cache (memory cache already prevents the 5 s refresh reloads) or cut it to ~30 MB; describe it accurately in the comment. (No PC thumbnails — that would be feature creep.)

### O13 — Low — "Interrupted transfer" banner blames vivo after the user stopped Ferry
**Where:** `service/TransferService.kt:161-174` (`onDestroy` cancels but leaves `prefs.interruptedCount`, written at line 130).
**Problem:** When the service is destroyed while busy (user "Stop" in Android's active-apps list, or swiping away while sending), the marker written every 5 s survives and the next start says "The last transfer stopped early… vivo may have closed Ferry. Do these two steps once". After a deliberate stop this is misleading (minor).
**Fix:** In `onDestroy` after `t.cancel()`, clear `interruptedCount` when the destroy was a normal service stop (not a process kill, where `onDestroy` doesn't run anyway).

### O14 — Low — README / checklist wording that doesn't match the app
**Where:** `pc/README.txt` §7 "\"Nothing found\" when you tap Play on TV" — the app says "No TV found. On the TV, open Ferry…" (`strings.xml` `tv_not_found`); `pc/DEVICE-CHECKLIST.txt` G5 "Ferry then uses the TV it played on last time" (not true on first use, see O2); README §6 step 4 (see O7).
**Fix:** Quote the on-screen text exactly ("No TV found"), fix G5, and the TV update instruction.

### O15 — Low (uncertain) — "Can't play this format/sound" box stays over a video that does play
**Where:** `ui/tv/PlayerHost.kt:68-76` (`onTracksChanged` sets `message`, nothing ever clears it), `ui/tv/TvPlayerScreen.kt:157-164` (box drawn in the centre while `message != null`).
**Problem:** `Tracks.Group.isSupported` is false for a track that merely **exceeds** the decoder's advertised capabilities (e.g. 4K from the vivo on a 1080p stick), yet Media3's default track selector still plays such tracks when it can. Then the centred black box "This TV can't play this video format (HEVC)" covers a playing video for its whole length; likewise an unsupported audio track keeps the box over a silent but playing picture. The check for real-device behaviour is checklist F3; not reproducible here.
**Fix:** Show the format line only when playback actually fails (`onPlayerError`) or when no video frame has rendered after a few seconds; show the sound line as a smaller notice that hides after ~5 s while the position advances.

### O16 — Low — "PC not found" never retries by itself
**Where:** `net/ConnectionManager.kt:84-94` (locates only on activity start, network change, `reportFailure`, "Try again"); `ui/tv/TvPcNotFound.kt`, `ui/phone/HomeScreen.kt:133-142`.
**Problem:** Typical first use on the TV: open Ferry, see "Can't find your PC", walk to the PC and start Ferry.bat. The TV app stays in the foreground (no `onStart`), so it keeps saying "not found" until the user presses Try again; the phone likewise if it stays on Home. One press (Try again is focused on the TV), but P8 promises "finds the PC automatically".
**Fix:** While the state is NotFound/NoWifi and a screen is visible, relocate every ~10 s (cheap: one `/info` + a 2.6 s broadcast).

### O17 — Low — Small dead code and tests that stopped looking at the screen
**Where:** `service/Notifications.kt:83` `update()` (no caller); `core/.../discovery/PcLocator.kt:109` `findTvs()` (only tests; the app uses `ConnectionManager.discoverTvs`); `data/Prefs.kt` `batteryAsked` (written in `WelcomeScreen.kt:54`, never read); `pc/ferry.py` `hostname_local()` (build log Ph1: unused); `androidTest/.../probe/HostReachProbe` still run in all three suites as a "throw-away" probe. `FromPcDownloadTest` (B82, B86) now asserts the saved name and the Open action from app state / by calling `FromPcActions.open` directly when UiAutomator can't see the text.
**Problem:** Dead paths mislead future sessions (two ways to find TVs, a flag that looks meaningful). After B82/B86 a regression where From PC no longer *shows* "Saved in Download/Ferry as …" or the Open button would still pass CI (the screenshot is only recorded).
**Fix:** Delete the unused members; either retire `HostReachProbe` or rename it as a real test; in `FromPcDownloadTest`, keep the state assertions but also assert the node via the Compose semantics tree (`onNodeWithText(...)`, which doesn't depend on UiAutomator's accessibility lag) so the UI itself is checked.

## Summary

**Count:** 0 High, 6 Medium (O1–O6), 11 Low (O7–O17). Nothing found that loses or corrupts a file within this scope, and nothing that should block the real-device test. O1, O2 and O3 are the ones the user is most likely to hit during the checklist (C5, G1/G5, the TV "Enter address").

**What I checked and why I think it's sound:**
- **Experiments run:** `cd pc && python3 -m pytest -q`: 169 passed, 4 skipped. `python3 -m pytest -q android/ci`: 36 passed. `gh run list` / `gh run view`: the last two `main` runs of `android.yml` (Ph9 and Ph10) passed every emulator suite on attempt 1, so the retry step is not hiding flaky failures on `main`. The Ph10 branch failures were test-side (UiAutomator text lag, see O17).
- **Foreground services / Android 14–15:** both service types are declared in the manifest and match the types passed to `startForeground`. Every start comes from a visible screen or a notification `getForegroundService` action. `onTimeout(int,int)` handles the dataSync limit. The stop race (`StopDecision`, `stopSelfResult`) is correct. Locks are taken with a timeout and released in every path. The progress notification is updated at most once a second. Channels are created only on API 26+. No `registerReceiver`, no trampolines, `FLAG_IMMUTABLE` everywhere.
- **Process death:** the engine is in memory by design (H16). The interrupted marker and banner work (apart from O13). Hand-over tokens that are missing after a restart are logged and ignored, with no crash. Share URI grants ride on the service intent (B45, proven by `ShareToFerryTest`).
- **Network:** every socket is bound to the Wi-Fi/Ethernet `Network` (OkHttp factory, discovery `bindSocket` after bind). The client is rebuilt when the network changes, and a relocate follows. Mobile-only means "not on Wi-Fi". `Connection: close` avoids stale pooled connections to the HTTP/1.0 PC (B23). The PC's discovery responder survives Windows 10054 (`SIO_UDP_CONNRESET` plus try/except). The README's firewall, Public/Private and port advice matches `ferry.py` (Public-network warning, `--port` with UDP fixed on 8765, banner "Fire TV: in Downloader enter …/app", "App page" line).
- **TV keys / Back / focus:** player and viewer keys are taken at activity level (B74), so `PlayerView` can't steal them. The controller can't take focus. Resume dialog has its own window with Resume focused. Back is handled per screen (Home exits, Player/Viewer/cast pop or stop). Focus memory is restored after the Player; outbox ids are stable on the PC, so the 5 s refresh doesn't reset focus. `tvClickable` gives one focus stop and one click per OK. The install dialog's main button is focused first.
- **Player:** retry policy (8 × 1 s) plus a 15/50 s buffer, long pause proven on the API 30 TV (H37). Seeks are clamped inside the video. The position is saved every 5 s and on close, and the resume rule is tested.
- **Casting:** 128-bit token, Range server with a write watchdog and a 16-connection cap, phone ↔ TV loss detection (30 s / 10 s), Stop from Remote and notification, and a new cast replaces the old one on both sides. The TV receiver runs only between `onStart` and `onStop`, as designed.
- **APK install:** FileProvider + `ACTION_VIEW` on every API. API 25 reads the global unknown-sources switch. `ACTION_MANAGE_UNKNOWN_APP_SOURCES` is wrapped in try/catch with a text fallback. The Fire OS 8 restart (B72) is explained in the dialog and README. The update APK's size and sha256 are verified before install, and update is disabled while busy.
- **UX text:** all wording is in `strings.xml` with plurals. The README menu paths, URLs, port and messages match the code, except for the points in O7 and O14. The vivo steps are in one string, as A8 asks.
- **Requirements:** P1–P10, T1–T5 and C3 are implemented as agreed. The gaps are TV manual address (O2), the TV slideshow interval (O8) and TV layout on Fire OS 6 untested (O6).

**Not verifiable here (uncertain):** anything that needs a real vivo or Fire TV or a real network change (O1 permission-controller behaviour, O3 cursor on the Fire TV keyboard, O9, O15). These map onto checklist items C5, E3/E4, G1 and F3.
