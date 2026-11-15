# Ferry v2 code plan (rev 1): audit 05a (implementability and CI)

Reviewer: Sonnet. Scope: `v2-04-code-plan.md` against `v2-01-requirements.md`, `v2-02-design.md` (rev 2), the repo (`android/`, `.github/workflows/`, `docs/v2-ci-baseline.md`).
Method: read the whole plan; read the existing Gradle files, `android.yml` and `signing.properties` (values not copied); checked tool availability locally; web lookups where possible. Maven and Google hosts are blocked from this workspace (curl got 403 from the proxy), so no coordinate was checked directly against a repository. Every dependency version in §0.3 is a release I recognise from 2024 and is consistent with the green smoke build. Items I could not confirm are marked **uncertain**.

Overall: the plan is thorough and well sequenced (risky unknowns first). The toolchain pins are sound. The findings below are mostly about CI economics and gating, one check that will fail as written (PA1), and a few unspecified or wrong details an implementing agent would otherwise guess at.

---

## PA1 (High) §1.4 `check_apk.py`, §4.1, §0.3 "Not used … any native library [A25]", Ph4 acceptance
**Problem.** The plan asserts the APK has no `lib/` entries. Compose UI/Graphics 1.7.x depends on `androidx.graphics:graphics-path`, which ships a native library (`libandroidx.graphics.path.so`, per ABI). A Kotlin Slack thread confirms that "compose 1.7 ui-graphics now depends on graphic-path that includes .so files". The pinned BOM 2024.12.01 resolves to Compose 1.7.6, so the release APK will contain `lib/<abi>/libandroidx.graphics.path.so`. The check fails in Ph4 on every run, and the premise "no native code, so one ABI-agnostic APK" in A25 is false as stated. The APK still works on all ABIs, because the library is shipped for arm64-v8a, armeabi-v7a, x86 and x86_64; only the check and the claim are wrong.
**Fix.** Change the check to "no `lib/` entries other than an allow-list (`libandroidx.graphics.path.so`)". Or drop it and assert only the ABI set (arm64-v8a, armeabi-v7a, x86, x86_64 present, nothing else). Reword A25 in §0.3. Add a note that the Fire TV Stick's 32-bit ARM is covered by the armeabi-v7a copy.
**Verified/uncertain.** Dependency existence confirmed via the Slack thread (web). The exact Compose 1.7.6 to graphics-path 1.0.x edge is **uncertain** (mvnrepository dependency page not fetched). It is very likely correct.

## PA2 (High) §0.1 "Work directly on `main`", §5.2 triggers, §6 (all phases)
**Problem.** The plan tells agents to push several times per phase directly to `main`, and `android.yml` triggers on `android/**`, `pc/**` and the workflow file. Every push to `main` therefore runs: `core-jvm` on ubuntu and **windows**, `app-build`, and three emulator jobs (timeouts 45 + 50 + 30 min), plus the `package` job. `pc.yml` adds a 4-way matrix (two Windows). On a private repo this is roughly 2 hours of runner time per push in the worst case. Windows minutes bill at 2x. On the GitHub Free plan (2 000 min/month, **uncertain** which plan the user has) about 10 pushes would exhaust the month. Blind-compile Kotlin phases (Ph2 to Ph9) will need many red iterations. Each red push on `main` also leaves `main` broken and burns a `run_number` (hence a versionCode and a `2.0.n` version). Any `pc/**` edit re-runs every emulator suite.
**Fix.** Make working on a branch the default: `git push origin HEAD:phase-N` runs only `core-jvm` (ubuntu) + `app-build` (the plan already says emulators run only on `main` and manual runs [H27]). Use `workflow_dispatch emulators=phone|tv|…` on the branch for a targeted emulator run, and fast-forward `main` only when the branch is green. Drop `pc/**` from the `android.yml` path filter, or keep it and gate emulators on a change-filter so a README tweak does not run 2 hours of emulators. Add one paragraph estimating minutes per `main` push, and state the plan assumption. Also set `cancel-in-progress: true` for non-release branches (already) and consider it for `main` pushes that are superseded within a few minutes.
**Verified/uncertain.** Arithmetic from the plan's own timeouts: verified. Billing numbers and plan tier: **uncertain**.

## PA3 (Medium) §4.1 signing/version, §5.2 `app-build`, Ph0 vs Ph4
**Problem.** Ph0 is meant to "prove all later CI assumptions", but `assembleRelease`, the release signing config, `apksigner verify`, the pinned-certificate check, `aapt2 dump badging` and the PC zip are first exercised in Ph4, together with ~2 400 lines of new code. The smoke build only ran `testDebugUnitTest assembleDebug`. `assembleRelease` also runs `lintVitalAnalyzeRelease` (fatal-severity lint only; for example missing or invalid foreground-service type, manifest permission and exported issues). Those can fail the build in Ph4 in ways that are hard to separate from the Ph4 code.
**Fix.** Add to Ph0: signing config reading `signing/signing.properties`, `assembleRelease`, `apksigner verify --print-certs`, and a first `check_apk.py` on the smoke app (with the PA1 allow-list). If lint vital proves noisy, set `lint { checkReleaseBuilds = false }` and instead run an explicit `lintDebug` (see PA8) so failures are intentional.
**Verified/uncertain.** The Ph0 file list (read) contains no release build. Lint-vital behaviour of AGP 8.7 on `assembleRelease`: known AGP behaviour, **uncertain** whether any fatal issue will fire for this app.

## PA4 (Medium) §5.2 `package` job `needs`
**Problem.** `package` needs all three emulator jobs. A flaky emulator test (screen-off on an emulator, 90 s player pause on a software-decoding x86 TV image, GPS redaction premise) blocks the only deliverable, the user zip, even after the one automatic retry. When a `workflow_dispatch` run uses `emulators=none` (or `phone`), the skipped `needs` jobs also cause `package` to be skipped, with no hint why (a job whose dependency was skipped is skipped unless `if: ${{ !cancelled() && … }}` is used).
**Fix.** Gate `package` on `core-jvm` + `app-build` only; run the emulator suites in parallel as a separate required-looking check. Publish the zip even when an emulator job failed, with the failing suite named in the job summary, so the user can still test on real devices while the agent fixes the emulator test. Or keep the gate but add `if: ${{ !cancelled() && needs.app-build.result == 'success' && needs.core-jvm.result == 'success' && (needs.emulator-*.result == 'success' || needs.emulator-*.result == 'skipped') }}`.
**Verified/uncertain.** GitHub Actions `needs`/skip semantics: known behaviour.

## PA5 (Medium) Ph0 `LongPauseProbe`, CP2, Ph8 `TvLongPauseTest`
**Problem.** The Ph0 probe uses ExoPlayer's `DefaultHttpDataSource` (an `HttpURLConnection` stack), but the product (CP2) uses `OkHttpDataSource`. The two stacks differ in exactly what the probe tests: idle connection reuse, silent retry of a stale pooled connection, and how a server-side close mid-body surfaces. A pass or fail in Ph0 does not predict the Ph8 behaviour, and Ph0's decision ("higher retry count vs longer PC idle timeout") is made on the wrong stack.
**Fix.** Add `media3-datasource-okhttp:1.5.1` and `okhttp:4.12.0` to the Ph0 smoke app and run the probe with `OkHttpDataSource.Factory` (connect 8 s, read 20 s) plus the planned retry policy. Optionally run the old data source as a second control.
**Verified/uncertain.** Plan text read. Behavioural differences between the two stacks: **uncertain**, but the mismatch is real regardless.

