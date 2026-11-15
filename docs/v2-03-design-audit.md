# Ferry v2: design audit log (Stage 3, 2026-11-15)

**Artifact:** `v2-02-design.md` rev 1 → **rev 2** (fixes applied in place, each tagged with the finding ID in brackets).

**Reviewers (independent, read-only):**
- **A: Sonnet** (`v2-03a-audit-sonnet.md`): Android platform correctness on the real devices (vivo X200 Pro, Fire OS 6/7/8) and UX for a non-developer. 25 findings (3 High, 14 Medium, 8 Low).
- **B: Opus** (`v2-03b-audit-opus.md`): data safety, wire protocol, PC program, testability/CI. 28 findings (1 High, 15 Medium, 12 Low).
- **Final review and fixes: Fable** (this log), applying `v2-audit-brief.md` strictly: non-goals and unneeded hardening rejected, implementation detail deferred.

**Result:** 53 findings → **48 confirmed** (fixed in rev 2; 6 of them only in part: A6, A15, A18, A20, B15, B27), **0 rejected outright** (every finding had a real core; the rejected *parts*, mostly over-engineering, are named in the rows), **5 deferred** (A22, A23, A24, A25, B25). Four pairs/groups were duplicates across the two reports and were fixed once: A2+B6, A5+B19, A16+B7+B8, A21+B5+B17.
New hands-free decisions: **H19–H27** in `v2-decisions-log.md`.

"Uncertain" items were decided on the best available knowledge and the fix was chosen to be safe whichever way the platform behaves; these are marked *(uncertain → safe either way)*.

## Triage table

