# Ferry v2: Stage 7 code audit (triage and fix log)

- Audited: `main` @ c6269ac (tag `stage7-snapshot-pre-audit`).
- Reviewers: Sonnet (`v2-07a-audit-sonnet.md`, S1–S8: file safety and data correctness) and Opus
  (`v2-07b-audit-opus.md`, O1–O17: device reliability, TV, UX, tests). Neither found a High finding and
  neither found a way to lose or corrupt a file.
- Triage and fixes: this document, on branch `stage7-fixes` (not merged: an independent verifier reviews the
  diff against the snapshot first).
- Rules used: every claim was checked in the code (and by experiment for `pc/ferry.py`) before it was
  confirmed. **Confirmed** = fixed now, with a test where one is possible. **Rejected** = not changed, with
  the reason. **Deferred** = not changed now; needs a real device (then it is on `pc/DEVICE-CHECKLIST.txt`)
  or belongs to a later version. Non-goals of `v2-audit-brief.md` were not touched. Decisions made on the
  user's behalf are H40 onward in `v2-decisions-log.md`.

## Triage

Fix commits on `stage7-fixes`: **PC** = d21acd8, **APP** = 54b687e (follow-up commits that only repair CI are
listed in the log below). Counts: 25 findings (S1–S8, O1–O17): **18 confirmed and fixed** (3 of them in part),
**3 rejected**, **4 deferred**.

