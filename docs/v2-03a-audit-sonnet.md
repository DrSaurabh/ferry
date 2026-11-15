# Ferry v2 design review 03a: Android app on real devices and UX (reviewer: Sonnet)

Scope: `v2-02-design.md` rev 1 against `v2-01-requirements.md`, focus on Android platform correctness (minSdk 25 / targetSdk 35), vivo X200 Pro, Fire TV (Fire OS 6 = API 25, Fire OS 7 = API 28, Fire OS 8 = API 30) and UX for a non-developer. PC internals and wire protocol are out of scope except where the app depends on them. "Verified" = checked against v1 code in `pc/ferry.py` or an Android API fact I am sure of. "Uncertain" = from memory of platform/OEM behaviour; test on the device.

---

## High

### A1. High. Design section 3 / 4 / 5 (no manifest section). Cleartext HTTP is not enabled
Problem: every connection in the design is plain HTTP to an IP (app to PC, TV player to PC, TV to phone on 8766, TV player to phone on 8767). With targetSdk 35 (and on Fire OS 7/8, API 28+) cleartext traffic is blocked by default. OkHttp, `HttpURLConnection` (used by Media3 `DefaultHttpDataSource`) and Coil all honour `NetworkSecurityPolicy`. Result: every request fails with `CLEARTEXT communication not permitted`, so nothing works on phone, Fire OS 7/8 or the TV player. It works on Fire OS 6 (API 25), which would hide the bug in the API 25 smoke test. The emulator tests would catch it only if they do not use `10.0.2.2` exemptions (they do not get any).
Fix: add `android:usesCleartextTraffic="true"` (or a `networkSecurityConfig` with `<base-config cleartextTrafficPermitted="true">`; IP-range domain rules are not supported, so use base-config). Add an explicit design line and a unit/instrumented assertion. Verified (API fact).

### A2. High. 4.3 / 4.4 and 2.x dedupe. A location-stripped copy can permanently block the good copy
Problem: if `ACCESS_MEDIA_LOCATION` is not granted (or the grant is lost, or `setRequireOriginal` only fails at open time), MediaProvider serves a redacted stream (same size, GPS bytes blanked). The app uploads it with the normal name/size/mtime. The PC's duplicate rule is name family + size + mtime within 2 s (`Inbox.precheck`, ferry.py line 782-794; verified), so after the user grants the permission and re-sends, the result is "Already on PC" and the GPS-less file stays. This violates priority 1 (originals keep GPS) silently. The design only records "location may be missing" per item; it does not say it is shown to the user or that it can be recovered.
Fix: (a) treat "no ACCESS_MEDIA_LOCATION" as a blocker for MediaStore items on API 29+: the first-run flow must not let the user reach Send without it, or Send shows a clear "Location is not allowed, photos will be sent without GPS. Allow / Send anyway" choice. (b) Probe at queue-build time by opening the stream (the `UnsupportedOperationException`/`SecurityException` for `setRequireOriginal` is thrown at open, not when building the URI). (c) Show "N sent without location" in the summary. (d) Optionally let the PC's rename path handle same-name/same-size/different-content when sizes match but the app flags `redacted=1` (needs a protocol param, so decide with the protocol reviewer). Verified (v1 code) for the dedupe rule; uncertain for exact redaction byte behaviour (same length, blanked bytes).

### A3. High. 3.1 :core and minSdk 25. Pure-JVM `:core` can call APIs that do not exist on Fire OS 6 (API 25)
Problem: `:core` is "pure Kotlin/JVM, Java 17" and is unit-tested only on a desktop JVM. Anything in it from `java.time`, `java.nio.file`, `List.of/Map.of`, `String.repeat/strip`, `Files.*`, `HexFormat`, etc. compiles and passes JVM tests but throws `NoClassDefFoundError`/`NoSuchMethodError` on API 25 (java.time and java.nio.file arrive in API 26). Android Lint `NewApi` does not run on a plain JVM module. The only API 25 coverage is a "boot smoke test" that never exercises `:core` code paths. The Fire TV Stick on Fire OS 6 is exactly the device this affects.
Fix: enable `coreLibraryDesugaring` in `:app`, compile `:core` with `--release 8`-compatible API checks (or the `animalsniffer` plugin with the Android API 25 signature, or make `:core` an Android library module that Lint checks), and make the API 25 emulator job run one real instrumented test (discovery + `/info` + a small upload against ferry.py on the host), not just boot. Verified (API fact) that java.time/java.nio.file are API 26+.

