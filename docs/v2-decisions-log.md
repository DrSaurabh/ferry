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
| H18 | Design | Release keystore **committed to the private repo** (`android/signing/`), not GitHub secrets | This workspace's proxy blocks the GitHub secrets API; asking the user to paste secrets needs typing. Risk: anyone with repo access could sign a "Ferry" APK — acceptable for a private single-user repo | User may move it to secrets later |
| H19 | Design audit | Photos are **not sent silently without location access**: the app asks "Allow / Send without location"; such files are reported "Saved without location"; "Already on PC → Send anyway" re-uploads without the pre-check (PC keeps both if the content differs) | A GPS-less copy has the same name/size/date, so the PC would refuse the good copy for ever (audit A2/B6) | Wording of the dialog |
| H20 | Design audit | Outbox downloads to the phone **restart from zero** after a drop; no resume in v2.0 | Appending to a pending download behaves differently across Android versions and can corrupt the file; a restart costs < 1 min per GB (A16/B7) | Add resume in v2.1 if large downloads are common |
| H21 | Design audit | APKs (self-update and outbox) are installed with `FileProvider` + `ACTION_VIEW`, not a `PackageInstaller` session | One simple path for API 25–35; the user confirms in the system dialog either way (A14) | — |
| H22 | Design audit | Photo picker for v2.0 drops long-press preview and per-date "Select all" (keeps Recent, Albums, tap-to-select, per-album Select all) | Not in the requirements; biggest UI build, untestable on real devices in CI (A17) | Want them in v2.1? |
| H23 | Design audit | Permissions are asked when first needed (photos + location at first "Send photos & videos", notifications at the first transfer). The battery step **stays on the welcome screen** | Fewer dialogs before first use; but vivo stopping the first big upload is the top risk, so battery is not delayed (A15) | — |
| H24 | Design audit | TV keeps its screen on during video/photos and for **30 min** of idle on the Ferry home screen | The Fire TV screensaver would otherwise close the cast receiver while you pick on the phone; a forgotten TV still sleeps (A10) | Preferred idle time? |
| H25 | Design audit | New app + old (v1) PC program: the app says "Your PC has an older Ferry. Replace it with the new Ferry folder" and sends nothing; no compatibility mode | A fallback path means extra code and tests for something fixed by copying one folder (B15) | — |
| H26 | Design audit | `versionCode` = 20000 + run number of `android.yml` on `main`; that workflow file is never renamed | Run numbers restart per workflow file; a lower number would block updates (B22) | — |
| H27 | Design audit | Extends H17: the API 25 emulator job runs a real instrumented test through `:core` (not only a boot). TV and API 25 emulator jobs run on `main` and manual runs only; the phone emulator on every push to `main` | Guards against Java APIs missing on Fire OS 6 (A3); keeps CI time reasonable (B27) | — |