## PA6 (Medium) §4.14 CP6, §4.5 permissions, `ScreenOffUploadTest`
**Problem.** `run_instrumented.sh` installs without `-g` and grants "only the permissions each class needs", but `POST_NOTIFICATIONS` (API 33+) is never in a grant list. Consequences on the API 34 emulator: (a) the first Send/Download/Play triggers the system permission dialog [H23] inside the instrumented test, which UiAutomator-based steps cannot see past; (b) `ScreenOffUploadTest` asserts "notification present during the transfer (UiAutomator)", yet with POST_NOTIFICATIONS denied the foreground-service notification is not shown in the shade (it appears only in the Task Manager). The test would fail for a reason unrelated to the product.
**Fix.** Add `pm grant app.ferry android.permission.POST_NOTIFICATIONS` to the host grant sequence for every class except one dedicated test of the denied path. State the grant list per class in a table in §4.14 (the current text describes only the media permissions).
**Verified/uncertain.** Android 13+ notification behaviour: I am confident. Exact FGS-in-shade behaviour on the API 34 emulator image: **uncertain** in detail.

## PA7 (Medium) §5.2 runners, `check_apk.py`, §0.3 actions
**Problem.** (1) `docs/v2-ci-baseline.md` records that `ubuntu-latest` is now 26.04. The smoke build passed there, but the emulator-runner + KVM udev rule + SDK `build-tools` combination is untested on that image. The plan's own Ph0 risk list does not mention the runner image. (2) `check_apk.py` hard-codes `$ANDROID_HOME/build-tools/35.0.0/{apksigner,aapt2}`; the preinstalled build-tools set differs per image and AGP 8.7.3 itself uses 34.0.0. (3) `reactivecircus/android-emulator-runner@v2.33.0`: I could not confirm that tag exists (the repo README references `@v2`; GitHub API access to the repo was refused in this session). A wrong pinned tag fails every emulator job at "set up job".
**Fix.** Pin the runner image used for the green smoke build explicitly (for example `ubuntu-24.04` if that is what works; if `ubuntu-latest` is 26.04, record that and keep `ubuntu-latest` only deliberately). Resolve build-tools by globbing the highest version under `$ANDROID_HOME/build-tools/`. In Ph0, verify the emulator-runner tag with `gh api repos/ReactiveCircus/android-emulator-runner/releases` (or use `@v2`), and write the verified tag into §0.3.
**Verified/uncertain.** Baseline file: read. Tag existence: **uncertain**. Build-tools on 26.04 image: **uncertain**.

## PA8 (Medium) §3.5, §0.1, §5.2 `app-build` (API 25 to 35 correctness tooling)
**Problem.** The API-level safety net has holes. `-Xjdk-release=1.8` plus the bytecode scan covers `:core`, but (a) the forbidden list is a hand-written sample: Java 8 APIs that are API 26+ and are missing from it include `String.join`, `Integer/Long.toUnsignedString|toUnsignedLong|compareUnsigned|divideUnsigned`, `java.util.Objects` additions, `Instant`-style callers via `Duration`, and `java.nio.file.*` users via Okio (not scanned, fine) and `Map`/`Collection` additions on 26+. The one real defence is a complete signature check. (b) `:app` has no API check at all: no `lintDebug`/`NewApi` is run, so API 26+ calls on the Fire OS 6 path, foreground-service type constants, and API 29+/30+/33+ MediaStore/permission calls are only caught by the single API 25 launch/download tests. (c) Kotlin stdlib extensions that map to newer JDK methods (for example `String.format` locale overloads) are not reasoned about.
**Fix.** Add `:app:lintDebug` to `app-build` with `lint { abortOnError = true; checkOnly += listOf("NewApi", "InlinedApi", "MissingPermission", "ForegroundServicePermission", "ExportedService", "UnsafeProtectedBroadcastReceiver"); baseline none }` (lint tools resolve on Google Maven at CI time). For `:core`, either use animal-sniffer with an Android API 25 signature (published as `net.sf.androidscents.signature:android-api-level-25`; **uncertain** of exact coordinate/version) or extend the scan list with the items above and say it is a best-effort list.
**Verified/uncertain.** `String.join` and the unsigned-integer helpers being API 26: I am confident. Lint rule names: confident. Animal-sniffer coordinate: **uncertain**.

## PA9 (Medium) §6 phase sizing: Ph0, Ph2, Ph5 (and Ph3, Ph8)
**Problem.** With no local compile, one agent session must produce and green-light: Ph2 ≈ 2 600 lines including `UploadEngine` (concurrent state machine, 25 unit tests) and 15 integration tests on two OSes; Ph5 ≈ 2 600 lines plus five emulator classes with host-side permission choreography; Ph3 ≈ 2 400 lines of servers, discovery, cast and downloads; Ph8 ≈ 2 400 lines of TV UI with focus and player. Ph0 (600 lines of code, but three emulator jobs, three probes, fixtures, v1 test fixes and workflow rework, all of which only prove themselves after multi-minute CI cycles) mixes a safe task (Windows test fixes, `.gitattributes`) with the riskiest infrastructure.
**Fix.** Split: Ph0a (v1 portability fixes, `.gitattributes`, `pc.yml` matrix: no Android) and Ph0b (emulator jobs and probes). Split Ph2 into Ph2a (models, HTTP client, `StreamingBody`, `FerryPcProcess`, simple integration tests) and Ph2b (`UploadEngine` + engine tests + the resilience integration tests). Split Ph5 into 5a (service, notifications, locks, factory, location choice) and 5b (share target, transfer screen, the emulator classes). Keep each at roughly 1 500 lines or less.
**Verified/uncertain.** Line counts are the plan's own.

## PA10 (Medium) §6 ordering, §4.14 `run_instrumented.sh`, §4.6
**Problem.** Hidden dependencies and unspecified items an implementer would guess at:
- `PermissionPlanner` (Ph6) is needed in Ph5 (`needsLocationChoice`, the `ACCESS_MEDIA_LOCATION` probe, `LocationChoiceDialog` "Allow" request). Ph5's tests also depend on `READ_MEDIA_*` being granted by the host, not on any in-app flow.
- §4.14 lists classes `3b` and `3` (Ph6) in the phone run order that Ph5 must already follow. The plan never says how `run_instrumented.sh` learns the class list (hard-coded list that grows per phase? annotation scan?). Two phases will edit the same script ordering.
- How instrumented tests inject the send without the picker (Ph5, before the Ph6 picker exists) is not stated: `ACTION_SEND_URIS` intent from the test, or a debug hook? `GpsNegativeControlTest` says "`UploadItemFactory` reports 'needs location choice'" which needs a testable factory API, not shown in §4.7.
- `ConnectionManager`/`TransferService` expose state through `AppGraph` singletons, but the plan never says how instrumented tests reset them between classes in one process (each `am instrument -e class` is a fresh instrumentation but the app process may be reused).
- Release APKs from Ph4 to Ph9 are packaged into `Ferry-2.0.n.zip` with "Coming in this build" buttons and the app auto-offers the next one, but nothing marks the zips as not-for-the-user (Ph4 says only "not given to user").
**Fix.** Move `PermissionPlanner` to Ph5. Specify the class list as a text file `android/ci/suites/<suite>.txt` read by the script, and say each phase appends to it. Add a "debug test hook" paragraph (a `@VisibleForTesting` entry point on `TransferService` and `UploadItemFactory`). Name the artifacts of pre-Ph10 builds `Ferry-2.0.n-wip.zip` (or add a `.wip` marker) until Ph10.
**Verified/uncertain.** Plan text read; dependency chain traced by hand.

