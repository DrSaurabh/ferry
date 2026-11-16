# Brief for a Ferry v2 implementation-phase agent

You implement ONE phase of the Ferry v2 code plan. Repo: /home/claude/ferry (GitHub DrSaurabh/ferry, private; you have push access via plain `git push`).
Ferry = a home tool for ONE non-developer user: Windows PC program (`pc/ferry.py`, Python stdlib only) + Android app (phone + Fire TV, one APK).

## Read first (in this order, only the parts you need)
1. `docs/v2-audit-brief.md` (who it's for, priorities, NON-GOALS — do not add unrequested features or hardening).
2. `docs/v2-04-code-plan.md` §0 (ground rules, CP decisions, pinned deps), then YOUR PHASE in §6, then the §1–§5 sections your phase's file list points to. The plan (rev 2) and `docs/v2-02-design.md` (rev 2) are the source of truth.
3. `docs/v2-06-build-log.md` (what earlier phases did and any deviations) if it exists.

## How to work
- Follow plan §0.1 exactly: phase branch named after the phase in lower case (e.g. `ph0a`) from current `origin/main`; small compile-safe steps; run `cd pc && python3 -m pytest -q` and `python3 -m pytest -q android/ci` (when it exists) before every push.
- Kotlin only compiles on GitHub Actions. After each push, wait for CI with `docs/agents/ci.sh (copy to /home/claude/tools/ci.sh)` (waits for runs on HEAD and prints failing log lines; for more detail use `gh api repos/DrSaurabh/ferry/actions/jobs/<id>/logs`). `gh api` works even though `gh auth status` says otherwise. `gh run watch`/`gh run view` may not work; use `gh api`.
- Commit messages end with:
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01F8tGVDvGjUr25EEjuH6H1S
- Push with `git -c push.negotiate=true push`. If a push or fetch hiccups ("remote end hung up"), wait a few seconds and retry once.
- The phase is DONE when: the branch is green on every workflow it touches, the phase's acceptance items are met, then `main` is fast-forwarded to the branch (`git checkout main && git merge --ff-only <branch> && git push`) and the resulting `main` run is green. Leave the phase branch on the remote (branch deletion is blocked by the proxy).
- If you must deviate from the plan (a dependency version doesn't exist, an API behaves differently, a test is impossible), choose the smallest safe change, and record it.
- Never change agreed requirements. If something would, stop and report it instead.

## Records (write AS YOU GO — you may be interrupted by usage limits)
- Append a section for your phase to `docs/v2-06-build-log.md` (create it if missing): start time, then a running list of steps/commits, CI results, deviations from the plan (with reasons), new decisions (number them B1, B2… continuing from the log), anything left for later. Commit it with your code.
- If interrupted, the next agent must be able to resume from your branch + build-log section.

## Final message (< 250 words)
Phase status (done / partially done + what's left), branch/commit, CI run result, deviations and decisions, anything the coordinator must know.
