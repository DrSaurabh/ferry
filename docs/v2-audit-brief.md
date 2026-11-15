# Ferry v2: audit brief (give this to every reviewer)

**What Ferry is.** A personal tool for ONE user (Saurabh, a non-developer) to move photos/videos/files over home Wi-Fi between a vivo X200 Pro phone (recent Android), a Fire TV Stick (Fire OS 6/7/8) and his Windows 10 PC. v1 was a browser page served by a Python program on the PC; v2 adds a native Android app (one APK, phone + TV layouts) and extends the PC program. Repo: /home/claude/ferry (pc/ = Python, android/ = Kotlin, docs/ = requirements, design, decisions).

**Priorities (in order).**
1. Never lose or corrupt a file; never overwrite on the PC; originals keep name, date and GPS.
2. Simplicity and speed for the user: zero-to-no typing, few taps, clear plain-English messages.
3. Works reliably on his real devices (vivo battery management, Fire TV remote, Windows firewall).
4. Maintainable by Claude in future sessions; testable in GitHub Actions (no local Android SDK).

**Explicit non-goals (do NOT recommend these).** Authentication, pairing, encryption, TLS (home LAN only, user's explicit choice). Multi-user support. Play Store publishing. iOS. Linux PC support. Auto-sending new photos. PC → device push. TV sending files. Browser UI. Enterprise hardening, telemetry, analytics. Feature creep beyond the requirements.

**How to review.**
- Read docs/v2-01-requirements.md and the artifact under review.
- Report concrete problems: things that are wrong, missing for a requirement, contradictory, risky on the real devices, or over-complicated. Give each finding an ID, severity (High/Medium/Low), the section, the problem, and a suggested fix.
- Verify claims by cheap experiment where possible (e.g. run Python, read the v1 code in pc/ferry.py, check Android API facts you are sure of). Mark anything you could not verify as **"uncertain"**.
- Do not rewrite the document. Do not edit any files except your own report.
- Write your report to the file path you are given, incrementally as you go (so nothing is lost if you are interrupted).