---

## PA11 (Low) §3.1, §4.1 Gradle details
**Problem.** `sourceCompatibility = targetCompatibility = 1.8` does not compile in Kotlin DSL (needs `JavaVersion.VERSION_1_8`; the plan abbreviates). `freeCompilerArgs += "-Xjdk-release=1.8"` must be written for the `compilerOptions { freeCompilerArgs.add(...) }` API. `-Xjdk-release` exists since Kotlin 1.7, so 2.0.21 supports it; with JDK 17 `options.release.set(8)` is valid (JDK 21 prints an obsolete-source warning only). `implementation(kotlinx-serialization-json)` in `:core` is not on `:app`'s compile classpath: if `:app` calls `Json.encodeToString` (the plan has `ACTION_DOWNLOAD` carry an `OutFile` as a JSON string) it will not compile; `core.Json` helpers or `api(...)` fix it. The `ferry.requirePython` default is `env CI ?: "false"`; in Kotlin DSL this is `System.getenv("CI") ?: "false"`. The existing root `build.gradle.kts` must gain `org.jetbrains.kotlin.jvm` and `...plugin.serialization` with `apply false` at the same 2.0.21; the plan says this, fine.
**Fix.** Give the exact snippets in §3.1 (a 10-line block), and make `:core` expose serialization with `api`.
**Verified/uncertain.** Read existing Gradle files; Kotlin DSL typing: confident. KGP 2.0.21 with Gradle 8.14.3: the smoke build proves the Android plugin path; the JVM plugin path is **uncertain** (should only warn).

## PA12 (Low) §4.7 MediaStore paging
**Problem.** For API ≥ 26 the plan pages with `Bundle` args (`QUERY_ARG_LIMIT/OFFSET`). I believe MediaProvider honours LIMIT/OFFSET in the Bundle form reliably only from Android 11 (API 30); on 26–29 the default `ContentProvider` bridge ignores them, so every page returns all rows and the loader would duplicate or never terminate. The `LIMIT n OFFSET m` sort-order hack works on ≤ 29 and throws `IllegalArgumentException` on 30+. The real phone is API 35, so this affects only emulator or old-phone paths.
**Fix.** Use the Bundle form on 30+, the `sortOrder` hack on ≤ 29, and guard the loader (stop when a page returns fewer than the limit or an already-seen `_ID`). Or load a projection of `_ID`+date only in one query (no paging) and fetch the rest lazily.
**Verified/uncertain.** **Uncertain** (from memory of MediaProvider behaviour, not tested).

## PA13 (Low) Over-engineering: legacy phone paths, desugaring, RingLog
**Problem.** Requirements: the phone is a vivo X200 Pro (recent Android); API 25 exists only for Fire OS 6 TVs, and the TV never sends files or downloads to Downloads (T4 uses `filesDir/apk`). The following are therefore dead code on every real device but cost implementation, tests and risk: `LegacyDownloadSink` (+ `LegacyNameClaimTest`, `Api25DownloadTest`, the `WRITE_EXTERNAL_STORAGE` request flow), the 25–28 thumbnail path (`Thumbnails.getThumbnail`), the 25–28 and 29–32 branches of `PermissionPlanner`, the API 25 MediaStore paging branch, and the Fire OS 6 install instructions are real but the others are not. Also `desugar_jdk_libs` is a "safety net" on top of a bytecode scan and `-Xjdk-release`; it adds an L8 dex step to both APKs and androidTest. Design §3.2 mandates some of this, so the choice is the owner's.
**Fix.** Keep one API 25 behaviour only where a real Fire OS 6 TV runs it (launch, locate, outbox list/play, APK install, cast receive). Replace `LegacyDownloadSink` by a minimum: on API < 29 the From PC screen shows "Needs Android 10 or newer" (the phone never hits it). Drop `desugar_jdk_libs` if PA8's lint and scan pass. State the decision in the decisions log.
**Verified/uncertain.** Requirements §1 and §3 read. Whether the owner wants old phones supported: not stated, so this is a recommendation.

## PA14 (Low) §4.9 `tvClickable`
**Problem.** `tvClickable` combines `focusable()`, `onPreviewKeyEvent` (KeyUp of DPAD_CENTER/Enter/NumPadEnter → onClick) and `clickable`. In Compose, `clickable` is itself focusable and already handles DPAD_CENTER/Enter on key up. As specified, a remote OK press can fire `onClick` twice (preview handler plus `clickable`) unless the preview handler returns `true`, and the extra `focusable()` can create two focus stops per card (D-pad needs two presses to pass one card).
**Fix.** Use only `clickable` (it supplies the focus target and key handling) plus `onFocusChanged` for the ring. If the preview handler is kept, it must consume the event and the extra `focusable()` must go. Add a TV test asserting one `onClick` per OK press and one D-pad move per card.
**Verified/uncertain.** Compose 1.7 `clickable` key handling: confident. Double-focus-stop effect: **uncertain** until run on the TV emulator.

## PA15 (Low) §4.3 `FromPcScreen`
**Problem.** `PullToRefreshBox` is `@ExperimentalMaterial3Api` in material3 1.3.x; using it without `@OptIn` is a compile error. The screen already auto-refreshes every 5 s, so pull-to-refresh adds nothing required.
**Fix.** Drop pull-to-refresh (keep the 5 s refresh and a visible Refresh button), or specify the opt-in.
**Verified/uncertain.** Material3 1.3.1 (BOM 2024.12.01): confident it is still experimental; not re-checked online.

## PA16 (Low) §4.14 host mechanics, §5.2 emulator config
**Problem.** (a) Fixtures are copied by the shell (`cp` from `/data/local/tmp`) and indexed with `MediaScannerConnection.scanFile` from the app: on API 30+ the app may be refused scanning files it does not own; a host-side `adb shell content call --uri content://media/ --method scan_volume --arg external_primary` (or `cmd media.scan` where present) is the robust form. Ph0's `RedactionProbe` would reveal this, but it is cheap to fix up front. (b) Screenshots are pulled with `run-as app.ferry tar`; `tar` may be missing in API 25's shell (**uncertain**), whereas writing to `getExternalFilesDir()` and `adb pull /sdcard/Android/data/app.ferry/files/screenshots` needs neither `run-as` nor `tar` (reading `Android/data` over adb works for the shell user on API 30+). (c) The TV job passes no `profile`; use `profile: tv_1080p` (the AVD should be 1080p/xhdpi, not a phone profile) and consider `ram-size: 3072M`. (d) `testOptions.animationsDisabled` does nothing for a host-run `am instrument`; the runner's `disable-animations: true` is the one that counts (already set). (e) `am instrument -r` output: parse `INSTRUMENTATION_CODE: -1` and the absence of `INSTRUMENTATION_STATUS_CODE: -2`/`-1` rather than the literal "OK (" text.
**Fix.** Adopt (a) to (e) in §4.14/§5.2.
**Verified/uncertain.** All **uncertain** from memory of platform behaviour; each is cheap to confirm in Ph0.

