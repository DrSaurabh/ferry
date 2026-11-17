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

(Table filled in as the work goes; "commit" is the fix commit on `stage7-fixes`.)

| ID | Reviewer | Severity | Verdict | Reason | Fix commit |
|---|---|---|---|---|---|
