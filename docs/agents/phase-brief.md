# Brief for a Ferry implementation-phase agent (2.1)

You implement ONE phase of the Ferry **2.1** code plan. Repo: /home/claude/ferry (GitHub DrSaurabh/ferry, private; you have push access via plain `git push`).
Ferry = a home tool for ONE non-developer user: Windows PC program (`pc/ferry.py`, Python stdlib only) + Android app (phone + Fire TV, one APK).
(Rewritten for 2.1 after the A5 plan audit, PS10/PO3. The 2.0 documents `v2-04-code-plan.md`, `v2-06-build-log.md` are history: read them only where the 2.1 plan points to them.)

## Read first (in this order, only the parts you need)
1. `docs/v2.1-audit-brief.md` and `docs/handoff/VISION.md` (who it's for, priorities, NON-GOALS — do not add unrequested features or hardening; rule 12: simple beats clever).
2. `docs/v2.1-04-code-plan.md` §0 (ground rules, CP decisions, pinned deps), §2 (shared interfaces), then YOUR PHASE in §3, then the §1/§4 rows your phase names. Source of truth: the plan (rev 1) and `docs/v2.1-02-design.md` rev 1 — §13 (M1–M25) and §14 override earlier sections; read every M amendment your phase names.
3. `docs/v2.1-06-build-log.md` and `docs/v2.1-progress.md` (what earlier phases did, deviations) if they exist; `docs/agents/carry-notes.md` "2.1" heading.
4. Only where the 2.1 plan says so: 2.0 plan `docs/v2-04-code-plan.md` §4.14 (instrumented test conventions) and §5.2 (CI).

## How to work
- Phase ids: Ph11a, Ph11b, Ph12 … Ph16, Ph17a, Ph17b, Ph18, Ph19. Branch = the phase id in lower case (`ph11a`, `ph17b`, …) from current `origin/main`; commit messages start with the phase id. Small compile-safe steps.
- Before every push run `cd pc && python3 -m pytest -q`, `python3 -m pytest -q android/ci` and (from Ph11a on) `python3 -m pytest -q tools/tests` from the repo root.
- Kotlin only compiles on GitHub Actions. Emulator suites run on a branch only with `[emu]`, `[emu:phone]`, `[emu:tv]` or `[emu:api25]` in the head commit message. After each push, wait for CI with `docs/agents/ci.sh` (copy to /home/claude/tools/ci.sh; waits for runs on HEAD and prints failing log lines; more detail with `gh api repos/DrSaurabh/ferry/actions/jobs/<id>/logs`). `gh api` works even though `gh auth status` says otherwise; `gh run watch`/`gh run view` may not.
- CI artifacts cannot be downloaded here. Screenshots and logs reach you through the job log: `android/ci/shots_from_log.py` (PNGs; `--logs` for the `FERRY_LOG_BEGIN/END` log blocks, from Ph11a on).
- Commit messages end with the attribution lines your session's instructions give (if none: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`).
- Push with `git -c push.negotiate=true push`. If a push or fetch hiccups ("remote end hung up"), wait a few seconds and retry once. Tag pushes and branch deletion are refused by the proxy: snapshots are branches; leave phase branches on the remote.
- The phase is DONE when: the branch is green on every workflow and emulator suite it touches, the phase's Done items are met, `main` is fast-forwarded to the branch (`git checkout main && git merge --ff-only <branch> && git push`) and the resulting `main` run is green with a verified (non-`-UNVERIFIED`) zip. Never push fix-ups straight to `main`; never weaken a test (adapt one only where 2.1 deliberately changes the behaviour, and list it in the build log).
- If you must deviate from the plan (a dependency version doesn't exist, an API behaves differently, a test is impossible), choose the smallest safe change and record it as a B-row.
- Never change agreed requirements. If something would, stop and report it instead.

## Records (write AS YOU GO — you may be interrupted by usage limits)
- `docs/v2.1-progress.md`: one line per step (`Ph13 · step · commit · run id · what is left`).
- `docs/v2.1-06-build-log.md`: a section for your phase (create the file if missing): start time, steps/commits, CI results, adapted tests and why, deviations with reasons, new decisions numbered **B101, B102…** continuing from the log, anything left for later. Commit it with your code.
- Notes for later phases: `docs/agents/carry-notes.md` under the "2.1" heading.
- If interrupted, the next agent must be able to resume from your branch + progress lines + build-log section.

## Final message (< 250 words)
Phase status (done / partially done + what's left), branch/commit, CI run result, deviations and decisions, anything the coordinator must know.
