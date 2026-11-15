# Ferry v2 design audit (reviewer B, Opus) — data safety, protocol, PC program, testability

Artifact: `docs/v2-02-design.md` (rev 1). Read against `v2-01-requirements.md`, `v2-decisions-log.md`, `v2-ci-baseline.md` and v1 `pc/ferry.py` + tests.
Experiments: scratch scripts in /tmp (Linux, Python 3). Windows-only behaviour that could not be run here is marked **uncertain**.

## Findings

### B1 — High — §10 Testing (`:app` API 34 emulator, "JPEG with GPS … byte-identical with GPS")
**Problem.** The only automated proof of the top-priority GPS requirement (P2) is likely invalid. The plan has the instrumented test insert the JPEG into MediaStore itself. Instrumented tests run in the app's UID, so the row's `owner_package_name` is `app.ferry`. MediaProvider does not redact location for the app that owns the file. So the test would pass even if `setRequireOriginal` were never called, or `ACCESS_MEDIA_LOCATION` were never granted. On the real phone, camera photos are owned by the camera app and redaction does apply.
**Suggested fix.** Put the fixture in place so that Ferry does not own it: `adb push` it to `/sdcard/DCIM/Camera/` and trigger a media scan (or insert it from a tiny separate helper APK / `adb shell content insert`). Add a **negative control**: run once with `pm revoke app.ferry android.permission.ACCESS_MEDIA_LOCATION` and assert that the PC copy is not byte-identical / has no GPS. That proves the test can see redaction. Assert with a GPS parser on the PC side (v1 had `exif_gps_status`, which the plan removes along with `/diag`; keep it as a test helper).
**Verified:** design text read. The claim that owners bypass redaction is from my knowledge of MediaProvider behaviour → **uncertain (likely)**. The negative control settles it either way.

### B2 — Medium — §2.4 Discovery responder (Windows)
**Problem.** On Windows, an unconnected UDP socket that sends a datagram to a port that is now closed gets an ICMP "port unreachable". The **next `recvfrom` then raises `ConnectionResetError` (WinError 10054)**. This happens, for example, when the phone has already closed its 2 s collect socket, or when duplicate broadcasts are answered late. If the responder loop doesn't catch it, the thread dies silently and discovery stops until Ferry restarts. The design specifies bind-failure handling but no per-datagram error handling. Linux never shows this, so the planned Linux-first tests would not catch it.
**Suggested fix.** Wrap each `recvfrom`/`sendto` in `try/except OSError: continue` (Python's `socket.ioctl` doesn't expose `SIO_UDP_CONNRESET`, so catching is the stdlib way). Use a 0.5 s socket timeout so the thread can stop. Add a unit test that injects `ConnectionResetError` from a fake socket and asserts the loop keeps answering.
**Verified:** on Linux, TCP and UDP bind on the same port number side by side, a second UDP bind is refused, and a broadcast is received and answered (/tmp/b/udp_tcp.py). Linux shows no error after an ICMP unreachable. The Windows 10054 behaviour is well known but **uncertain** here (no Windows box).

### B3 — Medium — §2.3 `check-batch` disk check; §4.4 flow
**Problem.** `disk_full` is decided on the **sum of all sizes**, including files that are duplicates. Acceptance #1 ("resend → 20 already on PC") on a PC that is nearly full would answer "The PC needs X more free space" and stop the queue, even though nothing needs to be sent. Real case: re-sending a big video folder when the disk is tight. It is also all-or-nothing: 19 of 20 files that would fit are refused. The per-item result shape "same as /check" also includes /check's own per-item `disk_full` (v1 `precheck` checks `size + 64 MB margin` per item), so two disk rules are mixed up. Chunks of 500 are checked separately, so the "sum" guard is per chunk, not per queue.
**Suggested fix.** Server: first find the duplicates, then compare `sum(sizes of non-duplicates) + DISK_MARGIN` with free space. Per-item results carry only `{name, duplicate?}`, in input order (or with an index). App: if the batch reports disk_full, offer "Send what fits" or stop. Don't refuse when the shortfall comes only from duplicates. Keep the per-upload 507 as the real guard (it exists and is safe). Document that the per-chunk sum is advisory.
**Verified:** read v1 `Inbox.precheck` (per-item `free < size + DISK_MARGIN`) and the design text.

