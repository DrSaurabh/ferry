# Ferry v2 Stage 7 code audit: file safety and data correctness (reviewer: Sonnet)

Repo `/home/claude/ferry`, branch `main` @ c6269ac. Nothing in the repo was modified. Scratch scripts are in `/home/claude/audit/scratch-sonnet/` (exp1 to exp6).

## Scope

- PC `pc/ferry.py`: upload (`Inbox.receive`, `_place`, `api_upload`), temp files, duplicate pre-check and `check-batch`, H30 disk-full rule, `sanitize_name`, timestamps, outbox serving and Range, `/app` and `/api/v1/app`, discovery responder, `ferry-log.txt`, shutdown and start-up cleanup.
- Android upload path: `UploadItemFactory`, `ContentUploadSource`, `StreamingBody`, `FerryClient`, `UploadEngine`, `Transfers`, `TransferService`, `TransferLocks`, share hand-over.
- Downloads: `DownloadQueue`, `SinkWriter`, `MediaStoreDownloadSink`, `DownloadSinks` (H31).
- APK self-update and install: `AppUpdate`, `AppUpdater`, `ApkInstaller`, `make_apk_json.py`, `check_apk.py`, signing set-up.
- Read but not re-litigated: the logged decisions H16, H19, H20, H30, H31, H35. None of them caused real harm in my review.

I ran `cd pc && python3 -m pytest -q`: **169 passed, 4 skipped**.

Android code cannot be built here, so Android claims come from reading the code and are marked "uncertain" where they depend on device or OS behaviour.

## Findings

No High or Medium findings. The data path is carefully built and my experiments did not break it. Everything below is Low.

### S1 — Low — Outbox download URL trusts only the numeric id
`pc/ferry.py:1943` (`RX_OUTBOX`), `:2020-2022`, `:2265`; app side `DownloadQueue.process` / `FerryClient.download`.

**Problem.** `/api/v1/outbox/<id>/<name>` ignores the `<name>` part. Ids are assigned from 1 on every PC start, and also change when a file is modified. If Ferry is restarted on the PC, or the outbox changes, while the phone still holds an old list, "Download" on a stale id fetches whatever file now has that id. The only guard is the app's `Content-Length == listed size` check. Two files of equal size (for example two photos both exactly N bytes) would be saved under the wrong name, silently.

**Evidence.** Experiment (exp3): `GET /api/v1/outbox/1/../../../etc/passwd` returned 200 with outbox file 1. This is not a traversal, but it confirms the name segment is not checked. Wrong-file reproduction was not run. The reasoning follows directly from the code.

**Fix.** In `send_outbox_file`, if the path has a name segment, require `unquote(segment) == of.name`, else 404. Five lines, and the app already sends the right URL. The app then reports "gone from PC" through its existing 404 path.

### S2 — Low — Legacy `/api/v1/check` answers disk full before duplicate
`pc/ferry.py:857-858` (`Inbox.precheck`), used by `api_check` at `:2153`.

**Problem.** When free space is under size + 64 MB, `precheck` returns `disk_full` before it looks for a duplicate. That contradicts the H30 rule ("a resend of something already on the PC is still answered 'already on PC'"). `check_batch` and `api_upload` do it correctly.

**Evidence.** Experiment: with free space forced to 1000 bytes and `a.jpg` already present, `precheck` gave `{'ok': False, 'reason': 'disk_full'}` and `check_batch` gave `duplicate: 'a.jpg'`. The Android app never calls `/check` (`FerryApi` has no such method), so there is no user impact today.

**Fix.** Delete `api_check` and `precheck` (v1 leftovers), or move the free-space test after the duplicate loop.

### S3 — Low — H30 exemption can let a non-duplicate fill the disk below the margin
`pc/ferry.py:2227`.

**Problem.** The early refusal is skipped if any file in the name family has exactly the same size. That includes a different photo that merely has the same size. It then passes through `receive()` and can use the last 64 MB and more.

**Evidence.** Reasoning only. The ENOSPC path itself is safe: experiment exp2 on a 72 MB tmpfs filled the disk mid-upload and the PC answered 507, removed the temp file and left no partial file.

