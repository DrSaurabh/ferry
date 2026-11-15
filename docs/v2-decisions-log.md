# Ferry v2: hands-free decisions log (for review with the user)

Session 2 became a **hands-free session** at the user's request (2026-11-14). Claude answers questions on the user's behalf and records each decision here. Stage approvals are self-given after each audit.

User-given permissions: use GitHub Actions minutes freely; self-approve stages; decide remaining questions; finish with a tested APK + PC program for real-device testing. Phone downloads → Downloads/Ferry (user's answer).

| # | Stage | Decision | Why | Revisit? |
|---|---|---|---|---|
| H1 | Req | Drop "rename numeric picker names" | User asked whether it's still needed; the app keeps names/dates; browser retired | — |
| H2 | Req | Phone → TV works by the phone **streaming** files to the TV (no copy) | No TV storage used; instant start | — |
| H3 | Req | Root page = minimal "Get the Ferry app" page + `/app`; QR points to it | First install on phone and TV needs a browser/Downloader path | — |
| H4 | Req | Discovery fallback = type PC address once | Discovery can fail on some routers | — |
| H5 | Req | Min Android 7.1 (API 25) | Older Fire TV Sticks run Fire OS 6 | Raise if TV is Fire OS 7+ |
| H6 | Req | Slideshow default 5 s (3/5/10 selectable); resume position for videos; English only | Sensible defaults | Preferred interval? |
| H7 | Req | Fire TV first install method chosen at the end with a guide | User said "guide me later" | **Yes, at review** |
| H8 | Req | Phone gets basic remote control while casting | Natural when started from the phone | — |
| H9 | Design | Remove upload page, /diag, /qr, PWA assets, short links `/<n>` `/new` from the PC | Browser retired; less code | — |
| H10 | Design | Discovery on UDP 8765 (same number as HTTP) | One port to explain; v1 reserved it | — |
| H11 | Design | Android split into `:core` (pure Kotlin, JVM-testable, tested against real ferry.py) and `:app` | Most logic testable without devices | — |
| H12 | Design | TV UI in plain Compose with focus handling, not androidx.tv | One design system, no alpha APIs | — |
| H13 | Design | Fixed release keystore in GitHub secrets; backup copy to user | Updates need the same signing key | Keep backup safe |
| H14 | Design | Phone casting = foreground service (mediaPlayback); TV receives casts only while Ferry is open on the TV | Fire TV apps are used in the foreground; simpler | — |
| H15 | Design | 2 parallel uploads + batch duplicate check | Small-file speed target ≥10 MB/s | — |
| H16 | Design | Upload queue in memory (no persistence); interrupted-transfer notice | Simplicity; resend is cheap thanks to dedupe | — |
| H17 | Design | API 25 emulator boot smoke test in CI | Guards the minSdk claim | — |
