# Ferry v2: Code plan audit — reviewer B (Opus)

Artifact: `v2-04-code-plan.md` (rev 1) against `v2-01-requirements.md`, `v2-02-design.md` (rev 2), `v2-decisions-log.md`, v1 `pc/ferry.py`.
Focus: correctness against the design, data safety, test adequacy. Written incrementally.

Format: ID · Severity · Plan section · Problem · Suggested fix · Verified (how) / uncertain.

## Findings

### PB1 · High · §3.2 `StreamingBody`, §3.7 `StreamingBodyTest`
**Problem.** `StreamingBody` "throws `SourceChanged` if the stream ends early **or has bytes past `size`**". If the extra-byte check runs after the last of the `size` bytes has been written (the obvious way to code it, and what the test text describes), the PC has already received exactly `Content-Length` bytes. v1 `Inbox.receive()` stops reading at `remaining == 0`, fsyncs and places the file, whatever the client does next (verified by reading `receive()`/`_place()`). So a file that grows while it is being sent (still downloading, still being recorded, a log) is **committed on the PC as a truncated `name.ext`**. The engine then refreshes size and retries, and the PC saves the full file as `name (1).ext`. Result: one corrupt file and one good file on the PC, the very case B5 was meant to handle. Priority 1 is broken on this path.
**Suggested fix.** Read ahead: before writing the **final** chunk, try to read one more byte from the source. If there is one, throw `SourceChanged` *before* the final bytes go to the sink, so the PC never gets the full length, sees a short body (`UploadAborted`) and deletes its temp file. Add to `StreamingBodyTest`: "extra bytes → the sink received **fewer than `size`** bytes" (a test that fails with the naive ordering). Add an integration test: a source that grows by 1 byte after open → the PC inbox ends with **one** file, the full new content, and no `name.ext` holding the truncated bytes.
**Verified.** By reading v1 `receive()`: there is no read after `remaining == 0`. The OkHttp write ordering is the implementer's choice, so the risk is in how the plan specifies it.

### PB2 · Medium · §2.6 `AppBundle` cache key + §2.11 `package.py` (deterministic zip)
**Problem.** The cache key is `(apk size, apk mtime_ns, json size, json mtime_ns)`. `package.py` writes every entry with the fixed timestamp `FIXED = (2026, 11, 14, 12, 0, 0)` (verified in `pc/tools/package.py`). Windows "Extract All" restores zip entry times, so after unzipping, **every** Ferry zip's `Ferry.apk` and `Ferry.apk.json` have the same mtime. §5.3 says a PC-only change rebuilds the APK with only `versionCode`/`versionName` changed. Those are fixed-width values (`20057`→`20058`, `2.0.57`→`2.0.58`), so the new APK can easily be the **same size**, and the json is the same size too (same digit counts, 64-hex sha). The key then does not change. A running Ferry keeps serving the **old** `version_code`/`sha256` from `/api/v1/app` and the **new** bytes from `/app`. The update is either not offered at all, or downloaded and rejected for a sha mismatch, until Ferry is restarted. That is exactly the B26 scenario ("a new zip is being unpacked over a running Ferry"). `test_replaced_while_running` writes files with fresh mtimes, so it cannot catch this.
**Suggested fix.** Re-read the json on every request (it is ≤ 64 KB) and key the cache on the json **bytes** plus the APK's size and mtime. Re-hash the APK whenever the json content changes. Also, or instead, have `package.py` stamp `app/*` with the build time rather than `FIXED`. Add a test: replace the APK and json with same-size files and identical mtimes (`os.utime`) → the new version is served.
**Verified.** `FIXED` timestamp read in `package.py`. That Windows Explorer extraction preserves entry mtimes is **uncertain** (believed true; 7-Zip does). The equal-size APK is plausible, not proven.

