# Ferry v2: Code plan (Stage 4, rev 2)

Implements `v2-02-design.md` **rev 2** (the source of truth) for `v2-01-requirements.md`. Hands-free decisions are **[H#]** (`v2-decisions-log.md`); design-audit findings are **[A#]/[B#]** (`v2-03-design-audit.md`). Decisions taken in this plan are **[CP#]** (§0.2) so the next audit can check them.

**Rev 2 (2026-11-15):** fixes from the plan audit (`v2-05-plan-audit.md`). Changed text carries the finding IDs in brackets: **[PA#]** / **[PR#]** (Sonnet, runs 1 and 2) and **[PB#]** (Opus). New decisions are CP17–CP22 here and H28–H35 in the decisions log.

Written for AI agents implementing Ferry v2 in **sequential phases** (§6). Each phase is one agent session on its own **phase branch**; it ends when the branch is green and has been fast-forwarded to `main` with a green `main` run [PA2] [H28], and leaves the repo releasable.

## Outline
- §0 Ground rules, code-plan decisions [CP#], pinned dependencies
- §1 Repository / module / file layout (pc/, android/:core, android/:app, android/ci)
- §2 PC program 2.0.0 (ferry.py changes, hidden test flags, log writer, discovery, check-batch, /app, Get-the-app page, console, package.py, README, Windows-portable tests, test list)
- §3 Android `:core` (APIs, state model, threading, errors, API-25 rules, JVM + integration tests)
- §4 Android `:app` (screens, ViewModels, services, permissions, MediaStore, installer, TV focus, Media3, manifest, strings [A22], insets [A25], instrumented tests, screenshots)
- §5 CI (workflows, jobs, emulators, caching, artifacts, versionCode [H26], PC zip with the APK)
- §6 Phases Ph0a–Ph10 (goal, files, tests, CI, acceptance, risks); Ph0, Ph2, Ph3, Ph5 and Ph8 are split into a/b [PA9] [PR11]
- §7 Traceability (requirements and acceptance items → phases and tests)

## 0. Ground rules

### 0.1 How agents work in this repo
- **Python (pc/, android/ci/) is the only code that runs locally**: `cd pc && python3 -m pytest -q` and `python3 -m pytest -q android/ci`. Run them before every push.
- **Kotlin compiles only on GitHub Actions** (no Android SDK; Gradle 8.14.3 + JDK 21 are installed locally but Maven/Google are blocked). So: small, compile-safe steps; push; `gh run watch`; on red, `gh run view --log-failed`. Several pushes inside one phase are normal.
- **Work on a phase branch, never directly on `main`** [PA2] [PR12] [PR11] [H28] [CP18]. Branch name = phase id in lower case (`ph0a`, `ph2b`, `ph5a`…), created from the current `main`.
  - A push to the branch runs the **fast jobs only**: `pc.yml`, and in `android.yml` `core-jvm` (ubuntu + windows) and `app-build`. Red runs here break nothing and nobody's `main`.
  - **Emulator suites on a branch are opt-in by commit message**: a head commit containing `[emu]` runs all three suites, `[emu:phone]`, `[emu:tv]` or `[emu:api25]` runs one (several tags may be combined). This needs only `git push`; `gh workflow run android.yml --ref <branch> -f emulators=all|phone|tv|api25|none` does the same where the proxy allows it (tried once in Ph0b and recorded). **Ph0b result: `gh workflow run android.yml -R DrSaurabh/ferry --ref ph0b -f emulators=none` works through the proxy** (run 43696045208).
  - A phase is **done** when (1) the last branch run is green **including every emulator suite the phase touches** (use `[emu]`), (2) the branch has been fast-forwarded to `main` (`git push origin <branch>:main`; rebase first if `main` moved), and (3) that `main` run, which always runs everything, is green with a verified zip (§5.2). If the `main` run is red, fix on the branch and fast-forward again; never push fix-up commits straight to `main`.
  - Cost note [PA2] [PR12]: a branch push is about 15–25 runner-minutes; a `main` run up to about 2 hours in the worst case (three emulator suites). With one or two `main` runs per phase that is roughly 15–25 `main` runs for the whole build. The user allowed free use of Actions minutes (decisions-log header); the branch rule is about a working `main` and short feedback loops, not about money.
- Never rename `.github/workflows/android.yml` [H26]. Run numbers count branch runs too, so `versionCode` only ever grows; skipped numbers are harmless (§5.3).
- **Resuming a half-done phase** [PR11]: after each green sub-step append one line to `docs/v2-progress.md` (`Ph2b · step · commit · run id · what is left`). A new session reads that file and the branch log first, and continues on the same branch.
- Commit messages start with the phase id, e.g. `Ph5: share target + TransferService`.
- **Never weaken a test to get green.** If a platform fact turns out different from the design (e.g. redaction behaviour), stop, record it in `docs/v2-decisions-log.md` as a new H-row, and adapt with the safest behaviour.
- Keep `:core` free of `android.*` imports (enforced by `NoAndroidImportsTest`, §3.7).
- Do not add dependencies beyond §0.3 without a decisions-log entry.

### 0.2 Decisions taken in this plan [CP#]
| # | Decision | Why |
|---|---|---|
| CP1 | **No navigation-compose.** A ~80-line `Navigator` (back stack of a sealed `Screen` class in a `SnapshotStateList`, `BackHandler` per screen) | One dependency less; TV focus restoration is easier when the app owns the back stack (design §3.1 listed navigation-compose as a library, not a requirement) |
| CP2 | The TV player uses **`OkHttpDataSource`** (`media3-datasource-okhttp`) with the shared Wi-Fi-bound `OkHttpClient` (connect 8 s, read 20 s) instead of `DefaultHttpDataSource` | `DefaultHttpDataSource` cannot take the bound socket factory [A9]; same timeouts as design §5.2 [B13]; the request carries `X-Ferry-Device` for the log |
| CP3 | **Upload-time free-space check on the PC** [H30]: when `free < Content-Length + 64 MB`, `POST /api/v1/upload` does not store anything: it **drains ≤ 64 MB of the body, then answers 507, then closes** (the same order as v1's `DiskFull` path) [PB4]. **Exception** [PB4] [PR18]: if a file of the same family already has exactly this size (a likely duplicate), the check is skipped and the upload goes through the normal path, so a resend of a file that is already on the PC is answered `skipped`, never "disk full" (design B3); if the content turns out to be different, v1's real `ENOSPC` handling still protects the disk | Real disks: refuse early instead of filling the disk; needed so `--test-free-bytes` can drive the disk-full tests, incl. the "connection closed before the 507 was read" case [B11]. This is a change to v1 upload behaviour (C1), so it is logged as H30 |
| CP4 | Extra hidden flags beyond design §2.8: `--app-dir DIR` (where `Ferry.apk(.json)` are looked up) and `--discovery-port 0` (pick a free UDP port); `FERRY_READY port=<tcp> discovery=<udp\|off>` | Emulator update test and JVM discovery test need them; still not in the README or `--help` |
| CP5 | Merged-manifest, certificate, APK and json checks are **Python scripts** in `android/ci/` with their own pytest tests on fixture files | Locally runnable and testable (Kotlin is not) |
| CP6 | Emulator suites run through a host script (`android/ci/run_instrumented.sh`) that installs the APKs **without `-g`**, starts/stops the host `ferry.py` instances, runs `am instrument` per test class in the order given by a **suite file** `android/ci/suites/<suite>.txt` (one line per class: class, grants before it, host check after it; a phase adds lines, it does not edit the script's control flow) [PA10] [PR20], grants permissions between classes, pulls screenshots, and runs host-side checks | B1's negative control needs `ACCESS_MEDIA_LOCATION` **not** granted in the first run (revoking a permission kills the test process); deterministic ordering; host files can be checked with `ferry.exif_gps_status` |
| CP7 | `pc/tools/package.py` builds only the user zip, named `Ferry-<version_name>.zip` from `app/Ferry.apk.json` (e.g. `Ferry-2.0.57.zip`); the v1 `-src` zip is dropped | The repo is the source; the version the user sees is the app's |
| CP8 | Picker selections reach `TransferService` through an in-process hand-over (`AppGraph.pendingPicks`) of **already prepared items** (built and probed in the UI layer by `SendCoordinator`, §4.7 [PB5]); shares go through the start intent's `ClipData` with the read grant (design §4.1.7) plus a per-URI `requireOriginal` flag array | Picker IDs can exceed the binder limit (30 000 items × "Select all"); Ferry already holds the media permission for them, so no grant is needed. The location question (H19) can only be asked where there is a screen, so the probe runs before the service starts [PB5] |
| CP9 | `:core` compiles with Kotlin `-Xjdk-release=1.8` / Java `release 8`, plus a **bytecode scan test** that fails on references to API-26+ Java classes (`java/time/`, `java/nio/file/`, `java/util/Base64`, …) | Makes the API-25 rule [A3] a compile/test error, not a Fire OS 6 crash |
| CP10 | Engine, queues and ViewModels use **coroutines**; `MiniHttpServer` and the discovery responder use **plain threads** | Coroutine virtual time makes the 30-min wait / retry tests instant; blocking sockets are simpler and cheaper as threads |
| CP11 | MediaStore: images and videos are queried **separately** and merged, newest first by `DATE_MODIFIED` (the date Ferry sends) | `BUCKET_*` columns exist on both tables on every API level 25–35 |
| CP12 | `DEVICE-CHECKLIST.txt` is rewritten as the v2 real-device acceptance checklist (requirements §5, plus the screen-on/off MB/s note [A7]) | Replaces the v1 browser checklist; it's what the user runs |
| CP13 | Off `main`, `versionCode = 1` and `versionName = "2.0.0-dev"` | Only `main` builds are packaged [H26] |
| CP14 | `check-batch` writes **one console line** per request ("checked 20 files · 3 already on PC"), not one per duplicate | A 2 000-item check would flood the console; `ferry-log.txt` has the request line |
| CP15 | Every app request (OkHttp, ExoPlayer, Coil) carries `X-Ferry-Device` via one interceptor on the shared client | The PC log and console show "Saurabh's Fire TV" instead of "browser" for player and thumbnail requests |
| CP16 | Screenshots are written to `getExternalFilesDir(null)/screenshots/` and pulled with `adb pull /sdcard/Android/data/app.ferry/files/screenshots` (no `run-as`, no `tar`, which may be missing on the API 25 image) [PA16]. Ph0b proves this on all three images; if a pull fails on one image, that suite falls back to `filesDir/screenshots/` + `adb exec-out run-as app.ferry tar -c -C files screenshots`, and the result is written here | No test-services/orchestrator dependency *(uncertain → Ph0b decides, both ways specified)* **Ph0b result**: `adb pull` works on the API 34 phone and API 25 images; on the **API 30 TV image the shell gets `Permission denied`** on `Android/data`, so the TV suite uses the fallback (`-e store internal` → `filesDir/screenshots/`, pulled with `adb exec-out run-as app.ferry tar -cf - -C files screenshots`; toybox `tar` exists there) |
| CP17 | **No download to `Downloads/Ferry` on Android 7.1–9 (API 25–28)** [PA13] [PB9] [H31]: `LegacyDownloadSink`, the `WRITE_EXTERNAL_STORAGE` permission and request, `LegacyNameClaimTest` and `Api25DownloadTest` are dropped. On API < 29 the phone layout's From PC screen lists the files and says "Downloading from the PC needs Android 10 or newer". TV APK installs are not affected (they use the app's own folder) | No real device runs this path: the phone is Android 15, and API 25 exists only for Fire OS 6 TVs, which never download to Downloads (T6). The path carried a data-safety trap (a 0-byte placeholder left behind when the rename fails, PB9) and its own permission flow and emulator test. Overrides design §4.5's API 25–28 bullet |
| CP18 | Phase branches; emulator suites on a branch only with an `[emu…]` commit tag or a manual run; `main` moves only by fast-forward of a green branch (§0.1, §5.2) [PA2] [PR12] [H28] | A red blind-compile push must not break `main` or cost an hour of emulators; no dependence on `gh workflow run` working through the proxy |
| CP19 | The `package` job needs only `core-jvm` and `app-build`. The artifact is named `Ferry-2.0.<n>` only when **all three emulator suites succeeded** in the same run; otherwise it is named `Ferry-2.0.<n>-UNVERIFIED` and the job summary names the suite that failed or was skipped [PA4] [PR6] [H29]. Only an artifact without that suffix is ever given to the user | A flaky emulator class no longer hides the zip from the agent (useful while fixing), and an untested build still cannot be mistaken for a release |
| CP20 | Picker date headers are **full-width grid items that scroll with the photos**, not sticky [PR10] [H32] | `LazyVerticalGrid` has no stable sticky header in Compose foundation 1.7; P3 only asks for newest-first and selection across albums |
| CP21 | A tiny debug-only helper app `android/sharehelper` (package `app.ferry.sharehelper`, one activity + a non-exported `FileProvider`) is built in CI and installed only on the phone emulator, to share files that Ferry can read **only through the forwarded URI grant** [PB6] [H33]. It is never packaged | Without a sender in another UID the share test cannot fail (Ferry holds the media permission and the test runs inside Ferry) |
| CP22 | `:app:lintDebug` runs in `app-build` with only the `NewApi` check as an error [PA8] [H34] | `:app` had no API-level check at all; `NewApi` is the authority for "this call needs API N" facts that reviewers could not verify (e.g. `MediaStore.getMediaUri`, PR3) |

### 0.3 Pinned toolchain and dependencies (all released before mid-2025; resolve on Maven Central / Google Maven)
Kept from the green smoke build: **AGP 8.7.3, Kotlin 2.0.21, Gradle 8.14.3 wrapper, compileSdk 35, Compose BOM 2024.12.01, Media3 1.5.1**. All versions go into `android/gradle/libs.versions.toml`.

| Use | Coordinates | Version | Module |
|---|---|---|---|
| Already in the root build (keep; **extend, do not replace**, `android/build.gradle.kts`) [PR17] | `com.android.application` 8.7.3, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose` | 2.0.21 | app, sharehelper |
| Kotlin JVM plugin (for `:core`) | `org.jetbrains.kotlin.jvm` | 2.0.21 | core |
| Serialization plugin | `org.jetbrains.kotlin.plugin.serialization` | 2.0.21 | core |
| JSON | `org.jetbrains.kotlinx:kotlinx-serialization-json` (declared **`api`** in `:core`, so `:app` can use `Json` and the models' serializers) [PA11] [PR5] | 1.7.3 | core (→ app) |
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

Plugin versions are written as plain strings in the root `build.gradle.kts` `plugins {}` block (as today); `libs.versions.toml` holds library versions only [PR17].

Not used: navigation-compose [CP1], androidx.tv [H12], Google Play services, Robolectric, MockWebServer (tests use real `ferry.py` and our own `MiniHttpServer`). **Native libraries** [PA1] [PR1]: Ferry adds none of its own, but Compose 1.7 brings `libandroidx.graphics.path.so` (via `androidx.graphics:graphics-path`) for all four ABIs, so the APK does contain `lib/`; `check_apk.py` allows exactly that file name and requires `armeabi-v7a` and `arm64-v8a` copies of anything under `lib/` (the Fire TV Stick is 32-bit ARM) (§1.4). The emulator-runner tag below is checked in Ph0b (`git ls-remote --tags https://github.com/ReactiveCircus/android-emulator-runner v2.33.0`); if it does not exist, use `@v2` and write the tag that worked here [PA7]. **Ph0b: `v2.33.0` exists and works.** GitHub Actions: `actions/checkout@v4`, `actions/setup-java@v4` (temurin 17), `actions/setup-python@v5`, `gradle/actions/setup-gradle@v4`, `actions/cache@v4`, `actions/upload-artifact@v4`, `actions/download-artifact@v4`, `reactivecircus/android-emulator-runner@v2.33.0`.

## 1. Repository layout

```
.gitattributes               *.sh/gradlew eol=lf, *.bat eol=crlf, *.py eol=lf, binaries (*.jks *.apk *.jpg *.png *.mp4) binary
.github/workflows/pc.yml     PC tests (Windows + Linux, Python 3.8 + 3.13)
.github/workflows/android.yml  core JVM (ubuntu+windows), app build/checks, emulators, package (NEVER rename) [H26]
pc/                          ferry.py 2.0.0, qrcodegen.py, Ferry.bat, Ferry self-test.bat, ferry.sh, ferry-icon.svg,
                             README.txt (rewritten), DEVICE-CHECKLIST.txt (v2), LICENSE*.txt,
                             app/ (CI puts Ferry.apk + Ferry.apk.json here; .gitignored), tests/, tools/package.py
android/                     settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradle.properties,
                             core/, app/, sharehelper/ (test-only helper app [CP21]), ci/ (scripts, fixtures, suites/),
                             signing/ (ferry-release.jks, signing.properties, cert-sha256.txt) [H18]
docs/                        requirements, design, audits, this plan, decisions, handoff, v2-progress.md (phase progress [PR11])
```

### 1.1 `pc/` files
| File | Responsibility | Design |
|---|---|---|
| `ferry.py` | The PC program (single file, stdlib only, Python ≥ 3.8). Sections in order: constants · helpers · Console · Windows/Linux platform · Config/State/folders · addresses/QR · sanitising · `Inbox` (+ `check_batch`) · `Outbox` · zip · `TransferTracker` · `exif_gps_status` (test helper) · **`RequestLog`** · **`DiscoveryResponder`** · **`AppBundle`** · `FerryServer`/`FerryHandler` · pages (`GET_APP_HTML`, `ERROR_PAGE`, `FAVICON_SVG`) · banner · `main` · `selftest` | §2 |
| `tests/test_*.py` | pytest; v1 files updated (§2.13), new files §2.14 | §10 |
| `tests/helpers.py` | **new**: `Srv` fixture moved out of `test_http.py` (adds `app_dir`, `free_override`, `reqlog` options; **teardown stops the `RequestLog`** so Windows can delete the tmp folder [PB19]), `upload()`, `jpeg_with_gps()`, `start_ferry_process()` (subprocess + `FERRY_READY`; stdout is read by a **reader thread into a `queue.Queue`** with a timeout on `get`, because `select()` does not work on pipes on Windows [PB19]), `await_log(path, marker)` (sentinel wait, §2.14 [PB12]) | §2.8 |
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
| `download/DownloadSink.kt` | Sink interface implemented by `:app` (MediaStore on API 29+ / app-private file; no legacy sink [CP17]) | §4.5 |
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
| `media/SendCoordinator.kt` | UI-side entry point for every send [PB5] [PA10]: `prepare(picks or URIs)` runs the factory and the original probe on `Dispatchers.IO` and returns `Prepared(items, needsLocationChoice)`; `start(prepared, withoutLocation)` hands the items to `TransferService`. Used by the picker, "Send other files", `ShareReceiverActivity` and by instrumented tests (no picker needed) | §4.3 |
| `media/ContentSources.kt` | `ContentUploadSource` (`UploadSource` over a `ContentResolver` URI), `ContentRangeSource` (cast) | §4.3, §6 |
| `media/DownloadSinks.kt` | `MediaStoreDownloadSink` (29+), `FileSink` (APK cache), `cleanupLeftovers()`. No sink for API 25–28 [CP17] | §4.5 |
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
| `check_manifest.py` | Asserts every §3.6 entry (as amended by §4.11) in the manifest **of the built release APK**: its input is the text of `aapt2 dump xmltree --file AndroidManifest.xml app-release.apk` (what ships; no dependence on AGP's intermediates folders); a plain merged-manifest XML file is accepted as a second input format for local use [A1][A4][CP5] [PR14] [PA19] |
| `check_apk.py` | `apksigner verify --print-certs` SHA-256 == `signing/cert-sha256.txt` [B23], compared after **normalising both sides** (strip colons and spaces, lower-case) [PA19]; `lib/` entries: only `libandroidx.graphics.path.so` allowed, and whatever is under `lib/` must exist for `armeabi-v7a` and `arm64-v8a` [PA1] [PR1]; `aapt2 dump badging` versionCode/versionName == expected. Takes `--build-tools DIR`; the workflow passes the **highest version found** under `$ANDROID_HOME/build-tools/` (`ls -d … \| sort -V \| tail -1`), never a hard-coded `35.0.0` [PA7] [PR8] |
| `make_apk_json.py` | Writes `Ferry.apk.json` (`version_code`, `version_name`, `size`, `sha256`, `url`) |
| `run_instrumented.sh <phone\|tv\|api25>` | Host side of an emulator suite (CP6): start ferry.py instances, seed outboxes, install APKs, run the classes of `suites/<suite>.txt` in order, grants, pull screenshots/logcat, host checks, stop |
| `suites/phone.txt`, `tv.txt`, `api25.txt` | One line per test class: `Class \| host steps before (grants, fixture pushes) \| host check after` (`#` comments). The only place a phase edits to add a class [PA10] [PR20] |
| `verify_inbox.py` | Host-side assertions on the PC inboxes after the phone suite (GPS present / byte-identical; negative control) [B1] |
| `make_fixtures.py` | (run locally once, results committed) GPS JPEG, 5 slideshow JPEGs, a broken JPEG, `small.mp4` |
| `make_big_video.sh` | Generates the test video in CI (cached) [B13] with a fixed recipe [PA17] [PR7]: `ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30 -f lavfi -i sine=frequency=440 -t 300 -vf noise=alls=40:allf=t -c:v libx264 -preset ultrafast -pix_fmt yuv420p -b:v 6M -minrate 6M -maxrate 6M -bufsize 12M -x264-params nal-hrd=cbr -c:a aac -b:a 128k -movflags +faststart big.mp4` (noise + constant bitrate, otherwise a test pattern compresses to a few MB). The script then **asserts** size ≥ 200 MB and duration ≥ 290 s (`ffprobe`), so the file is far longer than the player's 50 s buffer and the 90 s pause really meets the PC's 60 s timeout |
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
| `--port 0` | Allowed (range check becomes `0 <= port <= 65535`); OS picks a free TCP port; after the bind `cfg.port` is updated from `server.server_port` **and `app.primary_url` is recomputed** (v1 builds it in `make_app()` before the bind, so banner, QR, `Outbox.url_base` and the Get-the-app page's fallback host would otherwise say port 0) [PB15] |
| `--discovery-port N\|0\|off` | Default `8765` **independent of `--port`** [B16]; `0` = free UDP port; `off` = no responder |
| `--data-dir DIR` | Sets `DATA_DIR` (created if missing) for `ferry-state.json`, `ferry-error.log`, `ferry-selftest.txt`, `ferry-log*.txt` |
| `--test-free-bytes N` | `Inbox.free_bytes()` returns `N` instead of `disk_usage().free` (info, precheck, check-batch, upload pre-check) [B11] |
| `--app-dir DIR` | Folder searched for `Ferry.apk` + `Ferry.apk.json` (default `APP_DIR`) |
After the bind and the discovery start, `main()` prints exactly one line `FERRY_READY port=<tcp> discovery=<udp|off>` through `Console.print` (the Console writer flushes after every line; verify, add `flush()` if missing). Tests always pass `--no-open --inbox DIR --outbox DIR --data-dir DIR`.

### 2.4 Request log `RequestLog` (C5, design §2.5)
```
class RequestLog:
    __init__(self, folder, console_note, max_bytes=LOG_MAX_BYTES, retry_step=LOG_RETRY_STEP, hard_cap=LOG_HARD_CAP,
             queue_max=LOG_QUEUE_MAX, replace=os.replace, opener=open, clock=time.time, poll_flush_s=LOG_POLL_FLUSH_S)
             # opener: injection point for the "blocking file" test [PB19]
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

**Handler instrumentation.** `_dispatch` sets `self._t0`, `self._status`, `self._nbytes`, `self._outcome`, `self._error`, `self._range = headers.get("Range")`; `send_response` is overridden to record the status; `send_bytes`, the file/zip/upload loops **and `drain()`** add to `_nbytes` (so the log shows how much of a refused body was read [PB4]); every place that calls `console.log(...)` also sets `_outcome` (e.g. `"saved IMG_1.jpg"`, `"renamed a.jpg -> a (1).jpg"`, `"skipped"`, `"sent"`, `"playing"`, `"cancelled"`, `"FAILED disk full"`). A `finally` in `_dispatch` calls `reqlog.poll()` for successful polling requests and `reqlog.request()` for everything else.

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
- Every call stats the APK and **reads the json file (≤ 64 KB)**; cache key = `(json bytes, apk size, apk mtime_ns)` [PB2]. The json's size and mtime are not enough: `package.py` gives every zip entry the same fixed timestamp and two builds can have APKs and jsons of equal size, so after unzipping a new Ferry over a running one a stat-only key might not change and Ferry would keep announcing the old version and sha. The json text always differs between builds (it holds the sha256), so it is the key *(whether Windows "Extract All" keeps zip times is uncertain → safe either way)*. On a new key (under a lock): validate `version_code` int > 0, `version_name` str, `size` int, `sha256` 64 lowercase hex; APK size must equal `size`, then stream-hash the APK (1 MB chunks) and compare. Mismatch → `"copying"` (cached under that key; a still-growing copy changes the key) [B26].
- `main()` **warms the cache in a background thread** right after the bind [PB24], so the first `/api/v1/info` from the phone (1.5 s last-known timeout) does not wait for a 25–30 MB hash on a cold disk.
- Routes (`ROUTES_EXACT`): `/app` and `/app/Ferry.apk` → `send_app()`; `/api/v1/app` → `api_app()`; `/api/v1/check-batch` → `api_check_batch()` (POST); `/` → `get_app_page()`; `/favicon.svg` → `favicon()`.
- `send_app()`: status `ok` → the shared file sender (below) with name `Ferry.apk`, `APK_MIME`, the expected size/mtime from the key; Range/If-Range/HEAD as the outbox. Otherwise HTML 404 page: "No Ferry app in this Ferry folder" / "The app is being copied, try again in a moment". `api_app()`: `ok` → 200 `{version_code, version_name, size, sha256, url:"/app/Ferry.apk"}`; missing/invalid → 404 `{"result":"error","message":"no app bundled"}`; copying → 404 `{"result":"error","message":"The app is being copied, try again in a moment"}`.
- **Refactor** `send_outbox_file` into `send_file(path, name, ctype, expect_size, expect_mtime_ns, log_name, gone_message)` used by both outbox and app routes (behaviour of the outbox route unchanged; v1 tests prove it).
- `api_info()` adds `"protocol": 2`, `"capabilities": CAPABILITIES`, `"app_version_code": meta["version_code"] | None`; `free_bytes` from `Inbox.free_bytes()`.
- **Get-the-app page** `GET_APP_HTML` (≈ 60 lines, inline CSS, no JS, phone and TV readable, Ferry blue): title "Get the Ferry app"; big link button "Download Ferry for Android" → `/app` (with `Ferry 2.0.N · 24.1 MB`); "On your phone: 1. Tap Download. 2. Open the downloaded file. If Android asks, allow your browser to install apps. 3. Open Ferry." "On Fire TV: in the Downloader app, enter `<host>/app`". `<host>` = the request's `Host` header if it looks like `ip[:port]`, else `primary:port`; HTML-escaped. No APK → the button is replaced by "This Ferry folder has no app. Get the latest Ferry zip." Served with `Cache-Control: no-store`; HEAD supported.
- `info` and the page read the bundle status per request (cheap: one `stat` and one small file read).

### 2.7 `POST /api/v1/check-batch` (design §2.3)
- Handler: Content-Length required (411), ≤ 1 MB (413 + drain), JSON object with `files` list ≤ 2000 (400 otherwise), each `name` non-empty str, `size` int ≥ 0, optional `mtime` int; then `Inbox.check_batch(files)`.
- `Inbox.check_batch(files) -> dict`: **one** `os.scandir(self.path)` and **one** `free_bytes()` call per request [B4]. The index only **narrows**; the decision is made by the **same regex `family()` uses** [PB3] (factor it into one helper `family_rx(name, is_win)` used by both, including `\d+` and `re.IGNORECASE`). A plain `lower()` key is *not* equivalent to `re.IGNORECASE` (prototype: 9 wrong answers in 30 names such as `İstanbul.jpg`, `Σίσυφος.jpg`; a wrong extra match could report "Already on PC" for a file that is not there). Coarse key (must be a superset of the regex): on Windows, ASCII-lower-case the name and replace every non-ASCII character **and the ASCII letters `i`, `k`, `s`** by one placeholder (only these three have non-ASCII case partners in Python's `re`: İ ı, K (Kelvin sign), ſ); off Windows the name itself. Each inbox file is indexed under the coarse key of its name and, if its stem ends in ` (<digits>)` (`\d+`, so non-ASCII digits count), also under the coarse key of the base name. Per item: `sanitize_name(raw, path, is_win)` → candidates = index[coarse key] **filtered by `family_rx(name).match`** → duplicate = first candidate with equal size and `|mtime − st_mtime| ≤ 2 s`; no `mtime` → `null`. Still O(n) for ordinary `IMG_…` names. Reply `{"ok": true, "results": [{"duplicate": name|null}…], "needed_bytes": sum(non-duplicate sizes), "free_bytes": max(0, free − DISK_MARGIN)}`; never refuses for space [B3].
- Console: one line [CP14]; log: normal request line.

### 2.8 Upload pre-check [CP3]
In `api_upload`, after the 411/400 checks: `free = inbox.free_bytes()`; if `free is not None and free < length + DISK_MARGIN` **and no member of `inbox.family(sanitize_name(name))` has `st_size == length`** [PB4] [PR18] → console `FAILED <name> PC disk is full`, then in this order [PB4]: `drain(length)` (v1's `drain` reads at most `DRAIN_LIMIT` = 64 MB and marks the connection for closing), then 507 `{"result":"error","reason":"disk_full","message":"PC disk is full"}`, then close. With a same-size family member the request continues into `receive()` unchanged (identical content → `skipped`; different content → written, with v1's `ENOSPC` → `DiskFull` path as the guard). The family scan only happens when space is short. `Inbox.free_bytes()` = test override or `shutil.disk_usage(self.path).free` (None on OSError); `precheck()` uses it too.

### 2.9 Console (design §2.7)
- `build_banner(cfg, url, qr_lines, outbox_count, app_line)`: header `FERRY  is running … v2.0.0`; "First time? Scan to install the Ferry app:" + QR of `http://<ip>:<port>/`; `App page:  http://<ip>:<port>/app` ; `Fire TV:   in Downloader enter <ip>:<port>/app`; Inbox/Outbox line as v1; `App: Ferry 2.0.57 is ready for your phone and TV` or `! No app in this folder (app\Ferry.apk) - get the full Ferry zip`; "Keep this window open. Ctrl+C to stop.   Help: README.txt". Same width/line budget as v1 (`test_banner_budget`).
- `build_extras`: IP-change notice becomes "! Your PC's address changed (was X). The Ferry app finds it by itself; if not, enter <ip> in Ferry > Settings." The "Bookmark-friendly name" line is removed. Discovery-off line comes from §2.5.
- `seen_client` / activity lines use the device label (§2.4).

### 2.10 `main()` order
parse args → set `DATA_DIR` → console/hooks → `State.load(data_path(...))` → folders → addresses → `make_app` (adds `app.bundle = AppBundle(args.app_dir)`, `app.reqlog = RequestLog(DATA_DIR, console.note)`, `app.free_override`) → bind (port 0 OK) → `cfg.port = server.server_port`, recompute `app.primary_url` [PB15] → start the bundle warm-up thread [PB24] → reqlog.start + event "Ferry 2.0.0 started …" → discovery (unless off) → `FERRY_READY …` → banner/extras → threads as v1 → serve → `finally`: discovery.stop, outbox.stop, server_close, cleanup temps, reqlog event "stopped" + stop, console stop.

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
| `test_platform.py::test_main_starts_and_stops_quickly` | monkeypatch `ferry.win_known_folder` to return `tmp_path/<Downloads\|Desktop>` and `DATA_DIR` to `tmp_path`; assert on the folders the patched lookup returns (works on both OSes) |

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
| `Srv` fixture | moves to `tests/helpers.py`; gains `app_dir`, `free_override`, a `RequestLog` in a tmp folder, **stopped (handle closed) in teardown** [PB19] |

### 2.14 New PC tests (file · test · asserts)
**`test_check_batch.py`**
- `test_results_in_input_order` — 5 items, mixed duplicates, `results[i]` matches item i.
- `test_duplicate_needs_size_and_mtime_within_2s` — ±2 s duplicate, ±3 s not, size differs not, missing `mtime` → null.
- `test_needed_bytes_excludes_duplicates` [B3] — needed = sum of non-duplicates; all-duplicate resend → 0.
- `test_never_refuses_for_space` [B3] — `free_override=1` → 200 `ok:true`, `free_bytes == 0`.
- `test_same_answers_as_precheck` — 200 generated names (case variants, `name (3).jpg`, `name (1) (2).jpg`, unicode, reserved names, **and the case-folding traps `İstanbul`/`istanbul`/`i̇stanbul`, `ı`, `Σίσυφος`/`ΣΊΣΥΦΟΣ`/`σίσυφοσ`, `ſ`, `K` (Kelvin sign), `a (٣).jpg`** [PB3]) × `is_win` True/False: `check_batch` duplicate == `precheck` duplicate for every name.
- `test_one_scan_one_disk_usage` [B4] — counters on `os.scandir` / `free_bytes`: exactly 1 each for a 500-item request.
- `test_10k_inbox_time_budget` [B4] — 10 000 files in the inbox, 2 000-item request answered in < 3 s locally and **< 15 s when `CI` is set** (shared Windows runners are slow and uneven; the counter test above is the real guard, this one only catches an accidental O(n²)) [PR21].
- `test_limits_and_errors` — 2 001 items → 400; body > 1 MB → 413; bad JSON / bad item → 400; GET → 405; no Content-Length → 411.
- `test_console_one_line` [CP14].

**`test_app_bundle.py`** (B26, C3)
- `test_no_bundle` — `/api/v1/app` 404 JSON "no app bundled"; `/app` 404 HTML; `info.app_version_code` null; `/` says the folder has no app.
- `test_valid_bundle` — json fields echo; `/app` and `/app/Ferry.apk` bytes equal, `Content-Type` APK MIME, `Content-Disposition` `Ferry.apk`; `Range: bytes=0-9` → 206; HEAD length; `info.app_version_code == N`.
- `test_invalid_json` — garbage, missing field, `version_code: 0`, bad sha → 404 "no app bundled".
- `test_sha_or_size_mismatch_is_copying` — APK changed but json not → both routes 404 "being copied"; nothing served.
- `test_replaced_while_running` — write v1 pair → ok; overwrite APK only → copying; write matching json → new version without restart.
- `test_hash_only_when_key_changes` — counting hasher: 10 requests → 1 hash; json rewritten with different content → 2; json merely touched (same bytes) → still 2.
- `test_same_size_same_mtime_replacement` [PB2] — replace APK and json by a new pair of **identical sizes**, then `os.utime` both back to the old times → `/api/v1/app` reports the new `version_code`/`sha256` and `/app` serves the new bytes, without a restart.
- `test_warmup_thread` [PB24] — after start the hasher has run once before any request.

**`test_get_app_page.py`** — `test_page_content` (title, `/app` link, version and size, Downloader line with the Host header), `test_host_header_escaped` (`Host: <b>x` → escaped/fallback), `test_head`.

**`test_discovery.py`** (C2)
- `test_reply_on_loopback` — responder on port 0; datagram from a client socket → reply JSON: nonce echoed, `device_type "pc"`, `port` = TCP port, `protocol 2`, `version`.
- `test_garbage_ignored` — not JSON, wrong `app`/`q`, missing/odd nonce, 600-byte datagram → no reply within 0.3 s; responder still answers a valid one afterwards.
- `test_rate_limit` — 40 requests in a burst → ≤ 20 replies.
- `test_loop_survives_connection_reset` [B2] — fake socket: `recvfrom` raises `ConnectionResetError`, then `OSError`, then returns a valid request → reply sent; thread alive.
- `test_bind_failure_tolerated` [B16] — port already bound by another socket **on `0.0.0.0`, the same address the responder uses** (wildcard-vs-specific bind rules differ between Windows and Linux) [PB19] → `start()` False, console line "Automatic discovery is off. On the phone, enter …", HTTP server still answers.
- `test_udp_port_independent_of_tcp_port` [B16] — `parse_args(["--port","8800"]).discovery_port == 8765`; reply's `port` field is the TCP port.
- `test_stop_within_a_second`; `test_reply_logged_once_per_minute` (fake clock).

**`test_request_log.py`** (C5)
- `test_line_format` — 11 tab fields; query values dropped except `name`; tab/newline in a name replaced.
- `test_transfers_logged` (via `Srv`) — upload → `saved` line with bytes and ms; outbox Range GET → `bytes=0-9`, 206; zip download; cancelled download.
- `test_errors_always_logged` — 404, 405, 411, 507, 500 each produce a line.
- `test_polling_collapsed` [B28] — 50× outbox + 20× info from one device → 0 request lines; `flush` → one "70 list refreshes" line for that device; `POST /api/v1/info` (405) and `GET /api/v1/infox` (404) are still logged in full (a query string does not change the route, so `/api/v1/info?x` is a 200 and is collapsed) [PB19].
- `test_writer_never_blocks` [B9] — writer stalled on a blocking fake file (injected through `opener=` [PB19]); 20 000 `request()` calls return in < 1 s; later line reports the dropped count.
- `test_rotation` — > 5 MB → `.1` exists, current small; `.1` already present → replaced.
- `test_rotation_replace_fails_keeps_appending` [B9] — injected `replace` raising `PermissionError`: file grows past 5 MB, retry at +1 MB, truncated at 10 MB, console note once, no line lost before truncation.
- `test_rotation_with_other_handle_open` [B9] — a real second handle open on the log: on Windows rotation fails and logging continues; on Linux rotation succeeds; on both no exception and every line is in `log` or `log.1` (the test writes less than the 10 MB hard cap, above which truncation is by design [PB19]).
- `test_rotation_checked_at_start`.
- `test_device_name_percent_decoded` [B10] — header `Saurabh%E2%80%99s%20phone%20%F0%9F%93%B1` → log and console show `Saurabh’s phone 📱`.
- `test_device_header_sanitised` — controls stripped, 200 chars → 60, bad escapes → replacement char; missing header → UA class.
- `test_start_stop_events`.

**Log assertions never race the writer thread** [PB12]: every test that reads `ferry-log.txt` (here and in `test_upload_v2.py`) first sends a sentinel request that is always logged (`GET /api/v1/__sentinel_<uuid>` → 404), waits with `helpers.await_log()` until that line is in the file (≤ 5 s), and asserts only on the lines before it.

**`test_hooks.py`** (subprocess `ferry.py` through `start_ferry_process()`, whose reader thread makes it work on Windows [PB19]) [B24]
- `test_port0_ready_line` — `--port 0 --discovery-port off` → stdout `FERRY_READY port=N discovery=off` within 10 s; `GET /api/v1/info` on N works.
- `test_port0_page_shows_real_port` [PB15] — `--port 0`, raw HTTP/1.0 `GET /` **without a `Host` header** → the page's Downloader line shows port N, not 0.
- `test_discovery_port_zero_reported` — `discovery=M`, a datagram to M gets a reply.
- `test_data_dir_holds_runtime_files` — state and log in `--data-dir`, nothing new next to `ferry.py`.
- `test_test_free_bytes` — `info.free_bytes == N`; check-batch `free_bytes == max(0, N − 64 MB)`; 1 MB upload with N = 10 MB → 507.
- `test_app_dir` — bundle read from `--app-dir`.
- `test_hidden_flags_not_in_help`.

**`test_upload_v2.py`** — `test_info_protocol_2`; `test_upload_precheck_507_small_body` [CP3] (body read fully, 507 JSON); `test_upload_precheck_large_body_closes` (100 MB declared, free override 10 MB, raw socket client that counts what it managed to send: the client may see either the 507 or a reset, **and** the request-log line for this upload (read after a sentinel) has status 507 and `bytes` ≤ 64 MB + 1 MB, i.e. the server did not read the whole body [PB4]; inbox has no temp file) [B11]; `test_precheck_skipped_for_same_size_family` [PB4] [PR18] (free override 1 MB; a 2 MB file already in the inbox; uploading identical bytes → 200 `skipped`; uploading the same name with a **different** size → 507).

**`test_removed_routes.py`** [B25] — `test_removed_routes_404`: `/diag`, `/qr`, `/new`, `/1`, `/index.html`, `/manifest.webmanifest`, `/icon-192.png`, `/icon-512.png`, `GET /api/v1/diag/blob`, `GET /api/v1/diag/test.zip` → 404 (JSON for `/api/…`, HTML otherwise); `POST /api/v1/diag/sink` → 405 JSON; `test_v1_page_code_gone` (`not hasattr(ferry, "PAGE_HTML")`, no `ASSETS_B64`, no `BEGIN GENERATED` marker).

**`test_package.py`** [B25] — builds into tmp with a fake app pair: zip contains `Ferry/app/Ferry.apk` (bytes identical) and the json, CRLF on `.txt/.bat`, LF on `.py/.sh`, deterministic (two builds, same sha); missing APK → exit 1; sha mismatch → exit 1; name `Ferry-2.0.99.zip`.

## 3. Android `:core` (pure Kotlin/JVM)

### 3.1 Build (`android/core/build.gradle.kts`)
Exact Kotlin-DSL forms (the rev 1 text was shorthand that does not compile) [PA11]:
```kotlin
plugins { id("org.jetbrains.kotlin.jvm"); id("org.jetbrains.kotlin.plugin.serialization") }
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
tasks.withType<JavaCompile>().configureEach { options.release.set(8) }
kotlin { compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    freeCompilerArgs.add("-Xjdk-release=1.8")                       // [CP9]
} }
dependencies {
    api(libs.okhttp); api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)                             // api, not implementation [PA11] [PR5]
    testImplementation(libs.junit); testImplementation(libs.kotlinx.coroutines.test)
}
tasks.test {
    systemProperty("ferry.pcDir", rootDir.parentFile.resolve("pc").absolutePath)
    systemProperty("ferry.python", System.getenv("FERRY_PYTHON")
        ?: if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3")
    systemProperty("ferry.requirePython", System.getenv("CI") ?: "false")
    maxParallelForks = 1
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    timeout.set(java.time.Duration.ofMinutes(20))
}
```
`settings.gradle.kts`: `include(":core", ":app")` (later `":sharehelper"` [CP21]); it already has `FAIL_ON_PROJECT_REPOS` with `google()` + `mavenCentral()`, so modules declare no repositories. The root `build.gradle.kts` **keeps its three existing plugin lines** (`com.android.application`, `kotlin.android`, `kotlin.plugin.compose`) and **gains** `org.jetbrains.kotlin.jvm` and `org.jetbrains.kotlin.plugin.serialization`, both `version "2.0.21" apply false` [PR17].

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
    // connectTimeout 5 s, writeTimeout 60 s, readTimeout 30 s [PB11], retryOnConnectionFailure(false),
    // interceptor: X-Ferry-Device = PercentEncode.utf8(deviceName) [B10]; User-Agent "Ferry-Android/<ver>"
    // Only upload() waits longer: it uses http.newBuilder().readTimeout(10, MINUTES).build() (shares the pool),
    // because the PC may be hashing a same-named large file. With 10 min everywhere, a silent Wi-Fi stall would
    // hang a download or the TV's list refresh for 10 minutes before "waiting for PC" could start [PB11].
}
interface FerryApi {
    val base: HostPort
    suspend fun info(timeoutMs: Long = 5_000): PcInfo                  // throws FerryException.OldPc when protocol < 2 [B15]
    suspend fun checkBatch(files: List<CheckFile>): CheckBatchReply    // 60 s call timeout [B4]
    suspend fun upload(item: UploadItem, opened: OpenedSource, onProgress: (Long) -> Unit): UploadReply
    suspend fun outbox(): OutboxList                                    // 10 s call timeout [PB11]
    suspend fun appInfo(): AppInfo?                                     // null on 404; 10 s call timeout [PB11]
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
**Order of checks in `writeTo` (data safety)** [PB1]: the PC commits a file as soon as it has read exactly `Content-Length` bytes (v1 `receive()` never reads further). So "the source has more than `size` bytes" must be detected **before the last byte of `size` is handed to the sink**, never after. Rule: the body always holds back the most recently read chunk; when the bytes read so far reach `size`, it does one more `read()` on the source; a result ≥ 0 (the file grew) → throw `SourceChanged` with the held chunk unwritten. The PC then sees a short body, treats it as an aborted upload and deletes its temp file; the engine refreshes size/date and retries (step 5). Coded the naive way (send `size` bytes, then look for more), a file that is still being written would be saved on the PC as a **truncated `name.ext`** and then again in full as `name (1).ext`. `FerryClient` unwraps a `SourceChanged` thrown inside `writeTo` from OkHttp's wrapper exception, so it does not turn into `ConnectionLost`.

```kotlin
// ---------- upload engine (design §4.4)
interface UploadSource {
    val displayName: String
    fun open(): OpenedSource                               // throws FerryException.SourceUnreadable; a source built for the
                                                           // original (requireOriginal) throws OriginalUnreadable and NEVER
                                                           // falls back to the redacted stream [PB5]
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
enum class FailReason { ConnectionKeptDropping, FileChanged, CantRead, CantReadOriginal /* [PB5] */, PcError, BadRequest }
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
4. Two worker coroutines take the next `Waiting` row in selection order. **Big-file gate** [H15] [PB14]: rows are **claimed** strictly in selection order under the mutex. When the next row is > 256 MB, the worker that reaches it claims it at once; from that moment the other worker **finishes its current file and claims nothing** until the big row has ended (saved, failed or back to `Waiting`); the claiming worker starts sending as soon as the other is idle. So a big file is never overtaken by the small files behind it (no starvation, selection order kept) and the two workers never wait on each other (no deadlock).
5. Per upload: `source.open()` (failure → `Failed(CantRead)`; `OriginalUnreadable` → `Failed(CantReadOriginal)`, the row is **not** sent from the plain URI [PB5] [H19]); `opened.length` ≥ 0 and ≠ `size` → `refresh()` once, rebuild the row, retry; second mismatch → `Failed(FileChanged)` [B5]. Reply `saved/renamed/skipped` → `Saved` / `Saved(renamed=true)` / `AlreadyOnPc`, except `skipped` after an earlier attempt of this row reached `bodyFullySent` → `Saved` [B18]. `DiskFull` (507) → stop `DiskFull(null)`. `Http 400/411` → `Failed(BadRequest)`; `Http 500` → `Failed(PcError, server message)`. `SourceChanged` → same as the size mismatch path.
6. `ConnectionLost`/`Unreachable`/`Timeout` during an upload: if bytes were in flight, first `info()`: reachable and `free_bytes < size + 64 MB` → stop `DiskFull` [B11]; reachable → `netFailures++` (3 → `Failed(ConnectionKeptDropping)`) and the row goes back to `Waiting`; unreachable → row back to `Waiting` and the engine enters **WaitingForPc**: workers suspend on a `pcAvailable` `MutableStateFlow<Boolean>`; one prober coroutine calls `info()` every 3 s and `rediscover()` every 30 s; success reopens the gate; after 30 min all remaining rows → `NotSent(PcNotFound)` and `Finished` [B20].
7. `cancel()`: rows not started → `Cancelled`; in-flight rows whose body is fully sent wait ≤ 10 s for the reply (`Saved` if it comes, else `Cancelled`) [B18]; other in-flight requests are cancelled (socket closed).
8. State is published through a conflated internal flow, sampled every 250 ms (`EngineState`); speed = bytes over the last 3 s; ETA = remaining bytes / speed.

```kotlin
// ---------- downloads (design §4.5)
interface DownloadSink { fun open(): java.io.OutputStream; fun commit(): String; fun abort() }   // commit returns the final name
// Contract [PB17]: the queue counts bytes after each write(); when the body ends it calls stream.close() FIRST (sinks flush
// and fd.sync() in close()), then compares bytes written with the expected size, and only then commit(); any failure or
// mismatch → close() then abort(). commit() is never called on an open stream.
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
// A silent stall counts as a drop after the client's 30 s read timeout [PB11].

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
    suspend fun locate(): LocateResult      // manual/chosen (1.5 s) → last-known (1.5 s) → broadcast → verify /info → store.
                                            // A manual/chosen address that does not answer FALLS THROUGH to the next steps;
                                            // if the broadcast then finds a PC, it becomes last-known and the dead manual
                                            // entry is cleared, so a moved PC is found again by itself [PB22] [H35]
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
    suspend fun cast(req: CastRequest); suspend fun control(c: ControlRequest); suspend fun state(): CastState   // 10 s call timeout each [PB11]
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
    class OriginalUnreadable(cause: Throwable?) : FerryException("can't read the original (location)", cause)   // [PB5]
    class NotEnoughSpace(val needed: Long, val free: Long) : FerryException("not enough space on the phone")
}
```
Mapping rule in `FerryClient`: `ConnectException`, `NoRouteToHostException`, `UnknownHostException`, `PortUnreachable`, socket "ENONET/ENETUNREACH" → `Unreachable`; `SocketTimeoutException` → `Timeout` with the phase known from the body state; other `IOException` after the connection opened → `ConnectionLost`. The UI never shows exception text; `:app` maps every case to a string from §4.12.

### 3.5 API-25 rules for `:core` [A3] [CP9]
Allowed: Kotlin stdlib (no `kotlin.io.path`), `java.io`, `java.net`, `java.util`, `java.util.concurrent`, `java.security.MessageDigest/SecureRandom`, `System.currentTimeMillis/nanoTime`, OkHttp 4.12, kotlinx. Forbidden (enforced by `ApiLevelScanTest`, which reads every compiled `.class` of `:core` main and fails on constant-pool references): `java/time/`, `java/nio/file/`, `java/util/Base64`, `java/util/HexFormat`, `java/lang/invoke/VarHandle`, `java/util/concurrent/Flow`, `kotlin/io/path/`, method refs `java/util/List.of|copyOf`, `java/util/Map.of|entry`, `java/util/Set.of`, `java/lang/String.repeat|isBlank|strip|lines`, `java/io/InputStream.transferTo|readAllBytes|readNBytes`, `java/util/Optional.isEmpty|orElseThrow()`, and the Java 8 methods that Android only got at API 26 [PA8]: `java/lang/String.join`, `java/lang/Integer|Long.toUnsignedString|toUnsignedLong|parseUnsignedInt|parseUnsignedLong|compareUnsigned|divideUnsigned|remainderUnsigned`, `java/lang/invoke/MethodHandles` (not `LambdaMetafactory`: Kotlin lambdas use it and D8 desugars it). `-Xjdk-release=1.8` already rejects Java 9+ API calls at compile time; the scan also catches Java 8 APIs that only arrived on Android 26. No `android.*` import (scan for `android/`). **The list is best-effort, not complete** [PA8]: the real proof is `CoreOnApi25Test` running `:core` on the API 25 emulator (§4.14), plus desugaring in `:app`; `:app` itself is covered by lint `NewApi` [CP22].

### 3.6 Integration harness against real `ferry.py` [B24]
`FerryPcProcess` (test source, `app.ferry.core.it`):
- `start(freeBytes: Long? = null, discovery: Boolean = false, appDir: File? = null): FerryPcProcess` → temp root with `in/`, `out/`, `data/`; runs `<python> -u <pcDir>/ferry.py --no-open --port 0 --discovery-port <0|off> --inbox in --outbox out --data-dir data [--test-free-bytes N] [--app-dir D]` with `PYTHONIOENCODING=utf-8`; reads stdout until `FERRY_READY port=N discovery=M` (15 s timeout; on timeout the test fails with the captured output); a daemon thread drains the rest of stdout/stderr into a buffer (attached to failures).
- `base: HostPort("127.0.0.1", N)`, `discoveryPort`, `inbox`, `outbox`, `log: File`.
- `close()`: `destroy()`, `waitFor(5 s)`, `destroyForcibly()` fallback, then delete the temp root, ignoring Windows "file in use" errors (retry 3× 200 ms).
- Python missing: `Assume` skip locally, **fail** when `ferry.requirePython=true` (CI).
- Ports: TCP always `--port 0`, UDP `--discovery-port 0`; no fixed ports, so ubuntu and windows jobs never collide.
- `awaitLogUpTo(): List<String>` [PB12]: the PC log is written by a background thread, so a test must not read it directly. This helper sends `GET /api/v1/__sentinel_<uuid>` (404, always logged), polls `ferry-log.txt` until that line appears (≤ 5 s, else fail) and returns the lines before it. Every log assertion goes through it.
- `TcpRelay` (test source, ≈ 60 lines) [PB8] [PR16] [PA19]: a loopback TCP forwarder between the client under test and `ferry.py`, with modes `pass`, `reset` (close every connection and refuse new ones) and `blackhole` (accept, forward nothing, never answer). "The PC goes away" is simulated by switching the relay, **not** by killing and restarting `ferry.py` on the same port: on Windows `ferry.py` binds with `SO_EXCLUSIVEADDRUSE` and a rebind right after a kill can fail while old connections sit in `TIME_WAIT` *(uncertain → the relay is safe either way)*. The relay also gives the one case a restart cannot: a **silent stall** (no RST), which is what a real Wi-Fi drop looks like.

### 3.7 `:core` JVM tests (`android/core/src/test/kotlin/...`)
**Unit (no network)**
| Class | Tests (what each asserts) |
|---|---|
| `JsonModelsTest` | parse real v2 replies captured from ferry.py (info, check-batch, upload, outbox, app, error); unknown keys ignored; `mtime` omitted when null in `CheckFile` [B17] |
| `HostPortTest` | parse forms, default port, IPv4 only, junk → null, `toString` |
| `PercentEncodeTest` | `Saurabh’s phone 📱` → ASCII-only, round-trips with Python's `unquote` vector [B10] |
| `DiscoveryCodecTest` | request/reply round trip; nonce mismatch → null; > 512 B → null; non-JSON → null |
| `RangeParserTest` | the same vectors as `ferry.test_parse_range_unit` |
| `StreamingBodyTest` | 256 KB chunks; progress monotonic; short stream → `SourceChanged`; extra bytes → `SourceChanged` **and the sink has received fewer than `size` bytes at that moment** (sizes: 1 byte, exactly one chunk, several chunks + 1; this fails with the naive "send, then look" order) [PB1]; `bodyFullySent` only after the last byte |
| `UploadEngineTest` (fake `FerryApi` + `runTest`) | `happyPathTwoWorkersInSelectionOrder`; `duplicatesMarkedAlreadyOnPcBeforeSending`; `allDuplicateResendNeedsZeroBytes` [B3]; `neededMoreThanFreeStopsBeforeSending` [B3]; `checkBatchHttpErrorUploadsUnchecked` [B4]; `checkBatchTimeoutWithPcReachableUploadsUnchecked` [B4]; `networkDropEntersWaitingAndResumes`; `waitingForPcEndsAfter30MinWithNotSent` [B20]; `rediscoversEvery30sWhileWaiting`; `threeNetFailuresWhileReachableFails`; `diskFull507StopsQueue`; `midBodyDropWithLowFreeIsDiskFull` [B11]; `bodySentThenSkippedCountsAsSaved` [B18]; `cancelWaitsForBodySentReply` [B18]; `cancelAbortsInFlight`; `fileChangedRefreshesOnceThenFails` [B5]; `sizeMismatchAtOpenRefreshes` [B5]; `sendAnywaySkipsPrecheck` [A2]; `withoutLocationCountedInSummary` [A2]; `oldPcStopsWithNothingSent` [B15]; `bigFileRunsAlone` [H15]; `bigFileKeepsItsPlaceInOrder` [PB14] (queue [small, BIG, small×10]: BIG starts as soon as the first small one ends, no other request starts before BIG has ended, and the run finishes, i.e. no deadlock); `originalUnreadableFailsAndIsNotSent` [PB5] (source throws `OriginalUnreadable` → `Failed(CantReadOriginal)`, zero upload calls for that row); `addWhileRunningCombinesSummary`; `stopForTimeLimitMarksNotSent` [A6]; `progressThrottledTo250ms`; `speedIsThreeSecondAverage` |
| `DownloadQueueTest` | one at a time; length mismatch → `abort` not `commit` [B7]; drop → abort + restart into a new sink [H20]; cancel → abort; `NotEnoughSpace` → Failed; a recording sink sees `close` **before** `commit`, and `close` before `abort` [PB17] |
| `PcLocatorTest` (fake `FerryApi` factory + fake discovery) | last-known first, no UDP when it answers; `manualDeadFallsThroughToBroadcast` → `Found`, manual entry cleared, last-known updated [PB22]; old PC → `OldPc`; nothing answers → `NotFound` |
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
| `engineEndToEndSmallBatch` | 30 files (1–300 KB) through `UploadEngine` with 2 workers → all saved; resend → all `AlreadyOnPc` and **zero `POST /api/v1/upload` calls counted by a recording OkHttp interceptor** in the test client; `ferry-log.txt` read through `awaitLogUpTo()` is the second witness (no upload lines) [PB12] |
| `growingFileIsNeverSavedTruncated` [PB1] | a source that reports `size` N at open but yields N + 1 bytes on the first attempt (N on `refresh()` + 1, stable afterwards) → the inbox ends with **exactly one** file, holding the full N + 1 bytes; no `name (1).ext`, no file of length N, no temp file |
| `deviceNameWithCurlyQuoteAndEmoji` [B10] | `Saurabh’s phone 📱` appears decoded in `ferry-log.txt` (via `awaitLogUpTo()` [PB12]) |
| `parallelCaseClashDifferentContent` [B14] | two workers send `IMG_0001.jpg` and `img_0001.JPG` with different bytes at once → both kept (on Windows as `IMG_0001.jpg` + `img_0001 (1).JPG` or equivalent), none overwritten |
| `parallelIdenticalContent` [B14] | two workers send identical `a.jpg` at once → one file, the other reply `skipped` |
| `diskFullLargeUpload` [B11] | `--test-free-bytes 10485760`; 100 MB upload through the engine → stop `DiskFull`, no retry loop, no temp file left |
| `diskFullSmallUpload` | 1 MB upload with free 10 MB → 507 → `DiskFull` |
| `checkBatchSaysNotEnoughSpace` [B3] | free override small, 3 new files → engine stops before sending (`DiskFull(missing)`) |
| `cancelMidUploadLeavesNoTemp` | cancel at ~50 % → no `.ferry-*.part` in the inbox after 1 s |
| `pcGoesAwayAndComesBack` [PB8] | engine → `TcpRelay` → ferry.py; mid-batch the relay goes to `reset` for 8 s, then `pass` → engine shows WaitingForPc, resumes, all files saved exactly once (real-time, ≤ 60 s; runs on ubuntu **and** windows) |
| `silentStallIsNoticedAndRecovered` [PB8] [PB11] | relay goes to `blackhole` mid-upload (test client built with write timeout 3 s / read timeout 3 s so it runs in seconds), then `pass` → the row is retried and saved once; a download stalled the same way is aborted and restarted from zero, no partial file committed |
| `pcRestartOnSamePort` (**Linux only**, `Assume` not Windows) [PB8] [PR16] | kill ferry.py mid-batch, restart with `--port <old>` (harness retries the start for 15 s) → engine resumes, all files saved exactly once |
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
- `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`. (`testOptions.animationsDisabled` has no effect on a host-run `am instrument`; animations are switched off by the emulator runner's `disable-animations: true` [PA16].)
- **Lint** [PA8] [CP22]: `lint { checkOnly += setOf("NewApi"); abortOnError = true; checkReleaseBuilds = true }`; `app-build` runs `:app:lintDebug`. `NewApi` is the judge of API-level facts: if it reports a call this plan placed at a lower level (see §4.7 `getMediaUri` [PR3]), raise the guard to the level lint names and note it in `docs/v2-progress.md`; never suppress it. If `lintVitalRelease` (run by `assembleRelease`) fails on something that is not a real fault, record it in the decisions log before changing lint settings [PA3].
- **Opt-ins** [PR15] [PA15]: Media3 files carry `@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)`; material3 experimental composables carry `@OptIn(ExperimentalMaterial3Api::class)` (a compile error without it).
- Dependencies per §0.3; `implementation(project(":core"))`.

### 4.2 Startup, mode and navigation
- `FerryApp.onCreate`: `AppGraph.init(this)`; channels (26+); `AppGraph.cleanup: Deferred<Unit>` = `DownloadSinks.cleanupLeftovers()` + deleting `filesDir/apk/*` on IO [B7] [PB18]; **`DownloadQueue` and the TV APK download await it before creating their first sink**, so the start-up cleanup can never delete a download that has just begun [PB16]; RingLog "app start <version> <device> API <n>".
- `DeviceMode.isTv(ctx)`: `UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION || pm.hasSystemFeature(FEATURE_LEANBACK)`. Debug builds only (`BuildConfig.DEBUG`): intent extra `app.ferry.debug.UI = "tv"|"phone"` overrides; extra `app.ferry.debug.CAST_RECEIVER = true` starts `CastReceiverHost` in phone mode [B27].
- `MainActivity`: phone → `enableEdgeToEdge()` + `FerryTheme { PhoneRoot(navigator) }`; TV → `TvTheme { TvRoot(navigator) }`. Intent extras `open = "transfer" | "remote" | "fromPc"` (from notifications) push that screen [A24].
- `NetworkBinder` rule [A9]: prefer a Wi-Fi `Network`, else Ethernet (TV adapters), else the active network if it is **not** cellular (emulators, unusual TVs); only "no network" or "cellular only" gives "Your phone is not on Wi-Fi". The decision is a pure function `chooseNetwork(candidates, anyNetworkOverride)` with a unit test (`NetworkChoiceTest`). **Debug builds only** [PR2]: the instrumentation argument / intent extra `app.ferry.debug.NET = "any"` makes it use the active network whatever its transport. The host script passes it on the **API 25** suite, where the emulator's only network may be reported as cellular *(uncertain → Ph0b logs the transports of all three images; the override is safe either way)*. The product rule is unchanged. The shared `OkHttpClient` (core `FerryHttp.newClient(network.socketFactory, deviceName)`) is rebuilt when the chosen network changes; the old one is left to finish in-flight calls.
- `Navigator` [CP1]: `val stack: SnapshotStateList<Screen>`, `push`, `pop(): Boolean`, `replaceAll`; each screen composable installs `BackHandler(enabled = stack.size > 1) { pop() }`; on Home, Back finishes the activity (TV: also stops the cast receiver via `onStop`). `android:enableOnBackInvokedCallback` is left unset (targetSdk 35 default) [A25].

### 4.3 Phone screens and ViewModels (design §4.1)
| Screen (composable) | ViewModel / state source | Contents and behaviour |
|---|---|---|
| `WelcomeScreen` | `SettingsViewModel` | Shown until `Prefs.welcomeDone`. What Ferry does (3 lines), the **battery card** (if not ignoring optimisations): "vivo may stop Ferry in the background…" → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with `package:` URI (try/catch → open battery settings) [P10] [H23]; "Continue" |
| `HomeScreen` | `HomeViewModel` (`connection`, `transfer`, `downloads`, `update`, `outboxCount`, interrupted marker) | Header "→ Saurabhs-PC" + status dot (Connected / Looking for your PC… / PC not found + Try again + Enter address / not on Wi-Fi / older Ferry); buttons **Send photos & videos**, **Send other files (documents, APKs…)**, **From PC** (badge), **Play on TV**; banners: update ("Ferry 2.0.7 is available → Update"; disabled with "Update after this finishes" while a transfer/download/cast runs [B21]), partial-media access, interrupted transfer + vivo steps [A8], ongoing transfer card |
| `PickerScreen` (`mode = Send \| CastVideo \| CastPhotos`) | `PickerViewModel` (rows, albums, `selection: LinkedHashSet<Long>` [A17]) | Tabs Recent / Albums; `LazyVerticalGrid` with date headers as full-span items (`item(span = { GridItemSpan(maxLineSpan) })`) that scroll with the photos, **not sticky** [PR10] [CP20] (Recent); album list → album grid with **Select all**; tap toggles; video badge with duration; bottom bar "12 selected · 1.4 GB" + Send; CastVideo = single select, video only; CastPhotos = images only |
| `TransferScreen` | `TransferViewModel` (`AppGraph.transfer`) | Overall bar, "Sending 5 of 20 · 18.2 MB/s · about 1 min left", rows with per-status text (§4.12), Cancel (confirm), "Waiting for Saurabhs-PC… (continues automatically)" + Cancel; summary with Retry / **Send anyway** on "Already on PC" [A2] |
| `FromPcScreen` | `OutboxViewModel` | Outbox list (icon by kind, name, size, age), refresh every 5 s while visible + pull-to-refresh (`material3` `PullToRefreshBox`, needs `@OptIn(ExperimentalMaterial3Api::class)`; if it gives trouble, replace it by a Refresh icon button, the 5 s refresh already covers the need [PA15]), tap → download, **Download all**, per-row progress, then **Open** / **Install**. On API < 29 the list is shown with the line "Downloading from the PC needs Android 10 or newer" and no download action [CP17] |
| `PlayOnTvScreen` | `CastViewModel` | Finds TVs (`PcLocator.findTvs()`); remembered last TV used directly if it answers [A24]; none → "On the TV, open Ferry and leave it on its home screen" [A10]; then **Videos** / **Photo slideshow** → picker |
| `RemoteScreen` | `CastViewModel` (polls `state` 1 s while visible) | Title, position/duration, ⏮ ⏯ ⏭ ⏹, −10 s / +10 s (video), interval 3/5/10 s (photos) |
| `SettingsScreen` | `SettingsViewModel` | PC (found list, manual address field with `HostPort.parse` validation, "Find again"), "Android battery setting: OK / Fix" + "Still stopping?" steps [A8], slideshow default, version, **Share diagnostics log** (`ACTION_SEND` of the RingLog file via FileProvider `logs/`) |
| `LocationChoiceDialog` | caller | "Ferry isn't allowed to read photo locations. **Allow** / **Send without location**" [H19] |

ViewModels are created with a tiny `viewModelFactory { initializer { … AppGraph … } }`; no DI library.

### 4.4 Services
**`TransferService`** (FGS `dataSync`):
- Intents: `ACTION_SEND_PICKS` (takes the **prepared items** from `AppGraph.pendingPicks` [CP8]), `ACTION_SEND_URIS` (URIs in `ClipData` + `FLAG_GRANT_READ_URI_PERMISSION`; extras `source = share|documents` and `requireOriginal: BooleanArray`, one flag per URI, decided by the UI-side probe), `ACTION_SEND_ANYWAY` (ids), `ACTION_RETRY`, `ACTION_CANCEL`, `ACTION_DOWNLOAD` (outbox `OutFile` as JSON string), `ACTION_CANCEL_DOWNLOADS`.
- **The location question is settled before the service starts** [PB5] [H19]. A service has no screen, so it must never be the place where an original turns out to be unreadable and something has to be decided. `SendCoordinator.prepare()` (§4.7) runs in the picker's ViewModel, in the "Send other files" result handler and in `ShareReceiverActivity`, on `Dispatchers.IO` with a short "Preparing…" state: it builds the items and probes each original by opening it. If any item needs the choice, `LocationChoiceDialog` is shown there (Allow → request the permission and prepare again; Send without location → those items become `original = false` on the plain URI). Only then `SendCoordinator.start()` starts the service.
- In the service a source marked `requireOriginal` is opened with `setRequireOriginal` only. If that open fails later (permission taken away meanwhile, provider error), the row is `Failed(CantReadOriginal)` "Can't read this photo's location. Send it again"; it is **never downgraded to the plain URI** (a silent GPS-less copy would make the PC answer "Already on PC" for the good one for ever).
- `onStartCommand(intent: Intent?, …)` [PB10]: immediately `ServiceCompat.startForeground(this, ID, progressNotification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`; returns **`START_NOT_STICKY`** (the queue is in memory [H16]; a restart with a null intent would have nothing to do; the interrupted marker tells the user). A null intent → `stopSelf(startId)`. Work runs on `scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`; share URIs are wrapped into `ContentUploadSource`s inside the service (readable thanks to the intent's grant [A5][B19]).
- Engine/queue state → `AppGraph.transfer` / `AppGraph.downloads`; notification refresh ≤ 1/s [A6]; `Prefs.interruptedCount` updated every 5 s while rows remain, cleared on Finished.
- Idle (engine finished and queue empty) → **`stopSelfResult(lastStartId)`** [PB10], where `lastStartId` is the id of the newest `onStartCommand`. The "am I idle?" check and every `add()`/`enqueue()` take the same mutex (`StopDecision`, a pure helper with a unit test), so a send or download that arrives at the last moment either finds the service still alive and is queued, or arrives as a newer start id, in which case `stopSelfResult` returns false and the service carries on. Work that arrives late is never dropped and never reported as "Cancelled". Only when `stopSelfResult` returned true: `stopForeground(STOP_FOREGROUND_REMOVE)`, then post the **Results** notification ("18 saved · 2 already on PC", or "2 failed. Tap to retry" with a Retry action) [A6] [A22].
- `onTimeout(startId, fgsType)` (API 35; the `dataSync` limit does not exist before Android 15, and the one-argument `onTimeout` of API 34 is for `shortService` only, so it is not overridden [PR15]): `engine.stopForTimeLimit()`, downloads cancelled, `stopSelf()` at once [A6]. `onDestroy` while rows are still unfinished (user stopped it from "Active apps") → `engine.cancel()` = Cancelled, not a crash [A6].
- Locks: `TransferLocks` acquired while rows/downloads are active (§4.5), released in `finally`.
- Starts only from a visible activity or a notification action [A6]: `ContextCompat.startForegroundService` [A4]; notification actions use `PendingIntent.getForegroundService` on 26+, `getService` on 25, `FLAG_IMMUTABLE`.

**`CastService`** (FGS `mediaPlayback`): builds `CastFileServer` (port 8767, fallback +9) over `ContentRangeSource`s for the selected items, sends `CastRequest` through `CastClient`, publishes `AppGraph.cast`; notification with Pause/Next/Stop actions (→ `control`), tap → Remote [A24]; Stop → `control(stop)`, close server, `stopSelf()`. Same lock helper.

**`CastReceiverHost`** (TV, not a service): `LifecycleEventObserver` on `MainActivity`: `ON_START` → `MulticastLock.acquire()`, `CastReceiverServer(8766).start()`, `DiscoveryResponder(DatagramTransport(8765, binder = network::bindSocket)).start()`; `ON_STOP` → close both, release lock [A10] [H14]. If UDP 8765 can't be bound, log it and continue (the phone falls back to last TV / not found).

### 4.5 Locks, notifications (design §3.4)
`TransferLocks(ctx, tag)`: `PowerManager.PARTIAL_WAKE_LOCK` `acquire(4 h)`; `WifiManager.createWifiLock(if (SDK >= 29) WIFI_MODE_FULL_LOW_LATENCY else WIFI_MODE_FULL_HIGH_PERF, tag)` with `setReferenceCounted(false)`; a coroutine re-acquires every 3.5 h while held; `release()` idempotent [A7]. Channels: `transfers` (IMPORTANCE_LOW, progress), `results` (IMPORTANCE_DEFAULT), `casting` (IMPORTANCE_LOW). POST_NOTIFICATIONS is requested when the user taps the first Send / Download / Play (33+) [H23]; refusal changes nothing else.

### 4.6 Permission flow (design §4.2)
`PermissionPlanner` (pure Kotlin, unit-tested) → `fun mediaPermissions(sdk: Int): List<String>`: 34+ `READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, ACCESS_MEDIA_LOCATION`; 33 the same without `…_USER_SELECTED`; 29–32 `READ_EXTERNAL_STORAGE, ACCESS_MEDIA_LOCATION`; ≤ 28 `READ_EXTERNAL_STORAGE`. `fun access(granted: Set<String>, sdk: Int): MediaAccess { Full, Partial, None }`; `fun needsLocationChoice(...)`. Flow: first "Send photos & videos" → rationale sheet "Choose **Allow all**. If vivo asks again, choose Allow." → `RequestMultiplePermissions` → Full: picker; Partial: picker + banner "Ferry can only see some photos → Allow all" [A15]; None twice (`shouldShowRequestPermissionRationale` false after a denial) → button opens `ACTION_APPLICATION_DETAILS_SETTINGS`. (No `WRITE_EXTERNAL_STORAGE` request: downloads need API 29+ [CP17].) `PermissionPlanner` is written in **Ph5a** (the location choice needs it), not Ph6 [PA10].

### 4.7 MediaStore and file code paths
**Queries** [CP11] (`MediaRepo`): images `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` and videos `Video.Media.EXTERNAL_CONTENT_URI`, projection `_ID, DISPLAY_NAME, SIZE, DATE_MODIFIED, BUCKET_ID, BUCKET_DISPLAY_NAME, MIME_TYPE` (+ `DURATION` for video), sort `DATE_MODIFIED DESC`. Paging 500 rows [PA12] [PB23]: **API ≥ 30** `ContentResolver.query(uri, projection, Bundle(QUERY_ARG_SORT_COLUMNS, QUERY_ARG_SORT_DIRECTION, QUERY_ARG_LIMIT, QUERY_ARG_OFFSET), null)`; **API 25–29** `sortOrder = "date_modified DESC LIMIT 500 OFFSET n"` (MediaProvider is believed to honour the Bundle limit/offset only from Android 11, and to reject `LIMIT` inside the sort string from Android 11 on). Whatever the platform does, the loader is self-protecting *(uncertain → safe either way)*: rows are de-duplicated by `_ID`, and loading stops when a page brings fewer than 500 rows or no new `_ID`. First page shown at once; the rest loads in the background; the two streams are merged by date; albums aggregated in memory from all rows (`bucketId → name, count, newest id`) [A17]. A `ContentObserver` marks the list stale (reload on next resume).
**Thumbnails**: Coil custom `Fetcher` for `MediaThumb(id, isVideo)`: API 29+ `loadThumbnail(uri, Size(320, 320), null)`; 25–28 `MediaStore.Images.Thumbnails.getThumbnail(cr, id, MINI_KIND, null)` / `Video.Thumbnails…`; memory key `"thumb:$id"`.
**UploadItemFactory** (design §4.3):
- **Where it runs** [PB5] [PA10]: only through `SendCoordinator.prepare()`, in the UI layer, before the service starts (§4.4). API: `suspend fun prepare(req: SendRequest): Prepared` with `Prepared(items: List<PreparedItem>, needsLocationChoice: List<Long>)`; `fun withoutLocation(p: Prepared): Prepared`; `fun start(p: Prepared)`. Instrumented tests call these three directly, so the sending tests of Ph5 need no picker.
- Picker id → `uri = withAppendedId(...)`; on 29+ with `ACCESS_MEDIA_LOCATION`: `MediaStore.setRequireOriginal(uri)`; **probe by opening** `openFileDescriptor(uri, "r")` (and closing it) during `prepare()`; failure (`SecurityException`, `UnsupportedOperationException`, `FileNotFoundException`) or permission missing → item needs the location choice [A2][B6]; "Send without location" → plain URI, `original = false`. An item that passed the probe is handed over as `requireOriginal = true`.
- `name = DISPLAY_NAME`; `size` = `ParcelFileDescriptor.statSize` if ≥ 0 else `SIZE` [B5]; `mtimeMs = DATE_MODIFIED × 1000` only if > 0, else null [B17].
- Share/SAF URIs: `OpenableColumns.DISPLAY_NAME/SIZE`, `DocumentsContract.Document.COLUMN_LAST_MODIFIED` when present; media-document URIs → `MediaStore.getMediaUri(ctx, uri)` → treated as MediaStore items. The call is guarded by `SDK_INT >= 29` **and wrapped in `try { … } catch (e: Throwable)`** [PR3]: reviewers disagree whether the method is API 29 or 31, lint `NewApi` settles it in CI [CP22] (raise the guard to what lint says), and any exception simply counts as "mapping failed" *(uncertain → safe either way)*; mapping failure for `image/*`/`video/*` → one line "Photos sent this way may lose their location. Use Send photos & videos." [A19]; `content://media/…` shares are already MediaStore; other providers: try `_id`/`DATA` mapping, else as-is.
- Size unknown → `openAssetFileDescriptor(...).length`; still unknown → copy into `cacheDir/share/` and send the copy (deleted after the batch).
- URI no longer readable → `Failed(CantRead)` "Can't read this file any more. Share it again".
- `ContentUploadSource(uri, requireOriginal)`: `open()` = `openFileDescriptor` (of `setRequireOriginal(uri)` when `requireOriginal`) → `ParcelFileDescriptor.AutoCloseInputStream`, `length = statSize`; with `requireOriginal` a failure is `OriginalUnreadable`, with **no fallback** to the plain URI [PB5]; `refresh()` re-queries size/date.
**Downloads** (design §4.5; **API 29+ only** [CP17] [H31]): `StatFs(downloadsDir).availableBytes < size + 50 MB` → `NotEnoughSpace` [A16]. `MediaStoreDownloadSink` inserts into `MediaStore.Downloads.EXTERNAL_CONTENT_URI` with `DISPLAY_NAME`, `MIME_TYPE`, `RELATIVE_PATH = "Download/Ferry"`, `IS_PENDING = 1`; `open()` = a stream over `openFileDescriptor(uri, "w")` whose `close()` flushes and calls `fd.sync()` [PB17]; `commit()` (only ever after `close()`) = update `IS_PENDING = 0`, return the final `DISPLAY_NAME` (MediaStore appends " (1)"); `abort()` = `delete(uri)` [B7]. There is no API 25–28 sink: the design's `.part` + `createNewFile` claim could leave a 0-byte file with the final name when the rename fails or the app dies between the two steps [PB9], and no real device would ever run it [PA13]. `cleanupLeftovers()` deletes Ferry's own `IS_PENDING=1` rows in `Download/Ferry`; finding pending rows needs **`MediaStore.QUERY_ARG_MATCH_PENDING` on API 30+ and `MediaStore.setIncludePending(uri)` on API 29** [PR4] [B7]. "Open" = `ACTION_VIEW` with the content URI + `FLAG_GRANT_READ_URI_PERMISSION` [A16]; APKs go to `ApkInstaller`.

### 4.8 Installer and app update (design §4.6, §5.4)
- `res/xml/file_paths.xml`: `<files-path name="update" path="update/"/>`, `<files-path name="apk" path="apk/"/>`, `<files-path name="logs" path="logs/"/>`; provider authority `app.ferry.files`, `exported=false`, `grantUriPermissions=true` [A14].
- `ApkInstaller.canInstall()` = SDK < 26 || `packageManager.canRequestPackageInstalls()`; `openUnknownSourcesSettings(): Boolean` = `ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:app.ferry` in try/catch (`ActivityNotFoundException` → false → show the text steps; Fire OS 6 → its own text) [A14]; `install(file|uri)` = `ACTION_VIEW`, type `application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION | FLAG_ACTIVITY_NEW_TASK`. On return (`ON_RESUME`) re-check and offer the install again.
- Update: `ConnectionManager` calls `appInfo()` after each successful locate; `AppUpdate.isNewer(info, BuildConfig.VERSION_CODE)` → `AppGraph.update = Available(info)`; tap → `downloadApk` to `filesDir/update/Ferry.apk` (progress) → `install`. Disabled while transfer/download/cast is active [B21]. TV (and phone) outbox APKs [PB18]: downloaded through the same `DownloadQueue` rule as any outbox file (`Content-Length` and bytes written must equal `OutFile.size`, otherwise the file is deleted and the user sees "Download failed. Try again", so a cut-off APK never reaches the installer) into `filesDir/apk/<outbox id>.apk` with a `FileSink` (the PC's file name is shown, but **not used as a path**), with a progress dialog, then `install` (T4). `filesDir/apk/` is emptied at app start (§4.2) and after the installer returns; a Fire TV Stick has little storage.

### 4.9 TV UI (design §5)
- **Theme**: dark, typography × 1.5, root `Modifier.padding(horizontal = 48.dp, vertical = 27.dp)` (overscan) [A25]; focus ring = 3 dp amber border + `scale(1.05f)` driven by `onFocusChanged`.
- **Focus APIs** (Compose UI/foundation 1.7.6 from BOM 2024.12.01; stable APIs only) [A13]: `FocusRequester` + `Modifier.focusRequester()`, `Modifier.onFocusChanged {}`, `Modifier.focusable()`, `Modifier.focusGroup()`, `Modifier.focusProperties { up/down/left/right = requester }`, `Modifier.onPreviewKeyEvent {}`, `LazyRow`/`LazyColumn` + `rememberLazyListState()`, `LazyListState.scrollToItem()`, `withFrameNanos {}`. Not used: `focusRestorer` and `focusProperties { enter/exit }` (experimental in 1.7).
- `tvClickable(onClick)` modifier [PA14]: `onFocusChanged` (ring + focus memory) + **`clickable(onClick)` only**. In Compose `clickable` is itself the focus target and already turns D-pad centre / Enter (key up) into a click, so there is no extra `focusable()` (that would make two focus stops per card) and no own key handler (that would fire `onClick` twice). `TvLaunchAndFocusTest` asserts one click per OK press and one card per D-pad press.
- **Focus memory**: `TvViewModel.focus: RowFocus(row, index)` updated in `onFocusChanged`; `TvHomeScreen` on entry: `LaunchedEffect(Unit) { rowState.scrollToItem(index); withFrameNanos {}; requesters[row][index].requestFocus() }`; first visit → first card of the first non-empty row.
- **Home rows** (`TvHomeScreen`): Videos (`video_playable`), Photos (first card "▶ Slideshow (24)", then thumbnails), Apps, Other files (incl. `video` kind) [A12]; empty state; idle line "Ready for your phone. On the phone: Ferry → Play on TV." + TV name [A10]; update banner (focusable); outbox refresh 5 s (list diff keyed `id`). **Thumbnails** [A11]: dedicated Coil `ImageLoader` with `.callFactory(Call.Factory { request -> AppGraph.httpClient().newCall(request) })` [PR19] (without it Coil makes its own OkHttp client: not bound to the Wi-Fi/Ethernet network [A9] and without `X-Ferry-Device` [CP15]; the lambda looks the client up on every call because the shared client is rebuilt on network change; the full-screen viewer's loader is built the same way), `fetcherDispatcher(Dispatchers.IO.limitedParallelism(2))`, request `.size(320)`, `diskCacheKey/memoryCacheKey = "name|size|mtime"`, loaded only for composed cards; error → icon.
- **TvPcNotFound** [A18]: "Can't find your PC", **Try again**, found PCs, **Enter address** → `OutlinedTextField` with `KeyboardOptions(keyboardType = KeyboardType.Decimal)` prefilled with the TV's first three octets (`NetworkBinder.ownIpv4`).
- **KeepScreenOn** [A10][H24]: `FLAG_KEEP_SCREEN_ON` while Player/Viewer/Slideshow is on screen; on Home set on every key press (`Activity.dispatchKeyEvent` hook) or cast and cleared 30 min later by a restartable coroutine.
- **TvInstall**: OK on an APK → progress dialog → `ApkInstaller` (first time: explanation + settings / text steps, Fire OS 6 text) (T4).

### 4.10 Media3 player and slideshow (design §5.2, §5.3)
- `PlayerHost` builds `ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(sharedClient.newBuilder().connectTimeout(8 s).readTimeout(20 s).build())).setLoadErrorHandlingPolicy(FerryLoadErrorPolicy)).setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_500, 5_000).build())` [CP2][B13]. `FerryLoadErrorPolicy` [PA18] = `object : DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 8)` overriding `getRetryDelayMsFor(info)`: `C.TIME_UNSET` (give up) for `ParserException`, `FileNotFoundException`, HTTP 4xx (`InvalidResponseCodeException`) or when `info.errorCount > 8`; otherwise 1 000 ms. **Both** the retry count (constructor / `getMinimumLoadableRetryCount`) and the delay must be set: with only one of them the player gives up at the default 3 tries or never stops. This guarantees the reconnect after the PC's 60 s idle drop [B13]. **Ph0b `LongPauseProbe` (API 30 TV) proved it**: after a 90 s pause the first read failed with `ProtocolException: unexpected end of stream`, one retry reconnected, playback went on past the pre-pause buffer, no `PlaybackException` (decisions log H37).
- `PlayerView` (media3-ui) inside `AndroidView`: `useController = true`, `isFocusable = false`, `descendantFocusability = FOCUS_BLOCK_DESCENDANTS`, controller shown programmatically (display-only) [A12]. The `TvPlayerScreen` handles keys in `onPreviewKeyEvent`: OK/`MEDIA_PLAY_PAUSE`/`MEDIA_PLAY`/`MEDIA_PAUSE` toggle; Left/Right ±10 s; `MEDIA_REWIND`/`MEDIA_FAST_FORWARD` ±30 s (repeat on hold via `repeatCount`); Up shows the controller; Back stops and pops.
- Errors: `onPlayerError` with decoder/format codes → "This TV can't play this video format (<codec>)"; `onTracksChanged`: audio groups present but none supported → "This TV can't play this video's sound (<codec>)" [A12]; codec from `Format.sampleMimeType`/`codecs`.
- Resume: `ResumeStore.save(key, posMs)` every 5 s and on stop; reopen → dialog "Resume from 12:34" / "Start over" (only if > 30 s in and > 30 s before the end) with **initial focus on Resume** [A12].
- **Viewer/Slideshow** (`TvViewerScreen`): Coil `AsyncImage` with `size(displayW, displayH)`, EXIF orientation default on, cross-fade 400 ms, preloads next 2 (`imageLoader.enqueue`), interval from settings (3/5/10 s), Left/Right previous/next (pauses auto-advance for that photo), OK/Play-Pause pause, overlay "3 / 24 · name" for 2 s; decode error → "Can't show this photo" for 2 s then next [A20].
- The same player/viewer composables serve casts: `CastSinkImpl` maps `CastRequest` → `Screen.Player(urls)` / `Screen.Slideshow(urls)` pushed on top, toast "Playing from vivo X200 Pro"; `ControlRequest` → player/slideshow commands; `state()` from a `@Volatile` snapshot updated every 500 ms; phone server unreachable for 10 s → "Lost connection to the phone", pop to Home.

### 4.11 Manifest (design §3.6, asserted by `check_manifest.py`)
`<application android:name=".FerryApp" android:label="Ferry" android:icon android:roundIcon android:banner="@drawable/banner" android:usesCleartextTraffic="true" android:theme="@style/Theme.Ferry">` [A1]; permissions: `INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, READ_EXTERNAL_STORAGE (maxSdkVersion 32), ACCESS_MEDIA_LOCATION, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, REQUEST_INSTALL_PACKAGES`; `<uses-feature android.hardware.touchscreen required=false>`, `<uses-feature android.software.leanback required=false>`; `MainActivity` (`exported`, `launchMode="singleTask"`, `configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode"`) with MAIN + `LAUNCHER` + `LEANBACK_LAUNCHER`; `ShareReceiverActivity` (`exported`, `theme="@style/Theme.Ferry.Translucent"`, `excludeFromRecents`, `noHistory`, `taskAffinity=""`) with `SEND` and `SEND_MULTIPLE`, `mimeType="*/*"` [A5]; `TransferService` `foregroundServiceType="dataSync"`, `CastService` `foregroundServiceType="mediaPlayback"`, both `exported=false`; `androidx.core.content.FileProvider` authority `app.ferry.files`. Themes are platform themes (`android:Theme.Material.Light.NoActionBar`, `android:Theme.Translucent.NoTitleBar`) so no Material Components library is needed. `WRITE_EXTERNAL_STORAGE` from design §3.6 is **not** declared (no download path below API 29 [CP17]); `check_manifest.py` asserts its absence. `check_manifest.py` also asserts no `<uses-library>` and that `minSdkVersion=25`, `targetSdkVersion=35`; it reads the manifest out of the release APK (§1.4 [PR14]).

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
| `row_failed_location` | Can't read this photo's location. Send it again. [PB5] |
| `preparing` | Preparing… [PB5] |
| `download_failed` | Download failed. Try again. [PB18] |
| `downloads_need_android10` | Downloading from the PC needs Android 10 or newer. [CP17] |
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
`PermissionPlannerTest` (lists per SDK 25/28/29/32/33/34/35; Full/Partial/None), `FormatTest` (sizes, speed, ETA, ages, summary strings logic), `DeviceModeLogicTest` (pure decision function), `NetworkChoiceTest` (Wi-Fi > Ethernet > other non-cellular; cellular only → none; debug override) [PR2], `StopDecisionTest` (an `add` racing the idle check is never lost: either the service stays or the newer start id wins) [PB10], `BroadcastAddressTest` (prefix → directed broadcast), `VersionRuleTest` (`20000 + n`), `ResumeRuleTest` (> 30 s in and > 30 s before end), `TvFocusLogicTest` (row/index clamping after the list changes), `CastStateMapperTest`.

### 4.14 Instrumented tests (emulator) and how each is set up
Common: `androidTest` APK `app.ferry.test`; annotations `@PhoneSuite`, `@TvSuite`, `@Api25Suite`; the host script (CP6) runs the classes **in the order of `android/ci/suites/<suite>.txt`** [PA10] [PR20] with `am instrument -w -r -e class <Class> app.ferry.test/androidx.test.runner.AndroidJUnitRunner`. Before each class the script runs `adb shell am force-stop app.ferry`, so every class starts in a fresh process with fresh `AppGraph` singletons, and each test class clears `Prefs` in `@Before` (never `pm clear`: that would also drop the granted permissions) [PA10]. Tests that send files call `SendCoordinator` (§4.7) directly; no picker is needed [PA10]. `POST_NOTIFICATIONS` is granted by the host right after install on every API 33+ image [PA6]: otherwise the first send would open the system permission dialog in the middle of a test and the progress notification would not be in the shade. The host PCs are `ferry.py` processes on the runner reached at `10.0.2.2:<port>`; tests read their address from instrumentation args (`-e pcA 10.0.2.2:18765 -e pcB 10.0.2.2:18766`) and write it to `Prefs.manual` before launching the activity (broadcast discovery can't reach the host from the emulator NAT [B27]). `Shots.take(name)` = `uiAutomation.takeScreenshot()` → PNG in `getExternalFilesDir(null)/screenshots/<suite>/` [CP16] [PA16]. **Media fixtures** [B1] [PA16] [PR9]: created in shared storage by the **shell user** (host `adb push` straight to `/sdcard/DCIM/…`, or `uiAutomation.executeShellCommand` for the big `dd` files) and indexed by a **shell-side scan**, never by `MediaScannerConnection.scanFile` from Ferry's own process (on API 30+ that can be refused, or can make Ferry the owner of the row, and owners are never redacted). Which scan works is decided once, in Ph0b's `RedactionProbe`, by trying in this order and recording the first that makes the row appear with `OWNER_PACKAGE_NAME != "app.ferry"`: (1) nothing extra (files written through `/sdcard` are indexed by themselves on API 30+), (2) `adb shell content call --uri content://media --method scan_volume --arg external_primary`, (3) `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://<path>`. The winner becomes a shell function `scan_media` in `run_instrumented.sh` and is written here. **Ph0b result (API 34 google_apis)**: mechanism (1) alone is **not** enough: after `adb push` to `/sdcard/DCIM/Camera/` the row exists at once with `OWNER_PACKAGE_NAME = com.android.shell` but stays `is_pending=1`, so other apps (Ferry) don't see it (on a snapshot-booted emulator for > 60 s); (2) `content call --uri content://media --method scan_volume --arg external_primary` clears it (`is_pending=0`) synchronously. So `scan_media` = wait for the row, then always `scan_volume` (B10). Redaction is real on this image: without `ACCESS_MEDIA_LOCATION` the plain read has the same size but `exif_gps_status = zeroed`, and `setRequireOriginal` throws `UnsupportedOperationException`; with it, both reads return the original bytes (decisions log H36).

**Phone suite** (API 34, google_apis, x86_64) — host starts PC-A `:18765` and PC-B `:18766` (outbox seeded with `report.pdf`, `small.mp4`, `photo1.jpg`), PC-U `:18767` with `--app-dir` holding the debug APK + a json with `version_code 2000000000`:
| # | Class | Setup → asserts |
|---|---|---|
| 0 | `PhoneLaunchTest` [P8] [PB20] | (from Ph4, stays in the suite) manual address = PC-A → Home shows "Connected to …"; phone UI, not TV; screenshot |
| 1 | `GpsNegativeControlTest` [B1][A2] | Run 1, **no** `ACCESS_MEDIA_LOCATION` (installed without `-g`; host grants only `READ_MEDIA_IMAGES/VIDEO`). Copy `gps.jpg` to `DCIM/Camera/IMG_20250101_120000.jpg`, scan; assert `OWNER_PACKAGE_NAME != "app.ferry"`; `UploadItemFactory` reports "needs location choice"; choose "Send without location" → `TransferService` → PC-B; engine row = "Saved without location". Host check (`verify_inbox.py`): PC-B copy is **not** both byte-identical and GPS-present (if it is, the build fails: "redaction not observed, GPS test inconclusive") |
| 2 | `GpsOriginalTest` [B1][P2] | Host `pm grant app.ferry android.permission.ACCESS_MEDIA_LOCATION` between classes. Same file via the real service → PC-A: "Saved". Host check: sha256 equals the fixture and `exif_gps_status == "present"`; mtime within 2 s of the phone's `DATE_MODIFIED`. Resend → "Already on PC"; **Send anyway** → reply `skipped` (identical) |
| 3 | `PickerAcrossAlbumsTest` [P3] | 6 images copied into `DCIM/AlbumA` and `Pictures/AlbumB`; Compose: select 2 in Recent, open Albums → AlbumB → select 1, back to Recent → "3 selected"; Send → PC-A receives exactly these 3 files with their original names (host check on the **set** of names). Upload order is not asserted here: log lines are written when a request ends, so with two workers their order is a race; order is proven by `UploadEngineTest.happyPathTwoWorkersInSelectionOrder` [PB19] |
| 3b | `PartialAccessBannerTest` [A15] | Runs **first** on the fresh install with only `READ_MEDIA_VISUAL_USER_SELECTED` granted (see run order below) → picker shows "Ferry can only see some photos → Allow all"; screenshot |
| 4 | `ShareToFerryTest` [P1b][A5][B19] [PB6] | **The shared files must be readable only through the forwarded grant**, otherwise this test passes with the grant forwarding broken (Ferry holds the media permission, and the test code itself runs as Ferry). So the sender is the helper app `app.ferry.sharehelper` [CP21] (another UID): the host pushes 3 files (one 30 MB) into the helper's private folder (`run-as app.ferry.sharehelper`, it is debuggable) and starts its activity with `am start`; the helper sends `ACTION_SEND_MULTIPLE` to `ShareReceiverActivity` with URIs of its **non-exported** `FileProvider` in `ClipData` + `FLAG_GRANT_READ_URI_PERMISSION`, then finishes. **Control that the test can fail**: before the share, the test asks Ferry's own `ContentResolver` to open one of those URIs → `SecurityException`. Then: Ferry's share activity reaches DESTROYED and the helper activity is gone **before** the engine finishes, `UiDevice.sleep()` during the 30 MB item, and all 3 files arrive byte-identical on PC-A (host check) |
| 5 | `ScreenOffUploadTest` [P4] | 300 MB file (created with `dd` via shell into `Download/`, scanned) sent; after 5 s **press Home** (so the activity is not what keeps the process important), then `UiDevice.sleep()`; while it runs, `dumpsys activity services app.ferry` (via `executeShellCommand`) shows `TransferService` with `isForeground=true` and a `dataSync` foreground type, so the upload is proven to run in the foreground service and not in some UI scope [PB13]; wait ≤ 3 min; `wakeUp()`; summary "1 saved" and the PC-A file size matches; the progress notification is present during the transfer and its text contains "MB/s" (UiAutomator, shade opened before the sleep) [PB20] |
| 6 | `CancelAndWaitingTest` [P6] | cancel a 300 MB upload at ~30 % → row Cancelled; host check: no `.ferry-*.part` in the PC-A inbox. Then set the PC address to an unused port (`10.0.2.2:18799`) and send 1 file → Transfer screen shows "Waiting for …" + Cancel within 10 s (screenshot), Cancel → Cancelled. (PC-comes-back resume is proven against a real ferry.py in `:core` `pcGoesAwayAndComesBack`.) |
| 7 | `FromPcDownloadTest` [P7][A16] | From PC lists `report.pdf` from PC-B; download → MediaStore row in `Download/Ferry` with identical bytes (compare with a direct GET); download again → `report (1).pdf`, first unchanged; screenshot |
| 8 | `UpdateBannerTest` [C3] | connect to PC-U → banner "Ferry 2.0.… is available"; tap Update → download + sha check → an `Instrumentation.ActivityMonitor` for `ACTION_VIEW` + APK MIME (blocking) is hit (or, if `canRequestPackageInstalls()` is false, the monitor for `MANAGE_UNKNOWN_APP_SOURCES`) |
| 9 | `CastLoopbackTest` [P9][T5][B27] | `MainActivity` with debug extra `CAST_RECEIVER=true` (receiver on 127.0.0.1:8766); Play on TV → TV "127.0.0.1" → video `small.mp4` (copied into `Movies/`) → state `kind=video, playing=true` within 10 s; Pause → `playing=false`; Next/Stop; then photos ×3 → slideshow index advances; screenshot Remote |
| 10 | `PhoneScreenshotTour` | welcome, home (connected / not found / no Wi-Fi via a fake state), picker, location dialog, transfer running, summary, From PC, Play on TV, Remote, Settings |

**TV suite** (API 30, android-tv, x86, `profile: tv_1080p` [PA16] [PR13]) — host PC-T `:18765` started with `--app-dir` holding the debug APK + a json with `version_code 2000000000` (so the TV shows the update banner [PB20]), outbox seeded with `big.mp4` (≥ 200 MB, generated and cached), `small.mp4`, 5 photos + `broken.jpg`, `Test.apk` (copy of the debug APK), `notes.txt`, `old.avi`:
| # | Class | Setup → asserts |
|---|---|---|
| 1 | `TvLaunchAndFocusTest` [T1][A13] | launches in TV mode (assert `DeviceMode.isTv`); first Videos card focused; D-pad right/down via `UiDevice.pressDPad*`; OK on video 2 → Player; Back → `onNodeWithTag("card-videos-1").assertIsFocused()`; one D-pad press moves exactly one card and one OK press gives exactly one click (click counter on a test card) [PA14]; rows contain `old.avi` under Other files [A12]; **no node with the text "Send" exists in TV mode** (T6) [PB20]; the **update banner** "Ferry … is available" is shown and can be focused (C3 on the TV) [PB20]; screenshots |
| 2 | `TvLongPauseTest` [T2][B13][acc 5] | play `big.mp4` (300 s at a constant 6 Mbit/s, §1.4 [PR7]); playing 5 s; assert `player.bufferedPosition < player.duration` at the moment of pausing (otherwise the whole file is already in memory and the pause proves nothing [PR7]); pause 90 s (> PC 60 s timeout); resume → `isPlaying` within 10 s, position advances; `MEDIA_FAST_FORWARD` (+30 s) and `MEDIA_REWIND` (−30 s) → still playing; no `PlaybackException` recorded |
| 3 | `TvResumeTest` [T2] | play to 40 s, Back; reopen → "Resume from 0:40" focused; OK → position ≈ 40 s |
| 4 | `TvSlideshowTest` [T3][A20] | Slideshow card → index advances every 3 s (setting 3 s); Right/Left change index; `broken.jpg` shows the message and is skipped |
| 5 | `TvInstallTest` [T4] | OK on `Test.apk` → download dialog → monitor catches `ACTION_VIEW` APK or the unknown-sources settings intent |
| 6 | `TvPcNotFoundTest` [A18] | PC address set to an unused port → "Can't find your PC", Try again, Enter address prefilled with "10.0.2." |
| 7 | `TvScreenshotTour` | home (full, with the update banner [PB20]; empty), player with controller, viewer, slideshow overlay, install dialog, PC not found |

**API 25 suite** (API 25, default, x86) [A3][H17][H27] — host PC-25 `:18765`; every class gets `-e app.ferry.debug.NET any` [PR2]:
| # | Class | Asserts |
|---|---|---|
| 1 | `CoreOnApi25Test` | real `:core`: `DiscoveryCodec` round trip; `FerryClient.info()` to `10.0.2.2:18765` (protocol 2); `UploadEngine` sends a 1 MB in-memory source → Saved; `MiniHttpServer` on the device + OkHttp `Range: bytes=10-19` to itself → 206 and correct bytes; `AppUpdate` sha256 on a temp file; `RingLog` write |
| 2 | `Api25LaunchTest` | `MainActivity` launches (phone UI on the default image), Home shows "Connected to …"; From PC lists `report.pdf` with the line "Downloading from the PC needs Android 10 or newer" and no download action [CP17]; screenshot |

(`Api25DownloadTest` of rev 1 is gone with the API 25–28 download path [CP17] [PB9].)

**Run order (phone)** = the line order of `suites/phone.txt` [PA10]: after install the host grants `POST_NOTIFICATIONS` [PA6]; then `0`; then `3b` (only `READ_MEDIA_VISUAL_USER_SELECTED` granted; "first of the media classes, when present": it joins in Ph6); then `pm grant READ_MEDIA_IMAGES READ_MEDIA_VIDEO` (granting never kills the process); then 1; then `pm grant ACCESS_MEDIA_LOCATION`; then 2, 3, 4–10. No class needs a permission revoked after it was granted, so the test process is never killed by a revoke. A phase that adds a class adds one line at the right place in the file; it does not reorder the others.

**Pass rule for one class** [PA16]: the raw `am instrument -r` output must contain `INSTRUMENTATION_CODE: -1` and an `OK (` line, and none of `FAILURES!!!`, `INSTRUMENTATION_STATUS_CODE: -2`, `INSTRUMENTATION_FAILED`, `Process crashed`.

`verify_inbox.py` runs after the phone suite on the host and checks PC-A/PC-B as listed. Every suite uploads: `am instrument` raw output, `logcat -d`, host `ferry-log.txt` files, screenshots.

## 5. CI

### 5.1 `pc.yml` (final)
- Triggers: `push` with `paths: [pc/**, .github/workflows/pc.yml]`, `workflow_dispatch`.
- Job `test`, `fail-fast: false`, matrix `include`: `{os: windows-latest, py: "3.8"}`, `{os: windows-latest, py: "3.13"}`, `{os: ubuntu-22.04, py: "3.8"}` (no 3.8 on 26.04 runners, baseline), `{os: ubuntu-latest, py: "3.13"}`. Steps: checkout, setup-python, `pip install pytest`, `python -m pytest -q -ra --junitxml=pytest.xml`; upload `pytest.xml` + any `ferry-log*.txt` from tmp on failure. Timeout 20 min.

### 5.2 `android.yml` (final; **never rename** [H26])
- Triggers: `push` (**any branch**) with `paths: [android/**, pc/**, .github/workflows/android.yml]`; `workflow_dispatch` with input `emulators: all|phone|tv|api25|none` (default `all`). `pc/**` stays in the filter on purpose: the JVM integration tests and the emulator suites run against the real `ferry.py`, so a PC change must re-test them; with the branch rule this costs the fast jobs per push and one full run per merge [PR12].
- **Which emulator suites run** [PA2] [PR12] [H27] [H28] [CP18] (job-level `if`, shown for the phone suite; `tv` and `api25` use their own names):
  ```
  if: >-
    (github.event_name == 'push' && github.ref == 'refs/heads/main')
    || (github.event_name == 'workflow_dispatch' && contains(fromJSON('["all","phone"]'), inputs.emulators))
    || (github.event_name == 'push' && (contains(github.event.head_commit.message, '[emu]')
        || contains(github.event.head_commit.message, '[emu:phone]')))
  ```
  So: every push to `main` runs all three; a branch push runs none unless its head commit message carries a tag; a manual run obeys its input.
- **Runner images** [PA7] [PR13]: the three emulator jobs run on **`ubuntu-24.04`** (the image on which the emulator runner, KVM rule and AVD cache are well known), not on `ubuntu-latest`, which the CI baseline shows is already 26.04. If Ph0b finds a problem on 24.04 it tries `ubuntu-latest` and writes the image that worked here. **Ph0b: `ubuntu-24.04` worked for all three** (system images `android-34;google_apis;x86_64` profile `pixel_6`, `android-30;android-tv;x86` profile `tv_1080p`, `android-25;default;x86`). Network transports seen [PR2]: phone Wi-Fi (active) + mobile; TV Wi-Fi only; **API 25 mobile only, no Wi-Fi network**. The other jobs stay on `ubuntu-latest` / `windows-latest` (proven by the smoke build).
- `concurrency: { group: android-${{ github.ref }}, cancel-in-progress: ${{ github.ref != 'refs/heads/main' }} }`.
- Workflow env on `main`: `FERRY_RELEASE: ${{ github.ref == 'refs/heads/main' && '1' || '0' }}`, `FERRY_RUN_NUMBER: ${{ github.run_number }}`.

| Job | Runs on / when | Steps | Artifacts |
|---|---|---|---|
| `core-jvm` | matrix `ubuntu-latest`, `windows-latest`; every push | checkout, setup-java 17, setup-python 3.13, setup-gradle, `./gradlew --no-daemon :core:test` (`shell: bash` on Windows; env `CI=true`, `FERRY_PYTHON=python`) | `core-test-reports-<os>` (`android/core/build/reports/tests/`, `build/test-results/`) always |
| `app-build` | ubuntu-latest; every push | checkout, java 17, python 3.13, **`pip install pytest`** [PA19], setup-gradle; `python -m pytest -q android/ci`; `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :sharehelper:assembleDebug` [PA8] [CP21]; `BT=$(ls -d $ANDROID_HOME/build-tools/* \| sort -V \| tail -1)` [PA7] [PR8]; `$BT/aapt2 dump xmltree --file AndroidManifest.xml app-release.apk > manifest.txt` and `python android/ci/check_manifest.py manifest.txt` [PR14]; `python android/ci/check_apk.py app-release.apk --build-tools $BT --expect-code $CODE --expect-name $NAME`; `python android/ci/make_apk_json.py app-release.apk $CODE $NAME > Ferry.apk.json`; rename to `Ferry.apk` | `ferry-apk` (`Ferry.apk`, `Ferry.apk.json`), `ferry-test-apks` (debug + androidTest + sharehelper APKs), `app-unit-reports` (unit tests + lint report) |
| `emulator-phone` | **ubuntu-24.04**; `if` as above; `needs: app-build` | checkout; download `ferry-test-apks`; KVM udev step; setup-python 3.13; AVD cache; `reactivecircus/android-emulator-runner@v2.33.0` with `api-level: 34, target: google_apis, arch: x86_64, profile: pixel_6, disable-animations: true, emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none -no-snapshot-save`, `script: bash android/ci/run_instrumented.sh phone` (single line: the runner executes each script line in its own shell); timeout 45 min | `instrumented-phone` (am output, logcat, host logs), `screenshots-phone` |
| `emulator-tv` | **ubuntu-24.04**; `if` as above with `tv` / `[emu:tv]` [H27]; `needs: app-build` | as above + `big.mp4` cache (`make_big_video.sh` installs ffmpeg with apt if missing; cache key = script hash); `api-level: 30, target: android-tv, arch: x86, profile: tv_1080p` [PA16] [PR13]; `script: bash android/ci/run_instrumented.sh tv`; timeout 50 min | `instrumented-tv`, `screenshots-tv` |
| `emulator-api25` | **ubuntu-24.04**; `if` as above with `api25` / `[emu:api25]` | `api-level: 25, target: default, arch: x86`; `script: bash android/ci/run_instrumented.sh api25`; timeout 30 min | `instrumented-api25`, `screenshots-api25` |
| `package` | ubuntu-latest; `main` only; `needs: [core-jvm, app-build, emulator-phone, emulator-tv, emulator-api25]` (listed so that it waits for them and can read their results) with `if: ${{ !cancelled() && github.ref == 'refs/heads/main' && needs.core-jvm.result == 'success' && needs.app-build.result == 'success' }}` [PA4] [PR6] [CP19] | checkout, python 3.13, `pip install pytest`; `python -m pytest -q pc/tests` (Linux only, fast); download `ferry-apk` into `pc/app/`; `python pc/tools/package.py --app-dir pc/app --out dist`; a step sets `SUFFIX=""` when all three `needs.emulator-*.result` are `success`, else `SUFFIX="-UNVERIFIED"`, and writes the three results to `$GITHUB_STEP_SUMMARY` | artifact **`Ferry-2.0.<n>`** or **`Ferry-2.0.<n>-UNVERIFIED`** (the zip inside is `Ferry-2.0.<n>.zip` either way; retention 90 days). Without `!cancelled()` a failed or skipped emulator job would silently skip this job; with it, a flaky emulator class no longer hides the zip, and the name says whether the build is tested. **Only an artifact without `-UNVERIFIED` may be given to the user** [H29] |

Emulator details [B27]:
- **KVM** (first step of each emulator job): `echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules && sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm`.
- **AVD snapshot cache**: `actions/cache@v4` on `~/.android/avd/*` + `~/.android/adb*`, key `avd-<api>-<target>-<arch>-v1`; on miss, one emulator-runner step with `force-avd-creation: false`, `emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none`, `script: echo "AVD snapshot created"`; test steps then use `-no-snapshot-save`. **Ph0b** (B9, B11, B12): keys are `-v2`; the snapshot step waits 45 s before saving; attempt 2 cold-boots (`-no-snapshot-load`); each emulator job first frees runner disk (the API 25 AVD once refused to start for lack of space); **`emulator-api25` uses no snapshot at all** (`-no-snapshot`; cold boot ≈ 15 s, snapshot boots hung).
- **One automatic retry**: the test step has `id: try1, continue-on-error: true`; an identical step `if: steps.try1.outcome == 'failure'` runs once more; a final step fails the job if both failed. Each attempt writes artifacts to a separate folder.
- First step of every emulator job (Ph0b onwards) prints, for the record [PR13] [PR2]: `getprop ro.build.version.sdk`, `getprop ro.build.characteristics`, `pm list features | grep -i leanback`, and `dumpsys connectivity | grep -i -m5 NetworkAgentInfo` (which transports the image reports).
- `run_instrumented.sh <suite>` (CP6): (1) start the suite's `ferry.py` instances from a temp copy of `pc/` with `--no-open --port <fixed> --discovery-port off --inbox/--outbox/--data-dir` under `$RUNNER_TEMP/<name>` (`--app-dir` for PC-U), wait for `FERRY_READY`; (2) seed outboxes from `android/ci/fixtures/` (+ `big.mp4` for TV); (3) `adb wait-for-device`, `adb install -r` both APKs (**no `-g`**; the phone suite also installs the sharehelper APK [CP21]); `pm grant app.ferry android.permission.POST_NOTIFICATIONS` on API 33+ [PA6]; (4) read `suites/<suite>.txt` [PA10] [PR20] and for each line: `am force-stop app.ferry`, the line's host steps (grants, fixture pushes, `scan_media`), then `adb shell am instrument -w -r -e class … -e pcA … app.ferry.test/androidx.test.runner.AndroidJUnitRunner`, judged by the pass rule of §4.14 [PA16], then the line's host check; (5) `adb pull /sdcard/Android/data/app.ferry/files/screenshots` [CP16] [PA16]; `adb logcat -d`; (6) host checks (`verify_inbox.py` for phone); (7) kill the ferry.py processes (trap on EXIT).
- Ports 18765–18767 are fixed (one suite per runner, nothing else listens).

### 5.3 versionCode and the PC zip
- `versionCode = 20000 + github.run_number` of `android.yml`, `main` only; `versionName = 2.0.<run_number>` [H26]. Run numbers count every run of this workflow (all branches, all triggers), so they only grow; packaged builds are always `main` builds. `check_apk.py` verifies the APK's badging matches before packaging.
- The PC zip gets the APK inside the **same `android.yml` run**: `app-build` uploads `Ferry.apk` + `Ferry.apk.json`; `package` downloads them into `pc/app/` and runs `package.py`, which refuses to build without a consistent pair [B25]. A PC-only change also triggers `android.yml` (path `pc/**`), so every user zip carries a freshly numbered APK (the app will then offer the update, which is harmless and keeps "new Ferry folder ⇒ update phone and TV" simple).
- Reviewing artifacts from this workspace: ~~`gh run download <run-id> -n screenshots-phone -D /tmp/shots`~~ **does not work** (Ph0b: the proxy refuses the artifact blob host `productionresultssa0.blob.core.windows.net` with 403) [B27]. Instead `run_instrumented.sh` prints the `FERRY_PROBE` logcat lines and every screenshot (base64 between `FERRY_PNG_BEGIN/END`) into the job log: `gh api repos/DrSaurabh/ferry/actions/jobs/<job-id>/logs > job.log && python3 android/ci/shots_from_log.py job.log /tmp/shots` (B7).

## 6. Phases

Phase ids are **Ph0a–Ph10** (not P…, which are the phone requirements). Ordering principle: **prove the riskiest unknowns first** (emulators on Actions, host reachability, GPS redaction premise, player reconnect after a long pause, the release build, `:core` against real ferry.py on Windows, API-25 dexing), then build features on proven ground. Line counts are rough change sizes (added + changed, not counting deleted v1 HTML).

**Rev 2 sizing** [PA9] [PR11]: Kotlin cannot be compiled locally, so every phase is kept to about **1 500 lines or less**; rev 1's Ph0, Ph2, Ph3, Ph5 and Ph8 are each split into **a** and **b** (the other ids are unchanged, so references to "Ph4" etc. still hold). Order: Ph0a, Ph0b, Ph1, Ph2a, Ph2b, Ph3a, Ph3b, Ph4, Ph5a, Ph5b, Ph6, Ph7, Ph8a, Ph8b, Ph9, Ph10. Each phase is worked on its own branch and is done per §0.1 (branch green incl. the emulator suites it touches → fast-forward → `main` green), and records its progress in `docs/v2-progress.md`. Every phase leaves `main` green and, from Ph4 on, produces an installable `Ferry-2.0.<n>.zip`. **Zips before Ph10 are work in progress** (some Home buttons are placeholders): they are for the agent's checks only and are never handed to the user; the first zip the user gets is Ph10's verified one [PA10] [H29].

"**CI green**" in a phase means: the fast jobs on the branch, the listed emulator suites on the branch via `[emu…]`, then everything on `main` after the fast-forward.

### Ph0a — PC tests portable, Windows CI green (~150 lines) [PA9]
- **Goal**: the safe half of the old Ph0, with no Android in it: v1's tests pass on Windows and Linux, and the branch way of working (§0.1) is exercised once.
- **Files**: `.gitattributes`; the six v1 test portability fixes (§2.13 table 1); `pc.yml` matrix (§5.1); `docs/v2-progress.md` (first lines).
- **Tests that must pass**: all v1 pytest on the 4-way matrix.
- **CI green**: `pc` (all 4) on the branch `ph0a`, then on `main`.
- **Risks**: none beyond the six known failures of `v2-ci-baseline.md`.

### Ph0b — Android CI foundation, emulator and release-build spike (~700 lines) [PA9] [PA3]
- **Goal**: all later CI assumptions proven with throw-away probes, **including the release build**, on the real toolchain.
- **Files**: `android.yml` gets its final triggers and `if` rules (§5.2: branch pushes, `[emu…]` tags, dispatch input) and gains `emulator-phone`, `emulator-tv`, `emulator-api25` (KVM, AVD cache, retry, `run_instrumented.sh` first version that starts **v1** `ferry.py` from a temp copy with `--no-open --port --inbox --outbox` and waits by polling `/api/v1/info`); smoke app: `INTERNET`, `usesCleartextTraffic`, `READ_MEDIA_IMAGES`, `ACCESS_MEDIA_LOCATION`, androidTest deps, plus the Compose `ui`/`foundation`/`material3`, `media3-exoplayer`, `media3-datasource-okhttp` and `okhttp` dependencies of §0.3 (so the APK already has the final native-library picture [PR1] and the probe uses the real HTTP stack); **release pipeline first run** [PA3]: `signingConfigs.release` from `signing/signing.properties`, `:app:assembleRelease`, `:app:lintDebug` with `NewApi` [CP22], `android/signing/cert-sha256.txt`, and a first `check_apk.py` (certificate with normalisation [PA19], `lib/` allow-list [PA1], build-tools found by glob [PA7]) with its pytest; the job is already named `app-build` (rev 1 renamed it from `build` only in Ph4, which would have broken the emulator jobs' `needs`); `android/ci/make_fixtures.py` run locally (Pillow, ffmpeg) → committed `fixtures/gps.jpg` (verified with `ferry.exif_gps_status == "present"`), `photo1..5.jpg`, `broken.jpg`, `small.mp4`; `make_big_video.sh`. Probes (androidTest, later deleted): `HostReachProbe` (GET `10.0.2.2:18765/api/v1/info`, `app=="ferry"`) on all three images; `RedactionProbe` (phone; run 1 without `ACCESS_MEDIA_LOCATION`: shell-copied, scanned `gps.jpg` read through MediaStore → bytes differ from the fixture / `setRequireOriginal` open fails; run 2 after `pm grant`: original bytes == fixture); `LongPauseProbe` (TV; ExoPlayer with **`OkHttpDataSource`** (connect 8 s, read 20 s) and the `FerryLoadErrorPolicy` of §4.10, i.e. the product's stack [CP2], not `DefaultHttpDataSource`, whose connection reuse and error behaviour differ [PA5] [PB21]; on `big.mp4` (recipe in §1.4 [PA17] [PR7]) from v1 ferry.py: play 5 s, assert buffered < duration, pause 90 s, resume → playing); `Shots` helper + one screenshot per image, pulled the CP16 way.
- **Tests that must pass**: smoke unit test; `android/ci` pytest; the probes; `check_apk.py` on the release-signed smoke APK.
- **CI green**: `android` (`app-build`, `emulator-phone`, `emulator-tv`, `emulator-api25`), first on the branch with `[emu]`, then on `main`.
- **Acceptance** (each result is written into this plan where it is used, and into `docs/v2-progress.md`): `gh run download <id> -n screenshots-tv` works from this workspace and the PNG opens [B27]; whether `gh workflow run … --ref <branch>` works through the proxy (if not, only `[emu…]` tags are used) [PA2]; the emulator-runner tag that resolved (§0.3) and the runner image that worked (§5.2) [PA7] [PR13]; the system-image names and TV profile that worked; `unzip -l` of the release APK's `lib/` (expected: only `libandroidx.graphics.path.so` ×4 ABIs) [PA1] [PR1]; the media-scan mechanism and the observed `OWNER_PACKAGE_NAME` (§4.14) [PA16] [PR9]; the screenshot pull that worked on each image [PA16]; the network transports each image reports [PR2]; the redaction and long-pause results (also in the decisions log).
- **Risks**: `android-tv` API 30 `x86` image missing or too slow → try `x86_64`, then API 31 TV; record. Emulator boot flakiness → retry step. **If Android does not redact on the emulator** (after trying the three scan mechanisms of §4.14), B1's negative control can't fail, **and then the positive check can't fail either** (it would pass with or without `setRequireOriginal`) [PB7]. In that case: (1) record an H-row; (2) `GpsNegativeControlTest` is reduced to the dialog/"Saved without location" behaviour and stops failing the build as "inconclusive", so that Ph5a does not inherit a red build from an emulator property [PR9]; (3) add a **structural test that can fail**: for a picker item with `ACCESS_MEDIA_LOCATION` granted, the `PreparedItem` is `requireOriginal = true` and the `ContentUploadSource` opens exactly the URI returned by `MediaStore.setRequireOriginal` (asserted with a recording `ContentResolver` wrapper), and without the permission it reports "needs location choice"; (4) mark P2's GPS line as **real-device only** in §7.1/§7.4 and in the handoff (acceptance 1 then is the proof). **If the long-pause probe fails**, decide in this phase between a higher player retry count and a longer PC idle timeout for Range requests (design §5.2 rejected the PC change only "if the test passes").

### Ph1 — PC program 2.0.0 (~1 300 lines + ~1 500 lines of v1 HTML/assets deleted)
- **Goal**: `ferry.py` 2.0.0 complete per §2 (except README/DEVICE-CHECKLIST text, Ph10).
- **Files**: `pc/ferry.py`, `pc/tests/helpers.py`, updated v1 tests (§2.13), new tests (§2.14), `pc/tools/package.py`, deletions (§1.1), `.gitignore`; `run_instrumented.sh` switches to `--data-dir/--discovery-port off` + `FERRY_READY`.
- **Tests**: all of §2.13–2.14, plus `test_stdlib_only` (AST import scan of `ferry.py` against `sys.stdlib_module_names`, skipped on 3.8/3.9) for C7.
- **CI green**: `pc` (4-way); `android` (the probes still pass against the 2.0 PC, `HostReachProbe` checks only `app=="ferry"`).
- **Acceptance**: locally `python3 pc/ferry.py --port 0 --no-open --inbox /tmp/i --outbox /tmp/o --data-dir /tmp/d` prints `FERRY_READY`; `curl` of `/`, `/api/v1/info`, `POST /api/v1/check-batch`, a UDP datagram to the reported discovery port all answer as specified; `ferry-log.txt` lines look right; `python3 pc/tools/package.py --app-dir <fake>` builds the zip, and fails without the app pair.
- **Risks**: Windows-only behaviour of log rotation and UDP 10054 (tests run on `windows-latest`); keeping `ferry.py` stdlib-only and 3.8-compatible (no walrus-heavy/3.9+ syntax: CI 3.8 catches it).

### Ph2a — `:core` foundation: models, HTTP client, single uploads against ferry.py (~1 300 lines) [PA9] [PR11]
- **Goal**: the `:core` module builds on both OSes, one upload is proven byte-identical against the real PC program, and the `:core` jar is proven on API 25.
- **Files**: `android/gradle/libs.versions.toml`, `settings.gradle.kts` (`:core`), root `build.gradle.kts` (extended, §3.1 [PR17]), `core/build.gradle.kts`; `core/…`: `Json`, `HostPort`, `Clock`, `util/*`, `model/Protocol.kt`, `net/*` (FerryHttp, FerryClient, StreamingBody with the look-ahead rule [PB1], FerryException), `upload/UploadModels.kt` (types only); tests: `JsonModelsTest`, `HostPortTest`, `PercentEncodeTest`, `StreamingBodyTest`, `ApiLevelScanTest`, `NoAndroidImportsTest`, `FerryPcProcess` (+ `awaitLogUpTo` [PB12]), integration tests `uploadIsByteIdentical`, `mtimeRestored`, `mtimeOmittedWhenUnknown`, `renameOnDifferentContent`, `checkBatchFindsDuplicates`, `deviceNameWithCurlyQuoteAndEmoji`, `diskFullSmallUpload`, `oldPcDetected` (stub via a 30-line `ServerSocket` in the test until `MiniHttpServer` exists). `:app` gets `implementation(project(":core"))` + desugaring; `HostReachProbe` on API 25 now calls `FerryClient.info()` (first real `:core` code on Fire OS 6's API level [A3]).
- **CI green**: `android/core-jvm` (ubuntu + windows), `app-build`; `[emu:api25]`.
- **Acceptance**: `ApiLevelScanTest` fails when a deliberate `java.time.Instant` reference is pushed to the branch (try once, then remove); the first integration tests pass on Windows.
- **Risks**: as listed under Ph2b (toolchain items apply here first).

### Ph2b — `:core` upload engine + resilience against ferry.py (~1 500 lines) [PA9] [PR11]
- **Goal**: the upload path proven end to end on the JVM, on Linux and Windows, including drops, stalls, disk full and files that change.
- **Files**: `upload/UploadEngine.kt`, `upload/SpeedMeter.kt`; tests: `UploadEngineTest`, `TcpRelay` [PB8], integration tests `engineEndToEndSmallBatch`, `growingFileIsNeverSavedTruncated` [PB1], `parallelCaseClashDifferentContent`, `parallelIdenticalContent`, `diskFullLargeUpload`, `checkBatchSaysNotEnoughSpace`, `cancelMidUploadLeavesNoTemp`, `pcGoesAwayAndComesBack`, `silentStallIsNoticedAndRecovered` (upload half), `pcRestartOnSamePort` (Linux only). The API 25 probe adds a 1 MB `UploadEngine` send.
- **CI green**: `android/core-jvm` (ubuntu + windows), `app-build`; `[emu:api25]`.
- **Acceptance**: integration test report shows the case-clash and identical-content tests passing on Windows [B14]; `growingFileIsNeverSavedTruncated` was seen **red** once with the look-ahead switched off on the branch, then green (proof the test can fail) [PB1].
- **Risks**: `-Xjdk-release=1.8` with Kotlin 2.0.21 on JDK 17 (fallback: keep `jvmTarget 1.8` and rely on the scan test + a longer forbidden-method list; record it); OkHttp's behaviour when the PC closes mid-body (the B11 design is safe either way; the test documents which branch runs); Windows process teardown leaving files locked (harness retries deletes).

### Ph3a — `:core` server, downloads, update, log (~1 300 lines) [PA9]
- **Goal**: `MiniHttpServer` and the download side done and tested.
- **Files**: `http/*`, `download/*`, `update/AppUpdate.kt`, `log/RingLog.kt`; tests `MiniHttpServerTest`, `RangeParserTest`, `DownloadQueueTest`, `AppUpdateTest`, `RingLogTest`, integration `outboxListAndDownload`, `appInfoAndUpdateDownload`, `silentStallIsNoticedAndRecovered` (download half [PB11]); `oldPcDetected` moves to `MiniHttpServer`. API-25 probe adds a `MiniHttpServer` Range request to itself.
- **CI green**: `core-jvm` ×2, `app-build`; `[emu:api25]`.
- **Acceptance**: `stalledReaderSlotFreedWithin35s` green on both OSes; `MiniHttpServer` range vectors identical to `ferry.parse_range`'s.

### Ph3b — `:core` discovery, locator, cast protocol (~1 100 lines) [PA9]
- **Goal**: every `:core` component of §3.2 done and tested, so the app phases are mostly UI and Android plumbing.
- **Files**: `discovery/*`, `cast/*`, `model/CastModels.kt`, `model/Discovery.kt`; tests `DiscoveryCodecTest`, `DiscoveryResponderTest`, `PcLocatorTest` [PB22], `CastProtocolTest`, `CastFileServerTest`, integration `discoveryAgainstFerryPy`, `locatorPrefersLastKnown`.
- **CI green**: `core-jvm` ×2, `app-build`; `[emu:api25]` (probe adds the `DiscoveryCodec` round trip).
- **Acceptance**: discovery against the real `ferry.py` passes on Windows and Linux.
- **Risks**: timing flakiness in the watchdog/rate-limit tests (generous margins, fake clocks where possible, real-time only in the one stalled-reader test); Windows loopback UDP quirks (responder loop survives errors by design).

### Ph4 — App skeleton, connection, settings, release pipeline (~2 400 lines)
- **Goal**: an installable, release-signed, correctly versioned Ferry that finds the PC (last-known / broadcast / manual), shows phone or TV home, offers updates (banner only), and is packaged into the PC zip.
- **Files**: `app/build.gradle.kts` (signing, version rule, deps, buildConfig), full manifest (§4.11), `res/` (themes, all §4.12 strings, icons, banner, `file_paths.xml`), `FerryApp`, `AppGraph`, `DeviceMode`, `Prefs`, `NetworkBinder`, `ConnectionManager`, `Navigator`, themes, `MainActivity`, `WelcomeScreen` (battery step), `HomeScreen` (status + buttons; buttons for later phases show "Coming in this build" only on debug builds — release builds of Ph4 are not given to the user), `SettingsScreen` (address, battery status, version, share log), `TvRoot`/`TvHomeScreen` skeleton (header, status, outbox rows as plain lists, `TvPcNotFound`), update banner (`AppGraph.update`); `android/ci/check_manifest.py`, `check_apk.py` (final: version badging added to the Ph0b version), `make_apk_json.py`, `test_ci_scripts.py` + fixtures, `android/signing/cert-sha256.txt` (`90:07:63:92:A5:B5:5E:63:02:61:BA:15:77:16:88:11:3E:83:C1:FB:24:90:03:8D:B3:5E:FD:48:3B:75:C6:F1`, from `keytool -list -v`; already added in Ph0b [PA3]); `android/ci/suites/*.txt` take over the class lists [PA10]; `android.yml`: final `app-build` and `package` jobs (with the `-UNVERIFIED` rule [CP19]), workflow env; `.gitignore` `pc/app/`. Unit tests `DeviceModeLogicTest`, `VersionRuleTest`, `BroadcastAddressTest`, `FormatTest`. Instrumented: `PhoneLaunchTest` (Home "Connected to" PC-A via manual address), `Api25LaunchTest`, `TvLaunchTest` (TV mode, header shows the PC), first screenshots.
- **CI green**: all `android` jobs incl. `package`; `pc`.
- **Acceptance**: download `Ferry-2.0.<n>.zip`; locally unzip, start `ferry.py` → banner "App: Ferry 2.0.<n> is ready…"; `/api/v1/app` shows `version_code 20000+n` and the APK's sha; `check_apk.py` output shows the pinned certificate; screenshots of phone and TV home reviewed.
- **Risks**: parsing `aapt2 dump xmltree` output in `check_manifest.py` (text format; a sample dump from the Ph0b smoke APK is committed as the pytest fixture [PR14]); edge-to-edge insets; `bindSocket`/socket factory on the emulator's Wi-Fi (the emulator exposes Wi-Fi on API 30+; on API 25 the default network is used when no Wi-Fi `Network` exists — `NetworkBinder` must fall back to "unbound" on emulators/Ethernet TVs rather than reporting "not on Wi-Fi": rule = prefer Wi-Fi, else Ethernet, else the active network if it is not cellular; if the API 25 image reports its only network as cellular, the debug-only `NET=any` override of §4.2 is what keeps the API 25 suite meaningful [PR2]).

### Ph5a — Sending core: service, originals, transfer screen (~1 400 lines) [PA9]
- **Goal**: requirements P2, P4, P5, P6 working through the real foreground service, driven by `SendCoordinator` (no picker and no share target yet).
- **Files**: `TransferService` (+ `StopDecision` [PB10]), `Notifications`, `Locks`, `UploadItemFactory`, `SendCoordinator` [PB5], `ContentSources`, **`PermissionPlanner` + `PermissionPlannerTest`** (moved here from Ph6: the location choice needs it [PA10]), `TransferScreen` + `TransferViewModel`, `LocationChoiceDialog`, interrupted-transfer marker + banner [A8], POST_NOTIFICATIONS request, Results notification; unit `StopDecisionTest`; `android/ci/verify_inbox.py`; instrumented `GpsNegativeControlTest`, `GpsOriginalTest`, `CancelAndWaitingTest` (lines added to `suites/phone.txt`); `RedactionProbe` deleted.
- **CI green**: fast jobs; `[emu:phone]`.
- **Acceptance**: `verify_inbox.py` output in the phone artifact shows "PC-A gps.jpg: identical, GPS present; PC-B: redacted/not identical" (or the Ph0b fallback of [PB7] is in force and says so); transfer and summary screenshots reviewed against §4.12 wording.
- **Risks**: `onTimeout` only testable by unit test (engine `stopForTimeLimit`); the late-start race of [PB10] only by `StopDecisionTest`.

### Ph5b — Share target, "Send other files", screen-off proof (~1 200 lines) [PA9]
- **Goal**: requirement P1 (Share → Ferry, "Send other files") and the background proof for P4.
- **Files**: `ShareReceiverActivity` (incoming URIs via `IntentCompat.getParcelableExtra` / `getParcelableArrayListExtra` from core-ktx [A5 detail] + incoming `ClipData`; runs `SendCoordinator.prepare()` and the location choice before forwarding [PB5]), Home "Send other files" (`OpenMultipleDocuments`), module `android/sharehelper` (manifest, one activity, `FileProvider`; ≈ 60 lines) [CP21] [PB6]; instrumented `ShareToFerryTest`, `ScreenOffUploadTest`.
- **CI green**: fast jobs; `[emu:phone]`.
- **Acceptance**: `ShareToFerryTest`'s control ("Ferry cannot open the helper's URI without the grant") is in the test log, and all 3 files arrive [PB6]; the `dumpsys` foreground check is in the log [PB13].
- **Risks**: FGS start from the share activity on API 34+ (allowed while visible; test 4 proves); read grant lifetime after the share activity finishes (test 4 now really proves it, either way [PB6]); `run-as` into the helper's folder on the API 34 image (fallback: the helper writes its own three files at start); emulator screen-off ≠ vivo (real-device acceptance 2).

### Ph6 — Photo picker and permission flow (~1 800 lines)
- **Goal**: requirements P3 and the permission part of P10.
- **Files**: `MediaRepo` (+ Coil thumbnail fetcher), `PickerScreen` + `PickerViewModel` (modes Send/CastVideo/CastPhotos; hands its selection to `SendCoordinator` [PB5]), rationale sheet, partial-access banner, settings fallback, Home wiring (`PermissionPlanner` already exists from Ph5a [PA10]); instrumented `PartialAccessBannerTest` (inserted in `suites/phone.txt` before the other media classes), `PickerAcrossAlbumsTest`, picker screenshots.
- **CI green**: all jobs.
- **Acceptance**: picker screenshots (Recent with date headers, Albums, album grid with Select all, bottom bar) reviewed; selection survives tab/album switches in the test.
- **Risks**: MediaStore paging (`Bundle` args on 30+, `LIMIT` string on 25–29, self-protecting loader [PA12] [PB23]); thumbnail loading speed on large libraries (lazy grid + memory keys); partial access semantics on API 34 emulator.

### Ph7 — From PC, downloads, installer, app update (~1 800 lines)
- **Goal**: requirements P7, C3 (app side), the installer used by T4.
- **Files**: `FromPcScreen` + `OutboxViewModel`, `DownloadSinks` (+ cleanup), `DownloadQueue` wiring in `TransferService`, `ApkInstaller`, update download/install flow, outbox-APK download rule [PB18], Settings "Share diagnostics log" via FileProvider; instrumented `FromPcDownloadTest`, `UpdateBannerTest`; `Api25LaunchTest` gains the "needs Android 10" assertion [CP17]. (No `LegacyDownloadSink`, `LegacyNameClaimTest` or `Api25DownloadTest` [CP17].)
- **CI green**: fast jobs; `[emu:phone]`, `[emu:api25]`.
- **Acceptance**: From PC screenshots; the phone run shows `report (1).pdf` created, nothing overwritten; the API 25 screenshot shows the "needs Android 10 or newer" line.
- **Risks**: `IS_PENDING` behaviour on API 29 vs 30+ (publish-only-if-complete + delete on abort is safe on both); pending-row query for cleanup: `QUERY_ARG_MATCH_PENDING` is API 30, so API 29 uses `MediaStore.setIncludePending` (deprecated but present) [PR4]; installer intents cannot be completed in CI (monitors only).

### Ph8a — Fire TV home: rows, focus, install, update banner (~1 200 lines) [PA9] [PR11]
- **Goal**: T1, T4 and the TV part of C3.
- **Files**: `TvHomeScreen` (rows, focus memory, thumbnails loader with the shared call factory [PR19], idle line, update banner), `tvClickable` [PA14], `TvInstall` dialog, `KeepScreenOn`; tests `TvFocusLogicTest`; TV suite classes 1 (`TvLaunchAndFocusTest`, which replaces Ph4's `TvLaunchTest` [PB20]; its Player step uses the Ph0b probe player until Ph8b), 5, 6.
- **CI green**: fast jobs; `[emu:tv]`.
- **Acceptance**: TV home screenshots reviewed (focus ring visible on every focusable, overscan padding, update banner).

### Ph8b — Fire TV player, resume, viewer and slideshow (~1 200 lines) [PA9] [PR11]
- **Goal**: T2, T3.
- **Files**: `TvPlayerScreen` + `PlayerHost` (OkHttpDataSource, load control, retry policy [PA18], key handling, format/sound messages), `ResumeStore` + resume dialog, `TvViewerScreen` (viewer + slideshow); tests `ResumeRuleTest`; TV suite classes 2, 3, 4, 7; `LongPauseProbe` deleted. **First sub-step, pushed on its own** [PA18]: the player screen with `PlayerView` inside `AndroidView` renders under the TV theme (platform themes, no Material Components) on the TV emulator; if the view does not inflate cleanly, use `PlayerView` for the video surface only (`useController = false`) and draw the small display-only seek bar in Compose.
- **CI green**: fast jobs; `[emu:tv]`.
- **Acceptance**: player/viewer screenshots reviewed; `TvLongPauseTest` log shows the reconnect.
- **Risks**: Compose focus restoration (the manual requester approach avoids experimental APIs; if `requestFocus` races composition, retry once after another frame); software decoding speed on the x86 TV emulator for `big.mp4` (720p, 6 Mbit/s is enough to reach 200 MB in 5 min; lower the resolution, not the size, if needed); key event delivery through `PlayerView` (blocked descendants).

### Ph9 — Casting, both sides (~1 800 lines)
- **Goal**: requirements P9, T5, A24.
- **Files**: `CastService` (+ notification actions), `ContentRangeSource`, `PlayOnTvScreen`, `RemoteScreen`, `CastViewModel`, `CastReceiverHost` (lifecycle, MulticastLock, responder on UDP 8765, server on 8766), `CastSinkImpl` (→ player/slideshow), debug `CAST_RECEIVER` extra, last-TV memory [A24], notification tap → Remote [A24]; tests `CastStateMapperTest`, instrumented `CastLoopbackTest`, Remote screenshots.
- **CI green**: all jobs.
- **Acceptance**: loopback test shows play → pause → next → stop and a photo slideshow advancing; screenshots of Play on TV and Remote. (`[emu]`: phone and TV suites.)
- **Risks**: UDP 8765 already bound on a TV (log + fallback to remembered TV); choosing the phone IP the TV can reach (`CastClient.localAddressTowardsTv` via a connected socket); `mediaPlayback` FGS rules on Android 14/15 (permission declared; started from a visible activity).

### Ph10 — Polish, documentation, release candidate (~900 lines, mostly text)
- **Goal**: C6 and a release candidate the user can test on real devices.
- **Files**: `pc/README.txt` (§2.12), `pc/DEVICE-CHECKLIST.txt` (CP12), `PhoneScreenshotTour` / `TvScreenshotTour` complete, wording pass over `strings.xml` against §4.12 and the screenshots [A22], `docs/v2-handoff.md` (how to build/run CI, the branch rule and `[emu…]` tags [H28], "never give the user an `-UNVERIFIED` artifact" [H29], never rename `android.yml` [H26], keystore backup [H13][H18], what CI does not prove [B27], where logs are, how to review screenshots), decisions-log updates, this plan's §7 ticked; pytest `test_readme.py` (required section titles present, ≤ 88 columns, no hidden flag names, mentions `ferry-log.txt` and `8765/app`).
- **CI green**: everything on `main`, artifact **`Ferry-2.0.<n>` without `-UNVERIFIED`** produced (all three emulator suites green in that same run) [CP19].
- **Acceptance**: the final zip downloaded and inspected (contains `Ferry/app/Ferry.apk`, json, README, checklist); every screenshot viewed; keystore backup handed to the user; the user runs the acceptance list (requirements §5) on the vivo, the Fire TV and the PC.
- **Risks**: wording mistakes only found on real devices (strings are in one file; vivo steps in one string [A8]).

## 7. Traceability

### 7.1 Requirements → phases → tests
| Req | Summary | Phase(s) | Automated tests (CI) | Only on real devices |
|---|---|---|---|---|
| P1 | Manual send: in-app + Share → Ferry (single/multiple) | Ph5a, Ph5b, Ph6 | `ShareToFerryTest`, `PickerAcrossAlbumsTest`, `UploadEngineTest.happyPathTwoWorkersInSelectionOrder`, `engineEndToEndSmallBatch` | vivo Gallery share (acc. 3) |
| P2 | Originals: name, date, GPS, byte-identical | Ph2a, Ph2b, Ph5a | `GpsOriginalTest` + `GpsNegativeControlTest` + `verify_inbox.py` [B1], `uploadIsByteIdentical`, `mtimeRestored`, `mtimeOmittedWhenUnknown`, `withoutLocationCountedInSummary`, `StreamingBodyTest` + `growingFileIsNeverSavedTruncated` [PB1], `originalUnreadableFailsAndIsNotSent` [PB5] | vivo Gallery provider (acc. 1, 3). **If Ph0b finds that the emulator does not redact, GPS is proven on the real phone only** (the structural `requireOriginal` test of Ph0b's risks replaces the byte check) [PB7] |
| P3 | Picker across albums, newest first; "Other files" via system chooser | Ph6, Ph5b | `PickerAcrossAlbumsTest`, `PartialAccessBannerTest`, `PermissionPlannerTest`; the "Other files" result uses the same `SendCoordinator` → `ACTION_SEND_URIS` path as `ShareToFerryTest` (the system chooser UI itself is not automated) | — |
| P4 | Background uploads (FGS), progress, speed, summary | Ph5a, Ph5b | `ScreenOffUploadTest` (foreground-service state via `dumpsys` [PB13], notification text with MB/s [PB20]), `StopDecisionTest` [PB10], `UploadEngineTest.progressThrottledTo250ms`/`speedIsThreeSecondAverage`, transfer/summary screenshots | vivo battery manager, 1 GB screen off (acc. 2) |
| P5 | Duplicate pre-check; never overwrite; `name (1).ext` | Ph1, Ph2a, Ph2b, Ph5a | `test_check_batch.py` (all, incl. the case-folding names [PB3]), `test_precheck_skipped_for_same_size_family` [PB4], `checkBatchFindsDuplicates`, `renameOnDifferentContent`, `parallelCaseClashDifferentContent`, `parallelIdenticalContent`, `GpsOriginalTest` resend, v1 `test_save_rename_skip` | — |
| P6 | Resilient: pause/resume when Wi-Fi/PC goes; no partial files | Ph2b, Ph5a | `UploadEngineTest` waiting/retry/disk-full/cancel cases, `pcGoesAwayAndComesBack` and `silentStallIsNoticedAndRecovered` (through `TcpRelay` [PB8] [PB11]), `pcRestartOnSamePort` (Linux), `cancelMidUploadLeavesNoTemp`, `diskFullLargeUpload`, `CancelAndWaitingTest` | Real Wi-Fi drop |
| P7 | Browse outbox, download to `Downloads/Ferry`, no warnings | Ph3a, Ph7 | `outboxListAndDownload`, `DownloadQueueTest` (incl. close-before-commit [PB17]), `FromPcDownloadTest`, `check_manifest.py` (cleartext). Android 10+ only [CP17] [H31]: `Api25LaunchTest` asserts the "needs Android 10 or newer" line | acc. 4 |
| P8 | Finds the PC automatically; manual fallback | Ph1, Ph3b, Ph4 | `test_discovery.py`, `DiscoveryCodecTest`, `discoveryAgainstFerryPy`, `locatorPrefersLastKnown`, `PcLocatorTest.manualDeadFallsThroughToBroadcast` [PB22], `PhoneLaunchTest` (manual address), `TvPcNotFoundTest` | **Broadcast discovery** (emulator NAT blocks it [B27]) |
| P9 | Phone → TV: videos and photo slideshow, streamed | Ph3b, Ph9 | `CastProtocolTest`, `CastFileServerTest`, `CastLoopbackTest` | acc. 8 |
| P10 | One-time battery + permission guidance | Ph4, Ph5a, Ph6 | `PermissionPlannerTest`, welcome/settings screenshots | vivo settings wording (acc. 2) |
| T1 | Remote-friendly UI | Ph8a | `TvLaunchAndFocusTest` (one click per OK, one card per D-pad press [PA14]), `TvScreenshotTour` | Fire TV remote |
| T2 | Reliable player incl. long pause, seek, resume | Ph0b (probe, on the product's OkHttp stack [PA5]), Ph8b | `TvLongPauseTest`, `TvResumeTest` | 4K/HEVC on the stick (acc. 5) |
| T3 | Photo slideshow 5 s default, next/prev, pause | Ph8b | `TvSlideshowTest` | acc. 6 |
| T4 | Install APKs from the outbox | Ph7, Ph8a | `TvInstallTest` (intent), `ApkInstaller` paths, length rule for outbox APKs [PB18] | Fire OS installer and unknown-sources screens (acc. 7) |
| T5 | Receive "play on TV"; control from TV and phone | Ph3b, Ph9 | `CastLoopbackTest`, `DiscoveryResponderTest`, `CastProtocolTest` | acc. 8 |
| T6 | TV does not send files | Ph8a | `TvLaunchAndFocusTest` asserts no "Send" node exists in TV mode (§4.14 TV #1 [PB20]) | — |
| C1 | v1 `/api/v1` behaviour kept | Ph1 | v1 pytest suite (minus deleted browser tests), `:core` integration tests | — |
| C2 | Discovery on UDP 8765 | Ph1 | `test_discovery.py` (incl. 10054 survival, bind failure, port independence), `discoveryAgainstFerryPy` | Windows Firewall UDP |
| C3 | App updates via the PC | Ph1, Ph3a, Ph4, Ph7, Ph8a | `test_app_bundle.py` (incl. `test_same_size_same_mtime_replacement` [PB2]), `test_package.py`, `appInfoAndUpdateDownload`, `AppUpdateTest`, `UpdateBannerTest` (phone), update banner in `TvLaunchAndFocusTest` + `TvScreenshotTour` (TV) [PB20], `check_apk.py` (cert + versionCode) | acc. 9 |
| C4 | Browser retired; Get-the-app page; QR → page | Ph1 | `test_get_app_page.py`, `test_removed_routes.py`, banner tests | First install from Chrome / Downloader |
| C5 | `ferry-log.txt` detailed, rotated | Ph1 | `test_request_log.py` (all), `deviceNameWithCurlyQuoteAndEmoji` | acc. 10 |
| C6 | README rewritten | Ph10 | `test_readme.py` | User reads it |
| C7 | `Ferry.bat`, stdlib-only, Python ≥ 3.8 | Ph0a, Ph1 | `pc.yml` 3.8 jobs, `test_launchers_static`, `test_stdlib_only` | — |

### 7.2 Acceptance items (requirements §5) → evidence before the user tests
| # | Acceptance | Phase | CI evidence |
|---|---|---|---|
| 1 | 20 photos from two albums with names, dates, GPS; resend → "20 already on PC" | Ph5a, Ph6 | `PickerAcrossAlbumsTest`, `GpsOriginalTest` (+ resend), `engineEndToEndSmallBatch` |
| 2 | 1 GB+ video with the screen off | Ph5b | `ScreenOffUploadTest` (300 MB), `UploadEngineTest.bigFileRunsAlone`; note MB/s screen on vs off on the vivo [A7] |
| 3 | Share → Ferry from Gallery | Ph5b | `ShareToFerryTest` (sender in another UID, so the grant is really tested [PB6]) |
| 4 | Outbox download → Downloads/Ferry, no warnings | Ph7 | `FromPcDownloadTest` (API 34; no download path below Android 10 [CP17]) |
| 5 | TV: 241.9 MB video, pause 30 s, FF, RW, no freeze | Ph0b, Ph8b | `TvLongPauseTest` (≥ 200 MB, 300 s, buffered < duration at the pause [PR7], 90 s pause, ±30 s) |
| 6 | TV slideshow, next/previous | Ph8b | `TvSlideshowTest` |
| 7 | TV installs an APK from the outbox | Ph7, Ph8a | `TvInstallTest` (up to the installer intent) |
| 8 | PC off: phone video and slideshow on the TV | Ph9 | `CastLoopbackTest` (same device; no PC involved) |
| 9 | Newer APK on the PC → phone and TV offer the update | Ph1, Ph4, Ph7, Ph8a | `test_app_bundle.py`, `UpdateBannerTest` (phone), TV update banner in `TvLaunchAndFocusTest` [PB20], `test_package.py` |
| 10 | `ferry-log.txt` shows each transfer | Ph1 | `test_request_log.py::test_transfers_logged` |

### 7.3 Deferred design-audit items handled here
| Item | Where |
|---|---|
| A22 strings | §4.12 (table), Ph10 wording pass |
| A24 remember last TV; notification → Remote | §4.3 `PlayOnTvScreen`, §4.4 `CastService`, Ph9 |
| A25 insets, overscan, BackHandler, no native libs **of Ferry's own** | §4.2 (edge-to-edge, `BackHandler`, predictive back unset), §4.9 (48/27 dp), `check_apk.py` (`lib/` allow-list: only Compose's `libandroidx.graphics.path.so`, present for both ARM ABIs [PA1] [PR1]) |
| B25 v1 leftovers, replacement tests, package.py | §1.1, §2.2, §2.11, §2.13, `test_removed_routes.py`, `test_package.py`, Ph1 |
| B27 rest (KVM, AVD cache, animations, retry, `gh run download`) | §5.2, Ph0b acceptance |
| A5 detail (typed parcelable helper) | `IntentCompat` from core-ktx 1.15, Ph5b |
| A13 detail (Compose focus APIs) | §4.9 |
| B4 detail (10k check-batch budget) | §2.14 `test_10k_inbox_time_budget` (< 3 s locally, < 15 s on CI [PR21]) + `test_one_scan_one_disk_usage` |

### 7.4 What CI does not prove (copied into the handoff, Ph10) [B27]
Broadcast discovery on the real LAN; vivo's battery manager; Fire OS installer and unknown-sources screens; real Windows Firewall prompts (TCP and UDP); the Fire TV screensaver vs the 30-min keep-screen-on; hardware decoding of 4K/HEVC/DTS on the stick; real Wi-Fi speeds (≥ 10 MB/s small-file target). Added in rev 2: the vivo Gallery's own share provider (CI shares from a helper app [PB6]); photo GPS end to end **if** the Ph0b probe shows that the emulator does not redact [PB7]; `dataSync`'s 6-hour `onTimeout` (unit-tested decision only); a real Wi-Fi drop on the phone (CI uses a TCP relay on the JVM [PB8]).