**Fix.** Also require `abs(st.st_mtime - mtime_ms/1000) <= 2` (the same test as the duplicate check) in the exemption. A real resend still matches. It is cheap, and optional.

### S4 — Low — `sanitize_name` strips leading and trailing dots from the original name
`pc/ferry.py:699` (`s.strip(" .")`).

**Problem.** `.nomedia` is saved as `nomedia`, `.trashed-170-IMG.jpg` as `trashed-170-IMG.jpg`, `photo.` as `photo`. P2 and priority 1 say originals keep their name. The pre-check uses the same sanitised name, so resends stay consistent. This only affects odd files.

**Evidence.** Experiment (`sanitize_name('.nomedia') == 'nomedia'`).

**Fix.** Strip only trailing spaces and dots (the Windows rule), and keep leading dots. Windows allows leading-dot names.

### S5 — Low (uncertain) — Start-up cleanup deletes the APK the installer may be reading
`android/app/.../AppGraph.kt:127-132`.

**Problem.** On every process start, `files/update/*` and `files/apk/*` are deleted. If Android kills Ferry's process while the system installer is open (low memory is common while the installer is showing) and Ferry is started again by something else (a share, the cast service, a notification tap), the APK being installed is removed under it. The result would be "App not installed" or a parse error. The user can retry from the banner, so nothing is lost.

**Evidence.** Reasoning only. Not reproducible here.

**Fix.** Delete only files older than about 1 hour at start-up (or only when a new download begins).

### S6 — Low (uncertain) — Upload engine and download queue keep a stale API object after a network change
`UploadEngine.kt` `current` (set in `connect()` and `probeLoop`, never cleared); `DownloadQueue.kt` `found`.

**Problem.** `pick()` is `current ?: api()`. Once a re-discovery has set `current`, later network changes (Wi-Fi roam to a new `Network` object) rebuild the client in `ConnectionManager`, but the engine keeps using the old client, bound to the dead network's socket factory. It recovers at the next 30 s re-discovery, so the cost is up to 30 s of waiting per roam. It is not a data risk, because the file restarts safely.

**Evidence.** Reasoning from the code. Whether Android hands out a new `Network` on the real router is uncertain.

**Fix.** Drop the cached `current`, or clear it when `api()` returns a different instance.

### S7 — Low (uncertain, watch in acceptance test 2) — Wi-Fi lock may do nothing with the screen off
`TransferLocks.kt`.

**Problem.** On API 29+ `WIFI_MODE_FULL_LOW_LATENCY` is, as far as I remember the platform docs, only effective with the screen on and the app in the foreground. With the screen off, only the CPU wake lock and the foreground service protect the 1 GB upload. The code comment already says it is "not relied on". There is no better API (HIGH_PERF is deprecated). Wi-Fi power-save on the vivo could slow or drop a screen-off transfer.

**Evidence.** Memory of the docs. Not verified here, and no fix needed. Watch acceptance test 2 (1 GB+ video, screen off). Retry from the start is already designed (P6).

### S8 — Low (note) — Release keystore is public, only its passphrase protects it
`android/signing/ferry-release.jks.enc` (H38).

**Problem.** The encrypted keystore is in a public repo (AES-256-CBC, PBKDF2 200k). Anyone can download it and try to guess the passphrase offline. If the passphrase is short, the signing key (and so the ability to ship a "Ferry" update) is exposed. I could not see the passphrase and it is not in the repo (grep found no leak).

**Evidence.** Not testable here.

**Fix.** Make sure the passphrase is long and random (20+ random characters). This is not authentication; it is only about the signing key staying private.

## Checked and found sound