### PB3 · Medium · §2.7 `Inbox.check_batch` index ("key = name lower-cased when `is_win` … reproduces `family()` exactly")
**Problem.** `family()` uses `re.IGNORECASE`, which is not `str.lower()` equality. A prototype of the planned index against `family()` gave **9 mismatches** in 30 names in Windows mode. Examples: `İstanbul.jpg` vs `istanbul.jpg` (regex matches, `lower()` keys differ), `i̇stanbul.jpg` (index matches, regex does not), and Greek final sigma `Σίσυφος` / `ΣΊΣΥΦΟΣ`. A missed candidate only costs an upload, because the PC's hash catches it. A **false** candidate can mark a file "Already on PC" when it is not in its family. That needs the same size and mtime within 2 s, so it is improbable but it is silent. The digit rule also matters: `family()` uses `\d+`, which matches non-ASCII digits (`a (٣).jpg`); an implementer writing `[0-9]` diverges again.
**Suggested fix.** Use the index only to narrow, and confirm with the **same compiled regex `family()` uses**. The coarse key must be a superset: ASCII lower-cased, with every non-ASCII char and the ASCII letters `i k s` replaced by a placeholder. Only those three ASCII letters have non-ASCII `IGNORECASE` partners in Python's `re` (İ ı → i, K → k, ſ → s; brute-forced over all code points). The prototype of this key plus the regex check had **0 mismatches** and stays O(n) for normal `IMG_…` names. Put `İ`, `ı`, `ς`, `ſ`, `K` (Kelvin sign) and `(٣)` into `test_same_answers_as_precheck`.
**Verified.** `/tmp/pb/expcb.py`, `/tmp/pb/expfold.py` (Python 3, Linux; `re` semantics are the same on Windows).

### PB4 · Medium · §2.8 / CP3 (upload pre-check 507) and its tests
**Problem.**
(a) CP3 says "answers 507 **before reading the body** (then drains)", but §2.8 says `drain(length)` then 507. Both work: in my experiment, with an HTTP/1.0 handler and http.client on Linux loopback, the client could read the 507 in both orders, even after a `BrokenPipeError` on a 100 MB body, and the server drained exactly 64 MB. Still, the plan should say one thing.
(b) `test_upload_precheck_large_body_closes` says "client may see either the 507 or a reset". As written it cannot fail if the server read the whole 100 MB, and "no temp file" holds trivially because the pre-check runs before `mkstemp`. The test does not check its own claim ("closes after ≤ 64 MB").
(c) CP3 changes v1 upload semantics (C1 "keeps v1 behaviour"). It now refuses a file that would have been **skipped** by hash (an unchecked or Send-anyway upload of an identical large file on a nearly full disk). This contradicts the spirit of design B3, "a resend … is never refused". It is an edge case, but it is a change.
v1 clients are not broken: the browser page is gone, and HTTP/1.0 always closes the connection.
**Suggested fix.** (a) Pick "drain ≤ 64 MB, then 507, then close" (matches v1 `DiskFull`) and fix the CP3 wording. (b) Assert the bytes the client managed to send before EPIPE/RST, or the server-side drained count via the request log's `bytes` field: ≤ 64 MB + socket buffers. (c) Record it as accepted in the decisions log, or skip the 507 when an existing family member has the same size (cheap).
**Verified.** `/tmp/pb/exp507.py`: drain-then-507 and 507-then-drain, 1 MB and 100 MB. OkHttp's behaviour (does it read an early response after a write failure?) is **uncertain**; B11's `info()` fallback covers both cases.

### PB5 · Medium · §4.4 `TransferService` (items built in the service) vs §4.7 `UploadItemFactory` probe vs design §4.3 / H19
**Problem.** Design §4.3 / H19 say the original-access check is done **by opening the file when the queue is built**. If that fails, the user gets one dialog ("Allow / Send without location") and nothing is sent silently. The plan builds the items **inside `TransferService`** (§4.4, "items are built by `UploadItemFactory` inside the service") and puts the open probe in `UploadItemFactory` (§4.7). A service has no UI, so when a probe fails there, the plan has no way to ask. It also never says what the service does then.
- `ACTION_SEND_PICKS` carries no `withoutLocation` choice (only `ACTION_SEND_URIS` does).
- The permission check could run in the activity, but the **open** failure (the case B6 added: permission granted, but `setRequireOriginal` open throws) can only happen where the probe runs.
- The likely implementation falls back to the plain URI. That is exactly the silent GPS-less send H19 forbids, and the PC would then answer "Already on PC" to the good copy for ever.
**Suggested fix.** Run `UploadItemFactory` (including the probes) in the **activity/ViewModel** on `Dispatchers.IO`, before starting the service. Show the dialog there, and hand over fully built items: the `pendingPicks` hand-over for picks, and the ClipData URIs plus a per-URI `original` flag for shares. In the service, an item marked `original = true` whose original open then fails becomes `Failed("Can't read this photo's location")`; it is **never** downgraded to the plain URI. Add an engine/factory unit test: "requireOriginal open fails in the service → Failed, not sent".
**Verified.** By reading plan §4.4/§4.7 and design §4.3.