---

## Medium

### A4. Medium. 3.4 / 4.1 / 6. Missing manifest and permission list; several required entries are not in the design
Problem: the design lists some permissions in prose but there is no manifest checklist, and several items that make or break the app are absent: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (MulticastLock), `WAKE_LOCK`, `FOREGROUND_SERVICE` plus the type permissions `FOREGROUND_SERVICE_DATA_SYNC` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (required on Android 14+/targetSdk 34+; missing means `SecurityException` at `startForeground`), `REQUEST_INSTALL_PACKAGES` (without it the unknown-sources screen has no Ferry entry and installs are refused on 26+), `READ_MEDIA_VISUAL_USER_SELECTED`, `READ_EXTERNAL_STORAGE maxSdkVersion=32`, `WRITE_EXTERNAL_STORAGE maxSdkVersion=28`. TV requirements are also absent: `<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>`, `<uses-feature android:name="android.software.leanback" android:required="false"/>`, a `LEANBACK_LAUNCHER` category next to `LAUNCHER`, and a 320x180 `android:banner`. Without them the Fire TV may hide the app or list it as not optimised for TV (uncertain how strict Fire OS is for sideloaded apps; the launcher category is the one that matters).
Also `startForegroundService` does not exist below API 26 (use `ContextCompat.startForegroundService`), and notification channels are API 26+ (guard them), both relevant on Fire OS 6 if the TV ever starts the service code path.
Fix: add a "Manifest" subsection with this list and a CI test that parses the merged manifest and asserts it (cheap, no emulator).
Verified (API facts), except the Fire OS launcher behaviour (uncertain).

