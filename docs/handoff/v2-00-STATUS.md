# Ferry v2: live status

_Updated: 2026-11-24 ~11:30 IST (day session 6, hands-free at the user's request). **Runs start as FRESH sessions** from one repeating scheduled task "Ferry hands-free work runs" (~20:52 and ~01:52 IST). Read `00-HANDOFF.md` §0 first, then `VISION.md`, `v2-field-test-feedback.md`, repo `docs/v2-decisions-log.md` (newest; project copy is old). Mirrors of this file and the handoff: repo `docs/handoff/`._

## Morning summary (for the user — rewrite each run, covering the night's runs)
**Today (24 Nov, day session, hands-free):** the design was checked by two reviewers (Sonnet and Opus) — 33 problems found, all fixed in the design (e.g. shared photos are copied straight away while waiting for the PC; casts end after 10 idle minutes to save battery; the logs can now tell whether each tap actually worked). An independent check found 15 more small things, also fixed. Then the **build plan** was written (11 phases), checked by two reviewers (39 findings: 38 fixed, 1 waits for real logs) and re-checked twice. Decisions so far: **49** (H51–H99). Newest worth a look: H83 one **TV** button on the phone's Home replaces "Play on TV" + "TV remote"; H85 setup shows the phone's name for the PC with "Change"; H88 a waiting send stops checking after 3 days until Ferry is opened; H91 cast notification with 5 controls. Next: coding, phase Ph11a (logging core + PC logs endpoint).

**Progress page** (Refresh button): artifact slug `78744174-8d8c-4112-8868-011037b3874b` (https://claude.ai/artifact/FshmiE6KVBbzWJcAmwkZVU). The page reads db doc `progress/current`; update it with ONE `db_write` (merge, ifVersion from `db_get`) as the last action of each run — see handoff §0.3. Never use publish/edit/declare artifact tools in a run (they wait for approval).

## Stage A (build 2.1)
- [x] A0 research → `docs/v2.1-00-research.md`
- [x] A1 requirements → `docs/v2.1-01-requirements.md` rev 1 (B2 wording per H70)
- [x] A2 design → `docs/v2.1-02-design.md` rev 0 (commit fd9395c); decisions H61–H76
- [x] A3 design audit (2026-11-24 day session, hands-free): brief `docs/v2.1-audit-brief.md`; Sonnet S1–S16 + Opus O1–O17 → 33 confirmed → design **rev 1 = §13 M1–M25**; independent re-check (Sonnet) C1–C15 + D1–D4 all fixed; requirements rev 3; decisions H77–H93; dispositions `docs/v2.1-03-design-audit.md`; snapshot branch `snapshot/v2.1-design-rev0` (tag pushes are blocked by the proxy — use branches). Original plan was: write `docs/v2.1-audit-brief.md` (who/priorities/non-goals + VISION.md + where things are); reviewer 1 **Sonnet** focus: Android/Fire OS platform feasibility, correctness, data safety, tests (→ `docs/v2.1-03a-design-audit-sonnet.md`); reviewer 2 **Opus** focus: vision alignment, simplicity/UX for a non-developer, scope/over-engineering, battery, logging usefulness (→ `docs/v2.1-03b-design-audit-opus.md`); run one after the other, independent; then dispositions → `docs/v2.1-03-design-audit.md` + design rev 1.
- [x] A4 plan → `docs/v2.1-04-code-plan.md` (rev 0, phases + CP101–CP130)
- [x] A5 plan audit: Sonnet PS1–PS21 + Opus PO1–PO18 → `docs/v2.1-05-plan-audit.md` (38 confirmed, PS19 deferred to real logs); independent re-check `docs/v2.1-05c-plan-recheck.md` (E1–E12 fixed, second check F1 fixed) → **plan rev 2**, requirements rev 4 (L6), design §14, decisions H94–H99, CP131–CP145, phase brief rewritten for 2.1; snapshot branch `snapshot/v2.1-plan-rev0`
- [ ] **A6 code** ← NEXT: phases in order Ph11a → Ph11b → Ph12 → Ph13 → Ph14 → Ph15 → Ph16 → Ph17a → Ph17b → Ph18 → Ph19, each by one phase agent (Opus) following `docs/agents/phase-brief.md`, branch = phase id, CI green, fast-forward to main; record in `docs/v2.1-06-build-log.md` 
- [ ] A7 code audit → Opus fixer (snapshot) → independent re-check → deliver `Ferry-2.1.x.zip`
- [ ] User uses 2.1 for a few days → logs zip → review (F30)

## Stage B (build 2.2) — after Stage A logs review
F29, F32, F31, pairing (one-time Allow), F26, F27, F28 rest, F34, F33, F18.

Repo: https://github.com/DrSaurabh/ferry (public; signing passphrase backup in `v2-signing-key-BACKUP.md`).