### PB6 · Medium · §4.14 phone suite #4 `ShareToFerryTest` (B19 grant forwarding)
**Problem.** The test cannot fail if the ClipData grant forwarding is broken. It runs after `pm grant READ_MEDIA_IMAGES/VIDEO`, and the shared files are MediaStore files, so Ferry can read them with its **own** permission whatever the grant. The instrumentation also runs inside Ferry's process (UID `app.ferry`), so the "sender" granting the URI is Ferry itself. This is the same trap as the GPS test in the design audit: the test passes whether or not B19 is implemented. Asserting "the activity reaches DESTROYED before the engine finishes" does not help, because the permission does not depend on the activity.
**Suggested fix.** Share URIs that **only** a grant can open: a `content://` URI from a provider in a **different UID**. Options: a 30-line helper APK built in CI with a non-exported `FileProvider` (`grantUriPermissions=true`) and an activity that sends `ACTION_SEND_MULTIPLE` with ClipData + `FLAG_GRANT_READ_URI_PERMISSION`, started from the host with `am start`. Then assert that all 3 arrive after the helper activity and Ferry's share activity are finished, with the screen off for the 30 MB item. Add a negative control: the same share **without** the forwarded grant (debug flag) → `Failed(CantRead)`, which proves the test can fail.
**Verified.** By reasoning from plan §4.14 run order (grants before class 4) and the standard instrumentation model (test code runs in the target's process). Whether components declared in the androidTest APK run under a separate UID (which would allow a cheaper fixture) is **uncertain**.

### PB7 · Medium · Ph0 risks ("If Android does not redact on the emulator … keep the byte-identical + `exif_gps_status` positive check (still valid)")
**Problem.** The positive check is **not** still valid in that case. If the emulator does not redact, `GpsOriginalTest` passes whether or not `setRequireOriginal` is used. That is exactly the design-audit B1 problem (a test that can't fail). The fallback would quietly bring back the inadequate test, and §0.1 "never weaken a test" would not catch it, because it is presented as planned.
**Suggested fix.** If redaction is not observed: (1) record the H-row as the plan says, **and** (2) add a structural assertion that can fail, e.g. an instrumented test that `UploadItemFactory` returns, for a picker item with `ACCESS_MEDIA_LOCATION` granted, a URI that `MediaStore.getRequireOriginal(uri) == true` (API 29+) and that the engine opens exactly that URI (a fake `UploadSource` recorder). Mark P2 GPS as "real-device only" in §7.1 / §7.4.
**Verified.** Logic. Emulator redaction behaviour is **uncertain** (Ph0 probe decides).

### PB8 · Medium · §3.7 `pcGoesAwayAndComesBack` (restart on the same port) on `windows-latest`
**Problem.** The test kills ferry.py and restarts it with `--port <old>` after 5 s. On Windows `FerryServer` binds with `SO_EXCLUSIVEADDRUSE` (`exclusive=IS_WIN`, `allow_reuse_address=False`; read in v1). Ferry is HTTP/1.0 and closes every connection itself, so each finished request leaves a server-side `TIME_WAIT` on that port. Microsoft documents that a socket bound with `SO_EXCLUSIVEADDRUSE` can't rebind the port while connections accepted by the old listener are still active. `bind_server()` retries for only 5 s, then exits "Port … is used by another program". The JVM job on Windows would then fail or flake. Second gap: no test covers the most common real Wi-Fi drop, a **silent stall** (no RST/FIN). In that case only the 60 s write timeout and the **10 min** read timeout fire (see PB11).
**Suggested fix.** Put a tiny loopback TCP relay (≈ 60 lines, test source) between the engine and ferry.py, with modes `pass`, `reset` (close all) and `blackhole` (accept, never forward). "PC goes away" = relay in `reset`/`blackhole` for 10 s, then `pass`. The PC process never restarts, so there are no port games, and the stall path gets tested. Keep one restart case on Linux only, if wanted.
**Verified.** v1 bind code read. The Windows `TIME_WAIT` + `SO_EXCLUSIVEADDRUSE` interaction is **uncertain** (not testable here; from Microsoft's "Using SO_REUSEADDR and SO_EXCLUSIVEADDRUSE" as remembered).

### PB9 · Medium · §4.7 `LegacyDownloadSink` (API 25–28) and `cleanupLeftovers()`
**Problem.** `commit()` first claims `name.ext` with `createNewFile()`, which makes a **0-byte file with the final name**, and then `renameTo`s the part file onto it. If `renameTo` fails (returns `false`, which `File.renameTo` does instead of throwing), or the process dies between the two steps, a 0-byte `report.pdf` stays in `Download/Ferry`. That is a visible corrupt file, and the next download of the same name becomes `report (1).pdf`. `abort()` deletes only the part file, and `cleanupLeftovers()` deletes only `.*.part`, so the placeholder is never removed. Design §4.5's rule "never leave a partial file on the phone, on any Android version" is broken on this path.
**Suggested fix.** In `commit()`: if `renameTo` returns false, delete the claimed placeholder (only if its length is 0) and the part file, then report Failed. Record the claimed name in a small marker (e.g. `.<name>.claim` holding the part's name) before claiming, so `cleanupLeftovers()` can remove a 0-byte placeholder after a crash. Extend `LegacyNameClaimTest`: with an injected rename failure, no 0-byte file remains.
**Verified.** `java.io.File.renameTo` returns false rather than throwing (standard Java API). The crash window comes from the plan's order of operations.

### PB10 · Medium · §4.4 `TransferService` lifecycle (idle → `stopSelf()`)
**Problem.** "Idle (engine finished and queue empty) → `stopForeground`, `stopSelf()`". A new `ACTION_SEND_*`, `ACTION_DOWNLOAD` or share intent can arrive between "engine is idle" and `stopSelf()`, or between `stopSelf()` and `onDestroy()`. `stopSelf()` with no argument stops the service anyway, and `onDestroy` then cancels the scope. The new items are lost, or are marked Cancelled by "`onDestroy` without finish → `engine.cancel()`" as if the user had stopped it. The plan also leaves the `onStartCommand` return value unset. With `START_STICKY` the system restarts the service after a kill with a **null intent**, and the in-memory `pendingPicks` are gone, a crash risk if the Kotlin signature is non-null.
**Suggested fix.** Track the last `startId` and stop with `stopSelfResult(lastStartId)`. Make the idle check and `add()` take the same mutex, so a late `add` either keeps the service alive or arrives after `onDestroy` and starts a fresh instance. Return `START_NOT_STICKY` (matches H16: no persistence; the interrupted marker handles kills). Declare `intent: Intent?`. Unit-test the service's "stop decision" helper with an add racing the idle check.
**Verified.** Standard `Service` semantics; not testable here.

### PB11 · Medium · §3.2 `FerryHttp.newClient` (readTimeout 10 min for every call)
**Problem.** The 10-minute read timeout is right for **one** moment: waiting for the upload reply while the PC hashes. The shared client, though, is also used for `download()` (outbox downloads, APK update, TV APK install), `outbox()` (polled every 5 s on phone and TV), `appInfo()` and `CastClient`. Only `info` (5 s) and `checkBatch` (60 s) get call timeouts. After a silent Wi-Fi stall (no RST), a download, or the TV's list refresh, hangs for up to 10 minutes before the "restart from zero" / "waiting for PC" logic of §4.5 / H20 can start. On the TV the home screen silently stops updating.
**Suggested fix.** Default the shared client to `readTimeout(30 s)`. Raise it only for the upload call (`client.newBuilder().readTimeout(10, MINUTES)` per call, or OkHttp `callTimeout` handling in `upload()`). Give `outbox()`/`appInfo()`/cast a `callTimeout` of 10 s. Add a `DownloadQueueTest` / relay test (PB8) where the server stalls mid-download → restart within ~30 s.
**Verified.** By reading plan §3.2 (timeouts listed only for `info`/`checkBatch`).

### PB12 · Medium · §3.7 integration tests that read `ferry-log.txt` (`engineEndToEndSmallBatch`, `deviceNameWithCurlyQuoteAndEmoji`), §4.14 #3
**Problem.** `RequestLog` writes through an asynchronous writer thread (≤ 1 s queue timeout, §2.4). "Resend → **no upload requests in `ferry-log.txt`**" can pass **falsely**: the upload lines may simply not have been written yet when the test reads the file. Tests that look for a line fail for the opposite reason (flaky). Polling is collapsed, so a `GET /api/v1/info` can't be used as a marker.
**Suggested fix.** Use a sentinel. After the step under test, send a request that is always logged (e.g. `GET /api/v1/__sentinel_<uuid>` → 404), poll the log until the sentinel line appears (≤ 5 s), then assert on the lines **before** it. Put this in `FerryPcProcess.awaitLogUpTo()` and in the pytest `Srv` helper. For the "no uploads" check, also count `POST /api/v1/upload` from the engine's own fake/recording interceptor; the log is then a second witness, not the only one.
**Verified.** By reading plan §2.4 (writer thread, 1 s queue timeout) against §3.7.

### PB13 · Low · §4.14 phone #5 `ScreenOffUploadTest` (P4)
**Problem.** On an emulator with no battery restrictions, a 3-minute upload also finishes with the screen off when the service is **not** foreground (or there is no service at all, and the upload runs in a ViewModel scope). "Notification present" is the only part that could fail, and the test does not check that the notification is a *foreground-service* one.
**Suggested fix.** While the transfer runs, the host (or `uiAutomation.executeShellCommand`) checks `dumpsys activity services app.ferry/.service.TransferService` → `isForeground=true` and `foregroundServiceType=0x00000001` (dataSync). Also press Home before `sleep()`, so the activity is not what keeps the process at top priority.
**Verified.** Reasoning; not run.

### PB14 · Low · §3.2 engine step 4 (big-file gate) / `bigFileRunsAlone`
**Problem.** "A row > 256 MB may start only when the other worker is idle; while it runs the other worker takes nothing". The plan doesn't say what the **other** worker does while the big row is **waiting** for the gate. If it keeps taking the next small rows, the big file starves until all small files are done, which breaks the selection order (design: "ordered by the user's selection order"). If both workers wait on each other, the engine deadlocks. `bigFileRunsAlone` only checks exclusivity.
**Suggested fix.** State the rule: once a worker has claimed a big row, the other worker finishes its current file and claims nothing until the big row is done. Test it: [small, BIG, small×10] → BIG starts right after the first small one finishes, and no third request starts before BIG has finished.
**Verified.** Plan text.

### PB15 · Low · §2.3 `--port 0` / §2.10 `main()` order
**Problem.** v1 `make_app()` builds `primary_url = "http://%s:%d" % (primary, cfg.port)` **before** the bind (verified in v1 `main`/`make_app`). The plan keeps the order "`make_app` → bind → `cfg.port = server.server_port`", so with `--port 0` the console banner/QR, `Outbox.url_base` and the Get-the-app page's fallback `<host>` say port `0`. That affects only test runs, but the Get-the-app page test with no `Host` header would assert the wrong thing.
**Suggested fix.** After the bind, set `cfg.port` and recompute `app.primary_url` (or make `primary_url` a property). Test: `--port 0`, then `GET /` with an HTTP/1.0 request and no `Host` header → the page shows the real port.
**Verified.** v1 code read (`make_app`, `main`).

### PB16 · Low · §4.2 / §4.7 `cleanupLeftovers()` on start vs the first download
**Problem.** `FerryApp.onCreate` launches `cleanupLeftovers()` asynchronously on IO, and it deletes **all** of Ferry's `IS_PENDING=1` rows / `.part` files in `Download/Ferry`. If the process was started by the user tapping "Download all" quickly, or by a notification action, a download that has just started can have its fresh pending entry deleted by the still-running cleanup. The download fails; no corrupt file is published, but the failure is confusing.
**Suggested fix.** Keep the cleanup as a `Deferred` in `AppGraph`; `DownloadQueue` awaits it before creating the first sink.
**Verified.** Plan text.

### PB17 · Low · §3.2 `DownloadSink` contract
**Problem.** `open(): OutputStream; commit(); abort()`: the contract doesn't say the queue must **close** (flush) the stream before `commit()` and before the "bytes written == size" decision, nor that the sink should `fd.sync()`. A buffered stream that is committed before it is closed can publish a short file (`IS_PENDING=0`) whose last buffer never reached the disk.
**Suggested fix.** Write it into the interface doc: the queue counts bytes **after** `write`, calls `close()` and only then `commit()`. The sinks use `ParcelFileDescriptor`/`FileOutputStream` and `fd.sync()` before close. `DownloadQueueTest`: a fake sink records the call order close → commit.
**Verified.** Plan text.

### PB18 · Low · §4.8 TV outbox APK install (`filesDir/apk/<name>`)
**Problem.** (a) No length check is specified for outbox APK downloads (the self-update has size + sha; the outbox has only `size`). A truncated APK reaches the installer and gets a vague "There was a problem parsing the package". (b) `apk/` is never cleaned; Fire TV Sticks have about 5 GB free. (c) The PC-provided `name` is used as a path component.
**Suggested fix.** Reuse `DownloadQueue`'s rule (`Content-Length == OutFile.size`, bytes == size, else delete) with `FileSink`. Store as `apk/<id>.apk`, not the PC name. Delete `apk/*` at app start and after the installer returns.
**Verified.** Plan text.

### PB19 · Low · §2.14 PC test specs (assertions that can't happen, missing injection points, Windows portability)
**Problem / fix, item by item.**
- `test_polling_collapsed`: "a 404 on `/api/v1/info?x` still logged". Routing uses `urlsplit(path).path` (v1 `_dispatch`), so `/api/v1/info?x` answers **200** and is collapsed. Use `POST /api/v1/info` (405) or `/api/v1/infox` (404).
- `test_writer_never_blocks` needs "a blocking fake file", but `RequestLog.__init__` only takes `folder`. Add an `opener=open` parameter (alongside `replace`/`clock`).
- `Srv` fixture with a `RequestLog`: the fixture must `stop()` the log (close the handle) on teardown, or Windows can't delete or rotate the tmp folder in later tests.
- `start_ferry_process()` / `test_hooks.py`: on Windows `select()` doesn't work on pipes. Read stdout in a reader thread feeding a `queue.Queue`, with a timeout on `get`.
- `test_bind_failure_tolerated`: the blocking socket must bind `0.0.0.0:P` (the same address the responder uses). Windows rules for a wildcard bind next to a specific bind differ from Linux (**uncertain**).
- `PickerAcrossAlbumsTest`: "order in `ferry-log.txt` = selection order (2 workers: first two start in order)". Log lines are written when a request **ends**, so with 2 workers the order is racy. Assert the set of names on the PC, and leave order to `UploadEngineTest.happyPathTwoWorkersInSelectionOrder` (or run that UI test with `workers = 1` via a debug setting).
- `test_rotation_with_other_handle_open`: correct as specified. CPython opens files on Windows without `FILE_SHARE_DELETE`, so `os.replace` fails with a sharing violation. Keep the test's lines under the 10 MB hard cap, otherwise "every line is in `log` or `log.1`" is false by design.
**Verified.** v1 `_dispatch` read. The Windows items are standard behaviour, not run here.

### PB20 · Low · §7 Traceability vs phase/test contents
**Problem.**
(a) T6 is traced to "`TvLaunchAndFocusTest` asserts no 'Send' node exists in TV mode", but the §4.14 TV #1 row does not list that assertion.
(b) Ph4 lists instrumented `PhoneLaunchTest` and `TvLaunchTest` (P8 traces to `PhoneLaunchTest`), but neither appears in §4.14's suites or run order. It is unclear whether they survive into the final suite.
(c) C3 / acceptance 9 says "phone **and TV** offer the update", but there is no TV update-banner test or screenshot (`TvScreenshotTour` lacks it).
(d) P4 "speed … shown in the notification": notification content is never asserted.
**Suggested fix.** Add the T6 assertion to TV #1; add `PhoneLaunchTest`/`TvLaunchTest` to §4.14 (or retarget P8 to `Api25LaunchTest` + `TvPcNotFoundTest`); in the TV suite, point PC-T at an `--app-dir` with a high `version_code` and screenshot the focused update banner; read the notification text in `ScreenOffUploadTest` via UiAutomator.
**Verified.** Cross-reading §4.14, §6 and §7.

### PB21 · Low · CP2 vs Ph0 `LongPauseProbe`
**Problem.** CP2 moves the player to `OkHttpDataSource` (correct for A9), but the Ph0 probe that is meant to "prove the riskiest unknowns first" uses `DefaultHttpDataSource`. Socket reuse, timeouts and retry behaviour after the PC's 60 s idle close differ between the two stacks, so a green probe says little about the real player until Ph8.
**Suggested fix.** Build the probe with `media3-datasource-okhttp` and the same timeouts/retry policy as §4.10 (it's one dependency line); then the Ph0 decision ("higher retry count vs longer PC idle timeout") is made on the real stack.
**Verified.** Plan text.

### PB22 · Low · §3.2 `PcLocator.locate()` order vs design §3.5
**Problem.** The design order is last-known → broadcast → manual. The plan's is "manual/chosen → last-known → broadcast". Trying the user's explicit choice first is defensible, but the plan doesn't say that a failed manual/chosen address **falls through** to last-known and broadcast. If the user once typed an address and the PC later moves (DHCP, router replaced), Ferry would show "PC not found" for ever, even though discovery would work.
**Suggested fix.** Specify the fall-through, and that the manual entry is replaced (or demoted to a hint) when a broadcast finds a PC with the same `name`. Add a `PcLocator` unit test: manual dead, broadcast finds the PC → Found.
**Verified.** Plan vs design text.

### PB23 · Low · §4.7 MediaStore paging on API 26–29 (CP11) — uncertain
**Problem.** On API ≥ 26 the plan pages with `Bundle(QUERY_ARG_SORT_COLUMNS, QUERY_ARG_LIMIT, QUERY_ARG_OFFSET)`. As far as I know, MediaProvider honours `QUERY_ARG_LIMIT/OFFSET/SORT_COLUMNS` only from API 30. On 26–29 these args may be ignored, so every "page" returns the whole table, unsorted, and the merged list has duplicates. Only phones are affected (the vivo is API 35 and the TV doesn't use the picker), so the impact is small.
**Suggested fix.** Use the Bundle path on API ≥ 30, and `QUERY_ARG_SQL_SORT_ORDER = "date_modified DESC LIMIT 500 OFFSET n"` (or the plain `sortOrder` string as on 25) on 25–29. Dedupe by `_ID` on merge in any case.
**Verified.** **Uncertain** (MediaProvider history recalled, not checked).

### PB24 · Low · §2.6 `AppBundle.status()` on the request path
**Problem.** `/api/v1/info` calls `status()`, and on a new cache key that hashes the whole APK (~25–30 MB) under a lock. The first `/info` after Ferry starts (or after a copy) pays this cost. On a cold HDD that can exceed the app's **1.5 s last-known timeout** (design §3.5), so the phone falls back to broadcast and shows "Looking for your PC…" for no reason. Concurrent requests also queue behind the lock.
**Suggested fix.** Warm the cache in a background thread at startup (after the bind). While a hash is in progress, `info` reports the previous `app_version_code` (or `null`) instead of waiting; only `/app` and `/api/v1/app` wait for the result.
**Verified.** Plan text. HDD timing **uncertain**.

## Plan decisions CP1–CP16 against the design and decisions log
- **No conflict, justified:** CP1, CP4, CP5, CP6 (better than design §10's "revoke" wording, since a revoke kills the test process), CP7, CP9, CP10, CP12, CP13, CP14, CP15, CP16.
- **CP2** (OkHttpDataSource instead of design §5.2's DefaultHttpDataSource): justified by A9; the Ph0 probe should follow (PB21).
- **CP3** (upload-time 507): changes C1's "v1 upload behaviour unchanged" and goes slightly against B3 for duplicates. Acceptable, but should be logged as a decision, and its test tightened (PB4).
- **CP8** (picks handed over in-process): fine for permissions. It exposes the missing UI step for the original-access probe (PB5).
- **CP11:** fine on API 30+; paging on 26–29 is **uncertain** (PB23).

## Design faithfulness: checked and found faithful
check-batch reply shape, input order, `needed_bytes` without duplicates, one scan and one `disk_usage` (apart from PB3); discovery (UDP fixed at 8765, `--port` independence, 10054 survival with `SIO_UDP_CONNRESET` and `try/except`, no `SO_REUSEADDR`, 20/s limit, bind failure tolerated); log format, polling collapse, bounded queue, rotation with retry/+1 MB/10 MB truncation, "never switches off"; percent-encoded device header; test hooks; engine states, unchecked upload on check-batch failure, Send anyway, body-sent-then-skipped = Saved, cancel grace, B11 mid-body disk-full via `info`, 30 min wait then NotSent; downloads with IS_PENDING / length check / restart from zero (H20); FileProvider + ACTION_VIEW installs (H21); versionCode rule (H26); emulator gating (H27). The traceability table matches the phases apart from PB20.

## Summary

| ID | Sev | Section | One line |
|---|---|---|---|
| PB1 | High | §3.2 StreamingBody | "Extra bytes" detected after the full body is written → PC commits a truncated `name.ext`, then `name (1).ext` |
| PB2 | Medium | §2.6/§2.11 | AppBundle cache key misses a same-size APK with the fixed zip timestamp → stale version/sha while running |
| PB3 | Medium | §2.7 | `lower()` index ≠ `family()`'s `re.IGNORECASE` (9 mismatches prototyped); superset key + regex fixes it |
| PB4 | Medium | CP3/§2.8 | Wording contradiction; large-body test can't fail; CP3 can refuse a duplicate the PC would skip |
| PB5 | Medium | §4.4/§4.7 | Original-access probe runs in the service with no UI → silent GPS-less send risk (H19) |
| PB6 | Medium | §4.14 #4 | ShareToFerryTest can't fail without grant forwarding (Ferry holds the media permission and is the sender) |
| PB7 | Medium | Ph0 risks | "Positive GPS check still valid" without redaction is false; add a structural requireOriginal assertion |
| PB8 | Medium | §3.7 | Same-port restart likely fails on Windows (exclusive bind + TIME_WAIT); no silent-stall test; use a TCP relay |
| PB9 | Medium | §4.7 Legacy sink | 0-byte claimed placeholder left on rename failure/crash; cleanup ignores it |
| PB10 | Medium | §4.4 | `stopSelf()` race with new intents; START mode / null intent unspecified |
| PB11 | Medium | §3.2 FerryHttp | 10 min read timeout applies to downloads/outbox/cast → 10 min hangs after a stall |
| PB12 | Medium | §3.7 | Log-based assertions race the async writer ("no upload lines" can pass falsely) |
| PB13 | Low | §4.14 #5 | Screen-off test passes without a foreground service; assert via dumpsys |
| PB14 | Low | §3.2 step 4 | Big-file gate: starvation/deadlock rule unspecified |
| PB15 | Low | §2.10 | `--port 0`: primary_url built before the bind → port 0 in banner/page |
| PB16 | Low | §4.2/§4.7 | Async leftover cleanup can delete a just-started download |
| PB17 | Low | §3.2 DownloadSink | close/sync-before-commit not in the contract |
| PB18 | Low | §4.8 | TV outbox APK: no length check, never cleaned, PC name used as path |
| PB19 | Low | §2.14 | Test-spec nits: impossible 404, missing opener, Windows pipe/teardown/bind, racy order |
| PB20 | Low | §7 | Traceability claims not backed by §4.14 (T6, PhoneLaunchTest, TV update banner, notification text) |
| PB21 | Low | CP2/Ph0 | Long-pause probe uses a different HTTP stack than the product |
| PB22 | Low | §3.2 PcLocator | Manual-first order lacks the fall-through to discovery |
| PB23 | Low | §4.7/CP11 | Bundle paging likely ignored on API 26–29 (uncertain) |
| PB24 | Low | §2.6 | APK hash on the `/info` path can exceed the 1.5 s last-known timeout |

**Counts:** High 1 · Medium 11 · Low 12 (total 24).