## PA17 (Low) Ph0 `make_big_video.sh`
**Problem.** "≥ 200 MB H.264+AAC video generated in CI" has no recipe. A synthetic test pattern (`testsrc`) at 720p compresses to well under 1 Mbit/s, so five minutes of it is a few MB, not 200. The script needs a high-entropy source (for example `geq`/`noise` filter) with CBR (`-b:v 6M -minrate 6M -maxrate 6M -bufsize 12M`), then an assertion that the output is ≥ 200 MB. Encoding noise for 5 min at 720p on a 2-core runner takes minutes with `-preset ultrafast`. The user's real file is 241.9 MB. Locally, `ffmpeg` and Pillow are available (`/usr/local/bin/ffmpeg`, Pillow 12.3.0), so `make_fixtures.py` is runnable here as planned.
**Fix.** Put the ffmpeg command line in the plan and add a size assertion; cache by script hash (already planned).
**Verified/uncertain.** Local tool presence: verified. Compression claim for `testsrc`: standard behaviour, not measured here.

## PA18 (Low) §4.10 Media3 details
**Problem.** `FerryLoadErrorPolicy` is described as "retry IOExceptions up to 8 times with 1 s delay". A custom `LoadErrorHandlingPolicy` must also override `getMinimumLoadableRetryCount(dataType)` (used by the progressive period to decide when to surface the error) and `getRetryDelayMsFor` must return `C.TIME_UNSET` to stop; with only one overridden the player either gives up after the default count or never stops. Also `media3-ui` with platform (non-Material, non-AppCompat) themes: whether `PlayerView`/`PlayerControlView` inflate cleanly under `android:Theme.Material.Light.NoActionBar` and the TV dark theme is **uncertain** (the plan chose platform themes to avoid Material Components). Add a `PlayerView` inflation check to the first TV player test, or use `media3-ui` only for the surface and draw the controller in Compose.
**Fix.** Spell out both overrides in §4.10. Make Ph8's first sub-step "player screen renders with PlayerView on the TV theme" before building the rest.
**Verified/uncertain.** `LoadErrorHandlingPolicy` interface shape: confident. Theme question: **uncertain**.

## PA19 (Low) CI hygiene collected
- `app-build` runs `python -m pytest -q android/ci` but has no `pip install pytest` step (setup-python does not install it).
- `check_apk.py` compares to `signing/cert-sha256.txt` stored colon-separated upper-case; `apksigner --print-certs` prints lower-case hex without colons. Specify the normalisation.
- `check_manifest.py` globs `merged_manifest/release/**` (the "main manifest" output). The final merged manifest of AGP 8.x is under `intermediates/merged_manifests/release/**/AndroidManifest.xml` (plural); more robust is to always use `aapt2 dump xmltree` on the built APK (it tests what ships). Make that the primary and drop the glob.
- Actions pinned at v4/v5 are fine today; newer majors exist (the emulator-runner README already shows checkout/cache/setup-gradle majors one or two ahead). Node 20 deprecation warnings are expected; no action needed unless runs fail (**uncertain**).
- `pcGoesAwayAndComesBack` restarts `ferry.py` on the old port: on Windows with `SO_EXCLUSIVEADDRUSE` the rebind right after a kill may fail while the old connections drain (**uncertain**). Retry the bind for up to 10 s in the test harness, or let the restarted process wait for the port.
- Size of `UploadEngine` timing tests: all use virtual time, good; the only real-time tests are the stalled-reader (35 s) and the PC-restart (60 s) tests; keep them in one slow test class so a run can skip them locally.
**Fix.** As listed.
**Verified/uncertain.** Mixed; items marked uncertain above.

---

## Checked and found sound (no finding)
- AGP 8.7.3 + Kotlin 2.0.21 + Gradle 8.14.3 + compileSdk 35 + BOM 2024.12.01 + Media3 1.5.1: proven by the green smoke build (baseline doc). Other pins (coroutines 1.9.0, serialization 1.7.3, OkHttp 4.12.0, core-ktx 1.15.0, lifecycle 2.8.7, activity-compose 1.9.3, Coil 2.7.0, androidx.test 1.6.x, UiAutomator 2.3.0) are consistent with each other and with minSdk 25 (OkHttp 4.12 supports API 21+; Compose and Media3 1.5 support API 21+). Not checked against a repository (**uncertain**, hosts blocked).
- `:core` as a Kotlin/JVM module consumed by the Android app (`implementation(project(":core"))`) with Java 8 bytecode: standard and works with D8 at minSdk 25.
- Manifest, FGS types and permissions for API 34/35 (`dataSync` + `FOREGROUND_SERVICE_DATA_SYNC`, `mediaPlayback`, `onTimeout(int,int)` on API 35, `PendingIntent.getForegroundService`, `ServiceCompat.startForeground`), FileProvider + `ACTION_VIEW` install path from API 25, `ACTION_MANAGE_UNKNOWN_APP_SOURCES` inside try/catch, `READ_MEDIA_VISUAL_USER_SELECTED`, `setRequireOriginal`, `MediaStore.Downloads` + `IS_PENDING`: consistent with API 25–35 (apart from the paging note, PA12).
- Compose TV focus: the plan's stable-API-only list (`focusRequester`, `focusProperties`, `focusGroup`, `onPreviewKeyEvent`, manual restoration) is right for 1.7.6; `focusRestorer` is experimental there.
- versionCode scheme `20000 + run_number` fits an Int for decades; the test json value 2 000 000 000 is below the Int limit (2 147 483 647).
- Emulator targets exist as named: API 25 `default` x86, API 30 `android-tv` x86, API 34 `google_apis` x86_64; the runner accepts `android-tv` (README). The cached-AVD pattern and the udev KVM rule match the runner's documented setup.

