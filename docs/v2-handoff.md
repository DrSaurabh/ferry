# Ferry v2: handoff (Ph10)

For whoever works on Ferry next (Claude in a later session, or a reviewer). The user is Saurabh, a non-developer;
he tests on a vivo X200 Pro (Android 15), a Fire TV Stick (Fire OS 6/7/8) and a Windows 10 PC. Everything here is
in the repo `DrSaurabh/ferry` (public since H38).

## 1. What to read
- `docs/v2-01-requirements.md` (what Ferry must do), `docs/v2-audit-brief.md` (priorities, non-goals).
- `docs/v2-02-design.md` rev 2 and `docs/v2-04-code-plan.md` rev 2 (source of truth), `docs/v2-decisions-log.md` (H#).
- `docs/v2-06-build-log.md` (B# decisions and every deviation, per phase), `docs/v2-progress.md` (one line per green
  step), `docs/agents/carry-notes.md` (coordinator notes between phases).
- User-facing: `pc/README.txt` (C6) and `pc/DEVICE-CHECKLIST.txt` (real-device acceptance, CP12).

## 2. How to build and test
- Only Python runs locally: `cd pc && python3 -m pytest -q` and `python3 -m pytest -q android/ci`. Run both before
  every push. Kotlin compiles **only on GitHub Actions** (no Android SDK here; Maven/Google are blocked).
- Workflows: `pc.yml` (pytest on Windows 3.8/3.13 and Ubuntu 3.8/3.13) and `android.yml`. **Never rename
  `android.yml`** [H26]: `versionCode = 20000 + its run number`, and run numbers restart with a new file name, which
  would make updates impossible.
- **Branch rule** [H28] [CP18]: work on a phase/feature branch from `main`, never on `main`. A branch push runs the
  fast jobs (`core-jvm` ×2, `app-build`). Emulator suites run on a branch only when the **head commit message**
  carries `[emu]` (all three), `[emu:phone]`, `[emu:tv]` or `[emu:api25]`, or with
  `gh workflow run android.yml -R DrSaurabh/ferry --ref <branch> -f emulators=all|phone|tv|api25|none`.
  `main` moves only by fast-forward of a green branch (`git checkout main && git merge --ff-only <branch> && git
  -c push.negotiate=true push`); a push to `main` runs everything. If that `main` run is red, fix it on the branch
  and fast-forward again; never push fix-ups straight to `main`.
- Waiting for CI: `docs/agents/ci.sh` (waits for the runs on HEAD, prints failing log lines). `gh api` works through
  the proxy even when `gh auth status` says otherwise; use `gh api repos/DrSaurabh/ferry/actions/jobs/<id>/logs`
  for a job's log. Branch deletion, the secrets API and artifact downloads are blocked by the workspace proxy.
- Emulator suites (plan §4.14): `android/ci/run_instrumented.sh <phone|tv|api25>` runs the classes listed in
  `android/ci/suites/<suite>.txt` in order (one line per class: class | host steps before | host check after),
  against real `ferry.py` processes on the runner (`10.0.2.2:<port>`). Each suite gets one retry with a cold boot.
- Test technique that matters: assert on **app state**, not on text seen through UiAutomator (Compose text can lag:
  B57, B82); TV tests leave touch mode first (B73); `pressBack` during playback can take seconds, so read positions
  from what the app recorded (Ph8b note).

## 3. Releases: what the user may get
- On `main`, job `package` builds `Ferry-2.0.<n>.zip` (folder `Ferry/` with `ferry.py`, `Ferry.bat`, README,
  checklist, `app/Ferry.apk` + `.json`). It is named `Ferry-2.0.<n>-UNVERIFIED` unless **all three emulator suites
  passed in that same run** [CP19]. **Never give the user an `-UNVERIFIED` zip** [H29].
- Job `dist` (B38) commits the latest **verified** zip, `Ferry.apk`, `Ferry.apk.json` and `BUILD.txt` (version,
  main commit, run) to the orphan branch `dist` (force-pushed, latest only). Get it with `git fetch origin dist`
  and `git show origin/dist:<file>`; check `BUILD.txt` names the `main` commit you expect.
- Updating the user: he unzips the new zip over his Ferry folder and restarts `Ferry.bat`; phone and TV then offer
  "New Ferry 2.0.x is ready → Update" (the APK is signed with the same key, `versionCode` only grows).

## 4. Signing key [H13] [H18] [H38]
- Release key: `android/signing/ferry-release.jks.enc` (AES-256, PBKDF2), decrypted in CI with the repository
  secret `FERRY_SIGNING_PASS` (also the store and key password). Certificate SHA-256: `android/signing/cert-sha256.txt`;
  `check_apk.py` fails a build signed with anything else.
- The passphrase is backed up in the user's claude.ai project (the `v2-signing-key-BACKUP` doc). **Never write it
  into the repo, a log, a commit or a message.** If it is lost, a new key means the user must uninstall Ferry on
  the phone and TV once and install again (Android refuses an update signed with another key).

## 5. Logs and screenshots
- PC: `ferry-log.txt` (+ `ferry-log.1.txt`) in the Ferry folder: every request with device, path, Range, status,
  bytes, duration, outcome (C5). `ferry-error.log` after a crash; `ferry-selftest.txt` from `Ferry self-test.bat`.
- App: Settings → Share diagnostics log (RingLog `files/logs/ferry-app-log.txt`).
- CI: each emulator job prints the app's log lines, `FERRY_PROBE` results and **every screenshot as base64**
  between `FERRY_PNG_BEGIN/END` markers into its job log (B7). Save the job log
  (`gh api …/jobs/<id>/logs > job.txt`) and run `python3 android/ci/shots_from_log.py job.txt out/` to get the PNGs;
  `android/ci/junit_summary.py` summarises the classes. Review screenshots after every UI change: the tours are
  `PhoneScreenshotTour`, `PhonePermissionTour`, `PhoneDialogsTour` and `TvScreenshotTour` (`tour-*.png`); the
  other classes shoot the screens they test.
- Wording lives in one file: `android/app/src/main/res/values/strings.xml` [A22] (vivo steps in one string [A8];
  `pc/tests/test_readme.py` checks that the README repeats them word for word).

## 6. What CI does not prove (real devices only) [B27] (plan §7.4)
These are in `pc/DEVICE-CHECKLIST.txt` for the user:
- Broadcast discovery on the real LAN (PC and TV); the emulator NAT blocks it, so CI uses typed addresses.
- A Fire TV app that already owns UDP 8765 (Ferry then falls back to the last-used TV).
- vivo's own battery manager; uploads and casting with the vivo's screen off; MB/s with the screen on vs off [A7].
- Real Wi-Fi speed (target ≥ 10 MB/s for a batch of photos) and a real Wi-Fi drop (CI uses a TCP relay).
- Windows Firewall prompts (TCP and UDP 8765); Fire OS installer and "unknown apps" screens, incl. Fire OS 8
  restarting Ferry when that switch changes (B72).
- The Fire TV screensaver vs Ferry's 30-minute keep-screen-on; hardware decoding of 4K/HEVC/HDR/DTS from the vivo,
  and HEIC photos on Fire OS 6/7.
- The vivo Gallery's own share provider (CI shares from a helper app [PB6]); photo GPS end to end on the vivo.
- Android 15's 6-hour `dataSync` limit (`onTimeout`; unit-tested decision only).

## 7. Known limits (by decision)
No resume of interrupted downloads to the phone (H20); downloads to the phone need Android 10+ (H31); no pinned
picker date headers (H32); no long-press preview (H22); the TV receives casts only while Ferry is open on it (H14);
no security on the LAN (requirements §3).