### A5. Medium. 4.1 / 4.2 (Share flow) is not designed. URI grants can expire, and the first-run/permission/PC-missing cases are undefined
Problem: section 4.1 has no screen or behaviour for Share to Ferry, though it is P1(b) and acceptance 3. Specific risks:
1. `ACTION_SEND_MULTIPLE` URIs from non-MediaStore providers (Files app, vivo Gallery's own provider, cloud apps) carry a temporary read grant tied to the receiving component. If the share activity finishes (best UX: it should return the user to the Gallery) and the service later opens the URIs, the grant may be gone and `openInputStream` throws `SecurityException`. The design says the queue is "in memory inside TransferService" but never says how the grant is carried. Standard remedy: copy the incoming `ClipData` into the service start intent with `FLAG_GRANT_READ_URI_PERMISSION`, or hold the activity until the service has opened/probed every URI. Uncertain on exact grant lifetime rules per Android version; test with a non-MediaStore provider on the vivo.
2. Passing hundreds of URIs through an Intent can hit the 1 MB binder limit. Pass them via the in-process `AppGraph` instead.
3. What does the user see? Recommend: a translucent `ShareReceiverActivity` that validates, starts the service, shows a toast "Sending 12 files to Saurabhs-PC" (or opens the Transfer screen only if the user taps the notification) and finishes. If the PC is not found it still queues ("Waiting for your PC").
4. If first-run permissions were never granted, a share must still work (granted URIs do not need READ_MEDIA) but `setRequireOriginal` needs `ACCESS_MEDIA_LOCATION` (see A2).
5. `getParcelableExtra(EXTRA_STREAM)` is deprecated on 33+; use the typed overload with a compat helper.
Fix: add a "4.1a Share target" paragraph covering the above.

### A6. Medium. 4.2 / 3.4. Foreground-service types and Android 14/15 limits are underspecified
Problem and fixes:
- `dataSync` on targetSdk 35 has a cumulative 6-hour-per-24h limit; at the limit the system calls `Service.onTimeout(startId, fgsType)` and the service must call `stopSelf()` within seconds or the app gets an ANR/exception. Fine for normal use (1 GB+ video = a few minutes) but the design must implement `onTimeout` (stop gracefully, mark queue "paused, tap to continue", post a normal notification). Verified (API fact).
- `CastService` as `mediaPlayback` [H14] is a stretch: the phone is not playing media, it is serving files. Android does not currently police this strictly, but `mediaPlayback` FGS is expected to hold a `MediaSession` or an audio focus and the system may later tighten it. Simpler and honest: use `dataSync` for casting too (a slideshow session will be far below 6 h), or `connectedDevice` (needs one of CHANGE_WIFI_STATE/CHANGE_WIFI_MULTICAST_STATE in the manifest). Recommend `dataSync` for both services: one type, one permission, one timeout handler. Uncertain whether any current OEM build enforces mediaPlayback; low risk either way.
- The FGS may only start from the foreground (activity visible) or from a notification-action tap. All designed entry points qualify (button tap, share activity, notification Retry). Add a rule: never start the service from a `BroadcastReceiver` for network changes. Verified (API fact).
- On Android 14+ users can swipe away the FGS notification and can stop apps from the "Active apps" list; the design should treat "service stopped by user" as Cancelled, not as a crash.
- If `POST_NOTIFICATIONS` is denied the FGS still runs (and shows only in Active apps), but the final summary "in the notification" disappears. The summary notification must be a separate normal notification on a second channel after the FGS stops (the FGS notification is removed with the service), and the in-app summary must persist. Throttle notification updates to about 1/s (the 250 ms rate is for UI only).

### A7. Medium. 3.4. WifiLock mode is likely ineffective with the screen off on Android 10+
Problem: `WIFI_MODE_FULL_HIGH_PERF` was deprecated in API 29 (replaced by `WIFI_MODE_FULL_LOW_LATENCY`, which is documented to apply only while the app is in the foreground with the screen on; I recall FULL_HIGH_PERF was made a no-op in recent API levels). So on the vivo (Android 15) the lock the design relies on for "1 GB video with the screen off" may do nothing, and vivo's Wi-Fi power saving can throttle the radio (uncertain). The partial WakeLock plus FGS is what really keeps the CPU awake.
Fix: choose the mode by API (`FULL_HIGH_PERF` below 29, `FULL_LOW_LATENCY` on 29+), keep both locks with a timeout (e.g. `acquire(4h)`) and always release in `finally`, and make acceptance 2 report MB/s with the screen off versus on so the problem is visible. Uncertain (OS behaviour); verify on the device.

### A8. Medium. 4.2 P10. vivo battery guidance: "Battery: unrestricted" status can be a false green, and the key vivo step is missing
Problem: `isIgnoringBatteryOptimizations` reflects AOSP Doze allow-listing only. OriginOS/Funtouch has a separate manager ("Background power consumption management" / high background power consumption, auto-start, and the recents-lock) that kills background apps independently. Showing "Battery: unrestricted ✓" while vivo's own manager still kills Ferry will mislead the user and produce the exact failure the card tries to prevent. The expander text path is also a guess for OriginOS 5; menu names differ between Funtouch OS 15 (global) and OriginOS 5 (China build) (uncertain on exact names).
Fix: (a) keep the system dialog, but label the status line "Android battery setting: OK" and always show the vivo expander link; (b) add the most reliable vivo step: open Recents and lock Ferry (pull the card down / lock icon); (c) word the vivo steps generically ("Settings, Battery, Background power consumption, find Ferry, allow") and keep them in one string so they can be corrected after the first real test; (d) keep the interrupted-transfer detection (good) and make it the primary signal: after a kill, show the instructions, not before.

### A9. Medium. 3.5. Discovery does not bind to the Wi-Fi network (vivo multi-network behaviour)
Problem: vivo has features that route traffic over mobile data when Wi-Fi is weak/no internet ("Smart network switching", "Wi-Fi + mobile dual-channel" style options) (uncertain which are on by default). A plain `Socket`/`DatagramSocket` follows the default network, which may then be cellular, where `192.168.x.x` is unreachable and broadcast goes nowhere. Also the UDP sends to `255.255.255.255` on a multi-homed device can leave on the wrong interface.
Fix: obtain the Wi-Fi `Network` via `ConnectivityManager` (`TRANSPORT_WIFI` callback, which the design already needs for "network change"), use `network.socketFactory` for OkHttp and `network.bindSocket(datagramSocket)` for discovery. About 20 lines and removes a whole class of "PC not found" reports. Also note: for targetSdk 37+ Android introduces a local-network access permission; not an issue at targetSdk 35 but record it for when targetSdk is raised (uncertain on final form).

### A10. Medium. 5.5 / 5.1. TV app dies when the TV screensaver or sleep starts; "must be open" cast needs an idle-friendly home
Problem: the TV control server runs only while the app is in the foreground. Fire TV starts the screensaver after a few minutes of no input (and sleeps later). While the user waits on the Ferry home for the phone (or runs a photo slideshow with no key presses; ExoPlayer's keep-screen-on only covers video), the screensaver takes over, Ferry goes to the background, the server stops, and the phone shows "Lost connection" or "Open Ferry on the TV". The user's real flow is: pick up the TV remote, start Ferry, put the remote down, use the phone.
Fix: set `FLAG_KEEP_SCREEN_ON` on the TV activity for the whole session of the app (home idle, slideshow, viewer). Make the idle TV home state explicit: "Ready for your phone. On the phone: Ferry, Play on TV." with the TV name large. Document the 2-step flow in the README and in the phone's "TV not found" message ("On the TV, open Ferry and leave it on the home screen"). Tie the server lifetime to `onStart/onStop`. Verified for keep-screen-on mechanism, uncertain for screensaver timings.

### A11. Medium. 5.1. TV Home photo thumbnails would download full originals
Problem: the PC is stdlib-only and serves originals only, so a Photos row of thumbnails means downloading each 5-15 MB JPEG just to draw a card, over Wi-Fi to a 1-2 GB Fire stick (Fire OS 6 sticks have about 1 GB RAM). 100 photos = roughly 1 GB of traffic on Home load and every 5 s refresh must not redo it. Memory pressure/OOM on old sticks is likely.
Fix: no thumbnails on the TV Home (icon plus name plus size, as for videos) or load a thumbnail only for the focused card, with Coil `size(~320 px)`, a disk cache keyed by `name|size|mtime`, and no thumbnails on Fire OS 6. The slideshow itself is fine (one image at a time, preload next 2).

### A12. Medium. 5.2. Unsupported audio on Fire TV plays silently; ExoPlayer handles key focus oddly with the controller shown
Problem and fixes:
- Movies from the PC outbox commonly carry DTS/TrueHD/some E-AC3 audio. Fire TV decoders do not play DTS; ExoPlayer drops an unsupported track and plays the video silently with no error, so the user thinks the app is broken. Fix: in `onTracksChanged`, if a selected/available audio group `isSupported == false`, show "This TV can't play this video's sound format (DTS)". The design only covers unsupported video format.
- ".avi/.wmv/.flv/mpg" are not played by ExoPlayer; `file_kind` already splits `video_playable` vs `video` (verified, ferry.py line 190-195): make sure the TV Home puts non-playable `video` files under "Other files" with the reason, otherwise OK opens a player that fails.
- If the Media3 `PlayerView` controller is visible it consumes D-pad left/right for focus/seekbar, so "left/right = -/+10 s" conflicts. Own the key handling in `onKeyDown` at the activity level and use the controller only as a display (`useController` with `controllerAutoShow=false`), or hide it while handling keys.
- Resume prompt ("Resume from 12:34 / Start over") costs an extra D-pad dialog on every reopen. Prefer auto-resume with a 3 s overlay "Resumed at 12:34. Press Back-then-Play to restart" or a prompt that defaults focus to Resume so one OK press continues.
Uncertain on exact ExoPlayer silent-drop behaviour per version; test with a DTS MKV.

### A13. Medium. 5.1. Compose D-pad focus without tv-material needs explicit handling (design says "focus modifiers" only)
Problem: plain Compose lists on TV have known pitfalls: no initial focus (the first D-pad press only focuses something), focus lost when a focused `LazyRow` item scrolls out, focus not restored when returning from the Player/Viewer to a row, `Modifier.clickable` needs `focusable`/indication so the focus ring shows, and `LazyRow` does not scroll to keep the focused item visible unless `bringIntoViewRequester` or the default pivot is configured. Dropping `tv-material` ([H12]) is reasonable, but then the design must name the chosen mechanisms.
Fix: add to 5.1: an initial `FocusRequester` on the first card, `rememberSaveable` focused-item index per row used with `focusRestorer`/`FocusRequester` on return, `Modifier.onFocusChanged`+border for the ring, and an emulator test that walks Home, Player and Back and asserts focus is on the same card. Verified (known behaviour), details uncertain by Compose version.

### A14. Medium. 4.6 / 5.4. Install path is more complex than needed, and the Fire OS unknown-sources intent may not resolve
Problem:
- The design mixes "PackageInstaller session" with "`ACTION_INSTALL` confirmation". A session needs a status `BroadcastReceiver` and a `PendingIntent` that must be `FLAG_MUTABLE` on API 31+ (the system adds the confirm intent; `IMMUTABLE` fails silently), plus handling of `STATUS_PENDING_USER_ACTION`. For installing arbitrary APKs from the outbox the simpler, well-trodden path is a `FileProvider` URI with `ACTION_VIEW` (`application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION`), which works from API 24 to 35. Only the self-update gains from sessions (result callback), and even there the user must confirm anyway.
- `ACTION_MANAGE_UNKNOWN_APP_SOURCES` exists from API 26; Fire OS 7/8 may not resolve it (activity not found) or may land on a general list (uncertain). Fire OS 6 (API 25) uses the global "Apps from Unknown Sources" and `canRequestPackageInstalls` does not exist there (the design acknowledges the setting but not that the API call must be guarded by `SDK_INT >= 26`).
Fix: use `FileProvider` + `ACTION_VIEW` for both; wrap the settings intent in `resolveActivity`/try-catch and fall back to a static text screen "Settings, My Fire TV, Developer options, Install unknown apps, turn on Ferry" (the Developer options menu is hidden until About is clicked 7 times on Fire OS; say so). Do not rely on detecting the result; after returning, re-check `canRequestPackageInstalls()` and re-offer.

### A15. Medium. 4.2. First-run permission flow has more dialogs than needed, and partial photo access is underspecified
Problem:
- Welcome plus Photos (1-2 dialogs) plus Location in photos plus Notifications plus Battery (card plus system dialog) is roughly 6-8 taps before the first send, and notifications and battery are requested before the user sees any value. Fix: request `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` and `ACCESS_MEDIA_LOCATION` in a single `requestPermissions` array at the first tap on "Send photos & videos"; request notifications when the first transfer starts; show the battery card after the first successful transfer.
- Partial access (Android 14+): the app must declare `READ_MEDIA_VISUAL_USER_SELECTED` and request it with the other two to opt into the new flow; detect "partial" as `VISUAL_USER_SELECTED` granted while `READ_MEDIA_IMAGES` is not. If it is not declared, behaviour on a 34+ target is compat-dependent (uncertain). Ferry's picker is meant to cover the whole library, so word the welcome text "Choose **Allow all**" and handle partial with the designed banner; do not treat partial as an error loop (denied twice means system dialogs are suppressed, so route to app settings).
- On vivo, the OEM permission layer can add its own prompts for media and notification access; the welcome text should say "if vivo asks again, choose Allow".

### A16. Medium. 4.5 / 4.2. Downloads/Ferry edge cases
Problem and fixes:
- On API 25-28 the plain-file path has no stated rule for name clashes (the "MediaStore appends (1)" claim applies to 29+ inserts only). Never overwrite on the phone either: pick `name (1).ext` yourself and create with `createNewFile`/`CREATE_NEW`.
- No free-space check on the phone: add "Not enough space on your phone" using `StatFs` before starting a large download (priority 1: no partial junk).
- After reinstall or "Clear data", entries the app previously created on 29+ are not owned by the new install, so the app cannot rewrite or delete a leftover `IS_PENDING` row; harmless but the "Resume with Range" claim only works within one install. Note it, or skip resume when the pending row is not ours.
- Resume into a pending entry needs `openOutputStream(uri, "wa")`; plain `"w"` truncates.
- After an APK download "Install" uses a MediaStore Downloads URI; give `FLAG_GRANT_READ_URI_PERMISSION` or copy via FileProvider (see A14).
Verified (API facts), except resume mode behaviour (uncertain on vivo's MediaProvider).

### A17. Medium. 4.1 Photo picker is the largest UI build and several features are not needed
Problem: sticky date headers, Albums tab, per-header and per-album Select all, long-press preview, video badges and duration are a lot of Compose code with no way to test on a real device in CI, and Android 11+ restricts `GROUP BY` in MediaStore queries (album list must be aggregated in memory). Select-all per date header can silently select thousands of items. Selection "in selection order" requires a `LinkedHashSet`, not a Set of IDs as written.
Fix: ship v2.0 with Recent plus Albums plus tap-to-select plus a count/size bar plus per-album "Select all"; defer long-press preview and date-header select-all unless the requirements ask. Use `ContentResolver.loadThumbnail` (API 29+) for thumbnails and `MediaStore.Video/Images.Thumbnails` for 25-28; Coil on a MediaStore video URI needs the `coil-video` artifact (not listed). Use `LinkedHashSet<Long>` for order. Page the MediaStore query (a vivo library can be 30k items).

---

## Low

### A18. Low. 3.5 / 5.1. Typing an address on the TV
Problem: P8 fallback "type the PC address once" is painful with a D-pad (system keyboard). The TV is the device where discovery is most likely to fail (Fire OS may drop broadcast UDP unless the MulticastLock is held, and the lock needs `CHANGE_WIFI_MULTICAST_STATE`).
Fix: a TV address screen built from a number pad of focusable digit buttons (0-9, dot, backspace, OK) prefilled with the first three octets of the TV's own IP; also show the discovered PCs list. Consider letting the phone push the PC address to the TV over the cast channel (the TV answers on 8766) so the TV never needs typing. Verified (UX), uncertain on Fire broadcast behaviour.

### A19. Low. 4.3. GPS loss when photos are sent via "Other files"
Problem: photos picked through the system file chooser (`OpenMultipleDocuments`) come as `content://com.android.providers.media.documents/...` URIs with redacted location. Users will pick photos there (it has a "Recent" shortcut).
Fix: label the button "Send other files (documents, APKs)" and show a note when a selected item has an image/video MIME: "Photos sent this way may lose their location. Use Send photos & videos." Also try to map `media.documents` URIs to MediaStore ids (`MediaStore.getMediaUri` / the `image:123` id) so `setRequireOriginal` can be applied (documented path for 29+).

### A20. Low. 5.3. Slideshow of large originals and vivo video formats
Problem: the X200 Pro writes 50 MP (and 200 MP mode) JPEG/HEIC and 4K HEVC 10-bit HDR/Dolby Vision video. Sending each original to the TV over Wi-Fi is fine for 5 s steps (about 2-3 MB/s), but older Fire sticks can fail decoding very large HEIC or run low on memory; 10-bit HEVC may be unsupported on the oldest sticks.
Fix: decode with a target size (already planned); catch `OutOfMemoryError`/decode failures per photo and skip with a small message; for video, make the message include the codec profile ("HEVC 10-bit"). Verify on the real stick with a 50 MP HEIC and a 4K HDR clip.

### A21. Low. 4.4. Upload details that bite on Android
- `setRequireOriginal` probing (see A2).
- `ContentResolver.openInputStream` for MediaStore returns a stream whose total may differ from `SIZE` for items modified after indexing; the "file changed" abort is right, but suggest one automatic re-query of SIZE and a single retry before failing, to avoid false failures on freshly edited photos.
- `DATE_MODIFIED` can differ from the file's real mtime when Gallery edits or media scans touched the row; fine for dedupe but say so; fall back to `DATE_TAKEN` only if DATE_MODIFIED is 0.
- Wake lock without timeout leaks if the service dies abnormally; use `acquire(timeout)`.

### A22. Low. 4.1 / 4.4. Messages and states to make explicit for a non-developer
- Add plain-English strings for: phone storage full on download, "PC found but Ferry is not running its receiver" (HTTP error), "Your phone is not on Wi-Fi" (distinct from "PC not found"), "Battery saver is on" (Wi-Fi may be throttled), the PC `disk_full` message in plain units ("Your PC needs 2.1 GB more free space").
- "Waiting for Saurabhs-PC (continues automatically)" should have a visible "Stop waiting" action and a 30 min limit message ("Gave up after 30 min; N files not sent. Send again").
- Summary notification should tell what to do on failure ("2 failed. Tap to retry").

### A23. Low. 3.1. Release build with minify off
Problem: minify off with Compose, Media3, OkHttp, Coil and kotlinx can make an APK around 25-30 MB; on a 2nd-gen Fire TV Stick (about 1 GB free storage headroom, update requires APK + installed copy) that is uncomfortable, and the PC zip grows. Also first-run Compose startup on a Fire stick is slow without baseline profiles.
Fix: acceptable for 2.0; plan R8 (`isMinifyEnabled=true`, `isShrinkResources=true`) in 2.1 with kept rules for kotlinx.serialization, and avoid unused Material icons (use `material-icons-core`).

### A24. Low. 4.1 / 6. Phone to TV flow tap count and shortcuts
Problem: the flow is (open TV remote, start Ferry on TV) then phone: Ferry, Play on TV, TV choice, Videos or Slideshow, pick, then Remote screen: about 5 phone taps plus the picks. Acceptable. One cheap improvement: remember the last TV and skip the TV step even if two are found; show the Remote screen as the destination of the notification tap.
Also on the Remote screen allow the phone to be locked; Pause/Next/Stop in the notification (planned) covers it.

### A25. Low. 3.4. Targeting Android 15 specifics
- targetSdk 35 forces edge-to-edge: use `enableEdgeToEdge()` and `Scaffold` insets on the phone; the TV layout should set explicit overscan-safe padding (about 48 dp horizontal, 27 dp vertical) because many TVs still crop.
- Predictive back: on TV and phone `BackHandler` should be used consistently; ensure the `android:enableOnBackInvokedCallback` default does not break the "Back goes up one level" rule.
- No native libraries are used, so the 16 KB page-size requirement is not an issue; keep it that way (do not add FFmpeg extensions without checking).
Uncertain on Compose-version specifics.

---

## Things checked and fine
- Minimum 25 / target 35 combination is workable; Fire OS 6/7/8 equal API 25/28/30 (confirms HEIC decode only from API 28, as the design states).
- `ACCESS_MEDIA_LOCATION` plus `setRequireOriginal` is the right mechanism for GPS on MediaStore URIs (subject to A2).
- MediaStore `Downloads` with `IS_PENDING` and `RELATIVE_PATH="Download/Ferry"` needs no permission on 29+ and shows no browser/insecure-download warnings (P7 satisfied).
- The interrupted-queue marker plus instant duplicate skipping is a sound recovery design given vivo.
- The PC IP being reserved makes "last-known address first" the right primary path.
- Not using tv-material (alpha APIs) is defensible, given A13's mitigations.

---

## Summary table

| ID | Severity | Section | One-line |
|---|---|---|---|
| A1 | High | 3/4/5 | Cleartext HTTP not enabled; every request fails on API 28+ |
| A2 | High | 4.3/4.4 | GPS-redacted upload blocks the good re-send via PC dedupe (verified) |
| A3 | High | 3.1 | Pure-JVM `:core` may use APIs missing on API 25; no real API 25 test |
| A4 | Medium | manifest | No manifest list: FGS type permissions, REQUEST_INSTALL_PACKAGES, leanback entries |
| A5 | Medium | 4.1/4.2 | Share flow undefined; URI grants, binder size, no-permission and PC-missing cases |
| A6 | Medium | 3.4 | FGS: dataSync timeout handler, mediaPlayback misfit, notification fallback |
| A7 | Medium | 3.4 | WifiLock mode likely ineffective with screen off on 29+ |
| A8 | Medium | 4.2 | vivo battery status false green; recents-lock step missing |
| A9 | Medium | 3.5 | Discovery not bound to the Wi-Fi network (vivo multi-network) |
| A10 | Medium | 5.5/5.1 | TV screensaver/sleep kills the cast server; needs keep-screen-on and idle home |
| A11 | Medium | 5.1 | TV photo thumbnails would download full originals |
| A12 | Medium | 5.2 | Silent unsupported audio, controller key conflicts, resume prompt cost |
| A13 | Medium | 5.1 | Compose D-pad focus mechanics unspecified |
| A14 | Medium | 4.6/5.4 | Over-complex installer path; unknown-sources intent may not resolve on Fire OS |
| A15 | Medium | 4.2 | Too many first-run dialogs; partial-access opt-in underspecified |
| A16 | Medium | 4.5 | Downloads edge cases: API 25-28 clash, free space, resume mode |
| A17 | Medium | 4.1 | Picker scope too large; GROUP BY limits; ordering; coil-video |
| A18 | Low | 3.5/5.1 | Typing address on TV; digit pad or phone-pushed address |
| A19 | Low | 4.3 | GPS loss via "Other files" |
| A20 | Low | 5.3 | Large HEIC / 10-bit HEVC on old sticks |
| A21 | Low | 4.4 | SIZE drift, DATE_MODIFIED, WakeLock timeout |
| A22 | Low | 4.1/4.4 | Plain-English messages and Stop waiting action |
| A23 | Low | 3.1 | Minify off APK size on small Fire sticks |
| A24 | Low | 4.1/6 | Cast flow shortcuts |
| A25 | Low | 3.4 | Edge-to-edge, overscan, predictive back |

Counts: High 3, Medium 14, Low 8 (25 total).