## Summary table
| ID | Severity | Plan section | One-line |
|---|---|---|---|
| PA1 | High | §1.4 / §4.1 / A25 | "No `lib/`" check fails: Compose 1.7 ships `libandroidx.graphics.path.so` |
| PA2 | High | §0.1 / §5.2 | Direct-to-`main` + 3 emulators + Windows per push: cost, broken `main`, burned versions |
| PA3 | Medium | Ph0 vs Ph4 | Release build, signing, lint-vital, `apksigner` first run in Ph4, not in the spike |
| PA4 | Medium | §5.2 `package` | Zip gated on every emulator suite; skipped `needs` skips the job silently |
| PA5 | Medium | Ph0 / CP2 | Long-pause probe uses `DefaultHttpDataSource`, product uses OkHttp |
| PA6 | Medium | §4.14 CP6 | `POST_NOTIFICATIONS` never granted: dialog in tests, notification assert fails |
| PA7 | Medium | §5.2 / §0.3 | Runner image 26.04 unproven; hard-coded build-tools; emulator-runner tag unverified |
| PA8 | Medium | §3.5 / §5.2 | API-level net has holes (`String.join` etc.); no `lintDebug` for `:app` |
| PA9 | Medium | §6 sizing | Ph0, Ph2, Ph5 (also Ph3, Ph8) too big for blind-compile sessions: split |
| PA10 | Medium | §6 / §4.14 | `PermissionPlanner` needed in Ph5; class-list mechanism, test hooks, WIP zips unspecified |
| PA11 | Low | §3.1 / §4.1 | Kotlin DSL snippets wrong or abbreviated; serialization not `api` |
| PA12 | Low | §4.7 | Bundle LIMIT/OFFSET likely ignored on API 26–29 (uncertain) |
| PA13 | Low | §4.5–4.7 | Dead legacy phone paths, desugaring: over-engineering for the real devices |
| PA14 | Low | §4.9 | `tvClickable` can double-fire and double-focus |
| PA15 | Low | §4.3 | `PullToRefreshBox` is experimental and unneeded |
| PA16 | Low | §4.14 / §5.2 | Fixture scan, screenshot pull, TV profile, `am instrument -r` parsing |
| PA17 | Low | Ph0 | Big-video recipe missing (testsrc would be tiny) |
| PA18 | Low | §4.10 | Load-policy overrides incomplete; `PlayerView` under platform themes uncertain |
| PA19 | Low | §5.2 misc | pytest install, cert format, manifest path, Windows rebind, slow-test grouping |

Counts: High 2, Medium 8, Low 9 (total 19).

Scope: implementability and CI. Method: read the audit brief, requirements, the plan in full, the existing Gradle/CI files in `android/` and `.github/`, and `v2-ci-baseline.md`. Maven and Google hosts are blocked from this workspace and the GitHub API is not enabled for this session. Dependency coordinates were therefore NOT resolved here; version claims below are from memory and marked "uncertain" where it matters. I did not open any v2-05 file other than this one.

Overall: the plan is strong and unusually well specified. The pinned versions I know are real and mutually compatible (Compose BOM 2024.12.01 with Kotlin 2.0.21, Media3 1.5.1, OkHttp 4.12.0, Coil 2.7.0, coroutines 1.9.0, serialization 1.7.3, core-ktx 1.15.0, activity 1.9.3, lifecycle 2.8.7, androidx.test 1.6.x, uiautomator 2.3.0, desugar_jdk_libs 2.1.4; uncertain only in that none were resolved here). The findings below are mostly about places where an implementing agent will hit a red CI run or a wrong API.


---
# Second Sonnet run (relaunched after a workspace restart; ran independently of the first). IDs renamed PR1..PR21 to avoid clashes.

## Findings

### PR1 — High — §1.4 `check_apk.py`, §0.3, §6 Ph4 (the "no `lib/` entries" check [A25])
**Problem.** `check_apk.py` asserts the release APK has no `lib/` entries. Compose UI 1.7.x (BOM 2024.12.01 = 1.7.6) pulls in `androidx.graphics:graphics-path`, which ships a native library (`libandroidx.graphics.path.so` for all four ABIs). The check will therefore fail in Ph4 on the first release build, and §0.1 forbids weakening a test to get green. The plan's claim "any native library [A25]" is not met by its own pinned toolchain.
**Suggested fix.** Change the assertion to what A25 actually needs: no native libs *from Ferry's own dependencies other than an allow-list* (`libandroidx.graphics.path.so`), or assert that every `lib/<abi>/` set contains all four ABIs (armeabi-v7a is the one that matters for Fire OS 6 and some 32-bit-userspace sticks). State the reason in the plan.
**Verified/uncertain.** Uncertain (from memory of the Compose 1.7 dependency graph and the 16 KB-alignment reports on that library). Cheap to confirm in Ph0: run `unzip -l` on the smoke APK after adding `ui` and `foundation`.

### PR2 — Medium — §4.2 `NetworkBinder` rule vs §6 Ph4 risks and §4.14 API 25 suite
**Problem.** The two places disagree. §4.2 says only "no network" or "cellular only" shows "Your phone is not on Wi-Fi", and the fallback is "the active network if it is not cellular". Ph4 risks say the default network is used on API 25 when no Wi-Fi `Network` exists. On older emulator images (API 25 `default` x86) the emulated connection is, as far as I recall, reported as a mobile/cellular transport, so the rule as written would show "not on Wi-Fi" and every API 25 instrumented test (and `Api25LaunchTest`, `CoreOnApi25Test`) would fail on a harness artefact, not a product bug.
**Suggested fix.** Make the rule explicit and testable: when `Prefs.manual` is set (all emulator tests set it) skip the Wi-Fi gate and use the default network; keep "not on Wi-Fi" for the auto-discovery path only. Add a pure unit test for the decision function (`NetworkChoiceTest`). Alternatively add a debug-only extra `app.ferry.debug.NET=default`.
**Verified/uncertain.** Uncertain (emulator image transport type on API 25 not verified here).

### PR3 — Medium — §4.7 UploadItemFactory: `MediaStore.getMediaUri` "on 29+"
**Problem.** `MediaStore.getMediaUri(Context, Uri)` was added in API 31, not 29 (the platform API diff for level 31 lists it). Calling it on API 29–30 is a `NoSuchMethodError` at runtime. Same text appears in design §4.3. Not hit on the vivo (Android 15) or on Fire OS 6/7/8 (no Share target use), but it is a latent crash and an agent will copy it.
**Suggested fix.** Guard with `SDK_INT >= 31`; on 29–30 fall back to the plain document URI and the "may lose location" line (or `DocumentsContract` ID parsing for `com.android.providers.media.documents`).
**Verified/uncertain.** Uncertain (search result confirmed the API-31 diff page exists; I did not read the entry).

### PR4 — Medium — §4.7 cleanup of pending downloads: `QUERY_ARG_MATCH_PENDING` "29+"
**Problem.** `MediaStore.QUERY_ARG_MATCH_PENDING` is API 30. §4.7 says "29+ … (`QUERY_ARG_MATCH_PENDING`)". Ph7 risks mention `setIncludePending` for 29, so the plan is internally inconsistent, and the body text (what an agent codes from) is the wrong one.
**Suggested fix.** In §4.7 say: API 30+ `QUERY_ARG_MATCH_PENDING`; API 29 `MediaStore.setIncludePending(uri)`.
**Verified/uncertain.** Verified from memory (fairly sure).

