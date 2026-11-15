# Ferry v2: Design (Stage 2, rev 1)

Implements `v2-01-requirements.md`. Hands-free decisions are tagged **[H#]** and logged in `v2-decisions-log.md`.

## 1. Overview

```
             UDP 8765 discovery (broadcast → unicast reply)
  ┌──────────────┐      HTTP 8765 /api/v1 (+ v2 additions)      ┌──────────────────┐
  │ Phone app    │ ───────────────────────────────────────────▶ │ PC: ferry.py 2.0 │
  │ (phone UI)   │ ◀─────── outbox list / downloads / APK ───── │ Windows, stdlib  │
  └─────┬────────┘                                              └────────▲─────────┘
        │ cast: POST /api/v1/cast (TV 8766)                              │ outbox, APK,
        │ files served by phone (8767, Range)                            │ discovery
  ┌─────▼────────┐                                                       │
  │ Fire TV app  │ ──────────────────────────────────────────────────────┘
  │ (TV UI)      │
  └──────────────┘
```

- **One APK** (`app.ferry`), two UIs chosen at start: **TV** if `UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION` or the device has `android.software.leanback`; otherwise **phone**.
- **PC program** `ferry.py` 2.0.0: v1 core kept (inbox, outbox, Range downloads, never-overwrite, fsync, dedupe), browser UI removed, plus discovery, batch check, app-update endpoints, a "Get the app" page and `ferry-log.txt`.
- **Protocol version 2.** `/api/v1/*` paths stay (the app uses them); `info.protocol` becomes 2, and new capabilities are advertised in `info.capabilities`.

## 2. PC program (ferry.py 2.0.0)

### 2.1 Kept from v1 (unchanged behaviour)
`GET /api/v1/info`, `GET /api/v1/check`, `POST /api/v1/upload`, `GET /api/v1/outbox`, `GET|HEAD /api/v1/outbox/<id>/<name>` (Range/If-Range/ETag), `GET /api/v1/outbox.zip`. Inbox/outbox logic, sanitising, Windows rename retries, keep-awake while transferring, console design, `--selftest`, `Ferry.bat`.

### 2.2 Removed **[H9]**
Upload web page (`PAGE_HTML`), diagnostics page and `/api/v1/diag/*`, `/qr`, keep-awake video, PWA manifest/icons (favicon stays), short links `/<n>` and `/new` (they existed for Silk/Downloader; the TV app replaces them). Linux launcher and `--install-desktop` stay in the code but are no longer documented or tested in CI beyond what's free (Linux CI keeps running the tests, since it costs nothing).

### 2.3 New endpoints
| Method & path | Behaviour |
|---|---|
| `GET /` | **"Get the Ferry app" page** (plain HTML, phone/TV friendly): big "Download Ferry for Android" button → `/app`, version, 3-step install instructions for phone, and a Fire TV note "In Downloader, enter `<ip>:8765/app`". If no APK is bundled: says so. |
| `GET|HEAD /app`, `/app/Ferry.apk` | The bundled APK (`app/Ferry.apk` next to ferry.py), `application/vnd.android.package-archive`, Range support (reuses the outbox file sender). |
| `GET /api/v1/app` | `{"version_code":N,"version_name":"2.0.0","size":N,"sha256":"…","url":"/app/Ferry.apk"}` read from `app/Ferry.apk.json` (written by CI next to the APK). 404 `{"result":"error","message":"no app bundled"}` if missing/invalid. Size and sha256 are verified against the APK at startup and cached (recomputed if the APK's size/mtime changes). |
| `POST /api/v1/check-batch` | Body JSON `{"files":[{"name","size","mtime"}…]}` (≤ 2000 items, ≤ 1 MB body). Reply `{"ok":true,"results":[<same shape as /check per item>]}` or `{"ok":false,"reason":"disk_full","free_bytes":N}` if the **sum** of sizes doesn't fit. Saves one round trip per file. |

`/api/v1/info` adds `"protocol":2`, `"capabilities":["receive","outbox","check_batch","app_update","discovery"]`, `"app_version_code":N|null`.

### 2.4 Discovery responder (UDP 8765) **[H10]**
- Thread binds UDP `0.0.0.0:8765` (separate from TCP 8765; same port number keeps the firewall story to one number). If the bind fails, log one console line and continue (the app falls back to the last-known address / manual entry).
- Request (datagram ≤ 512 bytes, UTF-8 JSON): `{"app":"ferry","q":"discover","protocol":2,"name":"vivo X200 Pro","device_type":"phone","nonce":"<8 hex>"}`.
- Reply (unicast to the sender's address and port): `{"app":"ferry","protocol":2,"nonce":"…","name":"Saurabhs-PC","device_type":"pc","port":8765,"version":"2.0.0"}`. Anything else is ignored silently; at most 20 replies/s total (cheap flood guard).
- Windows Firewall: the existing "Python, Private networks" allow rule is a program rule (all protocols), so UDP is covered. README covers the case where the user only allowed TCP.

### 2.5 `ferry-log.txt` (C5)
- One line per request, tab-separated: `time  ip  device  method  path(no query values except name)  range  status  bytes  ms  outcome  error`. Device = `X-Ferry-Device` header (the app sends its device name) or the UA class.
- Plus events: start/stop, discovery replies (rate-limited to 1/min per IP), folder recreated, errors.
- Written by a dedicated writer thread through a queue (a slow disk never blocks transfers, as with the console). Rotation: when > 5 MB, rename to `ferry-log.1.txt` (replacing the old one) and start fresh; also rotate at startup if > 5 MB. Logging failures are swallowed after one console note.

### 2.6 App bundling and packaging
- `pc/app/Ferry.apk` + `pc/app/Ferry.apk.json` are produced by CI (release-signed APK) and included in the user's zip `Ferry-2.0.0.zip`. Updating the app = replacing those two files (or unzipping a newer Ferry zip over the old folder).
- `Ferry.bat` unchanged. README rewritten (C6).

### 2.7 Console
Banner: QR now encodes `http://<ip>:8765/` (the Get-the-app page) with the text "First time? Scan to install the Ferry app". Activity lines as v1 ("from vivo X200 Pro  saved  IMG_….jpg  4.1 MB  22 MB/s"); the device name now comes from the `X-Ferry-Device` header.

## 3. Android app: architecture

### 3.1 Modules and libraries **[H11]**
- `:core` — pure Kotlin/JVM, **no Android imports**: protocol models (org.json-free: a tiny JSON helper on top of `kotlinx.serialization`), `FerryClient` (OkHttp), `UploadEngine` (queue/state machine), `MiniHttpServer` (Range file server + JSON control endpoints), `DiscoveryCodec`. Unit-tested on the JVM, including **against a real `ferry.py`** started by the test (CI has Python).
- `:app` — Android: Compose UI (phone + TV), services, MediaStore access, Media3 player, installer.
- Libraries: OkHttp 4.12, kotlinx-serialization-json, kotlinx-coroutines, Compose BOM + Material3, `androidx.tv:tv-material` is **not** used (plain Compose with focus modifiers keeps one design system; fewer alpha APIs) **[H12]**, Media3 ExoPlayer + UI, Coil 2 (images; video thumbnails via MediaStore), androidx.lifecycle/navigation-compose. **No Google Play services** (Fire TV has none).
- minSdk 25, targetSdk 35, compileSdk 35. Kotlin 2.0.21, AGP 8.7.3, Java 17.

### 3.2 Signing **[H13]**
A dedicated release keystore is generated once and stored as GitHub Actions secrets (`FERRY_KEYSTORE_B64`, `FERRY_KEYSTORE_PASSWORD`). CI builds a **release-signed** APK (minify off for v2.0 to keep debugging simple). The same key must sign every future version, otherwise updates fail; a backup copy of the keystore is sent to the user at the end. Debug builds are only used for emulator tests.

### 3.3 Versioning
`versionCode` = CI run number + 100 (monotonic), `versionName` = `2.0.<n>`. `Ferry.apk.json` carries both.

### 3.4 Process and services
- `MainActivity` (single activity, Compose navigation). Picks phone or TV UI.
- `TransferService` (phone): foreground service, type `dataSync`, notification channel "Transfers". Runs the upload queue and outbox downloads. Holds a partial `WakeLock` and a `WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`) while active; releases them when idle and stops itself.
- `CastService` (phone): foreground service, type `mediaPlayback` **[H14]** (it serves media to the TV), runs the phone's file server while casting; notification with Pause/Next/Stop.
- `CastReceiver` (TV): runs the TV's control server while the app is in the foreground (no background service on TV, which matches how people use a TV app). Holds a `MulticastLock` so the TV can hear discovery broadcasts.
- State flows from services to UI via a process-wide `StateFlow` held in an `AppGraph` singleton (simple manual DI).

### 3.5 Finding devices (P8)
1. **Last-known PC** (stored host:port) — `GET /api/v1/info` with a 1.5 s timeout. The PC's IP is reserved in the router, so this is the normal fast path.
2. **UDP broadcast** on all IPv4 interfaces: to `255.255.255.255` and each interface's directed broadcast (e.g. `192.168.1.255`), 3 sends 300 ms apart, collect replies for 2 s. Replies are verified with `GET /api/v1/info` (`app == "ferry"`).
3. **Manual**: Settings → "PC address" (e.g. `192.168.1.5`), remembered.
- Multiple PCs: the first that answers is used; Settings lists all found and lets the user pick (remembered).
- Re-discovery runs on app start, on network change (ConnectivityManager callback), and when a request fails with a network error.
- TVs are found the same way (`device_type":"tv"`, port 8766).

## 4. Phone app

### 4.1 Screens (Compose, Material3, Ferry blue `#1d4ed8` / amber `#fbbf24`)
1. **Home**
   - Header: Ferry logo, "→ Saurabhs-PC" with a status dot (green "Connected", grey "Looking for your PC…", red "PC not found" + "Try again" + "Enter address").
   - Big buttons: **Send photos & videos**, **Send other files**, **From PC** (badge with count), **Play on TV**.
   - Banners: update available ("Ferry 2.0.7 is available → Update"), permissions/battery reminders, ongoing transfer card (tap → Transfer screen).
2. **Photo picker** (P3) — in-app, from MediaStore (images + videos):
   - Tabs **Recent** (all, newest first, sticky date headers) and **Albums** (bucket list → grid). Selection is a set of MediaStore IDs held in a ViewModel, so it **survives switching albums/tabs**.
   - Grid of thumbnails (video badge with duration), tap toggles, long-press opens a preview. Bottom bar: "12 selected · 1.4 GB" + **Send**. "Select all" per date header and per album.
   - Partial photo access (Android 14 "Select photos"): banner "Ferry can only see some photos → Allow all" (opens permission settings).
3. **Transfer** (P4/P5/P6): overall bar, "Sending 5 of 20 · 18.2 MB/s · about 1 min left", per-file rows (Waiting / Checking / Sending % / Saved / Saved as "x (1).jpg" / Already on PC / Failed + reason), **Cancel**. While the PC is unreachable: "Waiting for Saurabhs-PC… (continues automatically)". Summary at the end: "18 saved · 2 already on PC" (+ "1 failed → Retry"). Same info in the notification.
4. **From PC** (P7): list of outbox files (icon by kind, name, size, age), tap → download to `Downloads/Ferry` with progress; **Download all**. After download: "Open" (photos/videos/docs via ACTION_VIEW) or "Install" for APKs (system installer). Pull to refresh; refreshes every 5 s while visible.
5. **Play on TV** (P9): choose TV (auto-selected if one), then **Videos** (picker in single-select mode, video-only) or **Photo slideshow** (picker, images only, multi-select). Then the **Remote** screen: title, position/duration, ⏮ ⏯ ⏭ ⏹, "−10 s / +10 s" for video, and slideshow speed 3/5/10 s.
6. **Settings**: PC (found/manual address), "Battery: unrestricted ✓" status with a "Fix" button, slideshow default, app version, **Share diagnostics log**.

### 4.2 Permissions and first run (P10)
- First run: one welcome screen explaining what Ferry needs, then requests in order: **Photos and videos** (`READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` on 33+, `READ_EXTERNAL_STORAGE` on ≤ 32), **location in photos** (`ACCESS_MEDIA_LOCATION`, 29+; runtime on 29+), **notifications** (`POST_NOTIFICATIONS`, 33+).
- **Battery**: if `PowerManager.isIgnoringBatteryOptimizations` is false, a card explains "vivo may stop Ferry in the background. Allow Ferry to run in the background." → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; sideloaded, so Play policy doesn't apply), plus vivo-specific instructions in a "Still stopping?" expander (Settings → Battery → Background power consumption management → Ferry → Allow). Shown once, then only as a Settings status line, and again if a transfer was killed (detected on next start by an unfinished-queue marker).
- WRITE to `Downloads/Ferry`: MediaStore `Downloads` collection with `RELATIVE_PATH = "Download/Ferry"` on 29+ (no permission); on 25–28 `WRITE_EXTERNAL_STORAGE` (maxSdkVersion 28) and a plain file in `Environment.DIRECTORY_DOWNLOADS/Ferry`.

### 4.3 Reading originals (P2)
For each selected item we build an `UploadItem(uri, name, size, mtimeMs)`:
- **MediaStore items** (picker and Gallery shares with `content://media/…`): `uri = MediaStore.setRequireOriginal(contentUri)` when `ACCESS_MEDIA_LOCATION` is granted (29+), so GPS EXIF is **not redacted**. `name = DISPLAY_NAME`, `size = SIZE`, `mtimeMs = DATE_MODIFIED × 1000` (seconds precision; the PC's duplicate check allows ±2 s). If `setRequireOriginal` throws (permission revoked), fall back to the plain URI and record "location may be missing" in the item.
- **Other shares / "Send other files"** (SAF `OpenMultipleDocuments`, any type): `OpenableColumns.DISPLAY_NAME`/`SIZE`, `DocumentsContract.Document.COLUMN_LAST_MODIFIED` when present, otherwise now.
- **Size unknown** (−1/null): open `AssetFileDescriptor` and use its length; if still unknown, copy to the app cache first and send the copy (rare; documents from cloud providers).
- Gallery share of a `content://` URI from another provider (e.g. vivo Gallery's own provider): try to map it to a MediaStore ID (query `_id`/`DATA`); if that fails, send as-is (GPS may be stripped by the provider; nothing we can do).

### 4.4 Upload engine (`:core` `UploadEngine`)
- States per item: `Waiting → Checking → Sending(bytes) → Saved | Renamed(savedAs) | AlreadyOnPc | Failed(reason) | Cancelled`.
- Flow: one `check-batch` call for all items (chunks of 500). Duplicates are marked "Already on PC" immediately; `disk_full` stops the queue before sending anything ("The PC needs X more free space").
- Then uploads with **2 parallel workers** **[H15]** (small files are dominated by per-request latency; 2 keeps large-file speed and roughly doubles small-file throughput), ordered by the user's selection order. One big file (> 256 MB) at a time: when a worker starts one, the other finishes its current file and waits.
- Each upload: `POST /api/v1/upload?name=&size=&mtime=` with headers `Content-Length`, `X-Ferry-Device`, streaming body from `ContentResolver.openInputStream` in 256 KB chunks. Timeouts: connect 5 s, write stall 60 s, response wait after the body 10 min (the PC may be hashing a same-named large file).
- Results: 200 → by `result`; 507 → stop queue, "PC disk is full"; 400/411 → Failed (bug); 500 → Failed with the PC's message; network error / timeout → **Retry policy**: the item goes back to Waiting, and the engine enters **Waiting for PC**: it probes `/api/v1/info` every 3 s (with re-discovery every 30 s) for up to 30 min, then resumes. Each item gets at most 3 network failures while the PC is reachable before it's marked Failed. Cancel stops everything, aborting the in-flight request (the PC deletes its temp file).
- If the file changed while sending (read fewer/more bytes than `size`): abort the request, mark Failed "file changed while sending".
- Progress is published at most every 250 ms; speed is a 3 s moving average.
- The queue is **in memory** inside `TransferService` **[H16]**; the service keeps the process alive. If the process is killed anyway, on next start the app shows "The last transfer was interrupted (N files not sent). vivo may have stopped Ferry → Fix battery settings", and the user re-sends (duplicates are skipped instantly).
- Adding files while a transfer runs appends to the queue (one combined summary).

### 4.5 Outbox downloads
- `GET /api/v1/outbox` list. Download = `GET /api/v1/outbox/<id>/<name>` streamed into a MediaStore Downloads entry with `IS_PENDING=1`, then `IS_PENDING=0` on success (deleted on failure or cancel). Name clashes are handled by MediaStore (it appends " (1)"). Resume with `Range` after a network drop, if the ETag still matches; otherwise restart.
- Runs inside `TransferService` (survives the screen off). "Download all" queues them one at a time.

### 4.6 App update (C3)
- On every successful PC connection (phone and TV): `GET /api/v1/app`. If `version_code > BuildConfig.VERSION_CODE` → banner "Update available (2.0.7)". Tap → download to the app's cache (`files/update/Ferry.apk`), verify sha256 and size, then install via `PackageInstaller` session (`ACTION_INSTALL` confirmation by the system). Requires "Install unknown apps" for Ferry: if `canRequestPackageInstalls()` is false, first open `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for Ferry with an explanation.
- The same installer path handles **APKs from the outbox** on the TV (T4) and the phone.

## 5. Fire TV app

### 5.1 UI (10-foot, D-pad) **[H12]**
- Dark theme, 1.5× type scale, large focus ring (amber outline + scale 1.05). Every action reachable with D-pad + OK; Back always goes up one level; no pointer needed.
- **Home**: header "Ferry · Saurabhs-PC ●". Rows (left/right inside a row, up/down between rows):
  1. **Videos** (outbox `video_playable`/`video`): cards with name, size; OK → Player.
  2. **Photos** (outbox `image`): first card "▶ Slideshow (24)", then photo thumbnails (OK → Viewer at that photo).
  3. **Apps** (outbox `apk`): OK → Install.
  4. **Other files**: shown with a note "Open these on your phone or PC" (no action).
  Empty state: "Put files in **Desktop → Ferry outbox** on your PC. They'll appear here." Auto-refresh every 5 s.
  A small footer: "Phone: open Ferry → Play on TV to show your phone's videos and photos here."
- Update banner as on the phone (focusable).

### 5.2 Video player (T2)
- Media3 ExoPlayer with `DefaultHttpDataSource` (Range requests; the PC already supports Range/If-Range/ETag). HEVC/4K are decoded by the TV's hardware if it supports them; if the format is unsupported, show "This TV can't play this video format" with the codec name.
- Keys: OK/Play-Pause = toggle; D-pad left/right = −10/+10 s; Rewind/Fast-forward keys = −30/+30 s (hold repeats); up shows the controller with a seek bar; Back = stop and return.
- Buffer: `DefaultLoadControl` with min 15 s / max 50 s; a long pause doesn't break playback, because ExoPlayer reopens the connection with a Range request (the PC closes idle connections after 60 s; ExoPlayer retries).
- Resume: position saved every 5 s and on stop, keyed by `name|size|mtime`; reopening offers "Resume from 12:34" / "Start over" (if > 30 s in and > 30 s before the end).

### 5.3 Photo viewer and slideshow (T3)
- Full-screen Coil image, decoded at screen size (`size(Size(displayW, displayH))`), EXIF orientation honoured; HEIC decoded by the platform on Android 9+ (older TVs show "Can't show this photo format").
- Slideshow: auto-advance every 5 s (setting: 3/5/10 s), cross-fade 400 ms; preloads the next 2 images. Left/right = previous/next (pauses auto-advance for that photo), OK/Play-Pause = pause/resume, Back = exit. A small overlay "3 / 24 · name" for 2 s after each key press.

### 5.4 Install APKs (T4)
OK on an APK → download to cache with a progress dialog → `PackageInstaller` (as §4.6). First time: explain "Allow Ferry to install apps" and open the Fire TV setting (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`; on Fire OS 6, the global "Apps from Unknown Sources" setting).

### 5.5 Receiving casts (T5)
- `CastReceiver` = `MiniHttpServer` on TCP **8766** + discovery responder answering `device_type:"tv"` (UDP 8765, `MulticastLock` held while in the foreground).
- `POST /api/v1/cast` → opens the Player or Slideshow with the given URLs, on top of whatever is showing. A toast "Playing from vivo X200 Pro".
- `POST /api/v1/control` `{"action":"toggle|play|pause|next|prev|seek|stop|interval","delta_ms":±N,"interval_ms":N}`; `GET /api/v1/state` → `{"kind":"video|photos|idle","index":i,"count":n,"title":…,"playing":bool,"position_ms":…,"duration_ms":…,"interval_ms":…}`.
- If the phone's server stops answering: "Lost connection to the phone" and return to Home after 10 s.
- The TV app must be **open** to receive casts (no background service on Fire TV) **[H14]**. The phone says "Open Ferry on the TV" if it can't find a TV.

## 6. Phone → TV casting (P9)
- Phone starts `CastService` → `MiniHttpServer` on TCP **8767** (or the next free port) serving only the selected items at `/c/<token>/<index>/<urlencoded name>` (token = 128-bit random per cast session; requests with the wrong token get 404). GET/HEAD with Range, correct `Content-Type` from MediaStore `MIME_TYPE`, `Content-Length` from the AssetFileDescriptor; reads use `openAssetFileDescriptor` + channel position so seeking is cheap. HEIC photos are served as-is (the TV decodes them, or shows the format message).
- Phone → TV `POST http://<tv>:8766/api/v1/cast`: `{"kind":"video"|"photos","from":"vivo X200 Pro","interval_ms":5000,"start":0,"items":[{"url":"http://<phone-ip>:8767/c/<token>/0/VID_….mp4","name":"…","mime":"video/mp4","size":N}]}`. The phone's IP is the address of the interface that routes to the TV (taken from the socket connected to the TV).
- The Remote screen polls `GET /api/v1/state` every 1 s while visible; buttons send `/control`. The notification has Pause/Next/Stop. **Stop** sends `stop` to the TV and ends `CastService`.
- The phone's screen can be off while casting (foreground service + WifiLock).

## 7. `MiniHttpServer` (`:core`)
- Small blocking HTTP/1.1 server on a `ServerSocket`, thread per connection (max 8 concurrent; extra connections get 503), `Connection: close` after each response (simple, matches what the PC does), 30 s socket timeout, request line ≤ 8 KB, headers ≤ 16 KB, JSON bodies ≤ 64 KB.
- Routes are registered as handlers; file handler implements GET/HEAD, single-range `Range: bytes=a-b` / `a-` / `-n`, 206/416, `Accept-Ranges`, `ETag` (size-mtime), `If-Range`.
- JVM unit tests cover parsing, ranges, limits, and concurrent clients.

## 8. Security posture (unchanged choice: none)
No auth on the LAN (requirement). Small safety measures that cost nothing for the user: the cast file server only serves the items chosen for the current session, behind a random token, and only while casting; the TV accepts casts only while the app is open; the PC discovery responder rate-limits replies; JSON bodies are size-limited everywhere.

## 9. Diagnostics
- App: an in-app ring log (last 2000 lines, `files/ferry-app-log.txt`, rotated at 1 MB) of discovery results, requests, failures and service lifecycle. Settings → "Share diagnostics log" (Android share sheet).
- PC: `ferry-log.txt` (§2.5).

## 10. Testing strategy
| Layer | Tests | Where |
|---|---|---|
| PC | v1's 103 tests (made portable for Windows) + new: check-batch, `/app` + `/api/v1/app` (missing/invalid/valid sidecar, sha mismatch), Get-the-app page, discovery (request/reply, garbage ignored, rate limit, bind failure tolerated), `ferry-log.txt` (lines, rotation, writer never blocks), removed routes now 404 | GitHub Actions: Windows + Linux, Python 3.8 and 3.13 |
| `:core` | JSON models, discovery codec, UploadEngine state machine with a fake client (retries, disk full, cancel, waiting-for-PC, parallel workers, big-file rule), MiniHttpServer (ranges, limits), **integration: FerryClient + UploadEngine against a real ferry.py** (byte-identical, rename, duplicate skip, mtime restored) | JVM job on Actions (starts ferry.py) |
| `:app` | Robolectric-free: Compose UI tests and instrumented tests on an **API 34 phone emulator**: insert a JPEG with GPS into MediaStore → send via the real service to ferry.py on the host (`10.0.2.2`) → PC file byte-identical **with GPS**; outbox download lands in Download/Ferry; cast loopback (phone server + TV receiver in one emulator); screenshots of every phone screen. **TV emulator (Android TV API 30)**: launch in TV mode, D-pad navigation through Home, player opens a test video from ferry.py, slideshow advances. | Actions with `reactivecircus/android-emulator-runner` (KVM on ubuntu runners) |
| Real devices | Acceptance list in requirements §5, run by the user | vivo X200 Pro, Fire TV Stick, Saurabhs-PC |

Screenshots from the emulator tests are uploaded as CI artifacts and reviewed during the code audit (UX check without a real device).

## 11. Repository layout
```
pc/        ferry.py, qrcodegen.py, Ferry.bat, README.txt, tests/, tools/package.py, app/ (CI puts the APK here)
android/   settings.gradle.kts, core/, app/
docs/      requirements, design, audits, plans, decisions (mirrored to the claude.ai project)
.github/workflows/  pc.yml, android.yml (unit + integration + emulator + release APK + PC zip)
```
Release flow: push to `main` → CI builds and signs the APK, runs all tests, assembles `Ferry-2.0.<n>.zip` (PC program + `app/Ferry.apk` + json + README) as an artifact.

## 12. Risks and mitigations
| Risk | Mitigation |
|---|---|
| vivo kills the foreground service | Battery-exemption flow; WakeLock/WifiLock; interrupted-transfer detection + guidance; duplicate skip makes re-sending cheap |
| UDP broadcast blocked (router AP isolation, Windows firewall rule TCP-only) | Last-known address first (IP is reserved); manual address; README firewall note; log shows discovery results |
| GPS still stripped (e.g. vivo Gallery share from its own provider) | MediaStore mapping; in-app picker is the guaranteed path; emulator test proves the MediaStore path |
| Fire TV can't decode a codec | Clear message with the codec; PC-side transcoding is out of scope |
| Fire OS 6 quirks (unknown-sources setting is global) | Version-specific instructions; minSdk 25 tested on an API 25 emulator boot smoke test **[H17]** |
| Signing key lost | Secrets + backup copy to the user |
| HTTP/1.0 PC + one connection per request limits small-file speed | Batch check + 2 parallel uploads |