**PC upload path (experiments exp1, exp2, exp4, exp5, exp6)**
- Same name, same content: saved, then `skipped`. Same name, different content: `a (1).jpg`. Nothing is ever overwritten.
- 40 parallel uploads of the same name with 10 distinct contents: all 10 contents present as `IMG_1.jpg`, `IMG_1 (1..9).jpg`, no temp files left, no duplicates of identical content.
- Zero-byte file: saved, resend skipped.
- Hostile names: `../evil.txt`, `..`, `../../etc/passwd`, `a/b/c.txt`, `..\..\x.txt`, `C:\win\x.txt`, `/abs/path`, `con.txt`, `NUL`, `x.txt:ads`, NUL and control characters, RTL override, 400-char names. All land inside the inbox with safe names. A 20,000-case fuzz of `sanitize_name` (Windows and Linux modes) found no separators, no Windows-invalid characters, names within 255 bytes including `(9999)`, and Windows paths within 260 characters.
- Interrupted upload (client closes after 100 KB, or stalls): the temp file is removed and nothing is left in the inbox.
- ENOSPC mid-upload on a real 72 MB tmpfs: 507, temp file removed. A file bigger than free space minus 64 MB: 507 with the body drained.
- Huge Content-Length (10^15): 507 without reading the body. Negative Content-Length: 400. Chunked: 411. `size` not equal to Content-Length: 400. Missing name: 400.
- Timestamps: restored to the millisecond for dates from 1990 to tomorrow; out-of-range values (0, negative, year 2286) are ignored and the file gets the arrival time.
- Start-up temp cleanup happens only after the port is owned (`main()`), so a second Ferry window cannot delete the running one's temp file.
- `precheck` and `check_batch` were fuzzed against each other with 600 random inboxes (case folding, `İ ı K ſ`, `(n)` suffixes, NFC/NFD): 0 mismatches.

**PC serving**
- Outbox: Range (206, suffix range, 416), HEAD and ETag are correct. `.part`, dotfiles and temp names are not offered. A file changed after listing gives 404 (the listed id is gone). Traversal attempts `/app/../ferry.py`, `/%2e%2e/...` and `/api/v1/outbox/%2e%2e` all return 404.
- `/app`: a wrong size or wrong sha in `Ferry.apk.json` makes `/app` and `/api/v1/app` answer 404 "copying" instead of serving a half-copied APK. A good pair is served byte-identical.
- Log: ferry-log lines for interrupted, refused and bad requests have status, bytes, duration and outcome; rotation is unit-tested.
- Discovery responder: size and shape checks, rate limit, never dies on bad input.

**Android upload**
- `StreamingBody` look-ahead: a file that grows is detected before the last byte is sent (PB1). A shorter file throws `SourceChanged`. The PC never commits a truncated file, and the CI run that disabled the look-ahead failed as intended (build log run 43725763301).
- `ContentUploadSource`: with `requireOriginal` there is no fallback to the redacted stream. The GPS redaction was measured on the API 34 emulator (H36), so the GPS tests can fail.
- Engine: a lost reply after a fully sent body is recognised on retry (`bodySentOnce` plus `skipped` becomes Saved). Disk-full, cancel (with the 10 s reply grace), file-changed refresh, and PC-gone waiting (30 min) behave as designed. Rows added while running, and stop decisions, are done under one lock.
- Share hand-over: the grant rides on the service start intent. The build log run 2 shows the grant outliving both the share activity and the service on API 34, so a later Retry of shared items is likely to work (not tested on the vivo).

**Downloads and update**
- Downloads: new MediaStore row with `IS_PENDING=1`, close then compare size then commit, abort deletes the row, and leftovers are cleaned at start-up before any new sink. MediaStore picks `name (1).ext` on a clash, so nothing is overwritten. H20 (restart from zero) and H31 (Android 10+ only) are implemented as logged. The MIME-versus-extension rename quirk in MediaProvider should not trigger, because the MIME comes from the same extension.
- Update: size and sha256 are checked while writing, before the rename onto `Ferry.apk`; the part file is deleted on a mismatch. Android itself enforces same signer and no downgrade at install. CI (`check_apk.py`) checks the signing certificate against `cert-sha256.txt` and the versionCode against the JSON.
- Release key: the old key is retired (H38) and the passphrase is not in the repo.

## Not verified (Android code cannot be built here)

- Real-device behaviour of S5, S6, S7.
- Everything about vivo battery management, Fire OS installer behaviour and Android 15 foreground-service timeouts (the code handles `onTimeout`).
- Windows-only paths of `Inbox._place` (rename retry for antivirus locks) were read but not run; I did not check how much of that the test suite covers.