### PR5 — Medium — §3.1 / §0.3 `:core` exposes `Json` to `:app` but declares serialization as `implementation`
**Problem.** `core/build.gradle.kts` uses `implementation(kotlinx-serialization-json)` while `:core` exposes `Json.kt` (a public `Json` instance) and `@Serializable` models; `:app` needs `OutFile` as a JSON string for `ACTION_DOWNLOAD` (§4.4) and models from `Protocol.kt`. With `implementation`, the `:app` compile classpath does not contain `kotlinx-serialization-*`, so any use of `Json`, `serializer()` or `KSerializer` from `:app` fails with "Cannot access class … Check your module classpath". It will be a red Ph4/Ph5 build and an agent might "fix" it by adding a dependency, which §0.1 forbids without a log entry.
**Suggested fix.** Declare `api(kotlinx-serialization-json)` in `:core`, or give `:core` helper functions (`OutFile.toJson()`, `OutFile.fromJson()`), and say which.
**Verified/uncertain.** Verified (standard Gradle `implementation` vs `api` behaviour).

### PR6 — Medium — §5.2 `package` job gating (`needs` all three emulator jobs) and §0.1 "phase done = main green"
**Problem.** The user's zip is produced only if phone, TV and API 25 emulator suites all pass on `main`. Emulator suites on Actions are routinely flaky (one automatic retry is the only mitigation), and the TV suite depends on a 200 MB video playing in a software-rendered emulator. A single flaky class blocks the only deliverable and the update path, even when the APK is fine. For a one-user tool whose priority 2 is simplicity, this is the wrong failure mode, and "re-run failed jobs" keeps the same `run_number`, which is fine, but a manual `workflow_dispatch` with `emulators=none` skips `package` entirely (skipped `needs`), so there is no escape hatch.
**Suggested fix.** `package` needs only `core-jvm` and `app-build`; emulator results are advisory (publish them in the job summary, and name the zip or add a `VERIFIED.txt` when all passed). Or add `if: always() && needs.app-build.result == 'success' && needs.core-jvm.result == 'success'` plus an explicit `dispatch` input `package: true`.
**Verified/uncertain.** Verified by reading the workflow design.

### PR7 — Medium — §4.14 TV suite `TvLongPauseTest` / §6 Ph0 `LongPauseProbe` / `make_big_video.sh` (under-specified fixture)
**Problem.** The plan fixes only "≥ 200 MB, H.264+AAC". Whether the pause test exercises the PC's 60 s silence timeout depends on bitrate and duration: ExoPlayer stops loading at `maxBuffer` (50 s here). If the file is short in duration (for example 200 MB at 40 Mbit/s is about 40 s), the whole file is buffered during the first seconds, the connection finishes, and the 90 s pause tests nothing. Only Ph8's Risks paragraph gives "720p, 6 Mbit/s" (≈ 270 s); Ph0, where the probe is written first, and §4.14 do not. Also, `ffmpeg testsrc` compresses so well that reaching 200 MB needs CBR padding or noise.
**Suggested fix.** Put the recipe in §4.14 and use it in Ph0: duration ≥ 300 s, `-b:v 6M -minrate 6M -maxrate 6M -bufsize 12M -x264-params nal-hrd=cbr` (or `-vf noise=alls=20`), 1280x720, `-movflags +faststart` for one fixture and no faststart (moov at end) for a second small one so the seek path is covered. Assert `duration * bitrate` and size in `make_big_video.sh`. Add to the test: assert the player's buffered position is < duration when the pause starts.
**Verified/uncertain.** Verified by arithmetic; PC timeout from `SOCKET_TIMEOUT = 60` in `pc/ferry.py`.

### PR8 — Medium — §5.2 `app-build` uses `$ANDROID_HOME/build-tools/35.0.0/{apksigner,aapt2}`
**Problem.** AGP 8.7.3's default build-tools is 34.0.0 and the runner image's preinstalled build-tools set changes over time (images keep the newest few; with "ubuntu-latest" now 26.04 the set is unknown). A hard-coded `35.0.0` path can simply not exist, which fails `check_apk.py` in Ph4 for a reason unrelated to the APK.
**Suggested fix.** Resolve at run time: `BT=$(ls -d $ANDROID_HOME/build-tools/* | sort -V | tail -1)`, or `sdkmanager --install "build-tools;35.0.0"` as a step (licences are accepted on runners). Mention in `check_apk.py` that it takes `--build-tools` as an argument.
**Verified/uncertain.** Uncertain (runner image contents not checkable from here).

### PR9 — Medium — §4.14 phone suite `GpsNegativeControlTest` / Ph0 `RedactionProbe`: ownership of the scanned file
**Problem.** The test copies `gps.jpg` into `DCIM/Camera` with `uiAutomation.executeShellCommand("cp …")` and then calls `MediaScannerConnection.scanFile` from the instrumented process (uid = `app.ferry`). It asserts `OWNER_PACKAGE_NAME != "app.ferry"`. On API 30+ MediaProvider sets the owner from who created the file (FUSE) or from the scan caller depending on version, so the owner may come out as `app.ferry` (or null and not readable), and then Ferry has full access to its "own" file and no redaction happens. The plan does handle "redaction not observed → build fails inconclusive" but that turns an environment property into a red build in Ph5 rather than Ph0, and the probe is the only place the premise is checked.
**Suggested fix.** Make the Ph0 probe the decision point and record the observed `OWNER_PACKAGE_NAME` and bytes. Prefer to have the host push the file (`adb push` to `/sdcard/DCIM/Camera/` is shell-owned) and trigger the scan from the host (`adb shell content call` / `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE`, or `cmd media_provider` where available). If redaction cannot be reproduced, say up front that B1's negative control degrades to the positive byte-identical + GPS check (already allowed in Ph0 risks) and do not make Ph5 depend on it.
**Verified/uncertain.** Uncertain.

### PR10 — Medium — §4.7 Picker: "`LazyVerticalGrid` with sticky date headers"
**Problem.** `LazyVerticalGrid` has no `stickyHeader` in Compose foundation 1.7.x (only `LazyColumn`/`LazyRow` do; grid has full-span `item(span = …)`). An agent will either fail to compile or switch to an experimental or custom solution.
**Suggested fix.** Say "date headers are full-span items (not sticky)", or use a `LazyColumn` of rows-of-N for Recent. Requirement P3 only says newest first; design §4.1 "sticky" is nice-to-have.
**Verified/uncertain.** Uncertain (from memory of foundation 1.7 API surface).

### PR11 — Medium — §6 phase sizing and the CI feedback loop
**Problem.** Kotlin cannot compile locally, so every Kotlin line is validated by an Actions round trip. Ph2 (~2 600 lines + 25 engine tests + 16 integration tests + the Windows harness), Ph3 (~2 400), Ph5 (~2 600) and Ph8 (~2 400) are each far above what one agent session reliably writes compile-clean; each push's wall time is long (core-jvm on two OSes plus three emulator jobs of 30–50 min budget each). Windows-only failures in Ph2 (process teardown, file locks, UDP) multiply the loops. There is no instruction on how an agent resumes a half-done phase.
**Suggested fix.** (a) Split Ph2 into 2a (models, http, `FerryClient`, `StreamingBody`, harness, first integration tests) and 2b (`UploadEngine` + its tests). Split Ph8 into 8a (home, focus, install) and 8b (player, slideshow). (b) Let early phases skip emulator jobs on `push` unless `android/app/**` or `android/ci/**` changed (path filter via `dorny/paths-filter` or a small `changes` job), so a `:core`-only push is minutes, not an hour. (c) Add a one-paragraph "resuming a phase" rule: commit a `docs/v2-progress.md` line per sub-step.
**Verified/uncertain.** Judgement.

