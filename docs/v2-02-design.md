# Ferry v2: Design (Stage 2, rev 2)

Implements `v2-01-requirements.md`. Hands-free decisions are tagged **[H#]** and logged in `v2-decisions-log.md`.

**Rev 2 (2026-11-15):** fixes from the design audit (`v2-03-design-audit.md`). Changed text carries the finding IDs in brackets, e.g. **[A1]** (reviewer A) or **[B3]** (reviewer B).

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
- **Old PC program [B15] [H25]:** if `/api/v1/info` answers with `protocol < 2` (a v1 `Ferry.bat` was started), the app shows "Your PC has an older Ferry. Replace it with the new Ferry folder" and sends nothing. There is no per-file fallback path; the fix is copying one folder.

## 2. PC program (ferry.py 2.0.0)

### 2.1 Kept from v1 (unchanged behaviour)
`GET /api/v1/info`, `GET /api/v1/check`, `POST /api/v1/upload`, `GET /api/v1/outbox`, `GET|HEAD /api/v1/outbox/<id>/<name>` (Range/If-Range/ETag), `GET /api/v1/outbox.zip`. Inbox/outbox logic, sanitising, Windows rename retries, keep-awake while transferring, console design, `--selftest`, `Ferry.bat`.

### 2.2 Removed **[H9]**
Upload web page (`PAGE_HTML`), diagnostics page and `/api/v1/diag/*`, `/qr`, keep-awake video, PWA manifest/icons (favicon stays), short links `/<n>` and `/new` (they existed for Silk/Downloader; the TV app replaces them). Linux launcher and `--install-desktop` stay in the code but are no longer documented or tested in CI beyond what's free (Linux CI keeps running the tests, since it costs nothing). The EXIF GPS reader `exif_gps_status` (used by the removed diagnostics page) is **kept as a helper for tests** [B1].

### 2.3 New endpoints
| Method & path | Behaviour |
|---|---|
| `GET /` | **"Get the Ferry app" page** (plain HTML, phone/TV friendly): big "Download Ferry for Android" button → `/app`, version, 3-step install instructions for phone, and a Fire TV note "In Downloader, enter `<ip>:8765/app`". If no APK is bundled: says so. |
| `GET|HEAD /app`, `/app/Ferry.apk` | The bundled APK (`app/Ferry.apk` next to ferry.py), `application/vnd.android.package-archive`, Range support (reuses the outbox file sender). Served only while the APK matches its json (see next row) [B26]. |
| `GET /api/v1/app` | `{"version_code":N,"version_name":"2.0.0","size":N,"sha256":"…","url":"/app/Ferry.apk"}` read from `app/Ferry.apk.json` (written by CI next to the APK). 404 `{"result":"error","message":"no app bundled"}` if missing/invalid. **Both files are re-stat'ed on every `/app` and `/api/v1/app` request**; the json is re-read and the APK re-hashed only when the size or mtime of either changed (cache keyed by both). While the APK's size/sha256 disagree with the json (e.g. a new zip is being unpacked over a running Ferry), both routes answer 404 "The app is being copied, try again in a moment" [B26]. |
| `POST /api/v1/check-batch` | Body JSON `{"files":[{"name","size","mtime"}…]}` (≤ 2000 items, ≤ 1 MB body; `mtime` may be absent). Reply `{"ok":true,"results":[{"duplicate":"<existing name>"|null}…],"needed_bytes":N,"free_bytes":N}`: `results` is in input order; `needed_bytes` is the sum of sizes of the **non-duplicate** items only; `free_bytes` is the inbox disk's free space minus the 64 MB safety margin. The endpoint never refuses for disk space; the app decides (§4.4) [B3]. Implemented with **one folder scan and one `disk_usage` call per request** (a dict of name families → entries), not v1's `precheck` per item [B4]. Saves one round trip per file. |

`/api/v1/info` adds `"protocol":2`, `"capabilities":["receive","outbox","check_batch","app_update","discovery"]`, `"app_version_code":N|null`.

### 2.4 Discovery responder (UDP 8765) **[H10]**
- Thread binds UDP `0.0.0.0:8765` (separate from TCP 8765; same port number keeps the firewall story to one number). **UDP is always 8765, whatever `--port` says** (the app only broadcasts to 8765); the reply's `port` is the real TCP port [B16]. If the bind fails, print one console line "Automatic discovery is off. On the phone, enter `<ip>:<port>` once" and continue (the app falls back to the last-known address / manual entry) [B16].
- Request (datagram ≤ 512 bytes, UTF-8 JSON): `{"app":"ferry","q":"discover","protocol":2,"name":"vivo X200 Pro","device_type":"phone","nonce":"<8 hex>"}`.
- Reply (unicast to the sender's address and port): `{"app":"ferry","protocol":2,"nonce":"…","name":"Saurabhs-PC","device_type":"pc","port":8765,"version":"2.0.0"}`. Anything else is ignored silently; at most 20 replies/s total (cheap flood guard).
- **The loop never dies** [B2]: every `recvfrom`/`sendto` is wrapped in `try/except OSError: continue` (on Windows a reply to an already-closed port makes the *next* `recvfrom` raise `ConnectionResetError` 10054); the socket has a 0.5 s timeout so the thread can stop cleanly.
- Windows Firewall: the existing "Python, Private networks" allow rule is a program rule (all protocols), so UDP is covered. README covers the case where the user only allowed TCP.

### 2.5 `ferry-log.txt` (C5)
- One line per request, tab-separated: `time  ip  device  method  path(no query values except name)  range  status  bytes  ms  outcome  error`. Device = `X-Ferry-Device` header (the app sends its device name, **percent-encoded UTF-8**, decoded here with `unquote` [B10]) or the UA class.
- **Polling is collapsed** [B28]: successful (200) `GET /api/v1/outbox` and `GET /api/v1/info` are not logged one by one; they are counted and written as one line per device every 10 min ("118 list refreshes"). Every transfer and every error is always logged in full.
- Plus events: start/stop, discovery replies (rate-limited to 1/min per IP), folder recreated, errors.
- Written by a dedicated writer thread through a **bounded** queue (10 000 lines; overflow is dropped and counted in the next line written) [B9], so a slow disk never blocks transfers or grows memory.
- Rotation [B9]: when > 5 MB (also checked at startup) the writer **closes its handle**, `os.replace(ferry-log.txt, ferry-log.1.txt)` with 3 short retries, then reopens in append mode. If the replace still fails (file open in an editor, OneDrive, antivirus), it reopens the same file, keeps appending and tries again after another 1 MB; at 10 MB it truncates and starts fresh. **Logging never switches itself off**; only the console note about a logging problem is limited to once.

### 2.6 App bundling and packaging
- `pc/app/Ferry.apk` + `pc/app/Ferry.apk.json` are produced by CI (release-signed APK) and included in the user's zip `Ferry-2.0.0.zip`. Updating the app = replacing those two files (or unzipping a newer Ferry zip over the old folder).
- `Ferry.bat` unchanged. README rewritten (C6).

### 2.7 Console
Banner: QR now encodes `http://<ip>:8765/` (the Get-the-app page) with the text "First time? Scan to install the Ferry app". Activity lines as v1 ("from vivo X200 Pro  saved  IMG_….jpg  4.1 MB  22 MB/s"); the device name now comes from the `X-Ferry-Device` header (decoded, §2.5 [B10]).

### 2.8 Test hooks (not in the README) [B24]
So that JVM and emulator tests can start real `ferry.py` instances side by side:
- `--port 0` picks a free TCP port; once bound, ferry.py prints `FERRY_READY port=<n>` on stdout.
- `--discovery-port N|off` (default 8765).
- `--data-dir DIR` for `ferry-state.json`, `ferry-error.log` and `ferry-log.txt` (default: next to ferry.py, as v1).
- `--test-free-bytes N` makes the disk-space checks see N free bytes (for the disk-full tests) [B11].
- Tests always pass `--no-open --inbox DIR --outbox DIR`.

## 3. Android app: architecture

### 3.1 Modules and libraries **[H11]**
- `:core` — pure Kotlin/JVM, **no Android imports**: protocol models (org.json-free: a tiny JSON helper on top of `kotlinx.serialization`), `FerryClient` (OkHttp), `UploadEngine` (queue/state machine), `MiniHttpServer` (Range file server + JSON control endpoints), `DiscoveryCodec`. Unit-tested on the JVM, including **against a real `ferry.py`** started by the test (CI has Python).
- **`:core` must run on API 25** [A3]: it is compiled to Java 8 bytecode and uses only APIs that exist on Android 7.1: no `java.time`, no `java.nio.file`, no Java 9+ library methods (`List.of`, `String.repeat`, `HexFormat`…); Kotlin stdlib, `java.io`, `java.net`, `java.util.concurrent` and `System.currentTimeMillis` are enough. As a safety net `:app` enables core library desugaring, and the API 25 emulator job runs real `:core` code (§10).
- `:app` — Android: Compose UI (phone + TV), services, MediaStore access, Media3 player, installer.
- Libraries: OkHttp 4.12, kotlinx-serialization-json, kotlinx-coroutines, Compose BOM + Material3, `androidx.tv:tv-material` is **not** used (plain Compose with focus modifiers keeps one design system; fewer alpha APIs) **[H12]**, Media3 ExoPlayer + UI, Coil 2 (images; picker thumbnails come from the platform, §4.1 [A17]), androidx.lifecycle/navigation-compose. **No Google Play services** (Fire TV has none).
- minSdk 25, targetSdk 35, compileSdk 35. Kotlin 2.0.21, AGP 8.7.3, Java 17 toolchain (`:core` targets Java 8 bytecode [A3]).

### 3.2 Signing **[H13] [H18]**
A dedicated release keystore was generated once and is **committed to the private repo**: `android/signing/ferry-release.jks` with `android/signing/signing.properties` (alias and passwords) [B23]. (GitHub secrets were the first plan; this workspace cannot set them, see H18.) CI builds a **release-signed** APK (minify off for v2.0 to keep debugging simple). The same key must sign every future version, otherwise updates fail; CI checks that the release APK's signing-certificate SHA-256 equals a value pinned in the repo [B23], and a backup copy of the keystore is sent to the user at the end. Debug builds are only used for emulator tests.

### 3.3 Versioning
`versionCode` = **20000 + the run number of the one release workflow (`android.yml`), built from `main` only** [B22] [H26]; `versionName` = `2.0.<run number>`. `Ferry.apk.json` carries both. GitHub's run number is per workflow file, so **`android.yml` must never be renamed or split** (the count would restart, Android would refuse the "update" as a downgrade); this rule goes in the handoff. Branch/PR builds are never packaged for the user.

### 3.4 Process and services
- `MainActivity` (single activity, Compose navigation). Picks phone or TV UI.
- `TransferService` (phone): foreground service, type `dataSync`, notification channel "Transfers". Runs the upload queue and outbox downloads. Holds a partial `WakeLock` and a `WifiLock` while active; releases them when idle and stops itself.
  - Locks [A7] [A21]: both are acquired **with a timeout** (4 h, re-acquired while work continues) and released in `finally`. WifiLock mode by API level: `WIFI_MODE_FULL_HIGH_PERF` below API 29, `WIFI_MODE_FULL_LOW_LATENCY` on 29+. On recent Android the WifiLock may do nothing with the screen off, so the design **does not rely on it**: the foreground service, the WakeLock and the live transfer keep the connection up.
  - `dataSync` time limit [A6]: Android 15 allows about 6 h per 24 h. `onTimeout()` stops the engine cleanly (remaining items "Not sent", summary with Retry) and calls `stopSelf()` at once.
  - Stopped by the user (notification swiped away / "Active apps" → Stop) = **Cancel**, not a crash [A6].
  - Notifications [A6]: the progress notification is updated at most once per second. The **final summary is a separate normal notification** on a second channel "Results", posted after the service stops (the progress notification disappears with the service); the same summary stays on the Transfer screen. If notifications are denied, transfers still run and the summary is in the app.
- `CastService` (phone): foreground service, type `mediaPlayback` **[H14]** (it serves media to the TV; this type has no daily time limit, so a long film doesn't use up the `dataSync` allowance [A6]), runs the phone's file server while casting; notification with Pause/Next/Stop. Same lock rules as above.
- **Starting services** [A6]: only from a visible activity (button tap, share activity) or a notification action (Retry). Never from a network-change callback or broadcast receiver.
- `CastReceiver` (TV): runs the TV's control server while the app is in the foreground, **started in `onStart`, stopped in `onStop`** [A10] (no background service on TV, which matches how people use a TV app). Holds a `MulticastLock` so the TV can hear discovery broadcasts.
- State flows from services to UI via a process-wide `StateFlow` held in an `AppGraph` singleton (simple manual DI).

### 3.5 Finding devices (P8)
0. **Use the home network, not mobile data** [A9]: the app takes the Wi-Fi (or Ethernet, for a TV on an adapter) `Network` from `ConnectivityManager` and binds **every** socket to it: OkHttp via `network.socketFactory`, the discovery `DatagramSocket` via `network.bindSocket`, and the same factory for ExoPlayer and Coil. So vivo features that move traffic to mobile data when Wi-Fi has "no internet" cannot break Ferry. `:core` stays Android-free: `FerryClient` and discovery take a socket factory / binder as a parameter. No such network → status "Your phone is not on Wi-Fi" (distinct from "PC not found").
1. **Last-known PC** (stored `host:port` [B16]) — `GET /api/v1/info` with a 1.5 s timeout. The PC's IP is reserved in the router, so this is the normal fast path.
2. **UDP broadcast** on the bound network: to `255.255.255.255` and the interface's directed broadcast (e.g. `192.168.1.255`), always to UDP **8765**, 3 sends 300 ms apart, collect replies for 2 s. The PC's TCP port is taken from the reply [B16]. Replies are verified with `GET /api/v1/info` (`app == "ferry"`; `protocol < 2` → "older Ferry" message, §1 [B15]).
3. **Manual**: Settings → "PC address" (e.g. `192.168.1.5`, or `192.168.1.5:8800` if the PC uses another port [B16]), remembered.
- Multiple PCs: the first that answers is used; Settings lists all found and lets the user pick (remembered).
- Re-discovery runs on app start, on network change (ConnectivityManager callback), and when a request fails with a network error.
- TVs are found the same way (`device_type":"tv"`, port 8766).

### 3.6 Manifest checklist [A1] [A4]
A CI test reads the merged manifest of the release build and asserts every line below (no emulator needed).

| Entry | Why |
|---|---|
| `android:usesCleartextTraffic="true"` on `<application>` **[A1]** | Everything is plain HTTP to LAN addresses. Without this, every request fails on API 28+ (phone, Fire OS 7/8, ExoPlayer, Coil) and only Fire OS 6 would work |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Sockets, network callback, Wi-Fi network binding |
| `CHANGE_WIFI_MULTICAST_STATE` | `MulticastLock` (TV hears discovery) |
| `WAKE_LOCK` | WakeLock / WifiLock |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`; services declare `foregroundServiceType` `dataSync` / `mediaPlayback` | Required on Android 14+, otherwise `startForeground` throws |
| `POST_NOTIFICATIONS` | Android 13+ |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`; `READ_EXTERNAL_STORAGE` (maxSdkVersion 32); `WRITE_EXTERNAL_STORAGE` (maxSdkVersion 28) | Picker; partial access on 14+ [A15]; downloads on API 25–28 |
| `ACCESS_MEDIA_LOCATION` | GPS in photos (P2) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Battery dialog (P10) |
| `REQUEST_INSTALL_PACKAGES` | Without it Ferry is missing from "Install unknown apps" and installs are refused on API 26+ |
| `<uses-feature>` `android.hardware.touchscreen` and `android.software.leanback`, both `required="false"` | One APK installs on phone and TV |
| `MainActivity` intent filter with **both** `LAUNCHER` and `LEANBACK_LAUNCHER`; `android:banner` (320×180) | Ferry appears on the Fire TV home |
| `ShareReceiverActivity` with `ACTION_SEND` and `ACTION_SEND_MULTIPLE`, `*/*` [A5] | Share → Ferry |
| `FileProvider` (authority `app.ferry.files`; only the update and APK-download folders) [A14] | Hands APKs to the system installer |

Code rules for API 25 (Fire OS 6): start services with `ContextCompat.startForegroundService`; create notification channels only on API 26+ [A4].

## 4. Phone app

### 4.1 Screens (Compose, Material3, Ferry blue `#1d4ed8` / amber `#fbbf24`)
1. **Home**
   - Header: Ferry logo, "→ Saurabhs-PC" with a status dot (green "Connected", grey "Looking for your PC…", red "PC not found" + "Try again" + "Enter address"; "Your phone is not on Wi-Fi" [A9]).
   - Big buttons: **Send photos & videos**, **Send other files (documents, APKs…)** [A19], **From PC** (badge with count), **Play on TV**.
   - Banners: update available ("Ferry 2.0.7 is available → Update"), permissions/battery reminders, ongoing transfer card (tap → Transfer screen).
2. **Photo picker** (P3) — in-app, from MediaStore (images + videos):
   - Tabs **Recent** (all, newest first, sticky date headers) and **Albums** (bucket list → grid). Selection is an **ordered set** (`LinkedHashSet`) of MediaStore IDs held in a ViewModel [A17], so it **survives switching albums/tabs** and files are sent in the order they were picked.
   - Grid of thumbnails (video badge with duration), tap toggles. Bottom bar: "12 selected · 1.4 GB" + **Send**. "Select all" per album. Long-press preview and per-date "Select all" are **not in v2.0** [A17] [H22].
   - Data [A17]: the MediaStore query is paged (a library can hold 30 000 items); the album list is built in memory from the rows (Android 11+ doesn't allow `GROUP BY`); thumbnails come from the platform (`ContentResolver.loadThumbnail` on 29+, `MediaStore…Thumbnails` on 25–28), so no extra video library is needed.
   - Partial photo access (Android 14 "Select photos"): banner "Ferry can only see some photos → Allow all" (opens permission settings).
3. **Transfer** (P4/P5/P6): overall bar, "Sending 5 of 20 · 18.2 MB/s · about 1 min left", per-file rows (Waiting / Checking / Sending % / Saved / Saved as "x (1).jpg" / Saved without location [A2] / Already on PC / Failed + reason), **Cancel**. While the PC is unreachable: "Waiting for Saurabhs-PC… (continues automatically)". Summary at the end: "18 saved · 2 already on PC" (+ "1 failed → Retry"; + "3 saved without location" [A2]; "Already on PC" has a small **Send anyway** action, §4.4 [A2] [B6]). Same info in the notification.
4. **From PC** (P7): list of outbox files (icon by kind, name, size, age), tap → download to `Downloads/Ferry` with progress; **Download all**. After download: "Open" (photos/videos/docs via ACTION_VIEW) or "Install" for APKs (system installer). Pull to refresh; refreshes every 5 s while visible.
5. **Play on TV** (P9): choose TV (auto-selected if one), then **Videos** (picker in single-select mode, video-only) or **Photo slideshow** (picker, images only, multi-select). Then the **Remote** screen: title, position/duration, ⏮ ⏯ ⏭ ⏹, "−10 s / +10 s" for video, and slideshow speed 3/5/10 s. If no TV answers: "On the TV, open Ferry and leave it on its home screen" [A10].
6. **Settings**: PC (found/manual address), "Android battery setting: OK" status with a "Fix" button and the vivo steps [A8], slideshow default, app version, **Share diagnostics log**.
7. **Share target** (P1b) [A5] [B19] — no screen of its own:
   - `ShareReceiverActivity` (translucent, no UI) accepts `ACTION_SEND` / `ACTION_SEND_MULTIPLE` of any type.
   - It forwards the URIs to `TransferService` **inside the start intent's `ClipData` with `FLAG_GRANT_READ_URI_PERMISSION`**, so the service holds its own read grant and can keep reading after the share activity is gone and the screen is off. (The shared URIs already fitted in one intent, so forwarding them does too. An in-process hand-over would not carry the grant.)
   - Then it shows a toast "Sending 12 files to Saurabhs-PC" and finishes, returning the user to the Gallery. Progress is in the notification; tapping it opens the Transfer screen.
   - PC not found yet: the items are queued anyway ("Waiting for your PC", §4.4).
   - Works before the first-run permissions were given (shared URIs need no media permission). For MediaStore URIs the location rule of §4.3 applies: if location access is missing, the activity stays up to ask "Allow / Send without location" before queuing.
   - A URI that can no longer be read → that item Failed "Can't read this file any more. Share it again".

### 4.2 Permissions and first run (P10)
- **Ask when needed, not all up front** [A15] [H23]:
  - First run: one welcome screen explaining what Ferry does, with the single **battery** step below. No other dialogs.
  - First tap on **Send photos & videos**: one request for **Photos and videos + location in photos** together (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`, `ACCESS_MEDIA_LOCATION` on 33+/34+; `READ_EXTERNAL_STORAGE` + `ACCESS_MEDIA_LOCATION` on 29–32; `READ_EXTERNAL_STORAGE` on ≤ 28). The text before it says "Choose **Allow all**. If vivo asks again, choose Allow."
  - First transfer starts: **notifications** (`POST_NOTIFICATIONS`, 33+).
  - Partial access (14+) = `READ_MEDIA_VISUAL_USER_SELECTED` granted but `READ_MEDIA_IMAGES` not → the picker banner of §4.1. Denied twice (Android then stops showing the dialog) → the button opens Ferry's page in system Settings.
- **Battery**: if `PowerManager.isIgnoringBatteryOptimizations` is false, the welcome card explains "vivo may stop Ferry in the background. Allow Ferry to run in the background." → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; sideloaded, so Play policy doesn't apply). Shown once, then only as a Settings status line.
  - That Android setting does not cover vivo's own battery manager, so the status line reads **"Android battery setting: OK"**, never "unrestricted ✓", and always has the "Still stopping?" link [A8].
  - "Still stopping?" steps [A8], kept in **one string** so they can be corrected after the first real test: "1. Open Recents and lock Ferry (pull the Ferry card down or tap the lock). 2. Settings → Battery → Background power consumption → Ferry → Allow."
  - **After a kill** (detected on next start by an unfinished-queue marker) these steps are shown prominently with the interrupted-transfer message; that is the main signal, not the status line [A8].
- WRITE to `Downloads/Ferry`: MediaStore `Downloads` collection with `RELATIVE_PATH = "Download/Ferry"` on 29+ (no permission); on 25–28 `WRITE_EXTERNAL_STORAGE` (maxSdkVersion 28) and a plain file in `Environment.DIRECTORY_DOWNLOADS/Ferry` (never-overwrite rule in §4.5 [B8]).

### 4.3 Reading originals (P2)
For each selected item we build an `UploadItem(uri, name, size, mtimeMs?, original: Boolean)`:
- **MediaStore items** (picker and Gallery shares with `content://media/…`): `uri = MediaStore.setRequireOriginal(contentUri)` when `ACCESS_MEDIA_LOCATION` is granted (29+), so GPS EXIF is **not redacted**. `name = DISPLAY_NAME`, `mtimeMs = DATE_MODIFIED × 1000` (seconds precision; the PC's duplicate check allows ±2 s).
- **Original access is checked by opening the file when the queue is built** [A2] [B6] (the failure shows at open time, not when the URI is made). On API 29+, if the permission is missing or the open fails, the item is **not sent silently**: one dialog for the batch, "Ferry isn't allowed to read photo locations. **Allow** / **Send without location**" [H19]. Why it matters: a copy without GPS has the same name, size and date as the original, so the PC would later answer "Already on PC" and the good copy could never replace it. Items sent that way are marked `original = false`, shown as "Saved without location" and counted in the summary; the way back is **Send anyway** (§4.4).
- **Size** [B5] [A21]: `size` is the length of the **opened descriptor** (`ParcelFileDescriptor.statSize` when ≥ 0), falling back to MediaStore `SIZE`; the same value is used for the duplicate check and the upload, so the two always agree even when MediaStore is stale.
- **Date** [B17] [A21]: if the modified date is unknown, 0 or negative, **`mtime` is left out** of the check and the upload (never "now"). The PC then doesn't set a date and its content hash still prevents a duplicate file.
- **Other shares / "Send other files"** (SAF `OpenMultipleDocuments`, any type): `OpenableColumns.DISPLAY_NAME`/`SIZE`, `DocumentsContract.Document.COLUMN_LAST_MODIFIED` when present (otherwise omitted, as above). Photos/videos picked this way arrive as media-document URIs; on 29+ they are mapped to MediaStore with `MediaStore.getMediaUri` and then read as originals like picker items. If mapping fails for an image/video, the chooser result shows one line "Photos sent this way may lose their location. Use Send photos & videos." [A19]
- **Size unknown** (−1/null): open `AssetFileDescriptor` and use its length; if still unknown, copy to the app cache first and send the copy (rare; documents from cloud providers).
- Gallery share of a `content://` URI from another provider (e.g. vivo Gallery's own provider): try to map it to a MediaStore ID (query `_id`/`DATA`); if that fails, send as-is (GPS may be stripped by the provider; nothing we can do).

### 4.4 Upload engine (`:core` `UploadEngine`)
- States per item: `Waiting → Checking → Sending(bytes) → Saved | Renamed(savedAs) | AlreadyOnPc | Failed(reason) | Cancelled`.
- Flow: `check-batch` for all items (chunks of 500, client timeout 60 s [B4]). Duplicates are marked "Already on PC" immediately. The engine adds up `needed_bytes` over all chunks; if the total is more than `free_bytes`, the queue stops before sending anything ("Your PC needs X more free space") [B3]. A resend of files that are all duplicates needs 0 bytes and is never refused. If check-batch fails with an HTTP error or times out (PC reachable), the items are uploaded **unchecked**: the PC's content hash still skips duplicates, it only costs time [B4]. A network error goes to "Waiting for PC" as below.
- **Send anyway** [A2] [B6]: from "Already on PC" rows/summary, re-queues those items with the pre-check skipped. The PC compares content on receipt: identical → skipped as before; different (e.g. the PC copy had no location) → saved as `name (1).ext`. Nothing is overwritten.
- Then uploads with **2 parallel workers** **[H15]** (small files are dominated by per-request latency; 2 keeps large-file speed and roughly doubles small-file throughput), ordered by the user's selection order. One big file (> 256 MB) at a time: when a worker starts one, the other finishes its current file and waits.
- Each upload: `POST /api/v1/upload?name=&size=&mtime=` (`mtime` omitted when unknown [B17]) with headers `Content-Length`, `X-Ferry-Device` (**percent-encoded UTF-8**: device names can contain curly quotes or emoji, which OkHttp refuses in a header [B10]), streaming body from the opened descriptor in 256 KB chunks. Timeouts: connect 5 s, write stall 60 s, response wait after the body 10 min (the PC may be hashing a same-named large file).
- Results: 200 → by `result`; 507 → stop queue, "PC disk is full"; 400/411 → Failed (bug); 500 → Failed with the PC's message; network error / timeout → **Retry policy**: the item goes back to Waiting, and the engine enters **Waiting for PC**: it probes `/api/v1/info` every 3 s (with re-discovery every 30 s) for up to 30 min, then resumes. Each item gets at most 3 network failures while the PC is reachable before it's marked Failed. Cancel stops everything, aborting the in-flight request (the PC deletes its temp file).
- **30 min without the PC** [B20]: remaining items become "Not sent: PC not found", the summary is shown with **Retry**, locks are released and the service stops.
- **Disk full that looks like a network error** [B11]: on a big file the PC may close the connection before the app can read its 507. So after any network failure in the middle of a body, the engine first asks `/api/v1/info`; if `free_bytes` is less than the item's size + 64 MB, it is treated as disk full (queue stops, "PC disk is full"), not retried.
- **Failure after the whole body was sent** [B18]: the engine remembers "body fully sent" per item. If the retry is then answered `skipped`, the item counts as **Saved** (this transfer did save it). Cancel at that moment waits up to 10 s for the PC's answer before deciding Saved or Cancelled.
- If the file changed while sending (read fewer/more bytes than `size`): abort the request, **re-read size and date once and retry** [B5] [A21]; if it happens again, Failed "file changed while sending".
- Progress is published at most every 250 ms (UI; the notification at most once per second [A6]); speed is a 3 s moving average.
- The queue is **in memory** inside `TransferService` **[H16]**; the service keeps the process alive. If the process is killed anyway, on next start the app shows "The last transfer was interrupted (N files not sent). vivo may have stopped Ferry → Fix battery settings", and the user re-sends (duplicates are skipped instantly).
- Adding files while a transfer runs appends to the queue (one combined summary).

### 4.5 Outbox downloads
Rule: **never overwrite and never leave a partial file on the phone**, on any Android version [A16] [B7] [B8].
- `GET /api/v1/outbox` list. Before a download: free-space check (`StatFs`); too little → "Not enough space on your phone" [A16].
- **API 29+**: `GET /api/v1/outbox/<id>/<name>` streamed into a new MediaStore Downloads entry with `IS_PENDING=1`. Name clashes are handled by MediaStore (it appends " (1)").
- **API 25–28** [B8]: stream into `Download/Ferry/.<name>.part`; when complete, claim the first free `name.ext` / `name (n).ext` with `File.createNewFile()` (atomic; a plain rename would replace an existing file), rename the part file onto the claimed name, then run the media scanner.
- **Publish only if complete** [B7]: bytes written must equal the size from the outbox list / `Content-Length`; then `IS_PENDING=0` (or the rename). Otherwise the entry / part file is deleted.
- **Network drop or cancel: delete the entry and start again from zero in a fresh entry** when the PC is back. There is **no resume in v2.0** [B7] [A16] [H20]: appending to a pending MediaStore entry behaves differently across Android versions and a wrong guess corrupts the file; at ~20 MB/s a restart costs under a minute per GB.
- On app start, Ferry deletes its own leftover pending entries / `.part` files in `Download/Ferry` [B7].
- "Open" / "Install" after a download pass the URI with `FLAG_GRANT_READ_URI_PERMISSION` (APKs go through §4.6) [A16].
- Runs inside `TransferService` (survives the screen off). "Download all" queues them one at a time.

### 4.6 App update (C3) and installing APKs
- On every successful PC connection (phone and TV): `GET /api/v1/app`. If `version_code > BuildConfig.VERSION_CODE` → banner "Update available (2.0.7)". Tap → download to the app's cache (`files/update/Ferry.apk`), verify sha256 and size, then install.
- **Install = hand the file to the system installer** [A14] [H21]: a `FileProvider` URI with `ACTION_VIEW`, type `application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION`. This one path works from API 25 to 35 and is used for the self-update **and** for APKs from the outbox on the TV (T4) and the phone. No `PackageInstaller` session (no status receiver, no mutable `PendingIntent`); the user confirms in the system dialog either way.
- "Install unknown apps" [A14]: on API 26+, if `canRequestPackageInstalls()` is false, explain and open `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for Ferry inside try/catch; if the device has no such screen, show the steps as text instead (§5.4). On return, check again and offer the install again. On API 25 the call doesn't exist and isn't made.
- **Update is disabled while a transfer, download or cast is running** ("Update after this finishes"), because installing restarts Ferry [B21].

## 5. Fire TV app

### 5.1 UI (10-foot, D-pad) **[H12]**
- Dark theme, 1.5× type scale, large focus ring (amber outline + scale 1.05). Every action reachable with D-pad + OK; Back always goes up one level; no pointer needed.
- **Focus rules** (needed because `tv-material` isn't used) [A13]: the first card has focus when a screen opens (`FocusRequester`); each row remembers its focused card and gets it back when returning from the Player/Viewer; the focused card is always scrolled into view; every focusable shows the ring. An emulator test walks Home → Player → Back and asserts focus is on the same card (§10).
- **Home**: header "Ferry · Saurabhs-PC ●". Rows (left/right inside a row, up/down between rows):
  1. **Videos** (outbox kind `video_playable` only [A12]): cards with name, size; OK → Player.
  2. **Photos** (outbox `image`): first card "▶ Slideshow (24)", then photo thumbnails (OK → Viewer at that photo). Thumbnails [A11]: the PC only serves originals (5–15 MB each), so they are loaded **only for cards on screen**, at most 2 at a time, decoded at about 320 px, and kept in a disk cache keyed by `name|size|mtime`; the 5 s refresh never reloads unchanged files. A photo that can't be decoded shows an icon.
  3. **Apps** (outbox `apk`): OK → Install.
  4. **Other files**: shown with a note "Open these on your phone or PC" (no action). Videos the TV player can't handle (kind `video`: .avi, .wmv…) are listed here too [A12].
  Empty state: "Put files in **Desktop → Ferry outbox** on your PC. They'll appear here." Auto-refresh every 5 s.
  Idle line, large [A10]: "**Ready for your phone.** On the phone: Ferry → Play on TV." with this TV's name.
- **PC not found on the TV** [A18]: "Can't find your PC" with **Try again**, the list of PCs found (if any) and **Enter address**: the system on-screen keyboard in number mode, prefilled with the first three parts of the TV's own address (e.g. `192.168.1.`), so only the last number is typed. Remembered.
- **Screen stays on** [A10] [H24]: `FLAG_KEEP_SCREEN_ON` while the Player, Viewer or Slideshow is showing, and on Home for 30 min after the last key press or cast (so the Fire TV screensaver doesn't push Ferry into the background while the user walks to the phone, but a forgotten TV still goes to sleep).
- Update banner as on the phone (focusable).

### 5.2 Video player (T2)
- Media3 ExoPlayer with `DefaultHttpDataSource` (Range requests; the PC already supports Range/If-Range/ETag), connect timeout 8 s and read timeout 20 s so a dead connection is noticed quickly [B13]. HEVC/4K are decoded by the TV's hardware if it supports them; if the format is unsupported, show "This TV can't play this video format" with the codec name.
- **Sound format** [A12]: if the video plays but its audio track isn't supported (e.g. DTS), ExoPlayer would play silently; Ferry checks the tracks and shows "This TV can't play this video's sound (DTS)".
- Keys: OK/Play-Pause = toggle; D-pad left/right = −10/+10 s; Rewind/Fast-forward keys = −30/+30 s (hold repeats); up shows the controller with a seek bar; Back = stop and return. **The player screen handles these keys itself**; the Media3 controller is display-only and never takes D-pad focus [A12].
- Buffer: `DefaultLoadControl` with min 15 s / max 50 s; a long pause doesn't break playback, because ExoPlayer reopens the connection with a Range request (the PC drops a stalled connection after 60 s; ExoPlayer retries). This is the old Silk freeze, so it is **tested in CI** (§10) [B13].
- Resume: position saved every 5 s and on stop, keyed by `name|size|mtime`; reopening offers "Resume from 12:34" / "Start over" (if > 30 s in and > 30 s before the end), **with focus on Resume, so one OK press continues** [A12].

### 5.3 Photo viewer and slideshow (T3)
- Full-screen Coil image, decoded at screen size (`size(Size(displayW, displayH))`), EXIF orientation honoured; HEIC decoded by the platform on Android 9+ (older TVs show "Can't show this photo format").
- A photo that fails to decode (unsupported format or not enough memory on an old stick) shows "Can't show this photo" for 2 s and the slideshow **moves on** [A20].
- Slideshow: auto-advance every 5 s (setting: 3/5/10 s), cross-fade 400 ms; preloads the next 2 images. Left/right = previous/next (pauses auto-advance for that photo), OK/Play-Pause = pause/resume, Back = exit. A small overlay "3 / 24 · name" for 2 s after each key press.

### 5.4 Install APKs (T4)
OK on an APK → download to cache with a progress dialog → system installer (as §4.6 [A14]). First time: explain "Allow Ferry to install apps" and open the Fire TV setting (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`, API 26+, inside try/catch). If that screen doesn't open, and on Fire OS 6 (global setting), show the steps as text [A14]: "Settings → My Fire TV → Developer options → Install unknown apps → Ferry: ON. (No Developer options? Settings → My Fire TV → About → press OK on the device name 7 times.)" On Fire OS 6: "… → Apps from Unknown Sources: ON".

### 5.5 Receiving casts (T5)
- `CastReceiver` = `MiniHttpServer` on TCP **8766** + discovery responder answering `device_type:"tv"` (UDP 8765, `MulticastLock` held while in the foreground). Runs from `onStart` to `onStop` [A10].
- `POST /api/v1/cast` → opens the Player or Slideshow with the given URLs, on top of whatever is showing. A toast "Playing from vivo X200 Pro".
- `POST /api/v1/control` `{"action":"toggle|play|pause|next|prev|seek|stop|interval","delta_ms":±N,"interval_ms":N}`; `GET /api/v1/state` → `{"kind":"video|photos|idle","index":i,"count":n,"title":…,"playing":bool,"position_ms":…,"duration_ms":…,"interval_ms":…}`.
- If the phone's server stops answering: "Lost connection to the phone" and return to Home after 10 s.
- The TV app must be **open** to receive casts (no background service on Fire TV) **[H14]**; the keep-screen-on rule of §5.1 keeps it open while the user picks on the phone [A10]. The phone says "On the TV, open Ferry and leave it on its home screen" if it can't find a TV [A10].

## 6. Phone → TV casting (P9)
- Phone starts `CastService` → `MiniHttpServer` on TCP **8767** (or the next free port) serving only the selected items at `/c/<token>/<index>/<urlencoded name>` (token = 128-bit random per cast session; requests with the wrong token get 404). GET/HEAD with Range, correct `Content-Type` from MediaStore `MIME_TYPE`, `Content-Length` from the AssetFileDescriptor; reads use `openAssetFileDescriptor` + channel position so seeking is cheap. HEIC photos are served as-is (the TV decodes them, or shows the format message).
- Phone → TV `POST http://<tv>:8766/api/v1/cast`: `{"kind":"video"|"photos","from":"vivo X200 Pro","interval_ms":5000,"start":0,"items":[{"url":"http://<phone-ip>:8767/c/<token>/0/VID_….mp4","name":"…","mime":"video/mp4","size":N}]}`. The phone's IP is the address of the interface that routes to the TV (taken from the socket connected to the TV).
- The Remote screen polls `GET /api/v1/state` every 1 s while visible; buttons send `/control`. The notification has Pause/Next/Stop. **Stop** sends `stop` to the TV and ends `CastService`.
- The phone's screen can be off while casting (foreground service + WakeLock; lock rules in §3.4 [A7]).

## 7. `MiniHttpServer` (`:core`)
- Small blocking HTTP/1.1 server on a `ServerSocket`, thread per connection (max **16** concurrent [B12]; extra connections get 503), `Connection: close` after each response (simple, matches what the PC does), 30 s socket **read** timeout, request line ≤ 8 KB, headers ≤ 16 KB, JSON bodies ≤ 64 KB.
- **Write watchdog** [B12]: Java's socket timeout only covers reads, so a client that stops reading (a paused or vanished TV, a connection abandoned after a seek) would block a writer thread for many minutes and use up the slots, freezing playback. Each connection records when it last made write progress; a watchdog closes any socket with no progress for 30 s, which unblocks the write and frees the slot. The player simply reconnects with a Range request.
- Routes are registered as handlers; file handler implements GET/HEAD, single-range `Range: bytes=a-b` / `a-` / `-n`, 206/416, `Accept-Ranges`, `ETag` (size-mtime), `If-Range`.
- JVM unit tests cover parsing, ranges, limits, concurrent clients, and a client that asks for a large range and stops reading (slot freed within ~35 s, new requests still served) [B12].

## 8. Security posture (unchanged choice: none)
No auth on the LAN (requirement). Small safety measures that cost nothing for the user: the cast file server only serves the items chosen for the current session, behind a random token, and only while casting; the TV accepts casts only while the app is open; the PC discovery responder rate-limits replies; JSON bodies are size-limited everywhere.

## 9. Diagnostics
- App: an in-app ring log (last 2000 lines, `files/ferry-app-log.txt`, rotated at 1 MB) of discovery results, requests, failures and service lifecycle. Settings → "Share diagnostics log" (Android share sheet).
- PC: `ferry-log.txt` (§2.5).

## 10. Testing strategy
| Layer | Tests | Where |
|---|---|---|
| PC | v1's 103 tests (made portable for Windows) + new: check-batch (duplicates don't count towards `needed_bytes` [B3]; 10 000-file inbox answered from one scan within a time budget [B4]), `/app` + `/api/v1/app` (missing/invalid/valid sidecar, sha mismatch, files replaced while running [B26]), Get-the-app page, discovery (request/reply, garbage ignored, rate limit, bind failure tolerated, **loop survives an injected `ConnectionResetError`** [B2], UDP stays 8765 with `--port` changed [B16]), `ferry-log.txt` (lines, polling collapsed [B28], writer never blocks, **rotation while another handle has the file open and when `.1` exists** [B9]), percent-encoded device name decoded [B10], test hooks [B24], removed routes now 404 | GitHub Actions: Windows + Linux, Python 3.8 and 3.13 |
| `:core` | JSON models, discovery codec, UploadEngine state machine with a fake client and fake clock (retries, disk full incl. the mid-body case [B11], cancel, waiting-for-PC and its 30 min end [B20], body-sent-then-skipped = Saved [B18], unchecked upload when check-batch fails [B4], Send anyway [A2], older-PC message [B15], parallel workers, big-file rule), MiniHttpServer (ranges, limits, stalled reader [B12]), **integration: FerryClient + UploadEngine against a real ferry.py** started with the §2.8 hooks [B24] (byte-identical, rename, duplicate skip, mtime restored, mtime omitted [B17], device name `Saurabh’s phone 📱` [B10], two workers sending `IMG_0001.jpg` and `img_0001.JPG` with different content and two sending identical content [B14], 100 MB upload into a "full" disk → "disk full" [B11]) | JVM job on Actions (starts ferry.py), on **ubuntu and windows-latest** [B14] (Windows is where the rename/case/long-path code runs) |
| `:app` build | **Merged-manifest test**: every entry of §3.6 present [A1] [A4]; release APK certificate fingerprint equals the pinned value [B23] | Actions, no emulator |
| `:app` | Robolectric-free: Compose UI tests and instrumented tests on an **API 34 phone emulator**. **GPS test** [B1]: the JPEG with GPS is **pushed with adb to `DCIM/Camera` and media-scanned, so Ferry does not own it** (Android never strips location for the app that created a file, so a self-inserted fixture could not fail) → send via the real service to ferry.py on the host (`10.0.2.2`) → PC file byte-identical and `exif_gps_status` on the PC side says GPS present; **negative control**: same run with `ACCESS_MEDIA_LOCATION` revoked and "Send without location" → PC copy has no GPS / is not identical, and is reported "Saved without location" [A2]. Share → Ferry of 3 files with the share activity finished → all 3 arrive [A5] [B19]; outbox download lands in Download/Ferry, a second download of the same name doesn't overwrite [A16]; cast loopback (phone server + TV receiver in one emulator, the receiver started by a **debug-only intent** since it normally runs only in TV mode [B27]); screenshots of every phone screen. | Actions with `reactivecircus/android-emulator-runner` (KVM on ubuntu runners), every push to `main` |
| `:app` TV | **TV emulator (Android TV API 30)**: launch in TV mode, D-pad navigation through Home, focus returns to the same card after Player → Back [A13], player opens a test video from ferry.py, **plays a ≥ 200 MB file, pauses 90 s (longer than the PC's 60 s timeout), resumes, seeks +30 s and −30 s → still playing, no player error** [B13], slideshow advances. **API 25 emulator** [A3] **[H17] [H27]**: not just a boot: an instrumented test runs discovery codec + `/info` + a small upload + a MiniHttpServer range request against ferry.py on the host | Same runner; on `main` and manual runs only (cost) [B27] |
| Real devices | Acceptance list in requirements §5, run by the user. For item 2 also note MB/s with the screen off vs on [A7] | vivo X200 Pro, Fire TV Stick, Saurabhs-PC |

Screenshots from the emulator tests are uploaded as CI artifacts and reviewed during the code audit (UX check without a real device).

**What CI does not prove** [B27] (goes in the handoff; only the real-device acceptance covers these): broadcast discovery (the emulator's network doesn't pass broadcasts to the host; CI covers the codec and a loopback reply only), vivo's battery manager, the Fire OS installer and unknown-sources screens, real Windows Firewall prompts, the Fire TV screensaver.

## 11. Repository layout
```
pc/        ferry.py, qrcodegen.py, Ferry.bat, README.txt, tests/, tools/package.py, app/ (CI puts the APK here)
android/   settings.gradle.kts, core/, app/, signing/ (release keystore, H18)
docs/      requirements, design, audits, plans, decisions (mirrored to the claude.ai project)
.github/workflows/  pc.yml, android.yml (unit + integration + emulator + release APK + PC zip)
```
Release flow: push to `main` → CI builds and signs the APK, runs all tests, assembles `Ferry-2.0.<n>.zip` (PC program + `app/Ferry.apk` + json + README) as an artifact.

## 12. Risks and mitigations
| Risk | Mitigation |
|---|---|
| vivo kills the foreground service | Battery-exemption flow + vivo steps incl. Recents lock [A8]; WakeLock; interrupted-transfer detection + guidance; duplicate skip makes re-sending cheap |
| Screen-off speed drops (WifiLock ineffective on recent Android) [A7] | Not relied on; acceptance 2 compares screen-off and screen-on MB/s; revisit only if the real phone shows a problem |
| UDP broadcast blocked (router AP isolation, Windows firewall rule TCP-only) | Last-known address first (IP is reserved); manual address (phone and TV [A18]); README firewall note; log shows discovery results |
| Phone routes traffic over mobile data [A9] | All sockets bound to the Wi-Fi network |
| GPS still stripped (e.g. vivo Gallery share from its own provider) | MediaStore mapping; in-app picker is the guaranteed path; no silent send without location + "Send anyway" recovery [A2]; emulator test with a non-owned file and a negative control [B1] |
| Fire TV can't decode a codec (video or sound) | Clear message with the codec [A12]; PC-side transcoding is out of scope |
| Fire OS 6 quirks (unknown-sources setting is global; missing Java APIs) | Version-specific instructions; `:core` API rule + real instrumented test on an API 25 emulator [A3] **[H17] [H27]** |
| Signing key lost or a different key used | Keystore committed in `android/signing/` (H18), CI fingerprint check [B23], backup copy to the user |
| HTTP/1.0 PC + one connection per request limits small-file speed | Batch check + 2 parallel uploads |