### B4 — Medium — §2.3 `check-batch` cost / §4.4 timeouts
**Problem.** Re-using `precheck` per item means one full `os.scandir(inbox)` per item (`Inbox.family`) plus one `disk_usage` call. Measured on Linux: **500 prechecks against a 10,000-file inbox took 1.8 s**. The design allows up to 2000 items per request. `Downloads\Phone` grows without bound, and NTFS + Defender scandir is several times slower, so a batch could take tens of seconds. No client read timeout is specified for check-batch. The upload timeouts (10 min response wait) don't apply.
**Suggested fix.** Implement check-batch with **one scandir per request**: build a dict keyed by the family key (stem+ext, case-folded on Windows, ` (n)` suffix stripped) → [(name, size, mtime)], then answer every item from it. Call `disk_usage` once. Give the client an explicit 60 s timeout. If check-batch fails or times out, mark items "not checked" and go on to upload: the receive-time SHA-256 dedupe still prevents duplicates, it just costs bandwidth. Add a PC test with 10k dummy files and a time budget.
**Verified:** experiment /tmp/b/inbox_exp.py (1.80 s per 500 items, Linux). Windows timing **uncertain**.

### B5 — Medium — §4.3 Reading originals (size/mtime source) and §4.4 "file changed while sending"
**Problem.** `size = MediaStore.SIZE` is used as the authoritative upload length (`Content-Length` and `size=`). MediaStore `SIZE` and `DATE_MODIFIED` can be stale: a file edited in place by an app that doesn't trigger a rescan, or an item still being written. The engine then reads more or fewer bytes and aborts with "file changed while sending" **on every retry**. Safe (no truncation on the PC), but the file can never be sent and the reason is wrong. For the redacted fallback stream, the length could also differ from `SIZE`.
**Suggested fix.** Open the descriptor first (`openFileDescriptor`/`openAssetFileDescriptor`) and use `ParcelFileDescriptor.statSize` (when ≥ 0) as the upload size. Fall back to `SIZE` only when that is unknown. Do the check-batch with the same value, so check and upload agree. On a "changed" abort, re-query once and retry before marking Failed.
**Verified:** design read. How often MediaStore is stale on vivo is **uncertain**.