### PR12 — Medium — §5.2 triggers: `pc/**` also runs the whole `android.yml`
**Problem.** `paths` includes `pc/**` so that the zip has a fresh APK, but that means every Python tweak runs `core-jvm` ×2, `app-build` and all three emulator suites. In a private repo on the free plan (2 000 Linux minutes per month, Windows jobs count 2×, emulator jobs 30–50 min each) a normal phase of several pushes can exhaust the quota; then CI is simply off. The user's plan tier is unknown.
**Suggested fix.** Split into a light `package` path for `pc/**` (re-use the latest `ferry-apk` from the previous successful `main` run with `gh run download`, or rebuild the APK without emulators) and run emulators only for `android/**`. Record the expected minutes per push in §5.2.
**Verified/uncertain.** Uncertain (plan limits depend on the account).

### PR13 — Low — §5.2 emulator jobs: runner OS and TV profile
**Problem.** (1) Emulator jobs use `ubuntu-latest`, which the baseline says is now 26.04; KVM and the emulator-runner action on a brand-new image are the most likely Ph0 surprise. (2) The TV job gives no `profile`; for `android-tv` images emulator-runner users generally pass a TV profile (`tv_1080p`). With the default phone hardware profile the AVD may have touchscreen/keyboard config that changes `hasSystemFeature` and focus behaviour. The phone job does specify `pixel_6`.
**Suggested fix.** Pin emulator jobs to `ubuntu-24.04` (also stated in the pc.yml matrix for `ubuntu-22.04`, which will itself be retired at some point; note "revisit by 2027"). Add `profile: tv_1080p` to the TV job and `profile: Nexus 6`/`pixel` to API 25 explicitly. Ph0 acceptance should log `getprop ro.build.characteristics` and `pm list features | grep -i leanback`.
**Verified/uncertain.** Uncertain. `reactivecircus/android-emulator-runner@v2.33.0` is a real tag as far as I recall (late 2024); I could not list releases from here, so check that no newer v2.x line is required for the runner image.

### PR14 — Low — §4.11 / §5.2 merged-manifest path
**Problem.** §5.2 says "merged release manifest" without a path; Ph4 risks use `merged_manifest/release/**` (singular). In AGP 8.x the intermediates are `merged_manifest/<variant>/process<Variant>MainManifest/` (before test merge) and `merged_manifests/<variant>/process<Variant>Manifest/` (plural, final). Which one has the injected permissions and `application` attributes the check expects is something an agent must guess.
**Suggested fix.** Make the packaged APK the primary source: `aapt2 dump xmltree --file AndroidManifest.xml app-release.apk` (or `aapt2 dump permissions`/`badging` for permissions and uses-features), and only fall back to the intermediates. This also removes a dependency on AGP internals when the build is upgraded.
**Verified/uncertain.** Uncertain (path names from memory).

### PR15 — Low — §4.4/§4.7 `ForegroundService`, PendingIntent and `onTimeout` details
**Problem.** Minor items an agent may get wrong: (1) `Service.onTimeout(int, int)` exists only from API 35; with compileSdk 35 it compiles, but the plan should say "override `onTimeout(startId)` (API 34) as well as `(startId, fgsType)`" since on API 34 the single-arg `dataSync` time-limit callback is what fires for the 6 h limit; (2) `ServiceCompat.startForeground` with a type needs `FOREGROUND_SERVICE_DATA_SYNC` on 34+ (declared) and, on API 29–33, passes the type from the manifest, which is fine; (3) Media3 1.5.1 classes are `@UnstableApi`, so Kotlin files need `@OptIn(UnstableApi::class)`; lint (`UnsafeOptInUsageError`) is not run by the build, but the opt-in is needed to avoid compile warnings becoming noise; (4) `PullToRefreshBox` is `@ExperimentalMaterial3Api` in material3 1.3.1 and needs an opt-in.
**Suggested fix.** Add these four one-liners to §4.4, §4.9–4.10 and §4.3.
**Verified/uncertain.** Verified from memory except (1), uncertain.

