# Ferry v2: Code-plan audit (Stage 5) — triage and fixes

**Date:** 2026-11-15. **Artifact:** `v2-04-code-plan.md` rev 1 → **rev 2** (fixed in place; changed text carries the finding IDs in brackets).

**Reviewers**
| Report | Model | Focus | IDs |
|---|---|---|---|
| `v2-05a-plan-audit-sonnet.md` run 1 | Sonnet | Implementability by AI agents, CI | PA1–PA19 |
| `v2-05a-plan-audit-sonnet.md` run 2 | Sonnet | same brief, independent run | PR1–PR21 |
| `v2-05b-plan-audit-opus.md` | Opus | Correctness, data safety, test adequacy | PB1–PB24 |

Sonnet ran **twice** because the workspace restarted during the first run; both runs were kept and many of their findings overlap (merged below). The final review, triage and plan edits were done by a third pass (this document), applying `v2-audit-brief.md` strictly: findings that push non-goals or unneeded hardening are rejected.

Verdicts: **CONFIRMED** = fixed in the plan now · **REJECTED** = not acted on · **DEFERRED** = real, but left to implementation-time judgement or v2.1 (destination given at the end).

_(Triage table is being written; in progress.)_
