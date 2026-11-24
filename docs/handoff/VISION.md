# Ferry: vision and rules

_Draft 1, 2026-11-22 (session 5). For approval by Saurabh. Every reviewer and builder gets this document; work that conflicts with it is a defect, even if it is otherwise good engineering._

## Vision
Ferry makes moving and enjoying photos, videos and files between **phone, PC and TV at home** effortless: **no typing, no cables, no cloud, nothing to remember.** You pick something on one device and it is simply there, or playing, on the other, intact.

## Who it's for
- **Saurabh** and his household: one non-developer owner who is happy to try things but should never need technical knowledge to use Ferry.
- Later, **friends** he gives it to (MIT licence), set up in a few minutes without his help.
- Devices: Android phones (vivo X200 Pro first), Windows 10/11 PCs, Amazon Fire TV sticks (from the 1st-generation HD stick up).

## Rules
1. **Never lose, corrupt or overwrite a file.** Originals arrive byte-for-byte identical, with name, dates and photo location. Same name, different content → a new name, never a replacement. No partial files left behind.
2. **Zero-to-no typing.** Everything is a tap or a remote/phone-controller press. Typing is acceptable only once per device in its lifetime (e.g. the first TV install), and even then as short as possible.
3. **Works first time for a non-developer.** No step assumes technical knowledge. Defaults just work; settings exist for the rest.
4. **Nothing to remember.** Devices find each other by themselves; Ferry remembers choices; it asks each question once (all permissions at setup), not every time.
5. **Honest, plain messages.** The screen always tells the truth about what is happening ("Done" when done). Every problem message names the **real cause** and the **next step** in everyday words.
6. **Consistent and forgiving.** Back always does the same thing on every screen and device; nothing the user did (picks, a running send) is thrown away without asking.
7. **Everything visible is usable.** On the TV, every control can be reached with the remote or the phone controller; anything that can't be used is not shown.
8. **Light on the devices.** Nothing of Ferry runs on the phone when it is not in use; battery use only during real work. On the PC, the background program is quiet and light.
9. **Fast.** Transfers are limited only by the Wi-Fi.
10. **Home only, private.** Works on the home network; no cloud, no accounts, nothing leaves the home. New devices are approved once on the PC. Logs stay on the user's own devices.
11. **Observable without asking.** Every user intent and its outcome is logged, so problems are found from the logs, not by asking the user "how did it go?". A one-tap "Something's wrong" marks moments the logs can't judge.
12. **Simple beats clever.** Fewer screens, fewer options. A feature must earn its place in daily use. Over-engineering — and hardening beyond what a home tool needs — is a defect.
13. **Shareable.** Nothing tied to Saurabh's devices, names or addresses in the product; a friend's setup works the same way.

## Priorities when rules or goals conflict
1. Data safety (rule 1) → 2. Works first time / simplicity (2–4, 12) → 3. Honest messages and consistency (5–7) → 4. Battery and lightness (8) → 5. Speed (9) → 6. Maintainability → 7. New features.

## Technical ground rules
- **PC:** Python standard library only (a bundled Windows embeddable Python is allowed so users needn't install Python); Windows 10/11; started by double-click, later runs in the background.
- **Android:** one APK with phone and TV layouts; Fire OS 6/7/8 (Android 7.1/9/11) up to current Android (API 36). Only well-known, maintained libraries (AndroidX, Media3), kept to a minimum.
- Plain HTTP on the home network is accepted; protection comes from device approval, not encryption.
- Automated tests (unit + emulator) guard every fix and feature; real-device checking happens through normal use and the logs.

## Not goals (reviewers: do not push these)
- Internet or cloud transfer, remote access from outside the home, user accounts.
- Automatic backup or sync — sending is always the user's choice.
- Encryption/TLS, passwords, or enterprise-style security.
- Linux or Mac PCs, iPhones.
- Controlling the Fire TV outside Ferry, or the TV's power.
- Editing files; showing Word/Excel/PowerPoint files.

## How reviewers use this
- Check the work against the rules and priorities; name the rule a finding relates to.
- Don't propose features or hardening listed under "Not goals" or beyond what rule 12 allows.
- Verify by experiment where cheap; mark anything not verified as **uncertain**.