| ID | Reviewer | Severity | Verdict | Reason | Fix commit |
|---|---|---|---|---|---|
| S1 | Sonnet | Low | **Confirmed** | `RX_OUTBOX` ignored the name segment (checked: `GET /api/v1/outbox/1/whatever` served file 1). Now a name in the URL must equal the file's name, else 404; every client already uses the URL from the list. Tests `test_audit_stage7.py` (incl. the stale-list-after-restart case). H46 | PC |
| S2 | Sonnet | Low | **Confirmed** | Reproduced (`precheck` answered `disk_full` for a file that is already there). The duplicate test now comes first, as in `check_batch` and H30. Test `test_check_duplicate_wins_over_disk_full`. The app does not call `/check`; kept because C1 keeps the v1 API | PC |
| S3 | Sonnet | Low | **Rejected** | H30 as agreed says "a file of the same name family with exactly that size". Adding the date test would answer "disk full" to a real resend whose date differs (the PC still recognises it by content). The cost today is bounded: at most one file goes below the 64 MB margin, and a full disk mid-file is already safe (507, temp file removed). Reviewer marked it optional. H50 | — |
| S4 | Sonnet | Low | **Confirmed** | Reproduced (`.nomedia` → `nomedia`). Leading dots are kept, trailing dots/spaces still go; a name starting with `.ferry-` gets `_` in front so it can never match the temp-file cleanup. Tests in `test_sanitize.py`, upload + resend in `test_audit_stage7.py`. H46 | PC |
| S5 | Sonnet | Low (uncertain) | **Confirmed** | Code fact: every process start emptied `files/apk` and `files/update`; a restart while the installer is open is plausible (Fire OS 8 restarts Ferry around installs, B72). Now only files older than 1 h are deleted (`StaleFiles`, JVM test `StaleFilesTest`); a fresh process downloads again anyway | APP |
| S6 | Sonnet | Low (uncertain) | **Deferred** (v2.1) | True in the code, but no data risk and bounded (≤ 30 s more waiting after a Wi-Fi network change, then the normal re-discovery). The fix would touch the upload engine's retry path, the most tested part of the app, for a case that may not occur on the real router. DEVICE-CHECKLIST C6 now asks how long the phone took to carry on | — |
| S7 | Sonnet | Low (uncertain) | **Deferred** (device) | No better API exists; the CPU wake lock and the foreground service are what is relied on. DEVICE-CHECKLIST C3 (1 GB video, screen off, speed on/off) | — |
| S8 | Sonnet | Low (note) | **Rejected** (accepted as is) | The user's explicit decision H38. Checked: only the `.enc` file, the certificate hash and `signing.properties` (no secret) are in the tree; CI reads the passphrase from the repository secret. No flaw found; the passphrase was not looked at. H50 | — |
| O1 | Opus | Medium | **Confirmed** | (b) is certain from the code: a refused request showed the same dialog again and after two refusals "Allow" did nothing. Allow now asks for the photo permission set (`PermissionPlanner.locationRequest`); if the original still can't be read the dialog offers Open Settings. Tests: `PermissionPlannerTest`, new phone class `ShareLocationAllowTest` (real share activity → Allow → original on PC-U, host-verified), screenshot in `PhoneDialogsTour`. (a) "no photo access at all" can't be staged on the emulator: DEVICE-CHECKLIST C5. H43 | APP |
| O2 | Opus | Medium | **Confirmed** | No way in when the broadcast fails and no TV is remembered (code fact). The TV shows its address; the phone has "Enter the TV's address". Tests: `TvAddressTest`, `CastLoopbackTest` (no answer / bad / answers and is remembered). H40. DEVICE-CHECKLIST G5 corrected | APP |
| O3 | Opus | Medium (uncertain) | **Confirmed** | The String overload does start with the cursor at 0, and the focus stayed on the button. Now a `TextFieldValue` with the cursor after the prefill, focus moved into the field, keyboard asked for. `TvPcNotFoundTest` types a digit and asserts "10.0.2." + "9" | APP |
| O4 | Opus | Medium | **Confirmed** | Code fact (`Pinned` held the flag whatever the state). Pinned only while playing / slideshow running; otherwise the 30-minute rule. Tests: `TvLongPauseTest`, `TvSlideshowTest` (`KeepScreenOn.pinnedNow`). H41. DEVICE-CHECKLIST F7 | APP |
| O5 | Opus | Medium | **Confirmed** | Code fact (`retry()` on a null engine is a no-op, the service then stops). Retry opens Ferry and either retries or says the list is gone. New phone class `RetryAfterRestartTest`. H44 | APP |
| O6 | Opus | Medium | **Confirmed** | The API 25 suite ran only the phone layout. It now also runs `TvLaunchAndFocusTest` and `TvCastReceiveTest` in the TV layout. APK install, resume and long pause on API 25 stay for the device (DEVICE-CHECKLIST F8). H48 | APP |
| O7 | Opus | Low | **Confirmed** (README) | True: focus starts on the first card. Fixed in the README and checklist ("press Up until the bar is highlighted, then OK") instead of moving the first focus to the bar. H50 | APP |
| O8 | Opus | Low | **Confirmed** | H6 gap on the TV. Up / Down step 3/5/10 s, remembered by the TV. Tests: `TvAddressTest.slideshowSteps`, `TvSlideshowTest`. H42 | APP |
| O9 | Opus | Low (uncertain) | **Confirmed** (not testable) | The responder socket is bound once to the network of `onStart` (code fact); whether replies then fail needs a real Wi-Fi reconnect. Cheap fix: the responder is reopened when the chosen network changes. No emulator test possible; DEVICE-CHECKLIST G6. With H40 the phone also has the typed/remembered address | APP |
| O10 | Opus | Low | **Confirmed** | Code fact. The share activity asks once after the send has started and finishes when answered (or when it comes back to the front). Not testable in the suite (the host pre-grants notifications); DEVICE-CHECKLIST C5. H47 | APP |
| O11 | Opus | Low | **Confirmed** (in part) | `TvPlayerState.host` and `TvViewerState` are documented as the cast bridge (product code); `CastSinkImpl.received` and `TvViewerState.moves` keep the last 200 entries. Not done: a lint level for `VisibleForTests` (CI noise for no user benefit) | APP |
| O12 | Opus | Low | **Deferred** (v2.1) | True (Coil's disk cache stores the downloaded originals), but harmless: 100 MB cap, memory cache does the work. A real fix is thumbnails from the PC, a new feature. The code comment now says what the cache holds | — (comment in APP) |
| O13 | Opus | Low | **Rejected** | After a manual stop the card's facts are still right (the transfer stopped early, N files not sent) and it says "vivo **may** have closed Ferry". Clearing the marker would hide that files were not sent. H50 | — |
| O14 | Opus | Low | **Confirmed** | README quotes "No TV found", G5 corrected, TV update instruction fixed | APP |
| O15 | Opus | Low (uncertain) | **Confirmed** (not testable) | Code fact: nothing ever cleared the line. The format line is cleared when a video frame is drawn, the sound note after 8 s; an error line stays. Needs a file that plays but exceeds the decoder's advertised limits: DEVICE-CHECKLIST F3. H49 | APP |
| O16 | Opus | Low | **Confirmed** | Code fact. Quiet re-locate every 10 s while "not found" is on screen. `TvPcNotFoundTest` ends with the PC "appearing" and no key press. H45. DEVICE-CHECKLIST C7 | APP |
| O17 | Opus | Low | **Confirmed** (in part) | Removed: `Notifications.update()`, `Prefs.batteryAsked`, `hostname_local()`. Kept: `PcLocator.findTvs()` (tested `:core` API, harmless), `HostReachProbe` (relabelled: its results explain red emulator runs). Rejected: Compose-semantics assertions in `FromPcDownloadTest`: a Compose test rule crashed these classes (B50); the saved name and the Open action are asserted from app state and the screenshots are reviewed | PC + APP |

## Left for the real-device test

All on `pc/DEVICE-CHECKLIST.txt`: C3 (S7: 1 GB video with the screen off, speed on/off), C5 (O1: Allow from the
Gallery share with no photo access yet; O10: notification question), C6 (S6: how long until it carries on), C7
(O16), F3 (O15), F7 (O4), F8 (O6: install / resume on Fire OS 6), G5 (O2: typed TV address), G6 (O9).

## Fix log

- d21acd8 PC: S1, S2, S4, `hostname_local()` removed. `cd pc && python3 -m pytest -q`: 175 passed, 4 skipped
  (was 169 + 4). `test_apk_mime` used a made-up name in an outbox URL and now uses the real one.
- 54b687e APP: S5, O1–O6, O8–O11, O15–O17, README / DEVICE-CHECKLIST (O7, O14), decisions H40–H50. `[emu]`.
- Run 1 (54b687e `[emu]`, android 43997958709): `core-jvm` ×2, `app-build` (Kotlin, unit tests, lint), `emulator-phone`
  (attempt 1, all 17 classes incl. `RetryAfterRestartTest`, `ShareLocationAllowTest` with host check "PC-U … identical,
  GPS present, date ok", `CastLoopbackTest`) and `emulator-tv` green. `emulator-api25` red: `TvCastReceiveTest` PASS in
  the TV layout on API 25, `TvLaunchAndFocusTest` stopped at its own precondition "android-tv image must be in TV
  mode" (test-side: it now accepts the debug UI override and asserts the activity runs the TV layout).
  In `ShareLocationAllowTest` Android granted photo location without showing its own dialog (`o1_system_dialog=none`:
  photo access was already granted by the host), so the dialog-shown case stays on DEVICE-CHECKLIST C5.