### B6 — Medium — §4.3 fallback "plain URI, location may be missing" × §2.3/§4.4 duplicate check
**Problem.** If a photo is ever sent through the fallback (permission revoked, `setRequireOriginal` throws, or a provider share that strips GPS), the PC stores a GPS-less copy with the **same name, size (redaction overwrites EXIF bytes in place) and mtime**. From then on, every resend of the original is answered "Already on PC" by the name+size+mtime pre-check, so the GPS version can never reach the PC. The user is told the photo is safe on the PC, but its location is silently lost for good. That conflicts with priority 1 / P2.
**Suggested fix.** (a) For the in-app picker, don't send items without original access: show a blocking "Allow location in photos" step instead of falling back silently. (b) When the fallback is unavoidable, flag the item in the result ("Saved without location") and in the summary. (c) Give such items an option to skip the pre-check and upload anyway. The PC's receive-time hash then sees different content and saves `name (1).ext`, so nothing is overwritten.
**Verified:** v1 pre-check semantics confirmed by experiment (+2.0 s → duplicate, +2.5 s → not). That redaction keeps the size the same is **uncertain** (my understanding of MediaProvider's in-place redaction).

### B7 — Medium — §4.5 Outbox downloads (resume/restart into MediaStore)
**Problem.** "Resume with Range if the ETag still matches; otherwise restart" doesn't say how the pending MediaStore row is written:
- Resume needs append (`"wa"`) or a seekable `"rw"` FileChannel.
- Restarting into the **same** pending row with mode `"w"` is risky. On Android 10 a `"w"` open of a content URI did not truncate for MediaProvider-backed files, so a restart after a partial download could leave old trailing bytes. The result is a corrupt file of the wrong length, published with `IS_PENDING=0`.
- Nothing checks that the bytes written equal the server's length before publishing.
- Rows left pending by a killed process aren't cleaned up. MediaProvider deletes them only after about a week, and meanwhile the clash renaming ("x (1).jpg") is driven by an invisible orphan.
**Suggested fix.** Restart = delete the pending row and insert a new one. Resume = open `"rw"`, position the channel at the current length, and send `Range: bytes=N-` **with `If-Range: <etag>`**. If the reply is 200 instead of 206, truncate (`"rwt"`) and write from 0. Before `IS_PENDING=0`, assert written bytes == total from Content-Range/Content-Length/outbox size, else delete. On startup, delete Ferry's own `IS_PENDING=1` rows in Download/Ferry.
**Verified:** read v1 `send_outbox_file` (ETag `"size-mtime_ns"`, If-Range honoured, 404 when the file changed). The Android 10 `"w"`-no-truncate behaviour is from memory → **uncertain**. The fix is safe either way.

### B8 — Low — §4.2 WRITE on API 25–28 (plain file in Downloads/Ferry)
**Problem.** The design says name clashes are "handled by MediaStore". That is true only on API 29+. On the API 25–28 path (plain `File`), there is no clash rule, and a naive `FileOutputStream` would **overwrite** an existing `Download/Ferry/x.jpg`.
**Suggested fix.** On ≤ 28, write to `.x.jpg.part`. Claim the first free `x (n).jpg` atomically with `File.createNewFile()` (`File.renameTo` on Android/Linux **does** replace silently, so it must never be used alone), then rename the part file onto the claimed empty name. Run the media scanner after.
**Verified:** design read.

### B9 — Medium — §2.5 `ferry-log.txt` rotation on Windows
**Problem.** "Rename to `ferry-log.1.txt` (replacing the old one)" hits several Windows traps:
- Python's `open()` doesn't use `FILE_SHARE_DELETE`, so the writer thread's **own open handle** blocks the rename.
- `os.rename` fails if `ferry-log.1.txt` exists, so it has to be `os.replace`.
- Another program holding either file (an editor, OneDrive syncing a Desktop/Documents Ferry folder, antivirus) makes `os.replace` fail with WinError 32.
- "Logging failures are swallowed after one console note" reads as *stop logging*. The log would then be missing exactly when the user needs it (acceptance #10), or grow forever if only rotation fails.
**Suggested fix.** Writer thread: flush+close → `os.replace(log, log.1)` with 3 short retries → reopen in append mode. If the replace still fails, reopen the same file, keep appending, and retry rotation after another 1 MB, with a hard cap (e.g. 2 × 5 MB, then truncate-and-restart). Never disable logging permanently; at most, disable the console note. Use a bounded queue (e.g. 10k lines; drop and count the overflow) so a stuck disk can't grow memory. Tests: rotation with a reader handle open (Windows CI), and replace when `.1` exists.
**Verified:** on Linux renaming an open file works, so Linux CI can't catch this. The Windows sharing behaviour is standard Win32 semantics, but I could not run it here → **uncertain**. The Windows CI job would show it.

### B10 — Medium — §2.5 / §2.7 / §4.4 `X-Ferry-Device` header
**Problem.** The device name goes in an HTTP header. A user-chosen Android device name can contain non-ASCII characters (e.g. `Saurabh’s X200 Pro` with a curly apostrophe, or an emoji). **OkHttp rejects header values outside printable ASCII with `IllegalArgumentException`**, so every request from that phone would fail: uploads, check, outbox, everything. On the Python side, `http.server` decodes headers as Latin-1, so UTF-8 would show as mojibake in the console and log anyway.
**Suggested fix.** Percent-encode the name (UTF-8) in the header and `unquote` it on the PC. Or send it as a query parameter, as with `name`. Unit test with `"Saurabh’s phone 📱"` in `:core` against the real ferry.py.
**Verified:** OkHttp `Headers` value validation and Python's Latin-1 header decoding are API facts I'm confident of. Not run here (no JVM toolchain run).

### B11 — Medium — §4.4 result handling for 507/500 on big files
**Problem.** v1 answers 507/500 only after draining at most `DRAIN_LIMIT` = 64 MB, then closes the socket. For a 1 GB upload that hits disk-full early, the client is still writing when the PC closes. Depending on whether OkHttp reads the response after a failed body write, the engine sees a **network error**, not a 507. Then the "Waiting for PC / 3 retries" path re-uploads the 1 GB file up to 3 times and ends with "Failed (network)" instead of "PC disk is full".
**Suggested fix.** After any network failure **mid-body** on an item larger than 64 MB, call `GET /api/v1/info`. If `free_bytes < remaining item size + 64 MB`, treat it as disk-full (stop the queue with the right message). Add a `:core` integration test against real ferry.py: a 100 MB upload with a monkey-patched small free space (env var hook in a test-only launcher) asserts the "disk full" outcome.
**Verified:** v1 code read (`DRAIN_LIMIT = 64 << 20`, `drain()` then reply). OkHttp's behaviour when the request body write fails → **uncertain**.

### B12 — Medium — §7 `MiniHttpServer` limits (phone cast server, TV control server)
**Problem.** "30 s socket timeout" with "max 8 concurrent; extra connections get 503". In Java, `Socket.setSoTimeout` applies **only to reads**. A `write()` to a client that has stopped reading blocks until TCP gives up, which can take many minutes. A stalled client blocks the write: ExoPlayer with a full buffer during a long pause, a TV that lost Wi-Fi without sending RST, or ExoPlayer abandoning a connection after a seek without closing it promptly. Each such thread pins one of the 8 slots. A few seeks plus a pause can use up the pool, and then the TV gets **503 for new Range requests** and playback stops: the same kind of freeze T2 exists to fix.
**Suggested fix.** Add a per-connection write watchdog: track the last progress time and close the socket if a write has made no progress for 30 s. A blocking write unblocks when the socket is closed from another thread. Raise the cap to 16 and evict the oldest idle-blocked connection instead of answering 503. JVM test: a client that requests a 50 MB range, reads 1 KB and stops; assert the slot is freed within about 35 s and new requests succeed.
**Verified:** Java socket timeout semantics (read-only) are an API fact I'm confident of. Contrast: v1's Python `settimeout(60)` also covers sends, so the PC frees such connections after 60 s.

### B13 — Medium — §5.2 Video player "long pause" claim / §10 TV tests
**Problem.** Acceptance #5 (pause 30 s, then seek; the original Silk freeze) depends on an untested claim: "the PC closes idle connections after 60 s; ExoPlayer retries". In v1, `SOCKET_TIMEOUT = 60` and Python's socket timeout applies to `send`. When ExoPlayer's buffer is full during a pause, the PC's `wfile.write` times out and the connection drops mid-body. ExoPlayer then sees a premature EOF and has to reopen with `Range`. That probably works (the progressive loader retries and resets its error count on progress), but the design's TV emulator tests (D-pad, play, slideshow) don't cover it.
**Suggested fix.** Add to the TV emulator job: play a ≥ 200 MB test file from ferry.py, pause 90 s (longer than the 60 s PC timeout), resume, seek +30 s and −30 s, and assert `playbackState == READY`, no `PlayerError`, and the position advancing. Also set an explicit `DefaultHttpDataSource` read timeout (e.g. 20 s) so a dead connection is noticed quickly. Optionally raise the PC's timeout for 206 responses only.
**Verified:** v1 code read (`SOCKET_TIMEOUT = 60`, `FerryHandler.timeout`). ExoPlayer retry details → **uncertain**.

### B14 — Medium — §10 Testing: integration tests only exercise the Linux code paths of ferry.py
**Problem.** The `:core` "integration against a real ferry.py" job runs on Linux. The never-overwrite placement on Linux is `os.link` (atomic). On Windows it is `os.rename` + `PermissionError` retries + case-insensitive families + `WIN_MAX_PATH` trimming. That Windows path is what runs on Saurabh's PC, and it is never exercised end to end with 2 parallel workers. The first ever Windows CI run of v1 had 6 failures (v2-ci-baseline), which shows Windows paths can't be taken on trust.
**Suggested fix.** Run the `:core` integration test class on `windows-latest` too: JDK 17 and Python are preinstalled there, so it is cheap. Include: 2 workers uploading `IMG_0001.jpg` and `img_0001.JPG` (different content) at the same time, 2 workers uploading the same content at the same time (one `skipped`), a long name in a deep inbox, and the post-body retry case (B18).
**Verified:** v1 `_place` read. Concurrency on Linux tested in /tmp/b/inbox_exp.py: 4 parallel receives (A, B, A, A) gave `IMG_0001.jpg`=A, `IMG_0001 (1).jpg`=B and 2× skipped. Correct, no overwrite. Windows is **uncertain** until it runs.

### B15 — Low — §1 / §2.3 Protocol 2 vs an old PC (capability negotiation)
**Problem.** The design advertises `protocol: 2` and `capabilities` but never says what the app does when they're missing. A new app against a v1 PC (likely during the switch-over, or if an old copy of `Ferry.bat` is started) would fail like this:
- discovery: no UDP reply, so only the last-known/manual address works.
- `POST /api/v1/check-batch`: v1 answers **405** (any POST to an unknown path).
- `/api/v1/app`: 404.
**Suggested fix.** App: after `GET /api/v1/info`, if `protocol < 2` or `check_batch` is missing, fall back to per-file `GET /api/v1/check` (still kept in v2; keep it tested). Show a one-line banner: "Your PC has an older Ferry. It still works; update it for faster sending". Treat 404/405 on optional endpoints as "not supported". `:core` test against a fake v1 server.
**Verified:** v1 `_dispatch` read (an unknown POST returns 405).

### B16 — Low — §2.4 Discovery port vs `--port`
**Problem.** v1 tells users to restart with `Ferry.bat --port 8800` when Hyper-V/WSL has reserved 8765. The design doesn't say whether UDP follows `--port`. The app only broadcasts to 8765. Hyper-V excluded port ranges exist for UDP too, so UDP 8765 can also be blocked.
**Suggested fix.** Always bind UDP **8765**, independent of `--port`. The reply's `port` is the actual TCP port. Store `host:port` as the last-known PC, and let manual entry accept `host:port`. If the UDP bind fails, the console line says "Automatic discovery is off; on the phone, enter <ip>:<port> once".
**Verified:** v1 `bind_server` messages read. Hyper-V UDP exclusions → **uncertain**.

### B17 — Low — §4.3 "COLUMN_LAST_MODIFIED when present, otherwise now"
**Problem.** Sending "now" as `mtime` when the real date is unknown has two effects. (a) It stamps a wrong date onto the PC copy. (b) The pre-check can never match on a resend, because "now" changes every time. The file is fully re-uploaded and only then skipped by the hash. The same happens when MediaStore `DATE_MODIFIED` is 0 or null: the PC ignores the date (outside 1990..tomorrow), and the pre-check never matches.
**Suggested fix.** Omit the `mtime` parameter when it is unknown or ≤ 0. v1 already handles `mtime=None`: no date restore, no name/size pre-check match, and the hash dedupe still prevents a duplicate file.
**Verified:** experiment: `precheck(..., mtime=None)` and `mtime=0` both return not-duplicate (/tmp/b/inbox_exp.py).

### B18 — Low — §4.4 Retry and Cancel after the body was fully sent
**Problem.** A timeout or Wi-Fi drop **after** the last body byte (while the PC fsyncs, hashes or retries the rename for up to about 8 s) leaves the file saved on the PC while the app counts a network failure. The retry is then answered `skipped` (receive-time SHA-256), so the summary says "already on PC" for a file that this transfer saved. Cancel at that moment shows "Cancelled" for a saved file. This is data-safe (verified: an identical re-upload is skipped, never duplicated), but the summary counts are wrong and confusing.
**Suggested fix.** In UploadEngine, remember "body fully sent" per item. If the retry of such an item returns `skipped`, report it as **Saved**. For Cancel after body-complete, wait up to 10 s for the reply before declaring the outcome.
**Verified:** v1 `receive` read. Hash-skip of an identical second upload confirmed by experiment.

### B19 — Medium — §4.3/§4.4 Share → Ferry URIs used from `TransferService`
**Problem.** Read grants that come with a share intent (`FLAG_GRANT_READ_URI_PERMISSION`) belong to the **receiving activity**. Once that activity or task is finished, for example the user swipes Ferry away while the screen-off upload continues, later reads of those URIs from the service can throw `SecurityException`. Items queued from a share would then fail part-way through a long batch. Nothing in the design addresses grant lifetime.
**Suggested fix.** Hand the URIs to `TransferService` via `startForegroundService(intent)` with the URIs in `ClipData` plus `FLAG_GRANT_READ_URI_PERMISSION`, so the service holds its own grant. For SAF picks, call `takePersistableUriPermission` where it is offered. If a read still fails, mark the item "Can't read this file any more; share it again", not a generic failure. Add an emulator test: share 3 files, finish the activity, assert that all 3 arrive.
**Verified:** Android grant scoping is from my knowledge of the platform → **uncertain** on exact lifetime per version. The fix is the standard pattern.

### B20 — Low — §4.4 "Waiting for PC … up to 30 min, then resumes"
**Problem.** The text doesn't say what happens when 30 minutes pass without the PC. ("Then resumes" presumably describes the case where the PC comes back.) Without a defined end, the foreground service, WakeLock and WifiLock could be held indefinitely on vivo.
**Suggested fix.** After 30 min: mark the remaining items "Not sent: PC not found", show the summary with "Retry", release the locks and stop the service. Unit test with a fake clock.
**Verified:** design read.

### B21 — Low — §4.6 App update while a transfer or cast is running
**Problem.** Installing the update replaces the package and kills Ferry's process. An in-progress upload queue (in memory, H16), a pending outbox download, or a cast session dies. That is data-safe (PC temp deleted; pending row orphaned, see B7), but it interrupts the user exactly when they're using the app.
**Suggested fix.** Disable or hide "Update" while `TransferService` or `CastService` is active ("Update after the transfer finishes").
**Verified:** design read.

### B22 — Low — §3.3 Versioning (`versionCode = run number + 100`)
**Problem.** `github.run_number` is **per workflow**. Renaming or splitting `android.yml`, or building the release from another workflow, restarts the count. That makes versionCode lower than the installed app: Android refuses the "update" as a downgrade, and the app's `version_code >` check silently never offers it. Builds from branches or PRs also use numbers, so the sequence is not monotonic in practice.
**Suggested fix.** `versionCode = 20000 + run_number` of one fixed release workflow (only `main`). In CI, assert it is greater than the versionCode of the previous release artifact. Document "never rename android.yml" in the handoff.
**Verified:** GitHub Actions `run_number` semantics (per workflow) — confident.

### B23 — Low — §3.2 / §12 Signing contradicts decision H18
**Problem.** The design says the keystore lives in GitHub secrets (`FERRY_KEYSTORE_B64`…) and §12 lists "Secrets + backup". Decision H18 says it is **committed** to `android/signing/` because the secrets API is blocked. A future Claude session following the design would build with a different key, and every update would then fail with a signature conflict (which forces an uninstall and loses app data).
**Suggested fix.** Update §3.2/§12 to match H18, name the file path and the alias, and add a CI check that the release APK's signing-certificate SHA-256 equals a constant pinned in the repo.
**Verified:** docs compared.

### B24 — Medium — §10 `:core` tests "against a real ferry.py": the PC program isn't test-launchable as is
**Problem.** Starting ferry.py as a subprocess from JVM tests (and from the emulator job on the host) runs into v1 limits that the design doesn't address:
- `--port` must be 1–65535 (`parse_args`), so there is no `--port 0`. Tests must guess a free port, which is racy with parallel Gradle forks.
- UDP discovery is fixed at 8765 (B16), so parallel instances collide.
- `ferry-state.json`, `ferry-error.log` and the new `ferry-log.txt` are written **next to ferry.py** (`HERE`), so parallel instances share them and fight over rotation.
- On first run, ferry.py calls `open_folder` unless `--no-open` is given.
- There is no machine-readable "ready on port N" signal.
**Suggested fix.** Add hidden test flags: `--port 0` (print `FERRY_READY port=<n>` on stdout once bound), `--discovery-port N|off`, and `--data-dir DIR` (state + logs). Tests always pass `--no-open --inbox --outbox`. Keep these flags out of the README.
**Verified:** v1 `parse_args`, `STATE_FILE`/`ERROR_LOG` = `HERE/...`, `main()` read.

### B25 — Low — §2.2 Removed routes: leftovers and tests not listed
**Problem.** H9 removes the page, /diag, /qr, short links and PWA assets. Also affected but not mentioned:
- the sources `pc/tools/page.html`, `diag.html`, `keepawake.mp4`, `icon-maskable.svg` and `tools/build_assets.py`;
- the browser JS tests `pc/tests/js/queue.test.mjs` and `pc/tests/e2e/e2e.mjs` (and any CI steps that run them);
- v1 pytest cases that hit `/`, `/diag`, `/new`, `/qr` and `/api/v1/diag/*` (`test_http.py` lines ~87, 203, 305, 309–320);
- `tools/package.py`'s file list.
If these aren't planned, CI breaks or dead assets ship. `exif_gps_status` (used by /diag) is worth keeping as a test helper (B1).
**Suggested fix.** List them explicitly in the code plan. Replace the deleted tests with "removed route → 404 JSON/HTML" tests. Make `package.py` include `app/Ferry.apk(.json)` and fail the build if they're missing.
**Verified:** `grep` of `pc/tests` and `ls pc/tools`.

### B26 — Low — §2.3 `/api/v1/app` sidecar caching vs replacing the files while Ferry runs
**Problem.** The cache is recomputed only "if the APK's size/mtime changes". Unzipping a new Ferry zip over a running Ferry replaces the APK and the json at different moments, and the json change isn't watched. The PC may keep serving stale metadata, or briefly serve a half-written APK. The app verifies sha256 and size, so nothing is installed wrongly, but the user sees "Update failed". The browser first-install path via `/app` has no verification at all.
**Suggested fix.** On each `/api/v1/app` and `/app` request, re-stat **both** files. If either changed, re-read the json and re-hash (cached by (size, mtime_ns) of both). Answer 404 "update being copied, try again" while they disagree. Serve `/app` only when the hash matches the json.
**Verified:** design read.

### B27 — Medium — §10 CI feasibility and coverage of the emulator layer
**Problem.**
1. **Discovery can't be tested in the emulator**: the emulator's NAT (10.0.2.x) doesn't pass broadcasts to the host, so the broadcast → PC path is only covered by JVM tests on loopback (unicast). That is acceptable, but it should be stated so nobody trusts a "green" emulator run for P8.
2. **Cast loopback in one phone emulator**: `CastReceiver` only runs in TV mode in the foreground, so the plan needs a test-only way to start the receiver in a phone-mode emulator. Otherwise it needs a second (TV) emulator plus networking between emulators, which is not simple on Actions.
3. **Cost and flakiness**: three emulator configurations (API 34 phone, API 30 TV, API 25 smoke) per push take roughly 30–45 runner-minutes, with known boot flakiness. KVM works on `ubuntu-latest` only after the udev-rule step documented by `reactivecircus/android-emulator-runner`.
4. **Screenshots** are only useful if the reviewing session can fetch them: `gh run download` through the proxy. Check this once.
**Suggested fix.**
- Add the KVM udev step, AVD snapshot caching, `disable-animations: true`, and one automatic retry of the emulator step.
- Run the phone emulator on every push to `main`, and the TV + API 25 jobs on `main` and `workflow_dispatch` only.
- Add a debug-only intent/flag that starts `CastReceiver` in phone mode for the loopback test.
- Put a short "what CI does not prove" list in the handoff: broadcast discovery, vivo battery behaviour, Fire OS installer UI, real Windows Firewall.
**Verified:** emulator networking (no broadcast to the host) and KVM setup are from my knowledge → **uncertain** on exact current runner behaviour. The cost figures are estimates.

### B28 — Low — §2.5 Log volume from polling
**Problem.** "One line per request" includes the phone's From-PC screen polling `/api/v1/outbox` every 5 s, the TV Home auto-refresh every 5 s, and `/api/v1/info` probes every 3 s while waiting. A TV left on the Ferry home screen alone writes about 17k lines/day. Rotation keeps the size bounded, but real transfer lines get pushed out of the 10 MB window and are harder to find (acceptance #10).
**Suggested fix.** Don't log 200 responses to `GET /api/v1/outbox` and `/api/v1/info`, or collapse them ("12 list refreshes from 192.168.1.20 in the last 10 min"). Log all errors and all transfers.
**Verified:** arithmetic from the design's refresh intervals.

## Summary

| ID | Sev | Area | One-line |
|---|---|---|---|
| B1 | High | Testing / P2 | Emulator GPS test probably can't fail: Ferry owns the inserted file, so no redaction. Push the fixture via adb and add a negative control |
| B2 | Medium | Discovery (Windows) | WinError 10054 on `recvfrom` after ICMP unreachable can kill the responder thread |
| B3 | Medium | check-batch | Disk-full judged on the sum *including duplicates*: false "needs more space" on a resend |
| B4 | Medium | check-batch | One scandir per item: 1.8 s/500 items on a 10k inbox (Linux), so slow on Windows; no client timeout |
| B5 | Medium | Upload | Stale MediaStore SIZE → permanent "file changed" failures; use fd statSize |
| B6 | Medium | Data safety / GPS | A GPS-less fallback copy poisons the dedupe; the original with GPS is never sent again |
| B7 | Medium | Outbox download | Resume/restart into a pending MediaStore row: truncation, length check, orphans |
| B8 | Low | Outbox download | API 25–28 file path has no clash rule (could overwrite) |
| B9 | Medium | PC log | Windows rotation of an open file; logging must never switch itself off |
| B10 | Medium | Protocol | Non-ASCII device name in `X-Ferry-Device` breaks every OkHttp request |
| B11 | Medium | Upload | 507 after the 64 MB drain looks like a network error: 3× re-upload, wrong message |
| B12 | Medium | MiniHttpServer | Java read-only timeouts: stalled writes exhaust 8 slots, then 503 |
| B13 | Medium | TV player | Long pause > 60 s vs the PC socket timeout is untested (acceptance #5) |
| B14 | Medium | Testing | Run the ferry.py integration tests on Windows CI too (rename/case/path code) |
| B15 | Low | Protocol | No defined fallback for a v1 PC (check-batch → 405) |
| B16 | Low | Discovery | UDP port vs `--port`; store host:port |
| B17 | Low | Upload | Unknown mtime → omit it, don't send "now" |
| B18 | Low | Upload | Post-body retry/cancel gives wrong summary counts (data-safe) |
| B19 | Medium | Share | URI grant lifetime when the service outlives the share activity |
| B20 | Low | Upload | What happens after 30 min waiting for the PC is undefined |
| B21 | Low | Update | Self-update kills a running transfer/cast |
| B22 | Low | Versioning | run_number resets per workflow, so a downgrade blocks updates |
| B23 | Low | Signing | §3.2/§12 contradict H18 (committed keystore) |
| B24 | Medium | Testability | ferry.py needs `--port 0`/ready line, `--discovery-port`, `--data-dir` for tests |
| B25 | Low | PC cleanup | Removed routes: tools, JS/e2e tests and v1 pytest cases not listed |
| B26 | Low | App sidecar | APK/json cache staleness while files are replaced |
| B27 | Medium | CI | Emulator limits (no broadcast, cast loopback hook, cost/flakiness, KVM step) |
| B28 | Low | PC log | Polling requests flood the log |

**Counts:** High 1 · Medium 15 · Low 12 (total 28).

Positive checks: TCP and UDP coexist on one port number (Linux, verified). The v1 inbox is race-safe under parallel uploads (verified: no overwrite; identical content is skipped). The v1 Range/If-Range/ETag sender is suitable for resume and ExoPlayer.