| ID | Severity (reviewer's) | Verdict | Reason / what changed (section) |
|---|---|---|---|
| A1 | High | CONFIRMED | Cleartext HTTP is blocked by default on API 28+ and would break everything except Fire OS 6. `usesCleartextTraffic="true"` added to the new manifest checklist (§3.6), asserted by a CI manifest test (§10). |
| A2 | High | CONFIRMED (= B6) | A location-stripped copy has the same name/size/date, so the PC's pre-check would block the good copy for ever. §4.3: original access is probed by opening the stream; picker items without it are **not sent silently**: "Allow / Send without location" choice; such items are reported "Saved without location". §4.4/§4.1: "Already on PC → Send anyway" re-uploads without the pre-check (PC hash dedupe saves different content as `name (1)`). No protocol change (the `redacted=1` parameter was not needed). Decision H19. *(redaction byte behaviour uncertain → safe either way)* |
| A3 | High | CONFIRMED | `:core` could call Java APIs missing on API 25. §3.1: `:core` API rule (no `java.time`, `java.nio.file`, Java 9+ APIs) + core library desugaring in `:app`; §10/§12: the API 25 emulator job now runs a real instrumented test through `:core`, not just a boot. Animal Sniffer / converting `:core` to an Android library rejected as more machinery than needed. Decision H27. |
| A4 | Medium | CONFIRMED | New §3.6 "Manifest checklist" (permissions, FGS types, TV launcher entries, FileProvider, cleartext) + cheap CI test on the merged manifest (§10). API 26 guards (`ContextCompat.startForegroundService`, channels) noted in §3.6. |
| A5 | Medium | CONFIRMED (= B19) | Share → Ferry was not designed. New §4.1 item 7 "Share target": translucent `ShareReceiverActivity`, URIs forwarded to `TransferService` in the start intent's `ClipData` with the read-grant flag (service holds its own grant), queues even if the PC isn't found yet, works before first-run permissions. Passing URIs through `AppGraph` instead was **rejected** (it would not carry the grant; the incoming share already fitted the binder limit). Typed `getParcelableExtra` → code plan. *(grant lifetime uncertain → safe either way)* |
| A6 | Medium | CONFIRMED (part) | §3.4: `onTimeout` handler for `dataSync`, services started only from a visible activity or a notification action, user-stopped service = Cancelled, final summary as a separate normal notification (second channel), notification updates ≤ 1/s. **Rejected part:** changing `CastService` from `mediaPlayback` to `dataSync`: `mediaPlayback` has no 6-hour limit (a film cast from the phone would eat into the shared `dataSync` budget) and needs no extra prerequisite; H14 stands. |
| A7 | Medium | CONFIRMED | §3.4: WifiLock mode chosen by API level, both locks acquired with a timeout and released in `finally`; the design no longer relies on the WifiLock for screen-off speed (FGS + WakeLock + live traffic do the work). Acceptance 2 note: compare MB/s screen on/off (§12). Absorbs A21's WakeLock-timeout point. *(uncertain → safe either way)* |
| A8 | Medium | CONFIRMED | §4.2: status line renamed "Android battery setting: OK" (no false "unrestricted ✓"), vivo steps worded generically in one string, "lock Ferry in Recents" step added, vivo steps shown prominently after a detected kill. §4.1 Settings line updated. |
| A9 | Medium | CONFIRMED | §3.5: all sockets (OkHttp, discovery UDP, ExoPlayer, Coil) are bound to the Wi-Fi/Ethernet `Network`; "not on Wi-Fi" is a distinct state. `:core` takes a socket factory/binder so it stays Android-free. The targetSdk 36+/37 local-network permission note → deferred (v2.1). *(vivo defaults uncertain → safe either way)* |
| A10 | Medium | CONFIRMED | §5.1/§5.5: TV keeps the screen on during player/viewer/slideshow and for 30 min of idle on Home (not for ever, so a forgotten TV still sleeps); explicit idle Home text "Ready for your phone"; server lifetime tied to `onStart/onStop`; phone's "TV not found" message tells the 2-step flow. Decision H24. |
| A11 | Medium | CONFIRMED | §5.1: photo thumbnails only for visible cards, ≤ 2 loads at a time, decoded at ~320 px, disk-cached by `name|size|mtime`; the 5 s refresh never reloads unchanged items; undecodable → icon. "No thumbnails at all / none on Fire OS 6" rejected for now (decide on the real stick). |
| A12 | Medium | CONFIRMED | §5.2: message for unsupported **audio** ("can't play this video's sound"), player owns the remote keys (controller is display-only), Resume prompt defaults focus to Resume (one OK press). §5.1: non-playable `video` kind moves to "Other files". Auto-resume overlay alternative not taken (prompt with default focus is simpler). |
| A13 | Medium | CONFIRMED | §5.1: named focus mechanisms (initial `FocusRequester`, focus restored on return, focused item scrolled into view) and an emulator test asserting focus returns to the same card (§10). Exact Compose APIs → code plan. |
| A14 | Medium | CONFIRMED | Simplification. §4.6/§5.4: installs use `FileProvider` + `ACTION_VIEW` (API 25–35) for both self-update and outbox APKs, no `PackageInstaller` session; unknown-sources intent guarded (API 26+, try/catch) with a plain-text fallback; re-check on return. Decision H21. *(Fire OS intent resolution uncertain → safe either way)* |
| A15 | Medium | CONFIRMED (part) | §4.2: permissions asked at the point of use (media + location in one request at the first "Send photos & videos"; notifications at the first transfer); `READ_MEDIA_VISUAL_USER_SELECTED` declared and partial access detected; twice-denied → app settings; "if vivo asks again, choose Allow". **Rejected part:** moving the battery step to after the first transfer: vivo killing the first big upload is the top real-device risk, so the one-tap battery step stays on the welcome screen. Decision H23. |
| A16 | Medium | CONFIRMED (= B7, B8) | §4.5 rewritten: never overwrite on the phone on any API level (API 25–28: `.part` file + `createNewFile` claim of `name (n).ext`), free-space check, length verified before publishing, own leftover pending rows cleaned at start, installs/opens via granted URI. **Resume dropped for v2.0** (restart into a fresh entry): removes the `"w"`/`"wa"` truncation uncertainty entirely. Decision H20. *(uncertain → safe either way)* |
| A17 | Medium | CONFIRMED | §4.1 picker trimmed: long-press preview and per-date "Select all" removed from v2.0 (not in P3); selection is an ordered set; albums aggregated in memory; paged query; platform thumbnails (no `coil-video`). Decision H22. |
| A18 | Low | CONFIRMED (part) | The TV had **no** way to enter an address at all. §5.1: TV "PC not found" state with "Try again" / "Enter address" (system keyboard, numeric, prefilled with the TV's own first three octets) and the found-PCs list. **Rejected part:** custom digit pad and phone-pushes-address-to-TV (extra protocol/UI for a once-only fallback). |
| A19 | Low | CONFIRMED | §4.1/§4.3: button label "Send other files (documents, APKs…)"; media-document URIs are mapped to MediaStore (`MediaStore.getMediaUri`, 29+) so originals are read; a one-line note when a photo/video can't be mapped. |
| A20 | Low | CONFIRMED (part) | §5.3: a photo that fails to decode (format or out-of-memory) is skipped with a short message instead of stopping the slideshow. Codec-profile wording and real-stick checks → deferred (implementation / acceptance). |
| A21 | Low | CONFIRMED (= B5, B17) | §4.3: size from the opened descriptor, one re-query + retry on "file changed", `mtime` omitted when unknown/0. WakeLock timeout is under A7. `DATE_TAKEN` fallback rejected (it is not the file date; omitting is correct). |
| A22 | Low | DEFERRED | Message catalogue (phone storage full, not on Wi-Fi, battery saver, plain units) → code plan strings table. The wait limit is fixed under B20; **Cancel** is already visible while waiting. |
| A23 | Low | DEFERRED | Reviewer agrees minify-off is acceptable for 2.0. R8/shrink → v2.1. |
| A24 | Low | DEFERRED | Remember last TV; notification tap opens Remote → implementation (small). |
| A25 | Low | DEFERRED | Edge-to-edge insets, TV overscan padding, `BackHandler` consistency → code plan. |
| B1 | High | CONFIRMED | The GPS test could not fail (Ferry would own the inserted file; owners are not redacted). §10: fixture pushed with adb + media scan (not owned by Ferry), **negative control** with the permission revoked, GPS asserted on the PC side with v1's `exif_gps_status` kept as a test helper. *(uncertain → the negative control settles it either way)* |
| B2 | Medium | CONFIRMED | §2.4: responder loop catches per-datagram `OSError` (Windows 10054) and keeps going; 0.5 s socket timeout; test with an injected `ConnectionResetError` (§10). *(uncertain on Windows → safe either way)* |
| B3 | Medium | CONFIRMED | §2.3: `check-batch` never refuses; it returns per-item `duplicate` plus `needed_bytes` (non-duplicates only) and `free_bytes`; §4.4: the app adds `needed_bytes` over all chunks and decides, so a resend of duplicates is never "disk full". Per-upload 507 stays the real guard. "Send what fits" option rejected (extra UI; stopping with a clear message is enough). |
| B4 | Medium | CONFIRMED | §2.3: one folder scan and one `disk_usage` per request; §4.4: 60 s client timeout, and if the check fails with an HTTP error/timeout the items are uploaded unchecked (hash dedupe still prevents duplicates). 10k-file timing test → §10. |
| B5 | Medium | CONFIRMED (= A21) | §4.3: upload size = `statSize` of the opened descriptor (fallback `SIZE`), same value used for the check; one re-query + retry before "file changed". |
| B6 | Medium | CONFIRMED (= A2) | See A2. |
| B7 | Medium | CONFIRMED (= A16) | See A16 (restart = new entry; length check before publish; orphan cleanup; resume dropped). |
| B8 | Low | CONFIRMED (= A16) | See A16 (API 25–28 never-overwrite rule). |
| B9 | Medium | CONFIRMED | §2.5: rotation = close → `os.replace` with retries → reopen; if it fails keep appending and retry later, hard cap then truncate; logging never switches itself off; bounded queue with a dropped-lines count. Windows CI test (§10). *(uncertain on Windows → safe either way)* |
| B10 | Medium | CONFIRMED | §2.5/§2.7/§4.4: `X-Ferry-Device` is percent-encoded UTF-8 (ASCII-only on the wire), unquoted on the PC; test with a curly apostrophe + emoji (§10). |
| B11 | Medium | CONFIRMED | §4.4: after a mid-body network failure the engine asks `/api/v1/info`; if `free_bytes` can't hold the item → "PC disk is full" and stop, no blind re-upload. Test hook `--test-free-bytes` (§2.8). *(OkHttp behaviour uncertain → safe either way)* |
| B12 | Medium | CONFIRMED | §7: per-connection write watchdog (close after 30 s without progress), cap raised to 16; JVM test with a stalled reader. "Evict oldest connection" rejected (the watchdog already frees slots). |
| B13 | Medium | CONFIRMED | §5.2: explicit data-source timeouts; §10: TV emulator test "pause 90 s (longer than the PC's 60 s timeout), resume, seek ±30 s, still playing". Raising the PC timeout for 206 rejected (not needed if the test passes). |
| B14 | Medium | CONFIRMED | §10: the `:core` ↔ real `ferry.py` integration tests also run on `windows-latest`, incl. two workers with case-clashing names and with identical content. |
| B15 | Low | CONFIRMED (part) | Behaviour against an old PC is now defined (§1/§3.5): `protocol < 2` → "Your PC has an older Ferry. Replace it with the new Ferry folder", nothing is sent. **Rejected part:** per-file `/check` fallback path (extra code and tests for a situation fixed by copying one folder). Decision H25. |
| B16 | Low | CONFIRMED | §2.4/§3.5: UDP is always 8765 regardless of `--port`; the reply carries the real TCP port; last-known and manual address are `host[:port]`; clearer console line when the UDP bind fails. |
| B17 | Low | CONFIRMED (= A21) | §4.3: unknown or ≤ 0 `mtime` is omitted, never "now". |
| B18 | Low | CONFIRMED | §4.4: "body fully sent" remembered per item: a retry answered `skipped` counts as **Saved**; Cancel after the body waits up to 10 s for the PC's answer. |
| B19 | Medium | CONFIRMED (= A5) | See A5. `takePersistableUriPermission` not needed (queue is in memory, H16). |
| B20 | Low | CONFIRMED | §4.4: after 30 min without the PC: remaining items "Not sent: PC not found", summary with Retry, locks released, service stops. |
| B21 | Low | CONFIRMED | §4.6: Update is disabled while a transfer or cast is running. |
| B22 | Low | CONFIRMED | §3.3: `versionCode = 20000 + run number` of the one release workflow, `main` only; "never rename `android.yml`" rule. CI comparison with the previous artifact rejected (over-engineering). Decision H26. |
| B23 | Low | CONFIRMED | §3.2/§12 now match H18: keystore committed at `android/signing/ferry-release.jks` (+ `signing.properties`); CI checks the APK's certificate fingerprint against a pinned value. |
| B24 | Medium | CONFIRMED | New §2.8 "Test hooks": `--port 0` + `FERRY_READY port=<n>` line, `--discovery-port N|off`, `--data-dir DIR`, `--test-free-bytes N`; tests always pass `--no-open --inbox --outbox`. Not in the README. |
| B25 | Low | DEFERRED | Exact list of v1 files/tests to delete and `package.py` changes → code plan. (`exif_gps_status` is kept as a test helper, see B1; §2.2 notes it.) |
| B26 | Low | CONFIRMED | §2.3: APK and json are re-stat'ed on every `/app` and `/api/v1/app` request (cache keyed by both); while they disagree the PC answers 404 "being copied, try again" and does not serve the APK. |
| B27 | Medium | CONFIRMED (part) | §10: debug-only hook to start `CastReceiver` in a phone emulator; TV and API 25 jobs run on `main` + manual only; new "What CI does not prove" list. KVM udev step, AVD caching, retry, `gh run download` check → code plan. Decision H27. |
| B28 | Low | CONFIRMED | §2.5: successful polling requests (`/api/v1/outbox`, `/api/v1/info`) are collapsed into one summary line per device per 10 min; transfers and all errors are always logged. |

## Deferred items

### To the code plan (Stage 4)
- **A22**: plain-English strings table: phone storage full, "Your phone is not on Wi-Fi", battery saver on, PC error vs PC not found, disk-full in plain units, summary "2 failed. Tap to retry".
- **A25**: edge-to-edge insets on the phone (targetSdk 35), TV overscan-safe padding (~48 dp / 27 dp), consistent `BackHandler`; keep the app free of native libraries.
- **B25**: explicit list of v1 leftovers to delete (`tools/page.html`, `diag.html`, `keepawake.mp4`, `icon-maskable.svg`, `build_assets.py`, `tests/js`, `tests/e2e`, the v1 pytest cases for `/`, `/diag`, `/new`, `/qr`, `/api/v1/diag/*`), replacement "removed route → 404" tests, `package.py` must include `app/Ferry.apk(.json)` and fail if missing.
- **B27 (rest)**: KVM udev step, AVD snapshot caching, `disable-animations`, one automatic retry of the emulator step; check once that screenshots can be fetched (`gh run download`) through this workspace's proxy.
- **A5 (detail)**: typed `getParcelableExtra` compat helper. **A13 (detail)**: exact Compose focus APIs for the pinned Compose version. **B4 (detail)**: time budget for the 10k-file check-batch test.

### To implementation / real-device acceptance
- **A24**: remember the last TV and skip the TV choice; notification tap opens the Remote screen.
- **A20 (rest)**: codec profile in the "can't play" message ("HEVC 10-bit"); try a 50 MP HEIC and a 4K HDR clip on the real stick.
- **A7 / A8 / A11 (checks)**: screen-off vs screen-on MB/s on the vivo; correct the vivo menu names after the first real test; decide whether Fire OS 6 should drop photo thumbnails.

### To v2.1 or later
- **A23**: R8 minify + resource shrinking (APK size on small Fire sticks), baseline profile.
- **A9 (note)**: Android's local-network permission when targetSdk is raised above 35.
- **A16/B7 (dropped feature)**: resume of interrupted outbox downloads with `Range` + `If-Range` (H20).
- **A17 (dropped features)**: long-press preview and per-date "Select all" in the picker (H22).
- **A18 (rest)**: TV digit pad / phone pushes the PC address to the TV, only if TV discovery proves unreliable.