### PR16 — Low — §3.6 `pcGoesAwayAndComesBack`: restarting `ferry.py` on the same port on Windows
**Problem.** The test restarts ferry.py on the same `--port <old>` after 5 s. On Windows, ferry.py binds with `SO_EXCLUSIVEADDRUSE` (baseline), and exclusive binds can fail with "address in use" while connections from the killed process sit in TIME_WAIT, which would make this real-time test flaky on the `windows-latest` job (the plan's own acceptance wants Windows green).
**Suggested fix.** Retry the restart for up to 60 s, or run this one test on Linux only (Windows behaviour of reconnect is covered by `UploadEngineTest`), and say so in §3.6.
**Verified/uncertain.** Uncertain.

### PR17 — Low — §0.3 table omits plugins the build already needs
**Problem.** The existing smoke build uses `org.jetbrains.kotlin.android` and `org.jetbrains.kotlin.plugin.compose` 2.0.21, and `settings.gradle.kts` uses `FAIL_ON_PROJECT_REPOS`. §0.3/§3.1 list only the JVM and serialization plugins as new, and there is no `libs.versions.toml` yet. An agent rewriting the root build to "declare Kotlin JVM and serialization `apply false`" could drop the existing two. Also `:core` needs `mavenCentral()` only, which is already in `dependencyResolutionManagement`; fine.
**Suggested fix.** List all four plugins (`com.android.application`, `kotlin.android`, `kotlin.plugin.compose`, `kotlin.jvm`, `kotlin.plugin.serialization`) in §0.3 and say "extend, do not replace, the root `build.gradle.kts`". Say whether `libs.versions.toml` is used by plugin blocks (version catalogs and plugin aliases) or kept as plain strings.
**Verified/uncertain.** Verified (read `android/build.gradle.kts`).

### PR18 — Low — §2.8 CP3 upload pre-check vs "Send anyway"/duplicates and `Content-Length` = whole size
**Problem.** `api_upload` refuses with 507 whenever `free < length + 64 MB`, before the PC can tell the upload is a duplicate. A resend of an already-present 2 GB file on a nearly full disk (a plausible time to resend) is refused instead of "skipped". Edge case only, but the plan presents CP3 as a pure safety win.
**Suggested fix.** Either accept (document it) or let name+size+mtime duplicates (the cheap `precheck`) skip the free-space test.
**Verified/uncertain.** Verified by reading §2.8.

### PR19 — Low — §3.2 / design: `Coil` and `X-Ferry-Device` (CP15), `X-Ferry-Device` on the shared client
**Problem.** CP15 says Coil requests carry `X-Ferry-Device` via the shared client, but nothing in §4.9 says to set `ImageLoader.Builder().callFactory(sharedClient)` (otherwise Coil uses its own OkHttp client, not bound to the Wi-Fi `Network` and without the header; on a TV with Ethernet plus Wi-Fi that can pick the wrong route). The shared client is rebuilt on network change, so the factory must be a lazy `Call.Factory { sharedClient().newCall(it) }`.
**Suggested fix.** Add that one line to §4.9 (TV thumbnails) and §4.7 (phone thumbnails use the platform, not Coil's network path).
**Verified/uncertain.** Verified from memory of the Coil 2.x API.

### PR20 — Low — §6 Ph4/Ph6 `run_instrumented.sh` is edited by six phases
**Problem.** The class order, grants and per-class host steps are inline in a bash script that Ph0, Ph1, Ph5, Ph6, Ph7, Ph9 and Ph8 each edit. This is a classic place for ordering regressions (§4.14 "Run order" depends on `3b` running first, which only exists from Ph6, so Ph5 must be reworked in Ph6).
**Suggested fix.** Make the script data-driven: `android/ci/suites/phone.txt` with lines `class | grants-before | host-check`, so a phase adds a line instead of editing control flow; document `3b` as "first when present".
**Verified/uncertain.** Judgement.

### PR21 — Low — Over-engineering that adds risk without a requirement behind it
**Problem / suggested fix (grouped).**
- Screenshot tours (`PhoneScreenshotTour`, `TvScreenshotTour`) and the `tar` + `run-as` screenshot pipeline (CP16): useful for review, but they are not required. Keep one screenshot per suite until the end; build the full tours in Ph10 only.
- `test_rotation_with_other_handle_open`, `test_10k_inbox_time_budget` (3 s on Windows runners), `test_hash_only_when_stat_changes`: valuable but timing- and OS-sensitive; mark the two timing ones `xfail`-on-Windows or loosen, because they will be the first to flake.
- In-app `RingLog` rotation to a 1 MB file plus "Share diagnostics log" with its own FileProvider path: not in the requirements (C5 is the PC log). Reasonable for supportability, but the rotation code can be dropped (memory ring + one file overwritten at start).
- Three emulator suites gating release (see PR6): API 25 is justified (Fire OS 6), the TV suite is justified, but a 14-class phone suite with a GPS negative control, a 300 MB screen-off upload and an update-banner test is a lot of flake surface for a one-user tool.
- `CP1` (hand-rolled `Navigator`) and `CP10` (threads for the servers) are sensible; no objection.
**Verified/uncertain.** Judgement.

## Things I checked and found sound

- Gradle structure: `:core` as a `kotlin.jvm` module consumed by an Android app with `jvmTarget 8` vs `17` is fine; `-Xjdk-release=1.8` is accepted by Kotlin 2.0.21; javac `--release 8` works on JDK 17 (and 21 locally, with a warning). The Kotlin/Java target consistency check passes because both are set to 8.
- `reactivecircus/android-emulator-runner` single-line `script:` constraint, KVM udev step, AVD snapshot cache pattern: correct as described. System images `android-25;default;x86`, `android-30;android-tv;x86`, `android-34;google_apis;x86_64` exist as far as I know (uncertain; Ph0 is the right place to prove it, and its fallback list is sensible).
- Release signing config reading `signing/signing.properties` with `storeFile=signing/ferry-release.jks` relative to `android/` matches the files in the repo.
- `versionCode = 20000 + run_number`, `FERRY_RELEASE` env, `BuildConfig.VERSION_CODE` (still generated for application modules in AGP 8) are fine.
- Phase order is sound: Ph0 (prove CI) -> Ph1 (PC) -> Ph2 and Ph3 (`:core`, needs Ph1's `--port 0`, `FERRY_READY`, check-batch) -> Ph4 (app shell and package) -> Ph5 to Ph9 features -> Ph10. The only hidden dependency I found is the job rename `build` -> `app-build` between Ph0 and Ph4 (the `needs:` in the emulator jobs must be updated), and `run_instrumented.sh` growth (PR20).
- Android API choices that are correct for 25–35: `Network.bindSocket` and `socketFactory` (API 21+), `ContentResolver.query(…, Bundle, …)` (26+) with the API 25 `LIMIT` trick, `MediaStore.setRequireOriginal` (29+), `loadThumbnail` (29+), `MediaStore.Downloads` with `IS_PENDING` (29+), `PendingIntent.getForegroundService` (26+), `FileProvider` + `ACTION_VIEW` for the installer, `canRequestPackageInstalls` (26+), `StatFs.availableBytes` (18+), `ServiceCompat.startForeground` with types.
- Compose focus: only stable APIs (`FocusRequester`, `focusProperties`, `focusGroup`, `onPreviewKeyEvent`) are used; avoiding `focusRestorer` is right for foundation 1.7.

## Summary table

| ID | Severity | Plan section | One-line problem |
|---|---|---|---|
| PR1 | High | §1.4 check_apk, §0.3 | "No `lib/`" assertion will fail: Compose 1.7 brings `libandroidx.graphics.path.so` |
| PR2 | Medium | §4.2 vs Ph4 risks | NetworkBinder rule contradicts itself; API 25 emulator is likely cellular |
| PR3 | Medium | §4.7 | `MediaStore.getMediaUri` is API 31, not 29 |
| PR4 | Medium | §4.7 | `QUERY_ARG_MATCH_PENDING` is API 30; API 29 needs `setIncludePending` |
| PR5 | Medium | §3.1, §0.3 | `:core` `implementation(serialization)` hides `Json`/serializers from `:app` |
| PR6 | Medium | §5.2 package | Zip gated on all emulator suites; flake blocks the only deliverable |
| PR7 | Medium | §4.14, Ph0 | Big-video fixture bitrate/duration unspecified; pause test can be vacuous |
| PR8 | Medium | §5.2 app-build | Hard-coded `build-tools/35.0.0` may not exist on the runner |
| PR9 | Medium | §4.14, Ph0 | Shell-copied file ownership/redaction premise may not hold; red build in Ph5 |
| PR10 | Medium | §4.7 | `LazyVerticalGrid` has no sticky headers |
| PR11 | Medium | §6 sizing | Ph2/Ph3/Ph5/Ph8 too big for compile-by-CI; split, filter emulator runs |
| PR12 | Medium | §5.2 triggers | `pc/**` triggers every Android job; private-repo minutes risk |
| PR13 | Low | §5.2 | `ubuntu-latest` (26.04) for emulators; no TV profile |
| PR14 | Low | §4.11, §5.2 | Merged-manifest path ambiguous; use the APK's manifest |
| PR15 | Low | §4.4, §4.9, §4.10 | `onTimeout` overloads, `@UnstableApi`/Experimental opt-ins missing |
| PR16 | Low | §3.6 | Same-port restart on Windows can fail (exclusive bind, TIME_WAIT) |
| PR17 | Low | §0.3, §3.1 | Plugin list omits existing `kotlin.android`/`compose` plugins |
| PR18 | Low | §2.8 | 507 pre-check also refuses duplicates on a nearly full disk |
| PR19 | Low | §4.9 | Coil needs `callFactory(shared client)` for CP15 and network binding |
| PR20 | Low | §4.14, §6 | `run_instrumented.sh` edited by many phases; make it data-driven |
| PR21 | Low | various | Over-engineering: screenshot tours, log rotation, timing tests, flake surface |

Counts: High 1, Medium 11, Low 9 (total 21).
