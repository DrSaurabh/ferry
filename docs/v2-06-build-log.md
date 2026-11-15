# Ferry v2: build log (Stage 6)

One section per phase. Decisions taken while building are numbered **B1, B2, …** (continuing across phases).
Short resume lines (one per green sub-step) are in `docs/v2-progress.md` (plan §0.1).

## Ph0a — PC tests portable, Windows CI green

- Started 2026-11-15T07:28Z on branch `ph0a` from `main` @ 5ed1196 (plan rev 2).
- Steps:
  1. `.gitattributes` (plan §1: `*.sh`, `android/gradlew`, `*.py` eol=lf; `*.bat` eol=crlf; `*.jks *.apk *.jpg *.png *.mp4` binary). `git add --renormalize` moved the three `.bat` files to LF in the index (working copies stay CRLF on every checkout).
  2. The six v1 test fixes of plan §2.13 table 1 (`test_http`, `test_inbox`, `test_outbox`, `test_platform` ×3); `test_launchers_static` also asserts the two eol rules in `.gitattributes`.
  3. `pc.yml` matrix per plan §5.1 (windows 3.8/3.13, ubuntu-22.04 3.8, ubuntu-latest 3.13; `-ra --junitxml`; `pytest.xml` + `ferry-log*.txt` uploaded on failure).
- Deviations:
  - **B1** `test_main_starts_and_stops_quickly`: plan says "monkeypatch `ferry.win_known_folder`". `resolve_folders` binds `win_known_folder` as a default argument at definition time, so patching the module attribute alone has no effect. The test therefore also wraps `ferry.resolve_folders` to pass the fake `known=` explicitly (test-only; production code unchanged). Plan also mentions patching `DATA_DIR`: no such name exists in v1 `ferry.py`; the test already redirects `STATE_FILE`/`State.load` to `tmp_path`, which covers it.
- Run 1 (commit 0a6b3b1; pc run 43690136246, android run 43690136141): `android` green (it ran because `android/gradlew.bat` was renormalised); `pc` Linux legs green, Windows legs 99 passed / 7 skipped / **1 failed**: `test_launchers_static` now got past the CRLF/LF checks but `bash -n ferry.sh` returned 1 — on `windows-latest`, `bash` on PATH is the WSL launcher (no distro), not Git Bash.
  - **B2** `bash -n ferry.sh` is run only when not on Windows (the Linux legs still check the syntax; `ferry.sh` is the Linux launcher). Not in plan §2.13, which expected the test to pass "once `.gitattributes` exists".
- Run 2 (commit d343705; pc run 43690282909): **green** on all four legs (windows-latest 3.8/3.13, ubuntu-22.04 3.8, ubuntu-latest 3.13). Windows: 3 POSIX-only tests skipped as planned, plus the 4 existing `FERRY_SLOW` zip skips.
- Note for later phases: a change to `.gitattributes`/`android/**` triggers `android.yml` too; the current `android.yml` is still the smoke build (job `build`), Ph0b replaces it.
- Then: fast-forward `main` to `ph0a` and check the `main` run (below).
- `main` fast-forwarded to b5b3463: `main` runs pc 43690413274 **green**, android 43690413300 **green**. **Ph0a done** 2026-11-15. (This closing note is a docs-only commit, fast-forwarded the same way; docs-only pushes trigger no workflow.)
- Left for later: nothing for Ph0a. Plan §2.13's other rows (browser retirement, `Srv` → `helpers.py`) belong to Ph1.
