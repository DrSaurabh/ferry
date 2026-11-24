# Ferry v2: live status

_Updated: 2026-11-24 ~10:35 IST (day, with the user). **Runs now start as FRESH sessions** from one repeating scheduled task "Ferry hands-free work runs" (~20:52 and ~01:52 IST). The two old persistent-session tasks are disabled. Read `00-HANDOFF.md` §0 first, then `VISION.md`, `v2-field-test-feedback.md`, repo `docs/v2-decisions-log.md` (newest; project copy is old). Mirrors of this file and the handoff: repo `docs/handoff/`._

## Morning summary (for the user — rewrite each run, covering the night's runs)
Nights 1–2: research (14 ideas from users of similar apps, 5 adopted), requirements for 2.1, and the design (phone controller with touch pad, seek bar, volume and typing; TV ☰ menu with Zoom / Speed / Shuffle / Date / Something's wrong; upright portrait videos and video zoom; PDF/txt on the TV; sends that wait for the PC; 4-step setup; share-original fix; trial logging collected on the PC; log-review script). 26 decisions so far (H51–H76). Next: design audit (Sonnet, then Opus), starting this evening.

**Progress page** (Refresh button): artifact slug `78744174-8d8c-4112-8868-011037b3874b` (https://claude.ai/artifact/FshmiE6KVBbzWJcAmwkZVU). The page reads db doc `progress/current`; update it with ONE `db_write` (merge, ifVersion from `db_get`) as the last action of each run — see handoff §0.3. Never use publish/edit/declare artifact tools in a run (they wait for approval).

## Stage A (build 2.1)
- [x] A0 research → `docs/v2.1-00-research.md`
- [x] A1 requirements → `docs/v2.1-01-requirements.md` rev 1 (B2 wording per H70)
- [x] A2 design → `docs/v2.1-02-design.md` rev 0 (commit fd9395c); decisions H61–H76
- [ ] **A3 design audit** ← NEXT: write `docs/v2.1-audit-brief.md` (who/priorities/non-goals + VISION.md + where things are); reviewer 1 **Sonnet** focus: Android/Fire OS platform feasibility, correctness, data safety, tests (→ `docs/v2.1-03a-design-audit-sonnet.md`); reviewer 2 **Opus** focus: vision alignment, simplicity/UX for a non-developer, scope/over-engineering, battery, logging usefulness (→ `docs/v2.1-03b-design-audit-opus.md`); run one after the other, independent; then dispositions → `docs/v2.1-03-design-audit.md` + design rev 1.
- [ ] A4 plan → A5 plan audit → A6 code (CI green) → A7 code audit → Opus fixer (snapshot) → independent re-check → deliver `Ferry-2.1.x.zip`
- [ ] User uses 2.1 for a few days → logs zip → review (F30)

## Stage B (build 2.2) — after Stage A logs review
F29, F32, F31, pairing (one-time Allow), F26, F27, F28 rest, F34, F33, F18.

Repo: https://github.com/DrSaurabh/ferry (public; signing passphrase backup in `v2-signing-key-BACKUP.md`).
