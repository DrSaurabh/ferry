# Ferry v2: Code plan (Stage 4, rev 1)

Implements `v2-02-design.md` **rev 2** (the source of truth) for `v2-01-requirements.md`. Hands-free decisions are **[H#]** (`v2-decisions-log.md`); design-audit findings are **[A#]/[B#]** (`v2-03-design-audit.md`). Decisions taken in this plan are **[CP#]** (§0.2) so the next audit can check them.

Written for AI agents implementing Ferry v2 in **sequential phases** (§6). Each phase is one agent session, ends with a push to `main` and a green CI run, and leaves the repo releasable.

## Outline
- §0 Ground rules, code-plan decisions [CP#], pinned dependencies
- §1 Repository / module / file layout (pc/, android/:core, android/:app, android/ci)
- §2 PC program 2.0.0 (ferry.py changes, hidden test flags, log writer, discovery, check-batch, /app, Get-the-app page, console, package.py, README, Windows-portable tests, test list)
- §3 Android `:core` (APIs, state model, threading, errors, API-25 rules, JVM + integration tests)
- §4 Android `:app` (screens, ViewModels, services, permissions, MediaStore, installer, TV focus, Media3, manifest, strings [A22], insets [A25], instrumented tests, screenshots)
- §5 CI (workflows, jobs, emulators, caching, artifacts, versionCode [H26], PC zip with the APK)
- §6 Phases Ph0–Ph10 (goal, files, tests, CI, acceptance, risks)
- §7 Traceability (requirements and acceptance items → phases and tests)

## 0. Ground rules

### 0.1 How agents work in this repo
- **Python (pc/, android/ci/) is the only code that runs locally**: `cd pc && python3 -m pytest -q` and `python3 -m pytest -q android/ci`. Run them before every push.
- **Kotlin compiles only on GitHub Actions** (no Android SDK; Gradle 8.14.3 + JDK 21 are installed locally but Maven/Google are blocked). So: small, compile-safe steps; push; `gh run watch`; on red, `gh run view --log-failed`. Several pushes inside one phase are fine; a phase is **done** when the `main` run of every workflow it touches is green.
- Work directly on `main` (single-user private repo; emulator jobs only run on `main` and manual runs [H27]). Never rename `.github/workflows/android.yml` [H26].
- Commit messages start with the phase id, e.g. `Ph5: share target + TransferService`.
- **Never weaken a test to get green.** If a platform fact turns out different from the design (e.g. redaction behaviour), stop, record it in `docs/v2-decisions-log.md` as a new H-row, and adapt with the safest behaviour.
- Keep `:core` free of `android.*` imports (enforced by a test, §3.9).
- Do not add dependencies beyond §0.3 without a decisions-log entry.

### 0.2 Decisions taken in this plan [CP#]
| # | Decision | Why |
|---|---|---|
| CP1 | **No navigation-compose.** A ~80-line `Navigator` (back stack of a sealed `Screen` class in a `SnapshotStateList`, `BackHandler` per screen) | One dependency less; TV focus restoration is easier when the app owns the back stack (design §3.1 listed navigation-compose as a library, not a requirement) |
| CP2 | The TV player uses **`OkHttpDataSource`** (`media3-datasource-okhttp`) with the shared Wi-Fi-bound `OkHttpClient` (connect 8 s, read 20 s) instead of `DefaultHttpDataSource` | `DefaultHttpDataSource` cannot take the bound socket factory [A9]; same timeouts as design §5.2 [B13]; the request carries `X-Ferry-Device` for the log |
| CP3 | **Upload-time free-space check on the PC**: `POST /api/v1/upload` answers 507 before reading the body when `free < Content-Length + 64 MB` (then drains ≤ 64 MB and closes, as v1 does for errors) | Real disks: refuse early instead of filling the disk; needed so `--test-free-bytes` can drive the disk-full tests, incl. the "connection closed before the 507 was read" case [B11]. v1 clients already handle 507 |
| CP4 | Extra hidden flags beyond design §2.8: `--app-dir DIR` (where `Ferry.apk(.json)` are looked up) and `--discovery-port 0` (pick a free UDP port); `FERRY_READY port=<tcp> discovery=<udp\|off>` | Emulator update test and JVM discovery test need them; still not in the README or `--help` |
| CP5 | Merged-manifest, certificate, APK and json checks are **Python scripts** in `android/ci/` with their own pytest tests on fixture files | Locally runnable and testable (Kotlin is not) |
| CP6 | Emulator suites run through a host script (`android/ci/run_instrumented.sh`) that installs the APKs **without `-g`**, starts/stops the host `ferry.py` instances, runs `am instrument` per test class in a fixed order, grants/revokes permissions between classes, pulls screenshots, and runs host-side checks | B1's negative control needs `ACCESS_MEDIA_LOCATION` **not** granted in the first run (revoking a permission kills the test process); deterministic ordering; host files can be checked with `ferry.exif_gps_status` |
| CP7 | `pc/tools/package.py` builds only the user zip, named `Ferry-<version_name>.zip` from `app/Ferry.apk.json` (e.g. `Ferry-2.0.57.zip`); the v1 `-src` zip is dropped | The repo is the source; the version the user sees is the app's |
| CP8 | Picker selections reach `TransferService` through an in-process hand-over (`AppGraph.pendingPicks`); shares go through the start intent's `ClipData` with the read grant (design §4.1.7) | Picker IDs can exceed the binder limit (30 000 items × "Select all"); Ferry already holds the media permission for them, so no grant is needed |
| CP9 | `:core` compiles with Kotlin `-Xjdk-release=1.8` / Java `release 8`, plus a **bytecode scan test** that fails on references to API-26+ Java classes (`java/time/`, `java/nio/file/`, `java/util/Base64`, …) | Makes the API-25 rule [A3] a compile/test error, not a Fire OS 6 crash |
| CP10 | Engine, queues and ViewModels use **coroutines**; `MiniHttpServer` and the discovery responder use **plain threads** | Coroutine virtual time makes the 30-min wait / retry tests instant; blocking sockets are simpler and cheaper as threads |
| CP11 | MediaStore: images and videos are queried **separately** and merged, newest first by `DATE_MODIFIED` (the date Ferry sends) | `BUCKET_*` columns exist on both tables on every API level 25–35 |
| CP12 | `DEVICE-CHECKLIST.txt` is rewritten as the v2 real-device acceptance checklist (requirements §5, plus the screen-on/off MB/s note [A7]) | Replaces the v1 browser checklist; it's what the user runs |
| CP13 | Off `main`, `versionCode = 1` and `versionName = "2.0.0-dev"` | Only `main` builds are packaged [H26] |
| CP14 | `check-batch` writes **one console line** per request ("checked 20 files · 3 already on PC"), not one per duplicate | A 2 000-item check would flood the console; `ferry-log.txt` has the request line |
| CP15 | Every app request (OkHttp, ExoPlayer, Coil) carries `X-Ferry-Device` via one interceptor on the shared client | The PC log and console show "Saurabh's Fire TV" instead of "browser" for player and thumbnail requests |
| CP16 | Screenshots are written to the app's `filesDir/screenshots/` and pulled with `adb exec-out run-as app.ferry tar -c -C files screenshots` | Works on API 25–34 for a debuggable build; no test-services/orchestrator dependency |

### 0.3 Pinned toolchain and dependencies (all released before mid-2025; resolve on Maven Central / Google Maven)
Kept from the green smoke build: **AGP 8.7.3, Kotlin 2.0.21, Gradle 8.14.3 wrapper, compileSdk 35, Compose BOM 2024.12.01, Media3 1.5.1**. All versions go into `android/gradle/libs.versions.toml`.

| Use | Coordinates | Version | Module |
|---|---|---|---|
| Kotlin JVM plugin (for `:core`) | `org.jetbrains.kotlin.jvm` | 2.0.21 | core |
| Serialization plugin | `org.jetbrains.kotlin.plugin.serialization` | 2.0.21 | core |
| JSON | `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 | core |
| Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-core` / `-android` / `-test` | 1.9.0 | core / app / tests |
| HTTP client | `com.squareup.okhttp3:okhttp` | 4.12.0 | core |
| AndroidX core | `androidx.core:core-ktx` | 1.15.0 | app |
| Activity | `androidx.activity:activity-compose` | 1.9.3 | app |
| Lifecycle | `androidx.lifecycle:lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` | 2.8.7 | app |
| Compose | BOM `androidx.compose:compose-bom` → `ui`, `foundation`, `material3`, `ui-tooling-preview` (debug: `ui-tooling`, `ui-test-manifest`) | 2024.12.01 | app |
| Player | `androidx.media3:media3-exoplayer`, `media3-ui`, `media3-datasource-okhttp` | 1.5.1 | app |
| Images | `io.coil-kt:coil-compose` | 2.7.0 | app |
| Desugaring [A3] | `com.android.tools:desugar_jdk_libs` | 2.1.4 | app |
| Unit tests | `junit:junit` | 4.13.2 | core, app |
| Instrumented | `androidx.test:runner` 1.6.1, `androidx.test:rules` 1.6.1, `androidx.test:core-ktx` 1.6.1, `androidx.test.ext:junit` 1.2.1, `androidx.test.uiautomator:uiautomator` 2.3.0, Compose `ui-test-junit4` (BOM) | as listed | app androidTest |

Not used: navigation-compose [CP1], androidx.tv [H12], Google Play services, Robolectric, MockWebServer (tests use real `ferry.py` and our own `MiniHttpServer`), any native library [A25]. GitHub Actions: `actions/checkout@v4`, `actions/setup-java@v4` (temurin 17), `actions/setup-python@v5`, `gradle/actions/setup-gradle@v4`, `actions/cache@v4`, `actions/upload-artifact@v4`, `actions/download-artifact@v4`, `reactivecircus/android-emulator-runner@v2.33.0`.

## 1. Repository layout

```
.gitattributes               *.sh/gradlew eol=lf, *.bat eol=crlf, *.py eol=lf, binaries (*.jks *.apk *.jpg *.png *.mp4) binary
.github/workflows/pc.yml     PC tests (Windows + Linux, Python 3.8 + 3.13)
.github/workflows/android.yml  core JVM (ubuntu+windows), app build/checks, emulators, package (NEVER rename) [H26]
pc/                          ferry.py 2.0.0, qrcodegen.py, Ferry.bat, Ferry self-test.bat, ferry.sh, ferry-icon.svg,
                             README.txt (rewritten), DEVICE-CHECKLIST.txt (v2), LICENSE*.txt,
                             app/ (CI puts Ferry.apk + Ferry.apk.json here; .gitignored), tests/, tools/package.py
android/                     settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradle.properties,
                             core/, app/, ci/, signing/ (ferry-release.jks, signing.properties, cert-sha256.txt) [H18]
docs/                        requirements, design, audits, this plan, decisions, handoff
```

### 1.1 `pc/` files
| File | Responsibility | Design |
|---|---|---|
| `ferry.py` | The PC program (single file, stdlib only, Python ≥ 3.8). Sections in order: constants · helpers · Console · Windows/Linux platform · Config/State/folders · addresses/QR · sanitising · `Inbox` (+ `check_batch`) · `Outbox` · zip · `TransferTracker` · `exif_gps_status` (test helper) · **`RequestLog`** · **`DiscoveryResponder`** · **`AppBundle`** · `FerryServer`/`FerryHandler` · pages (`GET_APP_HTML`, `ERROR_PAGE`, `FAVICON_SVG`) · banner · `main` · `selftest` | §2 |
| `tests/test_*.py` | pytest; v1 files updated (§2.13), new files §2.14 | §10 |
| `tests/helpers.py` | **new**: `Srv` fixture moved out of `test_http.py` (adds `app_dir`, `free_override`, `reqlog` options), `upload()`, `jpeg_with_gps()`, `start_ferry_process()` (subprocess + `FERRY_READY`) | §2.8 |
| `tools/package.py` | Builds `dist/Ferry-<version_name>.zip` incl. `app/Ferry.apk(.json)`; fails if missing or inconsistent [B25] | §2.6 |
| `README.txt`, `DEVICE-CHECKLIST.txt` | Rewritten (C6, CP12) | §2.6 |
| **deleted** | `tools/page.html`, `tools/diag.html`, `tools/keepawake.mp4`, `tools/icon-maskable.svg`, `tools/build_assets.py`, `tests/js/`, `tests/e2e/` [B25] | §2.2 |

### 1.2 `android/core` (pure Kotlin/JVM, package `app.ferry.core`)
| File | Responsibility | Design |
|---|---|---|
| `model/Protocol.kt` | `@Serializable` wire models: `PcInfo`, `CheckFile`, `CheckBatchRequest/Reply`, `UploadReply`, `ErrorReply`, `OutFile`, `OutboxList`, `AppInfo` | §2.3 |
| `model/CastModels.kt` | `CastRequest`, `CastItem`, `ControlRequest`, `CastState` | §5.5, §6 |
| `model/Discovery.kt` | `DiscoveryRequest`, `DiscoveryReply`, `Peer` | §2.4 |
| `Json.kt` | The one `Json` instance (`ignoreUnknownKeys`, `explicitNulls=false`, `encodeDefaults=true`) | §3.1 |
| `HostPort.kt` | `host[:port]` value type + parser (manual entry, last-known) [B16] | §3.5 |
| `Clock.kt` | `Clock` interface, `SystemClock` | — |
| `net/FerryHttp.kt` | Builds the shared `OkHttpClient` (socket factory, timeouts, `X-Ferry-Device` interceptor [B10][CP15], `retryOnConnectionFailure(false)`) | §3.5, §4.4 |
| `net/FerryClient.kt` | `FerryApi` + implementation over OkHttp: info, checkBatch, upload, outbox, appInfo, download; maps failures to `FerryException` | §2, §4.4 |
| `net/FerryException.kt` | Error model (§3.4) | §4.4 |
| `net/StreamingBody.kt` | One-shot `RequestBody` streaming an `OpenedSource` in 256 KB chunks; progress, "body fully sent", size-mismatch detection [B5][B18] | §4.4 |
| `upload/UploadEngine.kt` | Queue + state machine (check-batch, 2 workers, big-file rule, waiting-for-PC, retries, disk full, Send anyway, cancel) | §4.4 |
| `upload/UploadModels.kt` | `UploadItem`, `UploadSource`, `OpenedSource`, `ItemStatus`, `EngineState`, `Summary`, `StopReason`, `EngineConfig` | §4.4 |
| `upload/SpeedMeter.kt` | 3 s moving average, ETA | §4.4 |
| `download/DownloadQueue.kt` | One-at-a-time outbox downloads, length check, restart-from-zero on drop [H20] | §4.5 |
| `download/DownloadSink.kt` | Sink interface implemented by `:app` (MediaStore / legacy file / cache file) | §4.5 |
| `update/AppUpdate.kt` | `isNewer()`, `downloadApk()` with size + sha256 verification | §4.6 |
| `discovery/DiscoveryCodec.kt` | Encode/parse request & reply JSON datagrams (≤ 512 B), nonce | §2.4 |
| `discovery/UdpTransport.kt` | `UdpTransport` interface + `DatagramTransport` (takes a socket binder) | §3.5 [A9] |
| `discovery/DiscoveryClient.kt` | 3 broadcasts 300 ms apart, collect 2 s, returns `Peer`s | §3.5 |
| `discovery/DiscoveryResponder.kt` | TV side: answer `device_type:"tv"`, port 8766; never-dying loop; 20 replies/s | §5.5 |
| `discovery/PcLocator.kt` | last-known → broadcast → verify `/info` → `LocateResult` (incl. `OldPc` [B15]) | §3.5 |
| `http/MiniHttpServer.kt` | Blocking HTTP/1.1 server, 16 slots, read timeout, write watchdog [B12], routes | §7 |
| `http/HttpTypes.kt` | `HttpRequest`, `HttpResponder`, `Handler`, `RangeSource` | §7 |
| `http/RangeParser.kt` | Single-range parsing, same semantics as `ferry.parse_range` | §7 |
| `cast/CastFileServer.kt` | Phone side: token-guarded file routes `/c/<token>/<i>/<name>` | §6 |
| `cast/CastReceiverServer.kt` | TV side: `/api/v1/cast`, `/api/v1/control`, `/api/v1/state` over a `CastSink` | §5.5 |
| `cast/CastClient.kt` | Phone → TV: cast / control / state | §6 |
| `log/RingLog.kt` | In-app diagnostics log: 2 000 lines in memory + file rotated at 1 MB | §9 |
| `util/Hex.kt`, `util/PercentEncode.kt` | Hex (no `HexFormat`) and UTF-8 percent-encoding (header) | [A3][B10] |

### 1.3 `android/app` (package `app.ferry`)
| File | Responsibility | Design |
|---|---|---|
| `FerryApp.kt` | `Application`: creates `AppGraph`, notification channels (API 26+), leftover-download cleanup | §3.4 |
| `AppGraph.kt` | Manual DI singleton: `Prefs`, `NetworkBinder`, `ConnectionManager`, `RingLog`, process-wide `StateFlow`s (transfer, downloads, connection, update, cast), `pendingPicks` [CP8] | §3.4 |
| `DeviceMode.kt` | TV vs phone decision (+ debug override) | §1 |
| `data/Prefs.kt` | SharedPreferences: last PC, manual address, chosen PC, last TV [A24], slideshow interval, welcome done, interrupted-queue marker, battery-asked | §3.5, §4.2 |
| `data/ResumeStore.kt` | Video positions keyed `name\|size\|mtime` (separate prefs file, capped at 200 entries) | §5.2 |
| `net/NetworkBinder.kt` | Tracks the Wi-Fi/Ethernet `Network`; exposes `socketFactory`, `bindDatagram()`, broadcast addresses, own IPv4; rebuilds the shared `OkHttpClient` on change; "not on Wi-Fi" state | §3.5 [A9] |
| `net/ConnectionManager.kt` | Wraps `PcLocator`; re-locates on start / network change / request failure; publishes `ConnectionState`; triggers the update check | §3.5, §4.6 |
| `media/MediaRepo.kt` | Paged MediaStore queries, albums in memory, thumbnails fetcher for Coil | §4.1 |
| `media/UploadItemFactory.kt` | Builds `UploadItem`s from picker IDs / share URIs / SAF URIs; original probe; `getMediaUri` mapping; size/date rules; cache copy fallback | §4.3 |
| `media/ContentSources.kt` | `ContentUploadSource` (`UploadSource` over a `ContentResolver` URI), `ContentRangeSource` (cast) | §4.3, §6 |
| `media/DownloadSinks.kt` | `MediaStoreDownloadSink` (29+), `LegacyDownloadSink` (25–28, `.part` + `createNewFile` claim), `FileSink` (APK cache), `cleanupLeftovers()` | §4.5 |
| `install/ApkInstaller.kt` | FileProvider + `ACTION_VIEW`; unknown-sources check/screen with text fallback | §4.6, §5.4 |
| `service/TransferService.kt` | FGS `dataSync`: runs `UploadEngine` + `DownloadQueue`, locks, notifications, `onTimeout`, user stop = Cancel | §3.4 |
| `service/CastService.kt` | FGS `mediaPlayback`: `CastFileServer`, notification Pause/Next/Stop | §3.4, §6 |
| `service/Notifications.kt` | Channels "Transfers", "Results", "Casting"; builders; ≤ 1 update/s | §3.4 [A6] |
| `service/Locks.kt` | WakeLock + WifiLock with 4 h timeout, re-acquire, mode by API [A7] | §3.4 |
| `share/ShareReceiverActivity.kt` | Translucent share target, location choice, forwards `ClipData` + grant | §4.1.7 |
| `ui/MainActivity.kt` | Single activity; phone or TV root; edge-to-edge (phone); debug intents | §3.4 |
| `ui/nav/Navigator.kt` | Back stack [CP1] | — |
| `ui/theme/*` | `FerryTheme` (phone), `TvTheme` (dark, 1.5× type, focus ring) | §4.1, §5.1 |
| `ui/common/*` | Formatting (sizes, speed, ETA, ages), `PermissionPlanner`, `tvClickable()` modifier, dialogs | — |
| `ui/phone/*` | Welcome, Home, Picker, Transfer, FromPc, PlayOnTv, Remote, Settings screens + ViewModels | §4.1 |
| `ui/tv/*` | TvHome, TvPlayer, TvViewer/Slideshow, TvPcNotFound, TvInstall + `TvViewModel`, `PlayerHost`, `CastReceiverHost`, `KeepScreenOn` | §5 |
| `res/` | strings.xml (§4.12), themes.xml (platform themes only), `xml/file_paths.xml`, `drawable-xhdpi/banner.png` (320×180), launcher icons (adaptive vector 26+, PNG mipmaps for 25) | §3.6 |
| `src/test/…` | JVM unit tests for pure `:app` helpers (§4.13) | — |
| `src/androidTest/…` | Instrumented tests (§4.14), `Shots` helper (media fixtures are pushed by the host from `android/ci/fixtures/`) | §10 |

### 1.4 `android/ci` (host-side scripts, Python 3 + bash)
| File | Responsibility |
|---|---|
| `check_manifest.py` | Asserts every §3.6 entry in the merged release manifest [A1][A4][CP5] |
| `check_apk.py` | `apksigner verify --print-certs` SHA-256 == `signing/cert-sha256.txt` [B23]; no `lib/` entries [A25]; `aapt2 dump badging` versionCode/versionName == expected |
| `make_apk_json.py` | Writes `Ferry.apk.json` (`version_code`, `version_name`, `size`, `sha256`, `url`) |
| `run_instrumented.sh <phone\|tv\|api25>` | Host side of an emulator suite (CP6): start ferry.py instances, seed outboxes, install APKs, run classes in order, grant/revoke, pull screenshots/logcat, host checks, stop |
| `verify_inbox.py` | Host-side assertions on the PC inboxes after the phone suite (GPS present / byte-identical; negative control) [B1] |
| `make_fixtures.py` | (run locally once, results committed) GPS JPEG, 5 slideshow JPEGs, a broken JPEG, `small.mp4` |
| `make_big_video.sh` | Generates the ≥ 200 MB H.264+AAC test video in CI (cached) [B13] |
| `fixtures/` | Committed fixtures (≈ 3 MB total) + a sample merged manifest for the script tests |
| `test_ci_scripts.py` | pytest for the scripts above (local + CI) |

## 2. PC program: ferry.py 2.0.0

### 2.1 Constants
- Change: `VERSION = "2.0.0"`, `PROTOCOL = 2`.
- New: `CAPABILITIES = ["receive", "outbox", "check_batch", "app_update", "discovery"]`; `DISCOVERY_PORT = 8765`; `DISCOVERY_MAX_DATAGRAM = 512`; `DISCOVERY_MAX_REPLIES_PER_S = 20`; `CHECK_BATCH_MAX_ITEMS = 2000`; `CHECK_BATCH_MAX_BODY = 1 << 20`; `LOG_NAME = "ferry-log.txt"`, `LOG_OLD_NAME = "ferry-log.1.txt"`; `LOG_MAX_BYTES = 5 << 20`; `LOG_RETRY_STEP = 1 << 20`; `LOG_HARD_CAP = 10 << 20`; `LOG_QUEUE_MAX = 10000`; `LOG_POLL_FLUSH_S = 600`; `LOG_DISCOVERY_EVERY_S = 60`; `APP_DIR = os.path.join(HERE, "app")`; `APK_NAME = "Ferry.apk"`, `APK_JSON = "Ferry.apk.json"`, `APK_MIME = "application/vnd.android.package-archive"`; `DEVICE_HEADER = "X-Ferry-Device"`, `DEVICE_NAME_MAX = 60`; `POLL_PATHS = {"/api/v1/outbox", "/api/v1/info"}`.
- `STATE_FILE`, `ERROR_LOG`, `SELFTEST_FILE` become functions of the data dir: `data_path(name)` returns `os.path.join(DATA_DIR, name)`, where the module global `DATA_DIR` defaults to `HERE` and is set once by `main()` from `--data-dir`. (Tests that monkeypatch `STATE_FILE` switch to `DATA_DIR`.)

### 2.2 Removed [H9] [B25]
From `ferry.py`: `PAGE_HTML`, `DIAG_HTML`, `QR_PAGE`, the whole `# --- BEGIN GENERATED` block incl. `ASSETS_B64` (keep `FAVICON_SVG` as a plain constant outside any marker), `build_assets()`, `render_page()`, `qr_svg()` (only `/qr` used it; `qr_matrix`/`qr_console_lines` stay), handler methods `page`, `diag_page`, `qr_page`, `asset` (replaced by `favicon`), `redirect_id`, `redirect_new`, `_redirect`, `diag_inspect`, `diag_blob`, `diag_sink`, `diag_zip`, `diag_apk`; `RX_SHORT`; route entries `/index.html`, `/new`, `/qr`, `/diag`, `/api/v1/diag/*`; `ASSET_PATHS` → only `/favicon.svg`; `AppContext.page_bytes/diag_bytes/assets`. `exif_gps_status` stays (section title becomes "EXIF GPS check (test helper)") [B1]. `zip_*`, `/api/v1/outbox.zip`, `--install-desktop`, `ferry.sh` stay. Expect `ferry.py` to shrink from 3 086 to ≈ 2 300 lines before the additions.

Files deleted: see §1.1. `.gitignore` gains `pc/app/` and `pc/dist/`.

### 2.3 Hidden test flags [B24] [CP4]
In `parse_args`, all with `help=argparse.SUPPRESS`:
| Flag | Effect |
|---|---|
| `--port 0` | Allowed (range check becomes `0 <= port <= 65535`); OS picks a free TCP port; `cfg.port` is updated from `server.server_port` after bind |
| `--discovery-port N\|0\|off` | Default `8765` **independent of `--port`** [B16]; `0` = free UDP port; `off` = no responder |
| `--data-dir DIR` | Sets `DATA_DIR` (created if missing) for `ferry-state.json`, `ferry-error.log`, `ferry-selftest.txt`, `ferry-log*.txt` |
| `--test-free-bytes N` | `Inbox.free_bytes()` returns `N` instead of `disk_usage().free` (info, precheck, check-batch, upload pre-check) [B11] |
| `--app-dir DIR` | Folder searched for `Ferry.apk` + `Ferry.apk.json` (default `APP_DIR`) |
After the bind and the discovery start, `main()` prints exactly one line `FERRY_READY port=<tcp> discovery=<udp|off>` through `Console.print` (the Console writer flushes after every line; verify, add `flush()` if missing). Tests always pass `--no-open --inbox DIR --outbox DIR --data-dir DIR`.

### 2.4 Request log `RequestLog` (C5, design §2.5)
```
class RequestLog:
    __init__(self, folder, console_note, max_bytes=LOG_MAX_BYTES, retry_step=LOG_RETRY_STEP, hard_cap=LOG_HARD_CAP,
             queue_max=LOG_QUEUE_MAX, replace=os.replace, clock=time.time, poll_flush_s=LOG_POLL_FLUSH_S)
    start() / stop(timeout=2.0)            # stop flushes poll counters and the queue
    request(ip, device, method, path, rng, status, nbytes, ms, outcome, error)   # never blocks
    event(text)                             # start/stop, folder recreated, discovery reply, errors
    poll(ip, device)                        # successful GET /api/v1/outbox|info: counted, not written
```
- Line = 11 tab-separated fields: `2026-11-15 14:03:22.123  ip  device  method  path  range  status  bytes  ms  outcome  error`; empty field = `-`; tabs/CR/LF inside fields → space. Events use `EVENT` as method and the text as outcome. Path keeps only the `name` query value (`/api/v1/upload?name=IMG_1.jpg&size&mtime`).
- Writer thread (`ferry-log`, daemon) reads a `queue.Queue(maxsize=queue_max)` with a 1 s timeout; `put_nowait` failures increment `dropped`, written as an event "(N log lines dropped)" before the next line [B9].
- Poll counters `{(ip, device): n}` are flushed every `poll_flush_s` as one line per device: `118 list refreshes` [B28].
- Rotation [B9], checked before each write and at start: size > `max_bytes` → close handle → `replace(log, log.1)` with 3 tries (0.1/0.2/0.4 s) → reopen `"a"`. On failure: reopen the same file, set `next_try = size + retry_step`; at `hard_cap` truncate (`"w"`) and write an event "log restarted (could not rotate)". The console note "Can't rotate ferry-log.txt (is it open in another program?)" is shown once. Any `OSError` on write → console note once, keep going (reopen next time). Logging never turns itself off.
- Opened with `encoding="utf-8", newline="\n", buffering=1`; flushed per line.

**Handler instrumentation.** `_dispatch` sets `self._t0`, `self._status`, `self._nbytes`, `self._outcome`, `self._error`, `self._range = headers.get("Range")`; `send_response` is overridden to record the status; `send_bytes` and the file/zip/upload loops add to `_nbytes`; every place that calls `console.log(...)` also sets `_outcome` (e.g. `"saved IMG_1.jpg"`, `"renamed a.jpg -> a (1).jpg"`, `"skipped"`, `"sent"`, `"playing"`, `"cancelled"`, `"FAILED disk full"`). A `finally` in `_dispatch` calls `reqlog.poll()` for successful polling requests and `reqlog.request()` for everything else.

**Device name** [B10]: new `device_label(headers, ua)`: `X-Ferry-Device` → `urllib.parse.unquote(v, errors="replace")`, strip C0/C1 controls, collapse spaces, cut to 60 chars; empty/missing → `device_type(ua)` ("browser", "phone", "Fire TV"). `self.device` uses it, so console lines read `from vivo X200 Pro  saved …`. `TransferTracker.seen_client(ip, label)` prints "vivo X200 Pro connected (192.168.1.20)".

### 2.5 Discovery responder `DiscoveryResponder` (C2, design §2.4)
```
class DiscoveryResponder:
    __init__(self, port, reply_info: Callable[[], dict], console, reqlog, sock_factory=None, clock=time.monotonic)
    start() -> bool          # bind 0.0.0.0:port; False on OSError (console: "Automatic discovery is off. On the phone, enter <ip>:<port> once")
    bound_port -> int
    stop()                   # sets flag; loop exits within 0.5 s; closes socket
    handle(data, addr) -> Optional[bytes]   # pure: validate + build reply (unit-testable)
```
- Socket: `AF_INET/SOCK_DGRAM`, `settimeout(0.5)`, on Windows best-effort `sock.ioctl(socket.SIO_UDP_CONNRESET, False)` inside try/except (`getattr` guard). No `SO_REUSEADDR`.
- Loop: `recvfrom(2048)` and `sendto` each wrapped in `try/except socket.timeout: continue / except OSError: continue` [B2]. Accept only `len ≤ 512`, UTF-8 JSON object with `app=="ferry"`, `q=="discover"`, `nonce` = 8–32 hex chars. Reply `{"app","protocol":2,"nonce","name":pc_name,"device_type":"pc","port":<real TCP port>,"version":VERSION}` to `addr`.
- Rate limit: sliding 1 s window, ≤ 20 replies total; extra requests dropped silently.
- Logging: `reqlog.event("discovery reply to <ip> (<name>, <device_type>)")` at most once per minute per IP.
- `main()` starts it after the TCP bind (thread `ferry-discovery`), stops it in `finally`.

### 2.6 App bundle, `/app`, `/api/v1/app`, Get-the-app page (C3, C4)
```
class AppBundle:
    __init__(self, folder, hasher=None)
    status() -> Tuple[str, Optional[dict], Optional[Tuple[int, int]]]
        # ("ok", meta, (apk_size, apk_mtime_ns)) | ("missing", None, None) | ("invalid", None, None) | ("copying", None, None)
```
- Every call stats both files; cache key = `(apk size, apk mtime_ns, json size, json mtime_ns)`; on a new key (under a lock): read json (≤ 64 KB), validate `version_code` int > 0, `version_name` str, `size` int, `sha256` 64 lowercase hex; APK size must equal `size`, then stream-hash the APK (1 MB chunks) and compare. Mismatch → `"copying"` (cached under that key; a still-growing copy changes the key) [B26].
- Routes (`ROUTES_EXACT`): `/app` and `/app/Ferry.apk` → `send_app()`; `/api/v1/app` → `api_app()`; `/api/v1/check-batch` → `api_check_batch()` (POST); `/` → `get_app_page()`; `/favicon.svg` → `favicon()`.
- `send_app()`: status `ok` → the shared file sender (below) with name `Ferry.apk`, `APK_MIME`, the expected size/mtime from the key; Range/If-Range/HEAD as the outbox. Otherwise HTML 404 page: "No Ferry app in this Ferry folder" / "The app is being copied, try again in a moment". `api_app()`: `ok` → 200 `{version_code, version_name, size, sha256, url:"/app/Ferry.apk"}`; missing/invalid → 404 `{"result":"error","message":"no app bundled"}`; copying → 404 `{"result":"error","message":"The app is being copied, try again in a moment"}`.
- **Refactor** `send_outbox_file` into `send_file(path, name, ctype, expect_size, expect_mtime_ns, log_name, gone_message)` used by both outbox and app routes (behaviour of the outbox route unchanged; v1 tests prove it).
- `api_info()` adds `"protocol": 2`, `"capabilities": CAPABILITIES`, `"app_version_code": meta["version_code"] | None`; `free_bytes` from `Inbox.free_bytes()`.
- **Get-the-app page** `GET_APP_HTML` (≈ 60 lines, inline CSS, no JS, phone and TV readable, Ferry blue): title "Get the Ferry app"; big link button "Download Ferry for Android" → `/app` (with `Ferry 2.0.N · 24.1 MB`); "On your phone: 1. Tap Download. 2. Open the downloaded file. If Android asks, allow your browser to install apps. 3. Open Ferry." "On Fire TV: in the Downloader app, enter `<host>/app`". `<host>` = the request's `Host` header if it looks like `ip[:port]`, else `primary:port`; HTML-escaped. No APK → the button is replaced by "This Ferry folder has no app. Get the latest Ferry zip." Served with `Cache-Control: no-store`; HEAD supported.
- `info` and the page read the bundle status per request (cheap: two `stat` calls).

### 2.7 `POST /api/v1/check-batch` (design §2.3)
- Handler: Content-Length required (411), ≤ 1 MB (413 + drain), JSON object with `files` list ≤ 2000 (400 otherwise), each `name` non-empty str, `size` int ≥ 0, optional `mtime` int; then `Inbox.check_batch(files)`.
- `Inbox.check_batch(files) -> dict`: **one** `os.scandir(self.path)` and **one** `free_bytes()` call per request [B4]. Index: for every regular file, key = name (lower-cased when `is_win`) and, if its stem ends in ` (n)`, also the key of the base name (stem without ` (n)` + ext). This reproduces `family()` exactly. Per item: `sanitize_name(raw, path, is_win)` → candidates = index[key] → duplicate = first candidate with equal size and `|mtime − st_mtime| ≤ 2 s`; no `mtime` → `null`. Reply `{"ok": true, "results": [{"duplicate": name|null}…], "needed_bytes": sum(non-duplicate sizes), "free_bytes": max(0, free − DISK_MARGIN)}`; never refuses for space [B3].
- Console: one line [CP14]; log: normal request line.

### 2.8 Upload pre-check [CP3]
In `api_upload`, after the 411/400 checks: `free = inbox.free_bytes()`; if `free is not None and free < length + DISK_MARGIN` → console `FAILED <name> PC disk is full`, `drain(length)`, 507 `{"result":"error","reason":"disk_full","message":"PC disk is full"}`. `Inbox.free_bytes()` = test override or `shutil.disk_usage(self.path).free` (None on OSError); `precheck()` uses it too.

### 2.9 Console (design §2.7)
- `build_banner(cfg, url, qr_lines, outbox_count, app_line)`: header `FERRY  is running … v2.0.0`; "First time? Scan to install the Ferry app:" + QR of `http://<ip>:<port>/`; `App page:  http://<ip>:<port>/app` ; `Fire TV:   in Downloader enter <ip>:<port>/app`; Inbox/Outbox line as v1; `App: Ferry 2.0.57 is ready for your phone and TV` or `! No app in this folder (app\Ferry.apk) - get the full Ferry zip`; "Keep this window open. Ctrl+C to stop.   Help: README.txt". Same width/line budget as v1 (`test_banner_budget`).
- `build_extras`: IP-change notice becomes "! Your PC's address changed (was X). The Ferry app finds it by itself; if not, enter <ip> in Ferry > Settings." The "Bookmark-friendly name" line is removed. Discovery-off line comes from §2.5.
- `seen_client` / activity lines use the device label (§2.4).

### 2.10 `main()` order
parse args → set `DATA_DIR` → console/hooks → `State.load(data_path(...))` → folders → addresses → `make_app` (adds `app.bundle = AppBundle(args.app_dir)`, `app.reqlog = RequestLog(DATA_DIR, console.note)`, `app.free_override`) → bind (port 0 OK) → `cfg.port = server.server_port` → reqlog.start + event "Ferry 2.0.0 started …" → discovery (unless off) → `FERRY_READY …` → banner/extras → threads as v1 → serve → `finally`: discovery.stop, outbox.stop, server_close, cleanup temps, reqlog event "stopped" + stop, console stop.

### 2.11 `package.py` [B25] [CP7]
- `python tools/package.py [--app-dir app] [--out dist]`. Files: `Ferry.bat`, `Ferry self-test.bat`, `ferry.sh`, `ferry.py`, `qrcodegen.py`, `ferry-icon.svg`, `README.txt`, `DEVICE-CHECKLIST.txt`, `LICENSE.txt`, `LICENSE-qrcodegen.txt`, **`app/Ferry.apk`, `app/Ferry.apk.json`** (binary, no CRLF conversion).
- Validates the app pair with `ferry.AppBundle(app_dir).status()` (imports `ferry` from `ROOT`); not `ok` → print why and exit 1. Zip name `Ferry-<version_name>.zip`, root folder `Ferry/`, deterministic (fixed timestamp, sorted, CRLF for `.txt/.bat` as v1). Asserts `ferry.VERSION` starts with `2.`. The "Page not built yet" assertion and the src zip go.

### 2.12 README.txt rewrite outline (C6)
Plain text, ≤ 88 columns, CRLF in the zip. Sections: (1) What Ferry is (phone ↔ PC, phone → TV, PC → TV; home Wi-Fi only; no security by design). (2) Quick start on the PC: unblock zip, Extract All, Python 3.8+, double-click `Ferry.bat`, Windows Firewall "Private networks". (3) Install the app on the phone: scan the QR → Download → allow Chrome to install apps → open Ferry → welcome screen and the battery step. (4) Install on Fire TV [H7]: install "Downloader" from the Amazon Appstore, allow it to install unknown apps (Fire OS 7/8: Settings → My Fire TV → Developer options → Install unknown apps; no Developer options → About → press OK 7 times on the device name; Fire OS 6: Apps from Unknown Sources), enter `<ip>:8765/app`, install, open Ferry; later updates need no Downloader. (5) vivo battery settings (the same one-string steps as the app [A8]). (6) Everyday use: Send photos & videos, Share → Ferry, Send other files, From PC, Play on TV, TV home rows/remote keys. (7) Updating: unzip the new Ferry zip over the old folder → phone and TV show "Update available". (8) Troubleshooting: PC not found (same Wi-Fi, not guest; Firewall incl. UDP 8765 if only TCP was allowed; enter the address in Settings), "Your PC has an older Ferry", install blocked, video/sound format not supported, transfers stop with the screen off (battery steps), where files go, `ferry-log.txt` and Settings → Share diagnostics log. (9) Keep the same address (router reservation, from v1). (10) Advanced: `--port`, `--inbox`, `--outbox`, `--no-open`. (11) Licences. Hidden test flags are **not** mentioned.

### 2.13 Existing v1 tests: changes
**Windows portability** (from `v2-ci-baseline.md`):
| Test | Change |
|---|---|
| `test_http.py::test_second_bind_refused_and_probe` | `exclusive=ferry.IS_WIN` |
| `test_inbox.py::test_permissions_follow_umask` | `@pytest.mark.skipif(ferry.IS_WIN, reason="POSIX modes")` |
| `test_outbox.py::test_undecodable_name_is_not_offered` | skip on Windows |
| `test_platform.py::test_resolve_folders_linux` | skip on Windows |
| `test_platform.py::test_launchers_static` | passes once `.gitattributes` exists; also assert `.gitattributes` has the two eol rules |
| `test_platform.py::test_main_starts_and_stops_quickly` | monkeypatch `ferry.win_known_folder` to return `tmp_path/<Downloads|Desktop>` and `DATA_DIR` to `tmp_path`; assert on the folders the patched lookup returns (works on both OSes) |

**Browser retirement** [B25]:
| Test | Change |
|---|---|
| `test_page_and_info` | `/` is the Get-the-app page (`"Get the Ferry app"` in body, no `{{`); `protocol == 2`; `capabilities` list; `app_version_code is None` without a bundle |
| `test_head_everywhere` | paths `/`, `/api/v1/info`, `/favicon.svg` (drop `/diag`) |
| `test_apk_mime_and_short_links` → `test_apk_mime` | drop the `/1`, `/new`, `/77` parts (moved to the removed-routes test) |
| `test_assets_and_manifest` → `test_favicon_only` | favicon 200 `image/svg+xml`; icons + manifest now 404 |
| `test_qr_page`, `test_diag_endpoints` | **deleted** (replaced by `test_removed_routes_404`) |
| `test_message_catalogue_present` | drop the `page.html` half; PC list gains "Automatic discovery is off", "First time? Scan to install the Ferry app", "The app is being copied, try again in a moment", "no app bundled", "Can't rotate ferry-log.txt" |
| `test_console.py::test_banner_budget`, `test_qr_encodes_exact_lowercase_url`, `test_ip_changed_notice` | new banner text; QR encodes `http://<ip>:<port>/` |
| `Srv` fixture | moves to `tests/helpers.py`; gains `app_dir`, `free_override`, a `RequestLog` in a tmp folder |

### 2.14 New PC tests (file · test · asserts)
**`test_check_batch.py`**
- `test_results_in_input_order` — 5 items, mixed duplicates, `results[i]` matches item i.
- `test_duplicate_needs_size_and_mtime_within_2s` — ±2 s duplicate, ±3 s not, size differs not, missing `mtime` → null.
- `test_needed_bytes_excludes_duplicates` [B3] — needed = sum of non-duplicates; all-duplicate resend → 0.
- `test_never_refuses_for_space` [B3] — `free_override=1` → 200 `ok:true`, `free_bytes == 0`.
- `test_same_answers_as_precheck` — 200 generated names (case variants, `name (3).jpg`, `name (1) (2).jpg`, unicode, reserved names) × `is_win` True/False: `check_batch` duplicate == `precheck` duplicate.
- `test_one_scan_one_disk_usage` [B4] — counters on `os.scandir` / `free_bytes`: exactly 1 each for a 500-item request.
- `test_10k_inbox_time_budget` [B4] — 10 000 files in the inbox, 2 000-item request answered in < 3 s (generous for Windows runners; measured locally first; the counter test is the real guard).
- `test_limits_and_errors` — 2 001 items → 400; body > 1 MB → 413; bad JSON / bad item → 400; GET → 405; no Content-Length → 411.
- `test_console_one_line` [CP14].

**`test_app_bundle.py`** (B26, C3)
- `test_no_bundle` — `/api/v1/app` 404 JSON "no app bundled"; `/app` 404 HTML; `info.app_version_code` null; `/` says the folder has no app.
- `test_valid_bundle` — json fields echo; `/app` and `/app/Ferry.apk` bytes equal, `Content-Type` APK MIME, `Content-Disposition` `Ferry.apk`; `Range: bytes=0-9` → 206; HEAD length; `info.app_version_code == N`.
- `test_invalid_json` — garbage, missing field, `version_code: 0`, bad sha → 404 "no app bundled".
- `test_sha_or_size_mismatch_is_copying` — APK changed but json not → both routes 404 "being copied"; nothing served.
- `test_replaced_while_running` — write v1 pair → ok; overwrite APK only → copying; write matching json → new version without restart.
- `test_hash_only_when_stat_changes` — counting hasher: 10 requests → 1 hash; touch json → 2.

**`test_get_app_page.py`** — `test_page_content` (title, `/app` link, version and size, Downloader line with the Host header), `test_host_header_escaped` (`Host: <b>x` → escaped/fallback), `test_head`.

**`test_discovery.py`** (C2)
- `test_reply_on_loopback` — responder on port 0; datagram from a client socket → reply JSON: nonce echoed, `device_type "pc"`, `port` = TCP port, `protocol 2`, `version`.
- `test_garbage_ignored` — not JSON, wrong `app`/`q`, missing/odd nonce, 600-byte datagram → no reply within 0.3 s; responder still answers a valid one afterwards.
- `test_rate_limit` — 40 requests in a burst → ≤ 20 replies.
- `test_loop_survives_connection_reset` [B2] — fake socket: `recvfrom` raises `ConnectionResetError`, then `OSError`, then returns a valid request → reply sent; thread alive.
- `test_bind_failure_tolerated` [B16] — port already bound by another socket → `start()` False, console line "Automatic discovery is off. On the phone, enter …", HTTP server still answers.
- `test_udp_port_independent_of_tcp_port` [B16] — `parse_args(["--port","8800"]).discovery_port == 8765`; reply's `port` field is the TCP port.
- `test_stop_within_a_second`; `test_reply_logged_once_per_minute` (fake clock).

**`test_request_log.py`** (C5)
- `test_line_format` — 11 tab fields; query values dropped except `name`; tab/newline in a name replaced.
- `test_transfers_logged` (via `Srv`) — upload → `saved` line with bytes and ms; outbox Range GET → `bytes=0-9`, 206; zip download; cancelled download.
- `test_errors_always_logged` — 404, 405, 411, 507, 500 each produce a line.
- `test_polling_collapsed` [B28] — 50× outbox + 20× info from one device → 0 request lines; `flush` → one "70 list refreshes" line for that device; a 404 on `/api/v1/info?x` still logged.
- `test_writer_never_blocks` [B9] — writer stalled on a blocking fake file; 20 000 `request()` calls return in < 1 s; later line reports the dropped count.
- `test_rotation` — > 5 MB → `.1` exists, current small; `.1` already present → replaced.
- `test_rotation_replace_fails_keeps_appending` [B9] — injected `replace` raising `PermissionError`: file grows past 5 MB, retry at +1 MB, truncated at 10 MB, console note once, no line lost before truncation.
- `test_rotation_with_other_handle_open` [B9] — a real second handle open on the log: on Windows rotation fails and logging continues; on Linux rotation succeeds; on both no exception and every line is in `log` or `log.1`.
- `test_rotation_checked_at_start`.
- `test_device_name_percent_decoded` [B10] — header `Saurabh%E2%80%99s%20phone%20%F0%9F%93%B1` → log and console show `Saurabh’s phone 📱`.
- `test_device_header_sanitised` — controls stripped, 200 chars → 60, bad escapes → replacement char; missing header → UA class.
- `test_start_stop_events`.

**`test_hooks.py`** (subprocess `ferry.py`; works on Windows) [B24]
- `test_port0_ready_line` — `--port 0 --discovery-port off` → stdout `FERRY_READY port=N discovery=off` within 10 s; `GET /api/v1/info` on N works.
- `test_discovery_port_zero_reported` — `discovery=M`, a datagram to M gets a reply.
- `test_data_dir_holds_runtime_files` — state and log in `--data-dir`, nothing new next to `ferry.py`.
- `test_test_free_bytes` — `info.free_bytes == N`; check-batch `free_bytes == max(0, N − 64 MB)`; 1 MB upload with N = 10 MB → 507.
- `test_app_dir` — bundle read from `--app-dir`.
- `test_hidden_flags_not_in_help`.

**`test_upload_v2.py`** — `test_info_protocol_2`; `test_upload_precheck_507_small_body` [CP3] (body read fully, 507 JSON); `test_upload_precheck_large_body_closes` (100 MB declared, free override 10 MB: server answers 507 and closes after ≤ 64 MB; client may see either the 507 or a reset; inbox has no temp file) [B11].

**`test_removed_routes.py`** [B25] — `test_removed_routes_404`: `/diag`, `/qr`, `/new`, `/1`, `/index.html`, `/manifest.webmanifest`, `/icon-192.png`, `/icon-512.png`, `GET /api/v1/diag/blob`, `GET /api/v1/diag/test.zip` → 404 (JSON for `/api/…`, HTML otherwise); `POST /api/v1/diag/sink` → 405 JSON; `test_v1_page_code_gone` (`not hasattr(ferry, "PAGE_HTML")`, no `ASSETS_B64`, no `BEGIN GENERATED` marker).

**`test_package.py`** [B25] — builds into tmp with a fake app pair: zip contains `Ferry/app/Ferry.apk` (bytes identical) and the json, CRLF on `.txt/.bat`, LF on `.py/.sh`, deterministic (two builds, same sha); missing APK → exit 1; sha mismatch → exit 1; name `Ferry-2.0.99.zip`.

## 3. Android `:core` (pure Kotlin/JVM)

### 3.1 Build (`android/core/build.gradle.kts`)
Plugins `org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.plugin.serialization`. `java { sourceCompatibility = targetCompatibility = 1.8 }`, `tasks.withType<JavaCompile> { options.release.set(8) }`, Kotlin `compilerOptions { jvmTarget = JVM_1_8; freeCompilerArgs += "-Xjdk-release=1.8" }` [CP9]. Dependencies: `api(okhttp)`, `api(kotlinx-coroutines-core)`, `implementation(kotlinx-serialization-json)`; tests: junit 4.13.2, coroutines-test. `tasks.test`: `systemProperty("ferry.pcDir", rootDir.parentFile.resolve("pc").absolutePath)`, `systemProperty("ferry.python", env FERRY_PYTHON ?: (if Windows "python" else "python3"))`, `systemProperty("ferry.requirePython", env CI ?: "false")`, `maxParallelForks = 1`, `testLogging { events("failed"); exceptionFormat = FULL }`, timeout 20 min. `settings.gradle.kts`: `include(":core", ":app")`; root `build.gradle.kts` declares the Kotlin JVM and serialization plugins `apply false`.

### 3.2 Key APIs (signatures; bodies are the implementer's)
```kotlin
// ---------- basics
data class HostPort(val host: String, val port: Int = 8765) {
    companion object { fun parse(text: String): HostPort? }   // "192.168.1.5", "192.168.1.5:8800", trims, rejects junk
    override fun toString(): String                            // "192.168.1.5" when port == 8765, else "host:port"
}
interface Clock { fun nowMs(): Long }                          // SystemClock = System.currentTimeMillis()

// ---------- HTTP
object FerryHttp {
    fun newClient(socketFactory: javax.net.SocketFactory?, deviceName: String): OkHttpClient
    // connectTimeout 5 s, writeTimeout 60 s, readTimeout 10 min, retryOnConnectionFailure(false),
    // interceptor: X-Ferry-Device = PercentEncode.utf8(deviceName) [B10]; User-Agent "Ferry-Android/<ver>"
}
interface FerryApi {
    val base: HostPort
    suspend fun info(timeoutMs: Long = 5_000): PcInfo                  // throws FerryException.OldPc when protocol < 2 [B15]
    suspend fun checkBatch(files: List<CheckFile>): CheckBatchReply    // 60 s call timeout [B4]
    suspend fun upload(item: UploadItem, opened: OpenedSource, onProgress: (Long) -> Unit): UploadReply
    suspend fun outbox(): OutboxList
    suspend fun appInfo(): AppInfo?                                     // null on 404
    suspend fun download(urlPath: String, expectedSize: Long, sink: DownloadSink, onProgress: (Long) -> Unit): String
    fun absoluteUrl(urlPath: String): String                            // for ExoPlayer / Coil
}
class FerryClient(private val http: OkHttpClient, override val base: HostPort) : FerryApi
```
Calls use `enqueue` + `suspendCancellableCoroutine` (`invokeOnCancellation { call.cancel() }`), so coroutine cancellation aborts the socket and the PC deletes its temp file. Upload: `POST /api/v1/upload?name=<pct>&size=N[&mtime=ms]` (mtime omitted when null [B17]), `Content-Length`, body = `StreamingBody`.

```kotlin
class StreamingBody(private val opened: OpenedSource, private val size: Long,
                    private val onProgress: (Long) -> Unit) : RequestBody() {
    override fun contentLength() = size
    override fun isOneShot() = true
    @Volatile var bodyFullySent = false; private set     // true after the last byte was written and flushed [B18]
    // writes 256 KB chunks; throws FerryException.SourceChanged if the stream ends early or has bytes past `size` [B5]
}
```

```kotlin
// ---------- upload engine (design §4.4)
interface UploadSource {
    val displayName: String
    fun open(): OpenedSource                               // throws FerryException.SourceUnreadable
    fun refresh(): SourceMeta                              // re-read size + date once after "file changed" [B5]
}
data class SourceMeta(val size: Long, val mtimeMs: Long?)
interface OpenedSource : java.io.Closeable { val length: Long /* -1 = unknown */; val stream: java.io.InputStream }
data class UploadItem(val id: Long, val source: UploadSource, val name: String, val size: Long,
                      val mtimeMs: Long?, val original: Boolean = true, val skipPrecheck: Boolean = false)

sealed interface ItemStatus {
    object Waiting : ItemStatus; object Checking : ItemStatus
    data class Sending(val sent: Long, val total: Long) : ItemStatus
    data class Saved(val savedAs: String, val renamed: Boolean) : ItemStatus     // renamed = "Saved as x (1).jpg"
    data class AlreadyOnPc(val existing: String) : ItemStatus
    data class Failed(val reason: FailReason, val detail: String? = null) : ItemStatus
    object Cancelled : ItemStatus
    data class NotSent(val reason: StopReason) : ItemStatus
}
enum class FailReason { ConnectionKeptDropping, FileChanged, CantRead, PcError, BadRequest }
sealed interface StopReason {
    data class DiskFull(val missingBytes: Long?) : StopReason     // "Your PC needs 2.1 GB more free space"
    object PcNotFound : StopReason                                // 30 min without the PC [B20]
    object TimeLimit : StopReason                                 // dataSync onTimeout [A6]
    object OldPc : StopReason                                     // [B15]
    object Cancelled : StopReason
}
enum class Phase { Idle, Checking, Sending, WaitingForPc, Finished }
data class ItemRow(val id: Long, val name: String, val size: Long, val original: Boolean, val status: ItemStatus)
data class EngineState(val phase: Phase, val items: List<ItemRow>, val sentBytes: Long, val totalBytes: Long,
                       val speedBps: Long, val etaSeconds: Long?, val pcName: String?, val summary: Summary?)
data class Summary(val saved: Int, val renamed: Int, val alreadyOnPc: Int, val savedWithoutLocation: Int,
                   val failed: Int, val notSent: Int, val cancelled: Int, val stop: StopReason?)
data class EngineConfig(val workers: Int = 2, val bigFileBytes: Long = 256L shl 20, val checkChunk: Int = 500,
    val probeEveryMs: Long = 3_000, val rediscoverEveryMs: Long = 30_000, val waitLimitMs: Long = 30 * 60_000L,
    val maxNetFailures: Int = 3, val cancelGraceMs: Long = 10_000, val progressEveryMs: Long = 250,
    val speedWindowMs: Long = 3_000, val diskMargin: Long = 64L shl 20)

class UploadEngine(
    private val scope: CoroutineScope,                     // owned by TransferService (Dispatchers.IO + SupervisorJob)
    private val api: () -> FerryApi?,                      // current PC client (null = none yet)
    private val rediscover: suspend () -> FerryApi?,       // ConnectionManager.relocate()
    private val clock: Clock,
    private val config: EngineConfig = EngineConfig(),
    private val log: (String) -> Unit = {},
) {
    val state: StateFlow<EngineState>
    fun add(items: List<UploadItem>)                       // append (one combined summary while running)
    fun sendAnyway(ids: Collection<Long>)                  // re-queue AlreadyOnPc rows with skipPrecheck [A2][B6]
    fun retry()                                            // re-queue Failed + NotSent rows
    fun cancel()                                           // abort in-flight; grace for body-sent items [B18]
    fun stopForTimeLimit()                                 // remaining → NotSent(TimeLimit) [A6]
    suspend fun awaitFinished(): Summary
}
```
**Engine algorithm** (all suspend, single `Mutex` guarding the queue):
1. `add` appends rows as `Waiting`; if idle, launches `run()`.
2. `run()`: `api()` or `rediscover()`; none → Waiting-for-PC (step 6). `info()` → `OldPc` stops everything with `NotSent(OldPc)`.
3. Pre-check: un-checked rows without `skipPrecheck` → `Checking`, chunks of 500 → duplicates `AlreadyOnPc`; sum `needed_bytes` over chunks; total > `free_bytes` → stop `DiskFull(total − free)`, all remaining `NotSent(DiskFull)`, nothing sent [B3]. `Http`/`Timeout` while `info()` still answers → rows stay unchecked and are uploaded (the PC hash dedupes) [B4]; `Unreachable` → step 6, then redo the check.
4. Two worker coroutines take the next `Waiting` row in selection order. **Big-file gate**: a row > 256 MB may start only when the other worker is idle; while it runs the other worker takes nothing [H15].
5. Per upload: `source.open()` (failure → `Failed(CantRead)`); `opened.length` ≥ 0 and ≠ `size` → `refresh()` once, rebuild the row, retry; second mismatch → `Failed(FileChanged)` [B5]. Reply `saved/renamed/skipped` → `Saved` / `Saved(renamed=true)` / `AlreadyOnPc`, except `skipped` after an earlier attempt of this row reached `bodyFullySent` → `Saved` [B18]. `DiskFull` (507) → stop `DiskFull(null)`. `Http 400/411` → `Failed(BadRequest)`; `Http 500` → `Failed(PcError, server message)`. `SourceChanged` → same as the size mismatch path.
6. `ConnectionLost`/`Unreachable`/`Timeout` during an upload: if bytes were in flight, first `info()`: reachable and `free_bytes < size + 64 MB` → stop `DiskFull` [B11]; reachable → `netFailures++` (3 → `Failed(ConnectionKeptDropping)`) and the row goes back to `Waiting`; unreachable → row back to `Waiting` and the engine enters **WaitingForPc**: workers suspend on a `pcAvailable` `MutableStateFlow<Boolean>`; one prober coroutine calls `info()` every 3 s and `rediscover()` every 30 s; success reopens the gate; after 30 min all remaining rows → `NotSent(PcNotFound)` and `Finished` [B20].
7. `cancel()`: rows not started → `Cancelled`; in-flight rows whose body is fully sent wait ≤ 10 s for the reply (`Saved` if it comes, else `Cancelled`) [B18]; other in-flight requests are cancelled (socket closed).
8. State is published through a conflated internal flow, sampled every 250 ms (`EngineState`); speed = bytes over the last 3 s; ETA = remaining bytes / speed.

```kotlin
// ---------- downloads (design §4.5)
interface DownloadSink { fun open(): java.io.OutputStream; fun commit(): String; fun abort() }   // commit returns the final name
sealed interface DownloadStatus { object Queued; data class Running(val got: Long, val total: Long);
    data class Done(val savedAs: String, val openUri: String); data class Failed(val message: String); object Cancelled }
class DownloadQueue(scope: CoroutineScope, api: () -> FerryApi?, waitForPc: suspend () -> FerryApi?, clock: Clock,
                    waitLimitMs: Long = 30 * 60_000L) {
    val state: StateFlow<Map<Int /*outbox id*/, DownloadStatus>>
    fun enqueue(file: OutFile, sinkFactory: (OutFile) -> DownloadSink)   // sinkFactory throws NotEnoughSpace
    fun cancel(id: Int); fun cancelAll()
}
// Rules: one at a time; Content-Length must equal file.size; bytes written == size → commit() else abort();
// network drop → abort(), wait for the PC (same 3 s probe / 30 min limit), restart from zero in a fresh sink [H20].

// ---------- app update (design §4.6)
object AppUpdate {
    fun isNewer(info: AppInfo?, installedVersionCode: Long): Boolean
    suspend fun downloadApk(api: FerryApi, info: AppInfo, target: java.io.File, onProgress: (Long) -> Unit): java.io.File
    // writes target.part, checks size == info.size and sha256 == info.sha256, renames; deletes on mismatch
}

// ---------- discovery (design §2.4, §3.5)
object DiscoveryCodec {
    const val PORT = 8765; const val MAX_BYTES = 512
    fun newNonce(random: java.util.Random = java.security.SecureRandom()): String        // 8 hex
    fun request(name: String, deviceType: String, nonce: String): ByteArray
    fun parseRequest(data: ByteArray, len: Int): DiscoveryRequest?
    fun reply(r: DiscoveryReply): ByteArray
    fun parseReply(data: ByteArray, len: Int, expectedNonce: String, from: java.net.InetAddress): Peer?
}
data class Peer(val name: String, val deviceType: String, val host: String, val port: Int, val version: String, val protocol: Int)
interface UdpTransport : java.io.Closeable {
    fun send(data: ByteArray, to: java.net.InetAddress, port: Int)
    fun receive(buffer: ByteArray, timeoutMs: Int): Datagram?          // null on timeout
}
class DatagramTransport(localPort: Int = 0, broadcast: Boolean = true,
                        binder: (java.net.DatagramSocket) -> Unit = {}) : UdpTransport   // binder = Network::bindSocket [A9]
class DiscoveryClient(private val transport: () -> UdpTransport, private val targets: () -> List<java.net.InetAddress>,
                      private val self: Pair<String, String> /* name, device_type */, private val port: Int = DiscoveryCodec.PORT) {
    suspend fun discover(sends: Int = 3, gapMs: Long = 300, listenMs: Long = 2_000): List<Peer>   // de-duplicated by host:port
}
class DiscoveryResponder(private val transport: UdpTransport, private val identity: () -> DiscoveryReply,
                         private val maxPerSecond: Int = 20, private val log: (String) -> Unit = {}) : java.io.Closeable {
    fun start()          // thread "ferry-discovery"; every receive/send in try/catch; never dies
}
interface PcStore { var lastKnown: HostPort?; var manual: HostPort?; var chosenName: String? }
sealed interface LocateResult {
    data class Found(val api: FerryApi, val info: PcInfo) : LocateResult
    data class OldPc(val base: HostPort, val protocol: Int) : LocateResult
    data class NotFound(val candidates: List<Peer>) : LocateResult
    object NoNetwork : LocateResult
}
class PcLocator(private val clientFor: (HostPort) -> FerryApi, private val discovery: DiscoveryClient?,
                private val store: PcStore, private val log: (String) -> Unit) {
    suspend fun locate(): LocateResult      // manual/chosen → last-known (1.5 s) → broadcast → verify /info → store
    suspend fun verify(hp: HostPort): LocateResult
    suspend fun findTvs(): List<Peer>       // same broadcast, device_type == "tv"
}

// ---------- MiniHttpServer (design §7)
data class MiniHttpConfig(val maxConnections: Int = 16, val readTimeoutMs: Int = 30_000, val writeStallMs: Long = 30_000,
    val maxRequestLine: Int = 8 * 1024, val maxHeaderBytes: Int = 16 * 1024, val maxJsonBody: Int = 64 * 1024,
    val watchdogTickMs: Long = 1_000)
class HttpRequest(val method: String, val path: String, val query: Map<String, String>,
                  val headers: Map<String, String> /* lower-case keys */, val body: ByteArray?, val remoteIp: String)
interface RangeSource { val length: Long; val etag: String; fun openAt(offset: Long): java.io.InputStream }
interface HttpResponder {
    fun json(code: Int, body: String)
    fun empty(code: Int)
    fun file(req: HttpRequest, src: RangeSource, name: String, mime: String)   // GET/HEAD, 200/206/416, Accept-Ranges, ETag, If-Range
}
fun interface Handler { fun handle(req: HttpRequest, res: HttpResponder) }
class MiniHttpServer(private val port: Int, private val config: MiniHttpConfig = MiniHttpConfig(),
                     private val portFallback: Int = 0, private val log: (String) -> Unit = {}) : java.io.Closeable {
    fun route(method: String, pathPrefix: String, handler: Handler)    // before start()
    fun start(): Int                                                    // returns the bound port (tries port..port+portFallback)
    val activeConnections: Int
}
object RangeParser { sealed interface R { data class Span(val start: Long, val end: Long) : R; object Invalid : R; object Whole : R }
                     fun parse(header: String?, size: Long): R }          // mirrors ferry.parse_range

// ---------- casting (design §5.5, §6)
class CastFileServer(private val items: List<CastFile>, private val port: Int = 8767,
                     private val token: String = Tokens.random128()) : java.io.Closeable {
    fun start(): Int; fun urlFor(index: Int, phoneIp: String): String   // http://ip:port/c/<token>/<i>/<pct name>
}
data class CastFile(val name: String, val mime: String, val source: RangeSource)
interface CastSink {                                   // implemented by the TV UI; called on server threads
    fun onCast(req: CastRequest, fromIp: String): Boolean
    fun onControl(c: ControlRequest): Boolean
    fun state(): CastState
}
class CastReceiverServer(private val sink: CastSink, private val port: Int = 8766) : java.io.Closeable { fun start(): Int }
class CastClient(private val http: OkHttpClient, private val tv: HostPort) {
    suspend fun cast(req: CastRequest); suspend fun control(c: ControlRequest); suspend fun state(): CastState
    fun localAddressTowardsTv(): String                 // IP of the socket connected to the TV (design §6)
}

// ---------- diagnostics (design §9)
class RingLog(private val file: java.io.File?, private val clock: Clock, private val maxLines: Int = 2_000,
              private val maxFileBytes: Long = 1L shl 20) { fun log(tag: String, msg: String); fun snapshot(): List<String> }
```

### 3.3 Threading model [CP10]
- **Coroutines** for `UploadEngine`, `DownloadQueue`, `PcLocator`, `DiscoveryClient`, `CastClient`: the caller supplies the scope; I/O-blocking calls run on `Dispatchers.IO`; OkHttp calls are suspend wrappers around `enqueue`. Shared mutable state lives behind one `Mutex` per class and is exposed only as `StateFlow`. No `GlobalScope`.
- **Threads** for `MiniHttpServer` (accept thread + one thread per connection from a bounded `ThreadPoolExecutor(16)`; the 17th connection gets 503 and is closed), its watchdog (single daemon thread, ticks every 1 s, closes sockets that are mid-write with no progress for 30 s [B12]) and `DiscoveryResponder` (one daemon thread, 0.5 s receive timeout). All threads are daemon and named `ferry-*`.
- `CastSink` callbacks arrive on server threads; the TV implementation forwards them to the UI through a `Channel`/`StateFlow` and answers `state()` from a `@Volatile` snapshot (no blocking on the main thread).
- Tests use `kotlinx-coroutines-test` `runTest` (virtual time: the 30-min wait runs instantly) and a `TestClock` backed by `testScheduler.currentTime`.

### 3.4 Error model
```kotlin
sealed class FerryException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause) {
    class Unreachable(cause: Throwable) : FerryException("PC not reachable", cause)          // connect/refused/no route/no network
    class ConnectionLost(val bodyFullySent: Boolean, val bytesSent: Long, cause: Throwable) : FerryException("connection lost", cause)
    class Timeout(val phase: String /* connect|write|response */, cause: Throwable) : FerryException("timed out ($phase)", cause)
    class Http(val code: Int, val serverMessage: String?) : FerryException("HTTP $code")
    class DiskFull : FerryException("PC disk is full")                                        // 507
    class BadReply(detail: String) : FerryException("unexpected reply: $detail")
    class OldPc(val protocol: Int) : FerryException("older Ferry on the PC")                  // [B15]
    class SourceChanged : FerryException("file changed while sending")
    class SourceUnreadable(cause: Throwable?) : FerryException("can't read the file", cause)
    class NotEnoughSpace(val needed: Long, val free: Long) : FerryException("not enough space on the phone")
}
```
Mapping rule in `FerryClient`: `ConnectException`, `NoRouteToHostException`, `UnknownHostException`, `PortUnreachable`, socket "ENONET/ENETUNREACH" → `Unreachable`; `SocketTimeoutException` → `Timeout` with the phase known from the body state; other `IOException` after the connection opened → `ConnectionLost`. The UI never shows exception text; `:app` maps every case to a string from §4.12.

### 3.5 API-25 rules for `:core` [A3] [CP9]
Allowed: Kotlin stdlib (no `kotlin.io.path`), `java.io`, `java.net`, `java.util`, `java.util.concurrent`, `java.security.MessageDigest/SecureRandom`, `System.currentTimeMillis/nanoTime`, OkHttp 4.12, kotlinx. Forbidden (enforced by `ApiLevelScanTest`, which reads every compiled `.class` of `:core` main and fails on constant-pool references): `java/time/`, `java/nio/file/`, `java/util/Base64`, `java/util/HexFormat`, `java/lang/invoke/VarHandle`, `java/util/concurrent/Flow`, `kotlin/io/path/`, method refs `java/util/List.of|copyOf`, `java/util/Map.of|entry`, `java/util/Set.of`, `java/lang/String.repeat|isBlank|strip|lines`, `java/io/InputStream.transferTo|readAllBytes|readNBytes`, `java/util/Optional.isEmpty|orElseThrow()`. `-Xjdk-release=1.8` already rejects Java 9+ API calls at compile time; the scan also catches Java 8 APIs that only arrived on Android 26. No `android.*` import (scan for `android/`).

### 3.6 Integration harness against real `ferry.py` [B24]
`FerryPcProcess` (test source, `app.ferry.core.it`):
- `start(freeBytes: Long? = null, discovery: Boolean = false, appDir: File? = null): FerryPcProcess` → temp root with `in/`, `out/`, `data/`; runs `<python> -u <pcDir>/ferry.py --no-open --port 0 --discovery-port <0|off> --inbox in --outbox out --data-dir data [--test-free-bytes N] [--app-dir D]` with `PYTHONIOENCODING=utf-8`; reads stdout until `FERRY_READY port=N discovery=M` (15 s timeout; on timeout the test fails with the captured output); a daemon thread drains the rest of stdout/stderr into a buffer (attached to failures).
- `base: HostPort("127.0.0.1", N)`, `discoveryPort`, `inbox`, `outbox`, `log: File`.
- `close()`: `destroy()`, `waitFor(5 s)`, `destroyForcibly()` fallback, then delete the temp root, ignoring Windows "file in use" errors (retry 3× 200 ms).
- Python missing: `Assume` skip locally, **fail** when `ferry.requirePython=true` (CI).
- Ports: TCP always `--port 0`, UDP `--discovery-port 0`; no fixed ports, so ubuntu and windows jobs never collide.

### 3.7 `:core` JVM tests (`android/core/src/test/kotlin/...`)
**Unit (no network)**
| Class | Tests (what each asserts) |
|---|---|
| `JsonModelsTest` | parse real v2 replies captured from ferry.py (info, check-batch, upload, outbox, app, error); unknown keys ignored; `mtime` omitted when null in `CheckFile` [B17] |
| `HostPortTest` | parse forms, default port, IPv4 only, junk → null, `toString` |
| `PercentEncodeTest` | `Saurabh’s phone 📱` → ASCII-only, round-trips with Python's `unquote` vector [B10] |
| `DiscoveryCodecTest` | request/reply round trip; nonce mismatch → null; > 512 B → null; non-JSON → null |
| `RangeParserTest` | the same vectors as `ferry.test_parse_range_unit` |
| `StreamingBodyTest` | 256 KB chunks; progress monotonic; short stream → `SourceChanged`; extra bytes → `SourceChanged`; `bodyFullySent` only after the last byte |
| `UploadEngineTest` (fake `FerryApi` + `runTest`) | `happyPathTwoWorkersInSelectionOrder`; `duplicatesMarkedAlreadyOnPcBeforeSending`; `allDuplicateResendNeedsZeroBytes` [B3]; `neededMoreThanFreeStopsBeforeSending` [B3]; `checkBatchHttpErrorUploadsUnchecked` [B4]; `checkBatchTimeoutWithPcReachableUploadsUnchecked` [B4]; `networkDropEntersWaitingAndResumes`; `waitingForPcEndsAfter30MinWithNotSent` [B20]; `rediscoversEvery30sWhileWaiting`; `threeNetFailuresWhileReachableFails`; `diskFull507StopsQueue`; `midBodyDropWithLowFreeIsDiskFull` [B11]; `bodySentThenSkippedCountsAsSaved` [B18]; `cancelWaitsForBodySentReply` [B18]; `cancelAbortsInFlight`; `fileChangedRefreshesOnceThenFails` [B5]; `sizeMismatchAtOpenRefreshes` [B5]; `sendAnywaySkipsPrecheck` [A2]; `withoutLocationCountedInSummary` [A2]; `oldPcStopsWithNothingSent` [B15]; `bigFileRunsAlone` [H15]; `addWhileRunningCombinesSummary`; `stopForTimeLimitMarksNotSent` [A6]; `progressThrottledTo250ms`; `speedIsThreeSecondAverage` |
| `DownloadQueueTest` | one at a time; length mismatch → `abort` not `commit` [B7]; drop → abort + restart into a new sink [H20]; cancel → abort; `NotEnoughSpace` → Failed |
| `AppUpdateTest` | `isNewer` cases (null, equal, lower, higher); sha mismatch deletes the file; size mismatch deletes |
| `RingLogTest` | keeps last 2 000 lines; rotates file at 1 MB |
| `ApiLevelScanTest` | §3.5 scan of `build/classes/kotlin/main` (path from a system property set in Gradle) |
| `NoAndroidImportsTest` | no `android/` references in compiled classes |

**Servers on loopback**
| Class | Tests |
|---|---|
| `MiniHttpServerTest` | GET/HEAD; ranges `a-b`, `a-`, `-n`, multi-range → 200 whole, out of range → 416 with `Content-Range: bytes */N`; `If-Range` match/mismatch; request line > 8 KB → 414/400; headers > 16 KB → 431/400; JSON body > 64 KB → 413; unknown route → 404; 16 concurrent slow clients served, 17th → 503; `stalledReaderSlotFreedWithin35s` [B12] (client requests 50 MB, reads 1 KB, stops; within 35 s `activeConnections` drops and a new request is served); read timeout closes idle sockets; port fallback |
| `CastProtocolTest` | `CastReceiverServer` + `CastClient` loopback: cast → sink called with items; control actions incl. `seek delta_ms` and `interval`; `state` JSON; bodies > 64 KB refused |
| `CastFileServerTest` | right token → bytes + correct MIME/Range; wrong token → 404; index out of range → 404; URL encoding of names with spaces/unicode |
| `DiscoveryResponderTest` | loopback reply with `device_type "tv"`, port 8766; garbage ignored; rate limit 20/s; transport throwing `IOException` (incl. "connection reset") keeps the loop alive [B2 analogue] |

**Integration against real `ferry.py`** (`app.ferry.core.it`, run on ubuntu **and** windows [B14])
| Test | Asserts |
|---|---|
| `uploadIsByteIdentical` | 5 MB random file → inbox file sha256 equal; reply `saved` |
| `mtimeRestored` | mtime set → inbox `lastModified` within 2 s |
| `mtimeOmittedWhenUnknown` [B17] | no `mtime` param; file saved; second identical upload → `skipped` by hash |
| `renameOnDifferentContent` | same name, different bytes → `renamed`, `x (1).jpg` |
| `checkBatchFindsDuplicates` | after upload, check-batch marks it; `needed_bytes` excludes it [B3] |
| `engineEndToEndSmallBatch` | 30 files (1–300 KB) through `UploadEngine` with 2 workers → all saved; resend → all `AlreadyOnPc` and no upload requests in `ferry-log.txt` |
| `deviceNameWithCurlyQuoteAndEmoji` [B10] | `Saurabh’s phone 📱` appears decoded in `ferry-log.txt` |
| `parallelCaseClashDifferentContent` [B14] | two workers send `IMG_0001.jpg` and `img_0001.JPG` with different bytes at once → both kept (on Windows as `IMG_0001.jpg` + `img_0001 (1).JPG` or equivalent), none overwritten |
| `parallelIdenticalContent` [B14] | two workers send identical `a.jpg` at once → one file, the other reply `skipped` |
| `diskFullLargeUpload` [B11] | `--test-free-bytes 10485760`; 100 MB upload through the engine → stop `DiskFull`, no retry loop, no temp file left |
| `diskFullSmallUpload` | 1 MB upload with free 10 MB → 507 → `DiskFull` |
| `checkBatchSaysNotEnoughSpace` [B3] | free override small, 3 new files → engine stops before sending (`DiskFull(missing)`) |
| `cancelMidUploadLeavesNoTemp` | cancel at ~50 % → no `.ferry-*.part` in the inbox after 1 s |
| `pcGoesAwayAndComesBack` | kill ferry.py mid-batch, restart it on the same port (`--port <old>`) after 5 s → engine resumes, all files saved exactly once (real-time test, ≤ 60 s) |
| `oldPcDetected` [B15] | tiny stub (`MiniHttpServer` answering v1 `info` with `protocol:1`) → `OldPc` |
| `outboxListAndDownload` | file in `out/` (wait for the 2 s stability) → list → `download` into a file sink → bytes equal; truncated (`expectedSize` wrong) → abort |
| `appInfoAndUpdateDownload` | `--app-dir` with a fake APK + json → `appInfo()` fields; `downloadApk` verifies sha; mismatched pair → `appInfo()` null (404) |
| `discoveryAgainstFerryPy` | `DiscoveryClient` targeting `127.0.0.1:<discoveryPort>` → `Peer(device_type "pc", port = TCP port)`; `PcLocator` verifies it via `/info` |
| `locatorPrefersLastKnown` | store has last-known → found without any UDP send |

## 4. Android `:app`

### 4.1 Build (`android/app/build.gradle.kts`)
- `namespace/applicationId "app.ferry"`, minSdk 25, targetSdk 35, compileSdk 35; `buildFeatures { compose = true; buildConfig = true }`; Java 17 + `isCoreLibraryDesugaringEnabled = true` + `coreLibraryDesugaring(desugar_jdk_libs)` [A3]; `packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }`.
- **Signing** [H18]: `signingConfigs.release` reads `rootProject.file("signing/signing.properties")` (`storeFile` relative to `android/`); `release { signingConfig = release; isMinifyEnabled = false }` [A23]. Debug keeps the default debug key (emulator tests only).
- **Version** [H26] [CP13]: `FERRY_RELEASE=1` and `FERRY_RUN_NUMBER=<n>` env (set by `android.yml` on `main`) → `versionCode = 20000 + n`, `versionName = "2.0.$n"`; otherwise `1` / `"2.0.0-dev"`. `BuildConfig.VERSION_CODE` drives the update check.
- `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`; `testOptions.animationsDisabled = true`.
- Dependencies per §0.3; `implementation(project(":core"))`.

### 4.2 Startup, mode and navigation
- `FerryApp.onCreate`: `AppGraph.init(this)`; channels (26+); `DownloadSinks.cleanupLeftovers()` on IO [B7]; RingLog "app start <version> <device> API <n>".
- `DeviceMode.isTv(ctx)`: `UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION || pm.hasSystemFeature(FEATURE_LEANBACK)`. Debug builds only (`BuildConfig.DEBUG`): intent extra `app.ferry.debug.UI = "tv"|"phone"` overrides; extra `app.ferry.debug.CAST_RECEIVER = true` starts `CastReceiverHost` in phone mode [B27].
- `MainActivity`: phone → `enableEdgeToEdge()` + `FerryTheme { PhoneRoot(navigator) }`; TV → `TvTheme { TvRoot(navigator) }`. Intent extras `open = "transfer" | "remote" | "fromPc"` (from notifications) push that screen [A24].
- `NetworkBinder` rule [A9]: prefer a Wi-Fi `Network`, else Ethernet (TV adapters), else the active network if it is **not** cellular (emulators, unusual TVs); only "no network" or "cellular only" gives "Your phone is not on Wi-Fi". The shared `OkHttpClient` (core `FerryHttp.newClient(network.socketFactory, deviceName)`) is rebuilt when the chosen network changes; the old one is left to finish in-flight calls.
- `Navigator` [CP1]: `val stack: SnapshotStateList<Screen>`, `push`, `pop(): Boolean`, `replaceAll`; each screen composable installs `BackHandler(enabled = stack.size > 1) { pop() }`; on Home, Back finishes the activity (TV: also stops the cast receiver via `onStop`). `android:enableOnBackInvokedCallback` is left unset (targetSdk 35 default) [A25].

### 4.3 Phone screens and ViewModels (design §4.1)
| Screen (composable) | ViewModel / state source | Contents and behaviour |
|---|---|---|
| `WelcomeScreen` | `SettingsViewModel` | Shown until `Prefs.welcomeDone`. What Ferry does (3 lines), the **battery card** (if not ignoring optimisations): "vivo may stop Ferry in the background…" → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with `package:` URI (try/catch → open battery settings) [P10] [H23]; "Continue" |
| `HomeScreen` | `HomeViewModel` (`connection`, `transfer`, `downloads`, `update`, `outboxCount`, interrupted marker) | Header "→ Saurabhs-PC" + status dot (Connected / Looking for your PC… / PC not found + Try again + Enter address / not on Wi-Fi / older Ferry); buttons **Send photos & videos**, **Send other files (documents, APKs…)**, **From PC** (badge), **Play on TV**; banners: update ("Ferry 2.0.7 is available → Update"; disabled with "Update after this finishes" while a transfer/download/cast runs [B21]), partial-media access, interrupted transfer + vivo steps [A8], ongoing transfer card |
| `PickerScreen` (`mode = Send \| CastVideo \| CastPhotos`) | `PickerViewModel` (rows, albums, `selection: LinkedHashSet<Long>` [A17]) | Tabs Recent / Albums; `LazyVerticalGrid` with sticky date headers (Recent); album list → album grid with **Select all**; tap toggles; video badge with duration; bottom bar "12 selected · 1.4 GB" + Send; CastVideo = single select, video only; CastPhotos = images only |
| `TransferScreen` | `TransferViewModel` (`AppGraph.transfer`) | Overall bar, "Sending 5 of 20 · 18.2 MB/s · about 1 min left", rows with per-status text (§4.12), Cancel (confirm), "Waiting for Saurabhs-PC… (continues automatically)" + Cancel; summary with Retry / **Send anyway** on "Already on PC" [A2] |
| `FromPcScreen` | `OutboxViewModel` | Outbox list (icon by kind, name, size, age), refresh every 5 s while visible + pull-to-refresh (`material3` `PullToRefreshBox`), tap → download, **Download all**, per-row progress, then **Open** / **Install** |
| `PlayOnTvScreen` | `CastViewModel` | Finds TVs (`PcLocator.findTvs()`); remembered last TV used directly if it answers [A24]; none → "On the TV, open Ferry and leave it on its home screen" [A10]; then **Videos** / **Photo slideshow** → picker |
| `RemoteScreen` | `CastViewModel` (polls `state` 1 s while visible) | Title, position/duration, ⏮ ⏯ ⏭ ⏹, −10 s / +10 s (video), interval 3/5/10 s (photos) |
| `SettingsScreen` | `SettingsViewModel` | PC (found list, manual address field with `HostPort.parse` validation, "Find again"), "Android battery setting: OK / Fix" + "Still stopping?" steps [A8], slideshow default, version, **Share diagnostics log** (`ACTION_SEND` of the RingLog file via FileProvider `logs/`) |
| `LocationChoiceDialog` | caller | "Ferry isn't allowed to read photo locations. **Allow** / **Send without location**" [H19] |

ViewModels are created with a tiny `viewModelFactory { initializer { … AppGraph … } }`; no DI library.

### 4.4 Services
**`TransferService`** (FGS `dataSync`):
- Intents: `ACTION_SEND_PICKS` (takes `AppGraph.pendingPicks` [CP8]), `ACTION_SEND_URIS` (URIs in `ClipData` + `FLAG_GRANT_READ_URI_PERMISSION`; extra `source = share|documents`, `withoutLocation: Boolean`), `ACTION_SEND_ANYWAY` (ids), `ACTION_RETRY`, `ACTION_CANCEL`, `ACTION_DOWNLOAD` (outbox `OutFile` as JSON string), `ACTION_CANCEL_DOWNLOADS`.
- `onStartCommand`: immediately `ServiceCompat.startForeground(this, ID, progressNotification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`; then work on `scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`; items are built by `UploadItemFactory` inside the service (URIs readable thanks to the intent's grant [A5][B19]).
- Engine/queue state → `AppGraph.transfer` / `AppGraph.downloads`; notification refresh ≤ 1/s [A6]; `Prefs.interruptedCount` updated every 5 s while rows remain, cleared on Finished.
- Idle (engine finished and queue empty) → `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf()`, then post the **Results** notification ("18 saved · 2 already on PC", or "2 failed. Tap to retry" with a Retry action) [A6] [A22].
- `onTimeout(startId, fgsType)` (API 35): `engine.stopForTimeLimit()`, downloads cancelled, `stopSelf()` at once [A6]. `onDestroy` without finish (user stopped it from "Active apps") → `engine.cancel()` = Cancelled, not a crash [A6].
- Locks: `TransferLocks` acquired while rows/downloads are active (§4.5), released in `finally`.
- Starts only from a visible activity or a notification action [A6]: `ContextCompat.startForegroundService` [A4]; notification actions use `PendingIntent.getForegroundService` on 26+, `getService` on 25, `FLAG_IMMUTABLE`.

**`CastService`** (FGS `mediaPlayback`): builds `CastFileServer` (port 8767, fallback +9) over `ContentRangeSource`s for the selected items, sends `CastRequest` through `CastClient`, publishes `AppGraph.cast`; notification with Pause/Next/Stop actions (→ `control`), tap → Remote [A24]; Stop → `control(stop)`, close server, `stopSelf()`. Same lock helper.

**`CastReceiverHost`** (TV, not a service): `LifecycleEventObserver` on `MainActivity`: `ON_START` → `MulticastLock.acquire()`, `CastReceiverServer(8766).start()`, `DiscoveryResponder(DatagramTransport(8765, binder = network::bindSocket)).start()`; `ON_STOP` → close both, release lock [A10] [H14]. If UDP 8765 can't be bound, log it and continue (the phone falls back to last TV / not found).

### 4.5 Locks, notifications (design §3.4)
`TransferLocks(ctx, tag)`: `PowerManager.PARTIAL_WAKE_LOCK` `acquire(4 h)`; `WifiManager.createWifiLock(if (SDK >= 29) WIFI_MODE_FULL_LOW_LATENCY else WIFI_MODE_FULL_HIGH_PERF, tag)` with `setReferenceCounted(false)`; a coroutine re-acquires every 3.5 h while held; `release()` idempotent [A7]. Channels: `transfers` (IMPORTANCE_LOW, progress), `results` (IMPORTANCE_DEFAULT), `casting` (IMPORTANCE_LOW). POST_NOTIFICATIONS is requested when the user taps the first Send / Download / Play (33+) [H23]; refusal changes nothing else.

### 4.6 Permission flow (design §4.2)
`PermissionPlanner` (pure Kotlin, unit-tested) → `fun mediaPermissions(sdk: Int): List<String>`: 34+ `READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, ACCESS_MEDIA_LOCATION`; 33 the same without `…_USER_SELECTED`; 29–32 `READ_EXTERNAL_STORAGE, ACCESS_MEDIA_LOCATION`; ≤ 28 `READ_EXTERNAL_STORAGE`. `fun access(granted: Set<String>, sdk: Int): MediaAccess { Full, Partial, None }`; `fun needsLocationChoice(...)`. Flow: first "Send photos & videos" → rationale sheet "Choose **Allow all**. If vivo asks again, choose Allow." → `RequestMultiplePermissions` → Full: picker; Partial: picker + banner "Ferry can only see some photos → Allow all" [A15]; None twice (`shouldShowRequestPermissionRationale` false after a denial) → button opens `ACTION_APPLICATION_DETAILS_SETTINGS`. Legacy downloads on 25–28 request `WRITE_EXTERNAL_STORAGE` at the first download.

### 4.7 MediaStore and file code paths
**Queries** [CP11] (`MediaRepo`): images `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` and videos `Video.Media.EXTERNAL_CONTENT_URI`, projection `_ID, DISPLAY_NAME, SIZE, DATE_MODIFIED, BUCKET_ID, BUCKET_DISPLAY_NAME, MIME_TYPE` (+ `DURATION` for video), sort `DATE_MODIFIED DESC`. Paging 500 rows: API ≥ 26 `ContentResolver.query(uri, projection, Bundle(QUERY_ARG_SORT_COLUMNS, QUERY_ARG_SORT_DIRECTION, QUERY_ARG_LIMIT, QUERY_ARG_OFFSET), null)`; API 25 `sortOrder = "date_modified DESC LIMIT 500 OFFSET n"`. First page shown at once; the rest loads in the background; the two streams are merged by date; albums aggregated in memory from all rows (`bucketId → name, count, newest id`) [A17]. A `ContentObserver` marks the list stale (reload on next resume).
**Thumbnails**: Coil custom `Fetcher` for `MediaThumb(id, isVideo)`: API 29+ `loadThumbnail(uri, Size(320, 320), null)`; 25–28 `MediaStore.Images.Thumbnails.getThumbnail(cr, id, MINI_KIND, null)` / `Video.Thumbnails…`; memory key `"thumb:$id"`.
**UploadItemFactory** (design §4.3):
- Picker id → `uri = withAppendedId(...)`; on 29+ with `ACCESS_MEDIA_LOCATION`: `MediaStore.setRequireOriginal(uri)`; **probe by opening** `openFileDescriptor(uri, "r")` while building the queue; failure (`SecurityException`, `UnsupportedOperationException`, `FileNotFoundException`) or permission missing → item needs the location choice [A2][B6]; "Send without location" → plain URI, `original = false`.
- `name = DISPLAY_NAME`; `size` = `ParcelFileDescriptor.statSize` if ≥ 0 else `SIZE` [B5]; `mtimeMs = DATE_MODIFIED × 1000` only if > 0, else null [B17].
- Share/SAF URIs: `OpenableColumns.DISPLAY_NAME/SIZE`, `DocumentsContract.Document.COLUMN_LAST_MODIFIED` when present; media-document URIs on 29+ → `MediaStore.getMediaUri(ctx, uri)` → treated as MediaStore items; mapping failure for `image/*`/`video/*` → one line "Photos sent this way may lose their location. Use Send photos & videos." [A19]; `content://media/…` shares are already MediaStore; other providers: try `_id`/`DATA` mapping, else as-is.
- Size unknown → `openAssetFileDescriptor(...).length`; still unknown → copy into `cacheDir/share/` and send the copy (deleted after the batch).
- URI no longer readable → `Failed(CantRead)` "Can't read this file any more. Share it again".
- `ContentUploadSource.open()` = `openFileDescriptor` → `ParcelFileDescriptor.AutoCloseInputStream`, `length = statSize`; `refresh()` re-queries size/date.
**Downloads** (design §4.5): `StatFs(downloadsDir).availableBytes < size + 50 MB` → `NotEnoughSpace` [A16]. 29+: `MediaStoreDownloadSink` inserts into `MediaStore.Downloads.EXTERNAL_CONTENT_URI` with `DISPLAY_NAME`, `MIME_TYPE`, `RELATIVE_PATH = "Download/Ferry"`, `IS_PENDING = 1`; `open()` = `openOutputStream(uri, "w")`; `commit()` = update `IS_PENDING = 0`, return the final `DISPLAY_NAME` (MediaStore appends " (1)"); `abort()` = `delete(uri)` [B7]. 25–28: `LegacyDownloadSink`: write `Download/Ferry/.<name>.part`; `commit()` claims the first free `name.ext` / `name (n).ext` with `File.createNewFile()` then `renameTo` the claimed file, `MediaScannerConnection.scanFile`; `abort()` deletes the part [B8]. `cleanupLeftovers()`: 29+ query own `IS_PENDING=1` rows in `Download/Ferry` (`MediaStore.QUERY_ARG_MATCH_PENDING`) and delete; 25–28 delete `.*.part` files [B7]. "Open" = `ACTION_VIEW` with the content URI + `FLAG_GRANT_READ_URI_PERMISSION` [A16]; APKs go to `ApkInstaller`.

### 4.8 Installer and app update (design §4.6, §5.4)
- `res/xml/file_paths.xml`: `<files-path name="update" path="update/"/>`, `<files-path name="apk" path="apk/"/>`, `<files-path name="logs" path="logs/"/>`; provider authority `app.ferry.files`, `exported=false`, `grantUriPermissions=true` [A14].
- `ApkInstaller.canInstall()` = SDK < 26 || `packageManager.canRequestPackageInstalls()`; `openUnknownSourcesSettings(): Boolean` = `ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:app.ferry` in try/catch (`ActivityNotFoundException` → false → show the text steps; Fire OS 6 → its own text) [A14]; `install(file|uri)` = `ACTION_VIEW`, type `application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION | FLAG_ACTIVITY_NEW_TASK`. On return (`ON_RESUME`) re-check and offer the install again.
- Update: `ConnectionManager` calls `appInfo()` after each successful locate; `AppUpdate.isNewer(info, BuildConfig.VERSION_CODE)` → `AppGraph.update = Available(info)`; tap → `downloadApk` to `filesDir/update/Ferry.apk` (progress) → `install`. Disabled while transfer/download/cast is active [B21]. TV outbox APKs: `downloadApk`-like download into `filesDir/apk/<name>` with a progress dialog, then `install` (T4).

### 4.9 TV UI (design §5)
- **Theme**: dark, typography × 1.5, root `Modifier.padding(horizontal = 48.dp, vertical = 27.dp)` (overscan) [A25]; focus ring = 3 dp amber border + `scale(1.05f)` driven by `onFocusChanged`.
- **Focus APIs** (Compose UI/foundation 1.7.6 from BOM 2024.12.01; stable APIs only) [A13]: `FocusRequester` + `Modifier.focusRequester()`, `Modifier.onFocusChanged {}`, `Modifier.focusable()`, `Modifier.focusGroup()`, `Modifier.focusProperties { up/down/left/right = requester }`, `Modifier.onPreviewKeyEvent {}`, `LazyRow`/`LazyColumn` + `rememberLazyListState()`, `LazyListState.scrollToItem()`, `withFrameNanos {}`. Not used: `focusRestorer` and `focusProperties { enter/exit }` (experimental in 1.7).
- `tvClickable(onClick)` modifier: `focusable()` + `onPreviewKeyEvent` (KeyUp of `DirectionCenter`, `Enter`, `NumPadEnter` → onClick) + `clickable` for touch.
- **Focus memory**: `TvViewModel.focus: RowFocus(row, index)` updated in `onFocusChanged`; `TvHomeScreen` on entry: `LaunchedEffect(Unit) { rowState.scrollToItem(index); withFrameNanos {}; requesters[row][index].requestFocus() }`; first visit → first card of the first non-empty row.
- **Home rows** (`TvHomeScreen`): Videos (`video_playable`), Photos (first card "▶ Slideshow (24)", then thumbnails), Apps, Other files (incl. `video` kind) [A12]; empty state; idle line "Ready for your phone. On the phone: Ferry → Play on TV." + TV name [A10]; update banner (focusable); outbox refresh 5 s (list diff keyed `id`). **Thumbnails** [A11]: dedicated Coil `ImageLoader` with `fetcherDispatcher(Dispatchers.IO.limitedParallelism(2))`, request `.size(320)`, `diskCacheKey/memoryCacheKey = "name|size|mtime"`, loaded only for composed cards; error → icon.
- **TvPcNotFound** [A18]: "Can't find your PC", **Try again**, found PCs, **Enter address** → `OutlinedTextField` with `KeyboardOptions(keyboardType = KeyboardType.Decimal)` prefilled with the TV's first three octets (`NetworkBinder.ownIpv4`).
- **KeepScreenOn** [A10][H24]: `FLAG_KEEP_SCREEN_ON` while Player/Viewer/Slideshow is on screen; on Home set on every key press (`Activity.dispatchKeyEvent` hook) or cast and cleared 30 min later by a restartable coroutine.
- **TvInstall**: OK on an APK → progress dialog → `ApkInstaller` (first time: explanation + settings / text steps, Fire OS 6 text) (T4).

### 4.10 Media3 player and slideshow (design §5.2, §5.3)
- `PlayerHost` builds `ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(sharedClient.newBuilder().connectTimeout(8 s).readTimeout(20 s).build())).setLoadErrorHandlingPolicy(FerryLoadErrorPolicy)).setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_500, 5_000).build())` [CP2][B13]. `FerryLoadErrorPolicy`: retry `IOException`s up to 8 times with 1 s delay (so the reconnect after the PC's 60 s idle drop always happens) [B13].
- `PlayerView` (media3-ui) inside `AndroidView`: `useController = true`, `isFocusable = false`, `descendantFocusability = FOCUS_BLOCK_DESCENDANTS`, controller shown programmatically (display-only) [A12]. The `TvPlayerScreen` handles keys in `onPreviewKeyEvent`: OK/`MEDIA_PLAY_PAUSE`/`MEDIA_PLAY`/`MEDIA_PAUSE` toggle; Left/Right ±10 s; `MEDIA_REWIND`/`MEDIA_FAST_FORWARD` ±30 s (repeat on hold via `repeatCount`); Up shows the controller; Back stops and pops.
- Errors: `onPlayerError` with decoder/format codes → "This TV can't play this video format (<codec>)"; `onTracksChanged`: audio groups present but none supported → "This TV can't play this video's sound (<codec>)" [A12]; codec from `Format.sampleMimeType`/`codecs`.
- Resume: `ResumeStore.save(key, posMs)` every 5 s and on stop; reopen → dialog "Resume from 12:34" / "Start over" (only if > 30 s in and > 30 s before the end) with **initial focus on Resume** [A12].
- **Viewer/Slideshow** (`TvViewerScreen`): Coil `AsyncImage` with `size(displayW, displayH)`, EXIF orientation default on, cross-fade 400 ms, preloads next 2 (`imageLoader.enqueue`), interval from settings (3/5/10 s), Left/Right previous/next (pauses auto-advance for that photo), OK/Play-Pause pause, overlay "3 / 24 · name" for 2 s; decode error → "Can't show this photo" for 2 s then next [A20].
- The same player/viewer composables serve casts: `CastSinkImpl` maps `CastRequest` → `Screen.Player(urls)` / `Screen.Slideshow(urls)` pushed on top, toast "Playing from vivo X200 Pro"; `ControlRequest` → player/slideshow commands; `state()` from a `@Volatile` snapshot updated every 500 ms; phone server unreachable for 10 s → "Lost connection to the phone", pop to Home.

### 4.11 Manifest (design §3.6, asserted by `check_manifest.py`)
`<application android:name=".FerryApp" android:label="Ferry" android:icon android:roundIcon android:banner="@drawable/banner" android:usesCleartextTraffic="true" android:theme="@style/Theme.Ferry">` [A1]; permissions: `INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, READ_EXTERNAL_STORAGE (maxSdkVersion 32), WRITE_EXTERNAL_STORAGE (maxSdkVersion 28), ACCESS_MEDIA_LOCATION, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, REQUEST_INSTALL_PACKAGES`; `<uses-feature android.hardware.touchscreen required=false>`, `<uses-feature android.software.leanback required=false>`; `MainActivity` (`exported`, `launchMode="singleTask"`, `configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode"`) with MAIN + `LAUNCHER` + `LEANBACK_LAUNCHER`; `ShareReceiverActivity` (`exported`, `theme="@style/Theme.Ferry.Translucent"`, `excludeFromRecents`, `noHistory`, `taskAffinity=""`) with `SEND` and `SEND_MULTIPLE`, `mimeType="*/*"` [A5]; `TransferService` `foregroundServiceType="dataSync"`, `CastService` `foregroundServiceType="mediaPlayback"`, both `exported=false`; `androidx.core.content.FileProvider` authority `app.ferry.files`. Themes are platform themes (`android:Theme.Material.Light.NoActionBar`, `android:Theme.Translucent.NoTitleBar`) so no Material Components library is needed. `check_manifest.py` also asserts no `<uses-library>` and that `minSdkVersion=25`, `targetSdkVersion=35`.

### 4.12 Strings table [A22] (`res/values/strings.xml`; one place to correct wording after real-device tests)
| Key | Text |
|---|---|
| `status_connected` | Connected to %1$s |
| `status_looking` | Looking for your PC… |
| `status_not_found` | PC not found. Is Ferry running on the PC, and is this phone on the same Wi-Fi? |
| `status_no_wifi` | Your phone is not on Wi-Fi. Connect to your home Wi-Fi. |
| `status_old_pc` | Your PC has an older Ferry. Replace it with the new Ferry folder. |
| `status_pc_error` | Your PC answered with an error (%1$s). Restart Ferry on the PC. |
| `waiting_for_pc` | Waiting for %1$s… (continues automatically) |
| `gave_up_waiting` | Couldn't reach %1$s for 30 minutes. %2$d files not sent. Tap Retry when the PC is on. |
| `pc_disk_full_need` | Your PC needs %1$s more free space. Nothing was sent. |
| `pc_disk_full` | Your PC's disk is full. Free some space, then tap Retry. |
| `phone_storage_full` | Not enough space on your phone (%1$s needed). |
| `battery_saver_on` | Battery saver is on. Transfers may be slower. |
| `row_waiting` / `row_checking` / `row_sending` | Waiting · Checking · Sending %1$d%% |
| `row_saved` / `row_saved_as` / `row_saved_no_location` | Saved · Saved as %1$s · Saved without location |
| `row_already` / `send_anyway` | Already on PC · Send anyway |
| `row_failed_read` | Can't read this file any more. Share it again. |
| `row_failed_changed` | The file changed while sending. |
| `row_failed_drops` | The connection kept dropping. |
| `row_not_sent` | Not sent: PC not found |
| `summary` | %1$d saved · %2$d already on PC |
| `summary_failed` | %1$d failed. Tap to retry |
| `summary_no_location` | %1$d saved without location |
| `interrupted` | The last transfer was interrupted (%1$d files not sent). vivo may have stopped Ferry. Fix battery settings |
| `vivo_steps` | 1. Open Recents and lock Ferry (pull the Ferry card down or tap the lock). 2. Settings → Battery → Background power consumption → Ferry → Allow. |
| `battery_ok` / `battery_fix` | Android battery setting: OK · Android battery setting: needs a change → Fix |
| `location_dialog` | Ferry isn't allowed to read photo locations. · Allow · Send without location |
| `lose_location_note` | Photos sent this way may lose their location. Use Send photos & videos. |
| `partial_access` | Ferry can only see some photos → Allow all |
| `media_rationale` | Choose Allow all. If vivo asks again, choose Allow. |
| `share_toast` | Sending %1$d files to %2$s |
| `update_available` / `update_after` | Ferry %1$s is available → Update · Update after this finishes |
| `install_allow` | Allow Ferry to install apps. |
| `install_steps_fireos` | Settings → My Fire TV → Developer options → Install unknown apps → Ferry: ON. (No Developer options? Settings → My Fire TV → About → press OK on the device name 7 times.) |
| `install_steps_fireos6` | Settings → My Fire TV → Developer options → Apps from Unknown Sources: ON |
| `tv_not_found` | On the TV, open Ferry and leave it on its home screen. |
| `tv_ready` | Ready for your phone. On the phone: Ferry → Play on TV. |
| `tv_pc_not_found` | Can't find your PC |
| `tv_empty` | Put files in Desktop → Ferry outbox on your PC. They'll appear here. |
| `tv_other_files` | Open these on your phone or PC |
| `cant_play_format` / `cant_play_sound` | This TV can't play this video format (%1$s) · This TV can't play this video's sound (%1$s) |
| `cant_show_photo` / `cant_show_format` | Can't show this photo · Can't show this photo format |
| `lost_phone` | Lost connection to the phone |
| `playing_from` | Playing from %1$s |
Sizes use 1 decimal and units B/KB/MB/GB (1000-based, matching the PC console); ETA "about 1 min left" / "less than a minute left".

### 4.13 `:app` JVM unit tests (`src/test`, no Android runtime)
`PermissionPlannerTest` (lists per SDK 25/28/29/32/33/34/35; Full/Partial/None), `FormatTest` (sizes, speed, ETA, ages, summary strings logic), `DeviceModeLogicTest` (pure decision function), `LegacyNameClaimTest` (`File.createNewFile` claim on a temp dir: `a.pdf` → `a (1).pdf` → `a (2).pdf`, never overwrites) [B8], `BroadcastAddressTest` (prefix → directed broadcast), `VersionRuleTest` (`20000 + n`), `ResumeRuleTest` (> 30 s in and > 30 s before end), `TvFocusLogicTest` (row/index clamping after the list changes), `CastStateMapperTest`.

### 4.14 Instrumented tests (emulator) and how each is set up
Common: `androidTest` APK `app.ferry.test`; annotations `@PhoneSuite`, `@TvSuite`, `@Api25Suite`; the host script (CP6) runs classes in the listed order with `am instrument -w -r -e class <Class> app.ferry.test/androidx.test.runner.AndroidJUnitRunner`. The host PCs are `ferry.py` processes on the runner reached at `10.0.2.2:<port>`; tests read their address from instrumentation args (`-e pcA 10.0.2.2:18765 -e pcB 10.0.2.2:18766`) and write it to `Prefs.manual` before launching the activity (broadcast discovery can't reach the host from the emulator NAT [B27]). `Shots.take(name)` = `uiAutomation.takeScreenshot()` → PNG in `filesDir/screenshots/<suite>/` [CP16]. Media fixtures are pushed by the host to `/data/local/tmp/ferry/`; tests copy them into shared storage with `uiAutomation.executeShellCommand("cp …")` (shell-owned, **not owned by Ferry**) and `MediaScannerConnection.scanFile` [B1].

**Phone suite** (API 34, google_apis, x86_64) — host starts PC-A `:18765` and PC-B `:18766` (outbox seeded with `report.pdf`, `small.mp4`, `photo1.jpg`), PC-U `:18767` with `--app-dir` holding the debug APK + a json with `version_code 2000000000`:
| # | Class | Setup → asserts |
|---|---|---|
| 1 | `GpsNegativeControlTest` [B1][A2] | Run 1, **no** `ACCESS_MEDIA_LOCATION` (installed without `-g`; host grants only `READ_MEDIA_IMAGES/VIDEO`). Copy `gps.jpg` to `DCIM/Camera/IMG_20250101_120000.jpg`, scan; assert `OWNER_PACKAGE_NAME != "app.ferry"`; `UploadItemFactory` reports "needs location choice"; choose "Send without location" → `TransferService` → PC-B; engine row = "Saved without location". Host check (`verify_inbox.py`): PC-B copy is **not** both byte-identical and GPS-present (if it is, the build fails: "redaction not observed, GPS test inconclusive") |
| 2 | `GpsOriginalTest` [B1][P2] | Host `pm grant app.ferry android.permission.ACCESS_MEDIA_LOCATION` between classes. Same file via the real service → PC-A: "Saved". Host check: sha256 equals the fixture and `exif_gps_status == "present"`; mtime within 2 s of the phone's `DATE_MODIFIED`. Resend → "Already on PC"; **Send anyway** → reply `skipped` (identical) |
| 3 | `PickerAcrossAlbumsTest` [P3] | 6 images copied into `DCIM/AlbumA` and `Pictures/AlbumB`; Compose: select 2 in Recent, open Albums → AlbumB → select 1, back to Recent → "3 selected"; Send → PC-A receives 3 files with their original names; order in `ferry-log.txt` = selection order (2 workers: first two start in order) |
| 3b | `PartialAccessBannerTest` [A15] | Runs **first** on the fresh install with only `READ_MEDIA_VISUAL_USER_SELECTED` granted (see run order below) → picker shows "Ferry can only see some photos → Allow all"; screenshot |
| 4 | `ShareToFerryTest` [P1b][A5][B19] | 3 MediaStore files (one 30 MB) sent as `ACTION_SEND_MULTIPLE` with `ClipData` + read grant to `ShareReceiverActivity`; assert the activity reaches DESTROYED **before** the engine finishes, then all 3 saved on PC-A |
| 5 | `ScreenOffUploadTest` [P4] | 300 MB file (created with `dd` via shell into `Download/`, scanned) sent; after 5 s `UiDevice.sleep()`; wait ≤ 3 min; `wakeUp()`; summary "1 saved" and the PC-A file size matches; notification present during the transfer (UiAutomator) |
| 6 | `CancelAndWaitingTest` [P6] | cancel a 300 MB upload at ~30 % → row Cancelled; host check: no `.ferry-*.part` in the PC-A inbox. Then set the PC address to an unused port (`10.0.2.2:18799`) and send 1 file → Transfer screen shows "Waiting for …" + Cancel within 10 s (screenshot), Cancel → Cancelled. (PC-comes-back resume is proven against a real ferry.py in `:core` `pcGoesAwayAndComesBack`.) |
| 7 | `FromPcDownloadTest` [P7][A16] | From PC lists `report.pdf` from PC-B; download → MediaStore row in `Download/Ferry` with identical bytes (compare with a direct GET); download again → `report (1).pdf`, first unchanged; screenshot |
| 8 | `UpdateBannerTest` [C3] | connect to PC-U → banner "Ferry 2.0.… is available"; tap Update → download + sha check → an `Instrumentation.ActivityMonitor` for `ACTION_VIEW` + APK MIME (blocking) is hit (or, if `canRequestPackageInstalls()` is false, the monitor for `MANAGE_UNKNOWN_APP_SOURCES`) |
| 9 | `CastLoopbackTest` [P9][T5][B27] | `MainActivity` with debug extra `CAST_RECEIVER=true` (receiver on 127.0.0.1:8766); Play on TV → TV "127.0.0.1" → video `small.mp4` (copied into `Movies/`) → state `kind=video, playing=true` within 10 s; Pause → `playing=false`; Next/Stop; then photos ×3 → slideshow index advances; screenshot Remote |
| 10 | `PhoneScreenshotTour` | welcome, home (connected / not found / no Wi-Fi via a fake state), picker, location dialog, transfer running, summary, From PC, Play on TV, Remote, Settings |

**TV suite** (API 30, android-tv, x86) — host PC-T `:18765`, outbox seeded with `big.mp4` (≥ 200 MB, generated and cached), `small.mp4`, 5 photos + `broken.jpg`, `Test.apk` (copy of the debug APK), `notes.txt`, `old.avi`:
| # | Class | Setup → asserts |
|---|---|---|
| 1 | `TvLaunchAndFocusTest` [T1][A13] | launches in TV mode (assert `DeviceMode.isTv`); first Videos card focused; D-pad right/down via `UiDevice.pressDPad*`; OK on video 2 → Player; Back → `onNodeWithTag("card-videos-1").assertIsFocused()`; rows contain `old.avi` under Other files [A12]; screenshots |
| 2 | `TvLongPauseTest` [T2][B13][acc 5] | play `big.mp4`; playing 5 s; pause 90 s (> PC 60 s timeout); resume → `isPlaying` within 10 s, position advances; `MEDIA_FAST_FORWARD` (+30 s) and `MEDIA_REWIND` (−30 s) → still playing; no `PlaybackException` recorded |
| 3 | `TvResumeTest` [T2] | play to 40 s, Back; reopen → "Resume from 0:40" focused; OK → position ≈ 40 s |
| 4 | `TvSlideshowTest` [T3][A20] | Slideshow card → index advances every 3 s (setting 3 s); Right/Left change index; `broken.jpg` shows the message and is skipped |
| 5 | `TvInstallTest` [T4] | OK on `Test.apk` → download dialog → monitor catches `ACTION_VIEW` APK or the unknown-sources settings intent |
| 6 | `TvPcNotFoundTest` [A18] | PC address set to an unused port → "Can't find your PC", Try again, Enter address prefilled with "10.0.2." |
| 7 | `TvScreenshotTour` | home (full, empty), player with controller, viewer, slideshow overlay, install dialog, PC not found |

**API 25 suite** (API 25, default, x86) [A3][H17][H27] — host PC-25 `:18765`:
| # | Class | Asserts |
|---|---|---|
| 1 | `CoreOnApi25Test` | real `:core`: `DiscoveryCodec` round trip; `FerryClient.info()` to `10.0.2.2:18765` (protocol 2); `UploadEngine` sends a 1 MB in-memory source → Saved; `MiniHttpServer` on the device + OkHttp `Range: bytes=10-19` to itself → 206 and correct bytes; `AppUpdate` sha256 on a temp file; `RingLog` write |
| 2 | `Api25LaunchTest` | `MainActivity` launches (phone UI on the default image), Home shows "Connected to …", screenshot |
| 3 | `Api25DownloadTest` [B8][A16] | host grants `WRITE_EXTERNAL_STORAGE`; download `report.pdf` from PC-25's outbox twice through `TransferService` → `Download/Ferry/report.pdf` and `report (1).pdf`, bytes equal, no `.part` file left (the abort path is covered by `DownloadQueueTest`) |

**Run order (phone)**: the host runs `3b` first (only `READ_MEDIA_VISUAL_USER_SELECTED` granted), then `pm grant READ_MEDIA_IMAGES READ_MEDIA_VIDEO` (granting never kills the process), then 1, then `pm grant ACCESS_MEDIA_LOCATION`, then 2, 3, 4–10. No class needs a permission revoked after it was granted, so the test process is never killed by a revoke.

`verify_inbox.py` runs after the phone suite on the host and checks PC-A/PC-B as listed. Every suite uploads: `am instrument` raw output, `logcat -d`, host `ferry-log.txt` files, screenshots.

## 5. CI

### 5.1 `pc.yml` (final)
- Triggers: `push` with `paths: [pc/**, .github/workflows/pc.yml]`, `workflow_dispatch`.
- Job `test`, `fail-fast: false`, matrix `include`: `{os: windows-latest, py: "3.8"}`, `{os: windows-latest, py: "3.13"}`, `{os: ubuntu-22.04, py: "3.8"}` (no 3.8 on 26.04 runners, baseline), `{os: ubuntu-latest, py: "3.13"}`. Steps: checkout, setup-python, `pip install pytest`, `python -m pytest -q -ra --junitxml=pytest.xml`; upload `pytest.xml` + any `ferry-log*.txt` from tmp on failure. Timeout 20 min.

### 5.2 `android.yml` (final; **never rename** [H26])
- Triggers: `push` with `paths: [android/**, pc/**, .github/workflows/android.yml]`; `workflow_dispatch` with input `emulators: all|phone|none` (default `all`).
- `concurrency: { group: android-${{ github.ref }}, cancel-in-progress: ${{ github.ref != 'refs/heads/main' }} }`.
- Workflow env on `main`: `FERRY_RELEASE: ${{ github.ref == 'refs/heads/main' && '1' || '0' }}`, `FERRY_RUN_NUMBER: ${{ github.run_number }}`.

| Job | Runs on / when | Steps | Artifacts |
|---|---|---|---|
| `core-jvm` | matrix `ubuntu-latest`, `windows-latest`; every push | checkout, setup-java 17, setup-python 3.13, setup-gradle, `./gradlew --no-daemon :core:test` (`shell: bash` on Windows; env `CI=true`, `FERRY_PYTHON=python`) | `core-test-reports-<os>` (`android/core/build/reports/tests/`, `build/test-results/`) always |
| `app-build` | ubuntu-latest; every push | checkout, java 17, python 3.13, setup-gradle; `python -m pytest -q android/ci`; `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest`; `python android/ci/check_manifest.py <merged release manifest>`; `python android/ci/check_apk.py app-release.apk --expect-code $CODE --expect-name $NAME` (uses `$ANDROID_HOME/build-tools/35.0.0/{apksigner,aapt2}`); `python android/ci/make_apk_json.py app-release.apk $CODE $NAME > Ferry.apk.json`; rename to `Ferry.apk` | `ferry-apk` (`Ferry.apk`, `Ferry.apk.json`), `ferry-test-apks` (debug + androidTest APKs), `app-unit-reports` |
| `emulator-phone` | ubuntu-latest; push to `main`, or dispatch with `emulators != none`; `needs: app-build` | checkout; download `ferry-test-apks`; KVM udev step; setup-python 3.13; AVD cache; `reactivecircus/android-emulator-runner@v2.33.0` with `api-level: 34, target: google_apis, arch: x86_64, profile: pixel_6, disable-animations: true, emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none -no-snapshot-save`, `script: bash android/ci/run_instrumented.sh phone` (single line: the runner executes each script line in its own shell); timeout 45 min | `instrumented-phone` (am output, logcat, host logs), `screenshots-phone` |
| `emulator-tv` | ubuntu-latest; push to `main`, or dispatch with `emulators == all` [H27]; `needs: app-build` | as above + `big.mp4` cache (`make_big_video.sh` installs ffmpeg with apt if missing; cache key = script hash); `api-level: 30, target: android-tv, arch: x86`; `script: bash android/ci/run_instrumented.sh tv`; timeout 50 min | `instrumented-tv`, `screenshots-tv` |
| `emulator-api25` | same gating as TV | `api-level: 25, target: default, arch: x86`; `script: bash android/ci/run_instrumented.sh api25`; timeout 30 min | `instrumented-api25`, `screenshots-api25` |
| `package` | ubuntu-latest; `main` only (`if: github.ref == 'refs/heads/main'`); `needs: [core-jvm, app-build, emulator-phone, emulator-tv, emulator-api25]` | checkout, python 3.13; `python -m pytest -q pc/tests` (Linux only, fast); download `ferry-apk` into `pc/app/`; `python pc/tools/package.py --app-dir pc/app --out dist` | **`Ferry-2.0.<n>.zip`** (retention 90 days) |

Emulator details [B27]:
- **KVM** (first step of each emulator job): `echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules && sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm`.
- **AVD snapshot cache**: `actions/cache@v4` on `~/.android/avd/*` + `~/.android/adb*`, key `avd-<api>-<target>-<arch>-v1`; on miss, one emulator-runner step with `force-avd-creation: false`, `emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none`, `script: echo "AVD snapshot created"`; test steps then use `-no-snapshot-save`.
- **One automatic retry**: the test step has `id: try1, continue-on-error: true`; an identical step `if: steps.try1.outcome == 'failure'` runs once more; a final step fails the job if both failed. Each attempt writes artifacts to a separate folder.
- `run_instrumented.sh <suite>` (CP6): (1) start the suite's `ferry.py` instances from a temp copy of `pc/` with `--no-open --port <fixed> --discovery-port off --inbox/--outbox/--data-dir` under `$RUNNER_TEMP/<name>` (`--app-dir` for PC-U), wait for `FERRY_READY`; (2) seed outboxes from `android/ci/fixtures/` (+ `big.mp4` for TV); (3) `adb wait-for-device`, `adb install -r` both APKs (**no `-g`**); `adb shell pm grant` only the permissions each class needs (§4.14); `adb push` fixtures to `/data/local/tmp/ferry/`; (4) run the classes in order with `adb shell am instrument -w -r -e class … -e pcA … app.ferry.test/androidx.test.runner.AndroidJUnitRunner`, failing on any `FAILURES!!!`/`INSTRUMENTATION_FAILED`/missing `OK (`; (5) `adb exec-out run-as app.ferry tar -c -C files screenshots > shots.tar` and extract [CP16]; `adb logcat -d`; (6) host checks (`verify_inbox.py` for phone); (7) kill the ferry.py processes (trap on EXIT).
- Ports 18765–18767 are fixed (one suite per runner, nothing else listens).

### 5.3 versionCode and the PC zip
- `versionCode = 20000 + github.run_number` of `android.yml`, `main` only; `versionName = 2.0.<run_number>` [H26]. Run numbers count every run of this workflow (all branches, all triggers), so they only grow; packaged builds are always `main` builds. `check_apk.py` verifies the APK's badging matches before packaging.
- The PC zip gets the APK inside the **same `android.yml` run**: `app-build` uploads `Ferry.apk` + `Ferry.apk.json`; `package` downloads them into `pc/app/` and runs `package.py`, which refuses to build without a consistent pair [B25]. A PC-only change also triggers `android.yml` (path `pc/**`), so every user zip carries a freshly numbered APK (the app will then offer the update, which is harmless and keeps "new Ferry folder ⇒ update phone and TV" simple).
- Reviewing artifacts from this workspace: `gh run download <run-id> -n screenshots-phone -D /tmp/shots` (checked once in Ph0 through the proxy [B27]).

## 6. Phases

Phase ids are **Ph0–Ph10** (not P…, which are the phone requirements). Ordering principle: **prove the riskiest unknowns first** (emulators on Actions, host reachability, GPS redaction premise, player reconnect after a long pause, `:core` against real ferry.py on Windows, API-25 dexing), then build features on proven ground. Every phase leaves `main` green and, from Ph4 on, produces an installable `Ferry-2.0.<n>.zip`. Line counts are rough change sizes (added + changed, not counting deleted v1 HTML).

### Ph0 — CI foundation and emulator spike (~600 lines)
- **Goal**: all later CI assumptions proven with throw-away probes; both workflows green on Windows and Linux.
- **Files**: `.gitattributes`; the six v1 test portability fixes (§2.13 table 1); `pc.yml` matrix (§5.1); `android.yml` gains `emulator-phone`, `emulator-tv`, `emulator-api25` (KVM, AVD cache, retry, `run_instrumented.sh` first version that starts **v1** `ferry.py` from a temp copy with `--no-open --port --inbox --outbox` and waits by polling `/api/v1/info`); smoke app: `INTERNET`, `usesCleartextTraffic`, `READ_MEDIA_IMAGES`, `ACCESS_MEDIA_LOCATION`, androidTest deps; `android/ci/make_fixtures.py` run locally (Pillow, ffmpeg) → committed `fixtures/gps.jpg` (verified with `ferry.exif_gps_status == "present"`), `photo1..5.jpg`, `broken.jpg`, `small.mp4`; `make_big_video.sh`. Probes (androidTest, later deleted): `HostReachProbe` (GET `10.0.2.2:18765/api/v1/info`, `app=="ferry"`) on all three images; `RedactionProbe` (phone; run 1 without `ACCESS_MEDIA_LOCATION`: shell-copied, scanned `gps.jpg` read through MediaStore → bytes differ from the fixture / `setRequireOriginal` open fails; run 2 after `pm grant`: original bytes == fixture); `LongPauseProbe` (TV; ExoPlayer `DefaultHttpDataSource` + a retrying load-error policy on `big.mp4` from v1 ferry.py: play 5 s, pause 90 s, resume → playing); `Shots` helper + one screenshot per image.
- **Tests that must pass**: all v1 pytest on the 4-way matrix; smoke unit test; the probes.
- **CI green**: `pc` (all 4), `android` (`build`, `emulator-phone`, `emulator-tv`, `emulator-api25`).
- **Acceptance**: `gh run download <id> -n screenshots-tv` works from this workspace and the PNG opens [B27]; the system-image names that worked are written into §5.2 of this plan if they differ; the redaction and long-pause results are written to the decisions log.
- **Risks**: `android-tv` API 30 `x86` image missing or too slow → try `x86_64`, then API 31 TV; record. Emulator boot flakiness → retry step. **If Android does not redact on the emulator**, B1's negative control can't fail: record an H-row and keep the byte-identical + `exif_gps_status` positive check (still valid). **If the long-pause probe fails**, decide in this phase between a higher player retry count and a longer PC idle timeout for Range requests (design §5.2 rejected the PC change only "if the test passes").

### Ph1 — PC program 2.0.0 (~1 300 lines + ~1 500 lines of v1 HTML/assets deleted)
- **Goal**: `ferry.py` 2.0.0 complete per §2 (except README/DEVICE-CHECKLIST text, Ph10).
- **Files**: `pc/ferry.py`, `pc/tests/helpers.py`, updated v1 tests (§2.13), new tests (§2.14), `pc/tools/package.py`, deletions (§1.1), `.gitignore`; `run_instrumented.sh` switches to `--data-dir/--discovery-port off` + `FERRY_READY`.
- **Tests**: all of §2.13–2.14, plus `test_stdlib_only` (AST import scan of `ferry.py` against `sys.stdlib_module_names`, skipped on 3.8/3.9) for C7.
- **CI green**: `pc` (4-way); `android` (the probes still pass against the 2.0 PC, `HostReachProbe` checks only `app=="ferry"`).
- **Acceptance**: locally `python3 pc/ferry.py --port 0 --no-open --inbox /tmp/i --outbox /tmp/o --data-dir /tmp/d` prints `FERRY_READY`; `curl` of `/`, `/api/v1/info`, `POST /api/v1/check-batch`, a UDP datagram to the reported discovery port all answer as specified; `ferry-log.txt` lines look right; `python3 pc/tools/package.py --app-dir <fake>` builds the zip, and fails without the app pair.
- **Risks**: Windows-only behaviour of log rotation and UDP 10054 (tests run on `windows-latest`); keeping `ferry.py` stdlib-only and 3.8-compatible (no walrus-heavy/3.9+ syntax: CI 3.8 catches it).

### Ph2 — `:core` foundation + integration against ferry.py (~2 600 lines)
- **Goal**: the upload path proven end to end on the JVM, on Linux and Windows, and the `:core` jar proven on API 25.
- **Files**: `android/gradle/libs.versions.toml`, `settings.gradle.kts` (`:core`), root `build.gradle.kts`, `core/build.gradle.kts`; `core/…`: `Json`, `HostPort`, `Clock`, `util/*`, `model/Protocol.kt`, `net/*` (FerryHttp, FerryClient, StreamingBody, FerryException), `upload/*`; tests: `JsonModelsTest`, `HostPortTest`, `PercentEncodeTest`, `StreamingBodyTest`, `UploadEngineTest`, `ApiLevelScanTest`, `NoAndroidImportsTest`, `FerryPcProcess`, integration tests `uploadIsByteIdentical` … `cancelMidUploadLeavesNoTemp`, `pcGoesAwayAndComesBack`, `oldPcDetected` (stub via a 30-line `ServerSocket` in the test until `MiniHttpServer` exists). `:app` gets `implementation(project(":core"))` + desugaring; `HostReachProbe` on API 25 now calls `FerryClient.info()` (first real `:core` code on Fire OS 6's API level [A3]).
- **CI green**: `android/core-jvm` (ubuntu + windows), `build`, all emulator jobs.
- **Acceptance**: integration test report shows the case-clash and identical-content tests passing on Windows [B14]; `ApiLevelScanTest` fails when a deliberate `java.time.Instant` reference is added (try once locally in the branch, then remove).
- **Risks**: `-Xjdk-release=1.8` with Kotlin 2.0.21 on JDK 17 (fallback: keep `jvmTarget 1.8` and rely on the scan test + a longer forbidden-method list; record it); OkHttp's behaviour when the PC closes mid-body (the B11 design is safe either way; the test documents which branch runs); Windows process teardown leaving files locked (harness retries deletes).

### Ph3 — `:core` networking: servers, discovery, downloads, update, cast protocol (~2 400 lines)
- **Goal**: every `:core` component of §3.2 done and tested, so the app phases are mostly UI and Android plumbing.
- **Files**: `http/*`, `discovery/*`, `download/*`, `update/AppUpdate.kt`, `cast/*`, `model/CastModels.kt`, `model/Discovery.kt`, `log/RingLog.kt`; tests `MiniHttpServerTest`, `RangeParserTest`, `DiscoveryCodecTest`, `DiscoveryResponderTest`, `CastProtocolTest`, `CastFileServerTest`, `DownloadQueueTest`, `AppUpdateTest`, `RingLogTest`, integration `outboxListAndDownload`, `appInfoAndUpdateDownload`, `discoveryAgainstFerryPy`, `locatorPrefersLastKnown`; `oldPcDetected` moves to `MiniHttpServer`. API-25 probe adds a `MiniHttpServer` Range request to itself.
- **CI green**: `core-jvm` ×2, `build`, emulators.
- **Acceptance**: `stalledReaderSlotFreedWithin35s` green on both OSes; `MiniHttpServer` range vectors identical to `ferry.parse_range`'s.
- **Risks**: timing flakiness in the watchdog/rate-limit tests (generous margins, fake clocks where possible, real-time only in the one stalled-reader test); Windows loopback UDP quirks (responder loop survives errors by design).

### Ph4 — App skeleton, connection, settings, release pipeline (~2 400 lines)
- **Goal**: an installable, release-signed, correctly versioned Ferry that finds the PC (last-known / broadcast / manual), shows phone or TV home, offers updates (banner only), and is packaged into the PC zip.
- **Files**: `app/build.gradle.kts` (signing, version rule, deps, buildConfig), full manifest (§4.11), `res/` (themes, all §4.12 strings, icons, banner, `file_paths.xml`), `FerryApp`, `AppGraph`, `DeviceMode`, `Prefs`, `NetworkBinder`, `ConnectionManager`, `Navigator`, themes, `MainActivity`, `WelcomeScreen` (battery step), `HomeScreen` (status + buttons; buttons for later phases show "Coming in this build" only on debug builds — release builds of Ph4 are not given to the user), `SettingsScreen` (address, battery status, version, share log), `TvRoot`/`TvHomeScreen` skeleton (header, status, outbox rows as plain lists, `TvPcNotFound`), update banner (`AppGraph.update`); `android/ci/check_manifest.py`, `check_apk.py`, `make_apk_json.py`, `test_ci_scripts.py` + fixtures, `android/signing/cert-sha256.txt` (`90:07:63:92:A5:B5:5E:63:02:61:BA:15:77:16:88:11:3E:83:C1:FB:24:90:03:8D:B3:5E:FD:48:3B:75:C6:F1`, from `keytool -list -v`); `android.yml`: final `app-build` and `package` jobs, workflow env; `.gitignore` `pc/app/`. Unit tests `DeviceModeLogicTest`, `VersionRuleTest`, `BroadcastAddressTest`, `FormatTest`. Instrumented: `PhoneLaunchTest` (Home "Connected to" PC-A via manual address), `Api25LaunchTest`, `TvLaunchTest` (TV mode, header shows the PC), first screenshots.
- **CI green**: all `android` jobs incl. `package`; `pc`.
- **Acceptance**: download `Ferry-2.0.<n>.zip`; locally unzip, start `ferry.py` → banner "App: Ferry 2.0.<n> is ready…"; `/api/v1/app` shows `version_code 20000+n` and the APK's sha; `check_apk.py` output shows the pinned certificate; screenshots of phone and TV home reviewed.
- **Risks**: merged-manifest path in AGP 8.7.3 (`check_manifest.py` globs `app/build/intermediates/merged_manifest/release/**/AndroidManifest.xml`; fallback to `aapt2 dump xmltree --file AndroidManifest.xml`); edge-to-edge insets; `bindSocket`/socket factory on the emulator's Wi-Fi (the emulator exposes Wi-Fi on API 30+; on API 25 the default network is used when no Wi-Fi `Network` exists — `NetworkBinder` must fall back to "unbound" on emulators/Ethernet TVs rather than reporting "not on Wi-Fi": rule = prefer Wi-Fi, else Ethernet, else the active network if it is not cellular).

### Ph5 — Sending: service, share target, transfer screen, originals (~2 600 lines)
- **Goal**: requirements P1 (share + "Send other files"), P2, P4, P5, P6 working through the real foreground service.
- **Files**: `TransferService`, `Notifications`, `Locks`, `UploadItemFactory`, `ContentSources`, `ShareReceiverActivity` (incoming URIs via `IntentCompat.getParcelableExtra` / `getParcelableArrayListExtra` from core-ktx [A5 detail] + incoming `ClipData`), `TransferScreen` + `TransferViewModel`, `LocationChoiceDialog`, Home "Send other files" (`OpenMultipleDocuments`), interrupted-transfer marker + banner [A8], POST_NOTIFICATIONS request, Results notification; `android/ci/verify_inbox.py`; instrumented `GpsNegativeControlTest`, `GpsOriginalTest`, `ShareToFerryTest`, `ScreenOffUploadTest`, `CancelAndWaitingTest`; `RedactionProbe` deleted.
- **CI green**: all jobs.
- **Acceptance**: `verify_inbox.py` output in the phone artifact shows "PC-A gps.jpg: identical, GPS present; PC-B: redacted/not identical"; transfer and summary screenshots reviewed against §4.12 wording.
- **Risks**: FGS start from the share activity on API 34+ (allowed while visible; test 4 proves); read grant lifetime after the share activity finishes (test 4 proves either way); `onTimeout` only testable by unit test (engine `stopForTimeLimit`); emulator screen-off ≠ vivo (real-device acceptance 2).

### Ph6 — Photo picker and permission flow (~1 800 lines)
- **Goal**: requirements P3 and the permission part of P10.
- **Files**: `MediaRepo` (+ Coil thumbnail fetcher), `PickerScreen` + `PickerViewModel` (modes Send/CastVideo/CastPhotos), `PermissionPlanner`, rationale sheet, partial-access banner, settings fallback, Home wiring; tests `PermissionPlannerTest`, instrumented `PartialAccessBannerTest`, `PickerAcrossAlbumsTest`, picker screenshots.
- **CI green**: all jobs.
- **Acceptance**: picker screenshots (Recent with date headers, Albums, album grid with Select all, bottom bar) reviewed; selection survives tab/album switches in the test.
- **Risks**: MediaStore paging (`Bundle` args 26+, `LIMIT` string on 25); thumbnail loading speed on large libraries (lazy grid + memory keys); partial access semantics on API 34 emulator.

### Ph7 — From PC, downloads, installer, app update (~1 800 lines)
- **Goal**: requirements P7, C3 (app side), the installer used by T4.
- **Files**: `FromPcScreen` + `OutboxViewModel`, `DownloadSinks` (+ cleanup), `DownloadQueue` wiring in `TransferService`, `ApkInstaller`, update download/install flow, Settings "Share diagnostics log" via FileProvider; tests `LegacyNameClaimTest`, instrumented `FromPcDownloadTest`, `UpdateBannerTest`, `Api25DownloadTest`.
- **CI green**: all jobs.
- **Acceptance**: From PC screenshots; API 25 run shows `report (1).pdf` created, nothing overwritten.
- **Risks**: `IS_PENDING` behaviour on API 29 vs 30+ (publish-only-if-complete + delete on abort is safe on both); `MATCH_PENDING` query for cleanup on API 29 (use `MediaStore.setIncludePending` there — deprecated but present on 29); installer intents cannot be completed in CI (monitors only).

### Ph8 — Fire TV UI: rows, player, slideshow, install (~2 400 lines)
- **Goal**: T1–T4 and the TV parts of C3.
- **Files**: `TvHomeScreen` (rows, focus memory, thumbnails loader, idle line, update banner), `tvClickable`, `TvPlayerScreen` + `PlayerHost` (OkHttpDataSource, load control, retry policy, key handling, format/sound messages), `ResumeStore` + resume dialog, `TvViewerScreen` (viewer + slideshow), `TvInstall` dialog, `KeepScreenOn`; tests `ResumeRuleTest`, `TvFocusLogicTest`, TV suite 1–7; `LongPauseProbe` deleted.
- **CI green**: all jobs (TV suite on `main`).
- **Acceptance**: TV screenshots reviewed (focus ring visible on every focusable, overscan padding); `TvLongPauseTest` log shows the reconnect.
- **Risks**: Compose focus restoration (the manual requester approach avoids experimental APIs; if `requestFocus` races composition, retry once after another frame); software decoding speed on the x86 TV emulator for `big.mp4` (720p, 6 Mbit/s is enough to reach 200 MB in 5 min; lower the resolution, not the size, if needed); key event delivery through `PlayerView` (blocked descendants).

### Ph9 — Casting, both sides (~1 800 lines)
- **Goal**: requirements P9, T5, A24.
- **Files**: `CastService` (+ notification actions), `ContentRangeSource`, `PlayOnTvScreen`, `RemoteScreen`, `CastViewModel`, `CastReceiverHost` (lifecycle, MulticastLock, responder on UDP 8765, server on 8766), `CastSinkImpl` (→ player/slideshow), debug `CAST_RECEIVER` extra, last-TV memory [A24], notification tap → Remote [A24]; tests `CastStateMapperTest`, instrumented `CastLoopbackTest`, Remote screenshots.
- **CI green**: all jobs.
- **Acceptance**: loopback test shows play → pause → next → stop and a photo slideshow advancing; screenshots of Play on TV and Remote.
- **Risks**: UDP 8765 already bound on a TV (log + fallback to remembered TV); choosing the phone IP the TV can reach (`CastClient.localAddressTowardsTv` via a connected socket); `mediaPlayback` FGS rules on Android 14/15 (permission declared; started from a visible activity).

### Ph10 — Polish, documentation, release candidate (~900 lines, mostly text)
- **Goal**: C6 and a release candidate the user can test on real devices.
- **Files**: `pc/README.txt` (§2.12), `pc/DEVICE-CHECKLIST.txt` (CP12), `PhoneScreenshotTour` / `TvScreenshotTour` complete, wording pass over `strings.xml` against §4.12 and the screenshots [A22], `docs/v2-handoff.md` (how to build/run CI, never rename `android.yml` [H26], keystore backup [H13][H18], what CI does not prove [B27], where logs are, how to review screenshots), decisions-log updates, this plan's §7 ticked; pytest `test_readme.py` (required section titles present, ≤ 88 columns, no hidden flag names, mentions `ferry-log.txt` and `8765/app`).
- **CI green**: everything on `main`, zip artifact produced.
- **Acceptance**: the final zip downloaded and inspected (contains `Ferry/app/Ferry.apk`, json, README, checklist); every screenshot viewed; keystore backup handed to the user; the user runs the acceptance list (requirements §5) on the vivo, the Fire TV and the PC.
- **Risks**: wording mistakes only found on real devices (strings are in one file; vivo steps in one string [A8]).

## 7. Traceability

### 7.1 Requirements → phases → tests
| Req | Summary | Phase(s) | Automated tests (CI) | Only on real devices |
|---|---|---|---|---|
| P1 | Manual send: in-app + Share → Ferry (single/multiple) | Ph5, Ph6 | `ShareToFerryTest`, `PickerAcrossAlbumsTest`, `UploadEngineTest.happyPathTwoWorkersInSelectionOrder`, `engineEndToEndSmallBatch` | vivo Gallery share (acc. 3) |
| P2 | Originals: name, date, GPS, byte-identical | Ph2, Ph5 | `GpsOriginalTest` + `GpsNegativeControlTest` + `verify_inbox.py` [B1], `uploadIsByteIdentical`, `mtimeRestored`, `mtimeOmittedWhenUnknown`, `withoutLocationCountedInSummary` | vivo Gallery provider (acc. 1, 3) |
| P3 | Picker across albums, newest first; "Other files" via system chooser | Ph6, Ph5 | `PickerAcrossAlbumsTest`, `PartialAccessBannerTest`, `PermissionPlannerTest`; the "Other files" result uses the same `ACTION_SEND_URIS` path as `ShareToFerryTest` (the system chooser UI itself is not automated) | — |
| P4 | Background uploads (FGS), progress, speed, summary | Ph5 | `ScreenOffUploadTest`, `UploadEngineTest.progressThrottledTo250ms`/`speedIsThreeSecondAverage`, transfer/summary screenshots | vivo battery manager, 1 GB screen off (acc. 2) |
| P5 | Duplicate pre-check; never overwrite; `name (1).ext` | Ph1, Ph2, Ph5 | `test_check_batch.py` (all), `checkBatchFindsDuplicates`, `renameOnDifferentContent`, `parallelCaseClashDifferentContent`, `parallelIdenticalContent`, `GpsOriginalTest` resend, v1 `test_save_rename_skip` | — |
| P6 | Resilient: pause/resume when Wi-Fi/PC goes; no partial files | Ph2, Ph5 | `UploadEngineTest` waiting/retry/disk-full/cancel cases, `pcGoesAwayAndComesBack`, `cancelMidUploadLeavesNoTemp`, `diskFullLargeUpload`, `CancelAndWaitingTest` | Real Wi-Fi drop |
| P7 | Browse outbox, download to `Downloads/Ferry`, no warnings | Ph3, Ph7 | `outboxListAndDownload`, `DownloadQueueTest`, `FromPcDownloadTest`, `Api25DownloadTest`, `LegacyNameClaimTest`, `check_manifest.py` (cleartext) | acc. 4 |
| P8 | Finds the PC automatically; manual fallback | Ph1, Ph3, Ph4 | `test_discovery.py`, `DiscoveryCodecTest`, `discoveryAgainstFerryPy`, `locatorPrefersLastKnown`, `PhoneLaunchTest` (manual address), `TvPcNotFoundTest` | **Broadcast discovery** (emulator NAT blocks it [B27]) |
| P9 | Phone → TV: videos and photo slideshow, streamed | Ph3, Ph9 | `CastProtocolTest`, `CastFileServerTest`, `CastLoopbackTest` | acc. 8 |
| P10 | One-time battery + permission guidance | Ph4, Ph6 | `PermissionPlannerTest`, welcome/settings screenshots | vivo settings wording (acc. 2) |
| T1 | Remote-friendly UI | Ph8 | `TvLaunchAndFocusTest`, `TvScreenshotTour` | Fire TV remote |
| T2 | Reliable player incl. long pause, seek, resume | Ph0 (probe), Ph8 | `TvLongPauseTest`, `TvResumeTest` | 4K/HEVC on the stick (acc. 5) |
| T3 | Photo slideshow 5 s default, next/prev, pause | Ph8 | `TvSlideshowTest` | acc. 6 |
| T4 | Install APKs from the outbox | Ph7, Ph8 | `TvInstallTest` (intent), `ApkInstaller` paths | Fire OS installer and unknown-sources screens (acc. 7) |
| T5 | Receive "play on TV"; control from TV and phone | Ph3, Ph9 | `CastLoopbackTest`, `DiscoveryResponderTest`, `CastProtocolTest` | acc. 8 |
| T6 | TV does not send files | Ph8 | `TvLaunchAndFocusTest` asserts no "Send" node exists in TV mode | — |
| C1 | v1 `/api/v1` behaviour kept | Ph1 | v1 pytest suite (minus deleted browser tests), `:core` integration tests | — |
| C2 | Discovery on UDP 8765 | Ph1 | `test_discovery.py` (incl. 10054 survival, bind failure, port independence), `discoveryAgainstFerryPy` | Windows Firewall UDP |
| C3 | App updates via the PC | Ph1, Ph3, Ph4, Ph7 | `test_app_bundle.py`, `test_package.py`, `appInfoAndUpdateDownload`, `AppUpdateTest`, `UpdateBannerTest`, `check_apk.py` (cert + versionCode) | acc. 9 |
| C4 | Browser retired; Get-the-app page; QR → page | Ph1 | `test_get_app_page.py`, `test_removed_routes.py`, banner tests | First install from Chrome / Downloader |
| C5 | `ferry-log.txt` detailed, rotated | Ph1 | `test_request_log.py` (all), `deviceNameWithCurlyQuoteAndEmoji` | acc. 10 |
| C6 | README rewritten | Ph10 | `test_readme.py` | User reads it |
| C7 | `Ferry.bat`, stdlib-only, Python ≥ 3.8 | Ph0, Ph1 | `pc.yml` 3.8 jobs, `test_launchers_static`, `test_stdlib_only` | — |

### 7.2 Acceptance items (requirements §5) → evidence before the user tests
| # | Acceptance | Phase | CI evidence |
|---|---|---|---|
| 1 | 20 photos from two albums with names, dates, GPS; resend → "20 already on PC" | Ph5, Ph6 | `PickerAcrossAlbumsTest`, `GpsOriginalTest` (+ resend), `engineEndToEndSmallBatch` |
| 2 | 1 GB+ video with the screen off | Ph5 | `ScreenOffUploadTest` (300 MB), `UploadEngineTest.bigFileRunsAlone`; note MB/s screen on vs off on the vivo [A7] |
| 3 | Share → Ferry from Gallery | Ph5 | `ShareToFerryTest` |
| 4 | Outbox download → Downloads/Ferry, no warnings | Ph7 | `FromPcDownloadTest`, `Api25DownloadTest` |
| 5 | TV: 241.9 MB video, pause 30 s, FF, RW, no freeze | Ph0, Ph8 | `TvLongPauseTest` (≥ 200 MB, 90 s pause, ±30 s) |
| 6 | TV slideshow, next/previous | Ph8 | `TvSlideshowTest` |
| 7 | TV installs an APK from the outbox | Ph7, Ph8 | `TvInstallTest` (up to the installer intent) |
| 8 | PC off: phone video and slideshow on the TV | Ph9 | `CastLoopbackTest` (same device; no PC involved) |
| 9 | Newer APK on the PC → phone and TV offer the update | Ph1, Ph4, Ph7 | `test_app_bundle.py`, `UpdateBannerTest`, `test_package.py` |
| 10 | `ferry-log.txt` shows each transfer | Ph1 | `test_request_log.py::test_transfers_logged` |

### 7.3 Deferred design-audit items handled here
| Item | Where |
|---|---|
| A22 strings | §4.12 (table), Ph10 wording pass |
| A24 remember last TV; notification → Remote | §4.3 `PlayOnTvScreen`, §4.4 `CastService`, Ph9 |
| A25 insets, overscan, BackHandler, no native libs | §4.2 (edge-to-edge, `BackHandler`, predictive back unset), §4.9 (48/27 dp), `check_apk.py` (no `lib/`) |
| B25 v1 leftovers, replacement tests, package.py | §1.1, §2.2, §2.11, §2.13, `test_removed_routes.py`, `test_package.py`, Ph1 |
| B27 rest (KVM, AVD cache, animations, retry, `gh run download`) | §5.2, Ph0 acceptance |
| A5 detail (typed parcelable helper) | `IntentCompat` from core-ktx 1.15, Ph5 |
| A13 detail (Compose focus APIs) | §4.9 |
| B4 detail (10k check-batch budget) | §2.14 `test_10k_inbox_time_budget` (< 3 s) + `test_one_scan_one_disk_usage` |

### 7.4 What CI does not prove (copied into the handoff, Ph10) [B27]
Broadcast discovery on the real LAN; vivo's battery manager; Fire OS installer and unknown-sources screens; real Windows Firewall prompts (TCP and UDP); the Fire TV screensaver vs the 30-min keep-screen-on; hardware decoding of 4K/HEVC/DTS on the stick; real Wi-Fi speeds (≥ 10 MB/s small-file target).
