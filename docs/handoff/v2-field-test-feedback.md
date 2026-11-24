# Ferry v2: field-test feedback (2026-11-17/18, build 2.0.102)

To discuss with the user **before the next build** (he asked for this; he wants ONE running list of his and Claude's thoughts, discussed all together before the next iteration). Follow the process: requirements change → design → audits.

## F1. vivo "lock Ferry in Recents" step (B3)
- User found the instruction confusing. "Swipe up and hold" did nothing; his vivo X200 Pro (model V2413, Android API 36) uses **three-button navigation** (≡ ○ <). The **≡ button** opens Recents, and he locked Ferry by tapping **the Ferry name above the card** (menu > Lock).
- **User wants this step either not needed or automated.**
- Notes: Android has no API for an app to lock itself in vivo's Recents. Options: (a) test whether the lock is needed at all (battery Allow + foreground service may be enough): repeat the long screen-off send with Ferry **unlocked**; drop the step if it passes; (b) an in-app guide with pictures of the exact taps; (c) only show it if vivo actually stopped a transfer (Ferry already detects this).
- The C3 long send (5.9 GB, screen off) passed **with the lock on**, so it doesn't settle (a) yet. **Extra test planned: same send with Ferry unlocked** (can now come from normal use + logs, F30).
- Fix README §4 / Settings > "Still stopping?" wording in any case: ≡ button (or swipe up and pause), then tap the app name above the card > Lock.

## F2. User guide needs screenshots
- README.txt is plain text. User wants screenshots in the guide.
- Notes: CI already produces phone/TV screenshots (screenshot tour); vivo and Fire OS system screens can't be captured on emulators (could use the user's own photos). Formats: a guide page served by the PC (`/guide`) and/or a PDF/HTML file in the zip.

## F3. BUG: picker's top "Back" drops the picks (C1)
- User picked photos in one album, tapped the blue **Back** (top right) to go to another album: the picker closed and the picks were lost.
- Cause (`PickerScreen.kt`): the top Back calls `nav.pop()` (leaves the picker; `reset()` clears the selection), while the Android back key (`BackHandler`) goes album → album list first. Tapping the Albums tab inside an album also doesn't return to the list.
- Fix: top Back must behave like Android back (album → list, keep picks); Albums tab returns to the list. Add a test. (My test instruction "tap Back" also misled.)
- Confirmed on retry: with the phone's < key the picks across two albums were kept.
- See also F21 (back navigation on all screens).

## F4. Ask all permissions during first setup (user request)
- He was asked for notifications on the first send. **He wants every permission asked at the start (welcome/setup), so no permission pop-ups appear afterwards.**
- Changes agreed behaviour H23 (ask in context). Requirements change → record in requirements doc. Permissions: photos/videos (+ photo location), notifications, battery; install-unknown-apps is a Settings switch (could be offered at setup too).

## F5. Transfer screen title after finishing (wording, not a hang)
- After the send finished, the screen still said **"Sending to the PC"** with "4 saved · 0 already on PC" and every row "Saved". User thought it was stuck.
- Fix: when finished, the title should say it's done (e.g. "Done: 4 sent to the PC"); consider a clear "Done"/"OK" button back to Home.

## F6. Minor: PC log shows the phone as "V2413" (model code)
- ferry-log.txt device column shows `V2413`. Consider the phone's user-visible device name or "vivo X200 Pro". Low priority. (More important with several phones, F34.)

## F7. Log photo details so checks don't need Windows Properties (user request)
- User verified date taken + GPS by hand (Properties > Details). **He wants Ferry to log this information**: "If the logs are heavy, so be it. We can also have a setting to determine logging verbosity."
- Interpretation (confirm with user): ferry-log.txt records, per received file, what was preserved — size match, original file date, photo **date taken** (EXIF), **GPS present** (maybe coordinates), video creation date where readable; whether the phone sent the original or a location-stripped copy. Plus a **verbosity setting** (normal / detailed) on the PC and in the phone's Settings for its diagnostics log.
- Note: PC is Python stdlib only — JPEG EXIF parsing feasible by hand; HEIC/MP4 harder (log "not checked", or have the phone report what it read).
- The phone log also lacked the reason for F11 (no URI / exception logged) — detailed level must log such failures.

## F8. Thumbnails on the transfer screen (user request)
- "A small/medium thumbnail on the sending to PC page would be nice to see which photos went." Each row should show a thumbnail of the photo/video.

## F9. "Send anyway" UX (C2)
- After C2 ("already on PC"), the user tapped **Send anyway**: a flicker, then the rows stay "already on PC" with the button still there. "Why have the option to send anyway twice?"
- Confirmed in ferry-log.txt (18:40–18:41) and phone log (3× SEND_ANYWAY → alreadyOnPc=4): the re-sends were uploaded in full and the PC answered **skipped** (identical, no copy saved). Correct for data safety, confusing in the UI.
- Fix ideas: after Send anyway, mark rows "Identical copy is already on the PC" (final, no button again); show progress while it re-uploads; consider whether Send anyway is needed at all, or move it into a per-row menu.

## F10. Readable transfer time and speed in the log (user request)
- The log's duration column is a bare number of ms (e.g. `6500`), easy to miss; the PC **window** already shows size + MB/s per file (user screenshot), the log file doesn't. User: "Maybe we can also log transfer times." → log lines like the window: "saved video.mp4 · 153.1 MB in 6.5 s · 23.6 MB/s"; plus a per-batch summary (files, total size, total time, average speed). Ties in with F7 verbosity.

## F11. BUG (confirmed): Share from vivo Gallery always asks about location, sends nothing (C5)
- User: Gallery > select 3 photos > Share > Ferry. Ferry showed the location question; after Allow, it showed it again with **"Open Settings"**. **Nothing was sent** (PC log: no upload after 18:56).
- Settings > Apps > Ferry > Permissions > Photos and videos = **"Always allow all"**.
- Phone log (19:00:56–19:01:10):
  - `share android.intent.action.SEND_MULTIPLE: 3 URI(s)`
  - `send prepared 3 item(s) from Uris; location choice for 3; location permission=true`
  - `send photo location permission: true (granted [READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, ACCESS_MEDIA_LOCATION])`
  - same "location choice for 3" again → Open Settings → share activity destroyed.
- So every permission is granted, yet `UploadItemFactory.fromMedia` failed to open `MediaStore.setRequireOriginal(uri)` for the shared URIs (only that path sets needsChoice when hasLocation=true). The in-app picker (MediaStore IDs built by Ferry) works. Likely: the vivo Gallery's share URIs (form/authority unknown — not logged) are media or mapped URIs for which require-original open fails (e.g. URI-grant-only access, `external_primary`/Files-table form, or a mapped URI).
- Fix: (1) log the shared URI (scheme/authority/path shape, no personal data beyond that) and the exception from the require-original open; (2) for any shared item that resolves to a MediaStore row, rebuild the canonical `Images/Video.Media.EXTERNAL_CONTENT_URI/<id>` and open that with require-original (works when READ_MEDIA_* + ACCESS_MEDIA_LOCATION are granted, as in the picker); (3) never show the location question when the permission is granted — if the original truly can't be opened, say so plainly and offer to send as is; (4) test on API 34+ with a share via a FileProvider/media-picker-style URI. API 36 on the user's phone.
- Workaround meanwhile: use **Send photos & videos** inside Ferry (keeps GPS).

## F12. Max logging verbosity = no "how did it go?" questions (user request, 2026-11-18)
- User: "At maximum logging verbosity, you should not ask me anything about how things ran — everything should be available as logs, possibly including automatic screenshots."
- Extends F7/F10. Claude's thoughts: a "test mode"/max-verbosity level on PC, phone and TV that logs every screen shown, every button pressed, every state change, permission result, error with reason, timings; optional automatic screenshot of the app's own screen at each step (an app can capture its own window without extra permission; system screens like vivo Settings/Fire OS installer can't be captured). One-tap "send logs to PC" so phone/TV logs land next to ferry-log.txt automatically (no emailing to himself + uploading). Watch storage use and privacy (screenshots contain photos) — off by default, auto-cleanup.
- **Now the basis of the new testing approach — see F30.**

## F13. No QR scanning every time; automatic PC ↔ phone ↔ Fire TV connection (user request, 2026-11-18)
- User: "How can we automate the Ferry PC-phone-Firestick connections so I don't need to scan the QR every time."
- User later: he isn't sure he needs to re-scan; Ferry.bat shows the QR at every start (labelled "First time? Scan to install the Ferry app"), which raised the question. "We'll look at it closely later."
- Claude's thoughts: the QR is only for the first install (B1); the phone then found the PC by itself (B2, C1–C5), and the TV found it too. Options: show the QR only when no phone has connected yet (or behind a key press / in a small "Install on a new device" line), so a returning user doesn't think it's needed. Fire TV has no camera/QR: first install needs Downloader + typed address once. Ideas: phone pushes the app to the TV (needs ADB debugging ON on the TV, one-time); updates delivered automatically by the PC to phone and TV (in-app update, already H1) so no reinstall; remember the PC by name, not IP.
- See also F27 (easy install, one primary device), F32 (PC GUI has an "Add a device" button instead of a QR at every start).

## F14. Fire TV install: `…/app` URL fails in Downloader, `…/app/Ferry.apk` works (E1, 2026-11-18)
- Fire TV: **Fire OS 7.7.1.7 (PS7717/5746)**, "Fire TV Stick HD (1st generation)" per user, name "Saurabh's FireTVStick", IP 192.168.1.7.
- Downloader opened in its **Browser** tab (AFTVnews page); keyboard "Next" on `192.168.1.5:8765/app` (later with `http://`) did nothing visible; ☰ > Home returned to the same Browser tab (no separate "Enter a URL" Home box in his Downloader version).
- PC window: `Fire TV connected (192.168.1.7)` then `to Fire TV cancelled Ferry.apk` → the TV did reach the PC; Downloader's browser started the download of `/app` (no .apk in URL, served as attachment) and dropped it.
- With **`http://192.168.1.5:8765/app/Ferry.apk`** → Downloader asked for install permission and installed. **E1 OK.**
- Fix: PC window, README, DEVICE-CHECKLIST and /app page must give the Fire TV the `.apk` URL (`http://<ip>:8765/app/Ferry.apk`, maybe a shorter alias like `/tv.apk`); consider redirecting `/app` → `/app/Ferry.apk` for TV user agents. Also update instructions: Downloader's URL box may be the browser bar; Play/Pause = Go.

## F15. Startup message "Phone can't connect?" when the phone simply isn't in use (2026-11-18)
- Ferry.bat at 10:52:13 printed "Phone can't connect? Check: (1) same Wi-Fi … (2) Windows Firewall … (3) README > Troubleshooting" — the user wasn't using the phone. Reads like an error. Consider showing this only after the phone actually tried and failed, or wording it as a tip ("If your phone doesn't find the PC: …").

## F16. Phone video "This TV can't play this video format (HEVC)" (2026-11-18)
- Playing phone videos on the Fire TV: some played, some failed; one showed "This TV can't play this video format (HEVC)". Outbox videos played. Phone slideshow on TV worked.
- **Answers (2026-11-18):** route = phone > **Play on TV**. Videos recorded in **4K and 8K** failed with the HEVC message; others played. So it's resolution (4K/8K beyond this HD stick), not the HEVC format as such → the message names the wrong cause. Phone should know the TV's limits (TV can report its max decoder size/profile) and say "This video is 4K/8K — too large for this TV stick" before trying, maybe offer a smaller copy (phone MediaCodec transcoding; PC is stdlib-only, no ffmpeg) — discuss.

## F17. BUG: portrait (vertical) phone videos play sideways and squashed on the Fire TV (2026-11-18)
- Vertical phone video `video_20250703_172820.mp4` (in the outbox; plays upright on the PC): on the TV the frame is a tall portrait box, but the picture inside is **rotated 90° and squeezed** (window seen sideways). Seen with outbox and phone videos (user photo 11:22).
- Likely cause: phone videos are stored landscape with a rotation tag (90°). `TvPlayerScreen.kt` uses Media3 `PlayerView` with the default **SurfaceView**, which relies on the decoder honouring the rotation; Fire OS 7's decoder ignores it, while the player already sized the box using the rotated size → sideways + squashed. Fix to verify: `TextureView` surface (Media3 applies rotation itself) or apply the rotation transform manually; add an emulator test with a rotated test clip (note: emulator decoders may honour rotation, so a test may pass there — check the picture orientation, not just the box). Also check the phone's own player and slideshow paths.

## F18. TV browsing: TV can't look through the PC or phone (user idea, 2026-11-18)
- User: no option on the TV to search the phone or PC. "I don't think I'll need that anyway, at least for the phone. Searching the PC might be an idea though."
- Current design: the TV home shows only the PC's **outbox** (rows Videos / Photos / Apps / Other files). Idea for discussion: let the TV browse more of the PC (e.g. the inbox Downloads\Phone with everything received from the phone, by date/folder; or chosen PC folders) and search by name. Privacy/scope: only folders the user picks on the PC (F32 settings window could hold that list too). Browsing/search could also be done from the phone controller (F35).

## F19. TV player: settings (gear) icon bottom right can't be reached with the remote (2026-11-18)
- While a video plays on the TV (Ferry's own player, outbox video), the gear icon in the bottom-right corner can't be focused with the D-pad. Play/pause/jumps/sound work.
- Likely the Media3 `PlayerView` default controller's settings button (speed / tracks). Decide: hide it (not needed — simplicity) or make it reachable and useful (e.g. speed, audio track). Claude's lean: hide it; anything visible must be reachable by remote.

---
# User ideas, round 2 (2026-11-22, session 5) — with Claude's first thoughts. Not yet decided unless marked.

## F20. Sends survive the PC program being closed (user idea 1)
- User: if the PC app is switched off (terminal killed etc.), the phone's send should keep its work and finish once the PC app is back.
- Already partly there: P6 pauses and resumes the queue when the PC comes back **while the send is running**. Missing: the queue surviving a long wait, Ferry being closed or the phone restarting.
- Claude's thoughts: persist the send queue on the phone (list of items + which are done); show "Waiting for your PC — will send when Ferry is running on it" in the app and notification; resume automatically when the PC is found. **Battery (F24):** keep the foreground service while waiting only briefly (e.g. 10 min), then stop it and hand over to a background job that checks every ~15 min and on Wi-Fi changes (WorkManager). Picker items (MediaStore IDs) survive restarts; **Share URIs may not** (temporary grants) → for shared items, either copy them into Ferry's private storage while waiting, or ask the user to share again. Files still restart from the start (no partial files on PC).
- Pairs naturally with F29 (PC Ferry always running in background).

## F21. Better back navigation on all screens (user idea 2)
- Generalises F3. Rule: the top Back button always does exactly what the phone's < key does; Back never throws away work (picks, a running send) without asking; Back from Home leaves the app. Same on TV with the remote's Back.
- Testing: an automated test that walks every screen and presses both Backs, checking where it lands and that picks are kept.

## F22. Preview a video before sending or playing on TV (user idea 3)
- In the picker (and on Play on TV) a long-press or a small ▶ on a video thumbnail opens a quick preview player on the phone; the pick isn't lost. The app already has the Media3 player, so cost is low.
- Could also show resolution/length there and warn "4K/8K — too large for this TV" (F16).

## F23. Read-only PDF on the TV (user idea 4)
- Feasible with no extra library: Android has a built-in PDF page renderer (`PdfRenderer`, available on Fire OS 6/7/8). Remote: left/right = page, up/down = scroll, OK = zoom in/out steps. From the PC outbox (and possibly phone > Show on TV).
- Plain text (.txt) is equally easy. Word/Excel/PowerPoint are not (would need a big library) — suggest out of scope.

## F24. Phone: switch off when idle to save battery (user idea 5) — clarified 2026-11-22
- **User: idea 5 is for the phone; the concern is battery life.**
- Facts: the phone app has no background service when idle; Android stops the app's process by itself. What can cost battery: (a) the foreground send service if it lingers after a send or while waiting for the PC (F20), (b) Wi-Fi lock / wake lock held during sends, (c) the PC/TV discovery retries while a Ferry screen is open (O16: every ~10 s), (d) a TV session (Play on TV) left running, phone serving the video, (e) the TV controller (F35) if it keeps a live connection.
- Proposal: a hard rule "nothing of Ferry runs on the phone when not in use": service stops the moment a send ends; while waiting for the PC, at most ~10 min of foreground service, then a cheap background check (F20); discovery retries only while a screen is visible, and slow down after a minute; a Play-on-TV session ends after ~10 min with nothing playing (paused/idle) and when the TV goes home; wake/Wi-Fi locks only during actual transfers.
- Verification from logs (F30): the phone logs service start/stop, lock held times, and session ends, so idle time with something running shows up in the review. Android's own battery screen (Settings > Battery > Ferry) is a cross-check.

## F25. Authorship and MIT Licence (user idea 6)
- Add `LICENSE` (MIT) to the repo and the zip; an **About** screen in the app (phone + TV) and an About line in the PC window/`/app` page: "Ferry © 2026 Saurabh Sawhney · MIT Licence · Built with Claude (Anthropic)". Repo is public and currently has no licence (= all rights reserved by default).
- Claude's note: the copyright holder line should name Saurabh; Anthropic/Claude is better credited as "built with" than as a copyright holder (Claude isn't a legal author and Anthropic doesn't claim it). Not legal advice. Also list the third-party licences used (AndroidX/Media3 = Apache 2.0, Nayuki QR generator = MIT) on the About screen.

## F26. Choose the PC destination folder for phone → PC sends (user idea 7)
- Today: fixed inbox `Downloads\Phone`. Idea: PC setting for the default folder; and the phone picks a destination per send.
- Claude's thoughts: letting the phone browse the whole PC is a security/privacy step up (anyone on the Wi-Fi with the app could see the folder tree). Suggest: the PC user sets one or more **allowed destination folders** on the PC (**in the PC settings window, F32**); the phone shows a drop-down of those plus "new subfolder" (e.g. "Goa trip 2026") and can browse **only inside** them. Remembers the last choice. Duplicate check works per destination.

## F27. Easy install from one primary device (user idea 8)
- PC → phone/TV install directly: needs ADB/USB debugging and extra tools on the PC — not simple; not recommended.
- **Phone as the primary device** looks best:
  1. Phone gets Ferry once (from a GitHub Releases link/QR — repo is public — or from the PC as now).
  2. Phone → PC: phone shows "Set up your PC": an address to type in the PC browser (e.g. `http://192.168.1.x:8766`); the phone serves the PC zip. Python is the obstacle → bundle the Windows **embeddable Python** in the zip (~10 MB) so nothing else needs installing.
  3. Phone → Fire TV: phone serves the TV APK at a short URL for Downloader (one typed address, as now but from the phone). Fully automatic push needs "ADB debugging" ON on the TV once + accepting a prompt on the TV; phone implements ADB-over-Wi-Fi (libraries exist). Worth it? Discuss — moderately complex. **The same ADB link would also power whole-TV control from the phone (F35 level 2)**, which makes it more worthwhile.
  4. After that, updates flow automatically (F31).
- Ties into F13, F33 (giving Ferry to a friend).

## F28. Phone + TV only mode, no PC (user idea 9)
- Much of it already exists: P9 Play on TV / slideshow streams **directly** from the phone, no PC involved.
- What's missing: (a) TV home shows "Can't find your PC" when the PC is off → should instead say "Ready for your phone" and offer PC features only when a PC is found; (b) installing/updating the TV app without the PC (phone serves the APK — F27/F31); (c) maybe phone → TV file copy (APKs to install on the TV, PDFs to read — F23) without the PC.
- Requirement change: "PC optional" becomes an explicit mode; test the whole TV flow with the PC off.

## F29. Ferry runs constantly in the background on the PC (user idea 10) — direction agreed 2026-11-22
- **User likes: Ferry on the PC runs constantly in the background by default.**
- Plan: starts with Windows (Startup-folder shortcut, created/removed by a setting), no console window (pythonw), very light when idle. Window opens from the GUI (F32); closing the window keeps Ferry running; a clear "Quit Ferry" exists. A tray icon is possible with stdlib only via ctypes (Shell_NotifyIcon) — fiddly but doable; otherwise a small taskbar window. Decide in design.
- Wake-on-LAN from the phone if the PC is **asleep/off**: works only if the PC's network card/BIOS supports it (usually wired Ethernet; Wi-Fi rarely) — optional extra, low priority.
- Combined with F20, a send just waits until the PC is up.
- Security: always listening on the home LAN, and with friends' phones possibly on the Wi-Fi (F33/F34) → **pairing**: each new phone/TV is approved once on the PC ("Allow vivo X200 Pro? Yes/No"), remembered in the config; settings list of approved devices with Remove. Discuss.

---
# Round 3 (2026-11-22, session 5): GUI/config, testing, updates, portability, TV controller

## F30. New testing approach: normal use + log review (user decision, 2026-11-22)
- User: "I got very tired testing things last time." **He wants to use Ferry normally, without deliberately testing, and after a few days Claude goes through the logs to see what can be improved and what caused distress (intent in log but no matching action, etc.).**
- Replaces the long manual DEVICE-CHECKLIST for everyday features. What it needs (builds on F7/F10/F12):
  - **Usage log on phone, TV and PC** (structured, one event per line, timestamped, with a session id): screen shown, button pressed, Back pressed, picks made, permission asked/result, send started/each file/finished, errors shown to the user (with the exact text), waits, retries, app sent to background/killed, service start/stop, discovery results, player events (play, pause, seek, error, decoder/format info), controller commands sent/received (F35).
  - **Logs collected automatically**: phone and TV upload their logs to the PC whenever connected (into e.g. `Ferry logs\<device>\`); PC keeps its own. One folder to zip and upload to Claude — no emailing, no copying.
  - **"Something's wrong" button** (phone; TV long-press of a key): one tap marks the moment in the log, optional short note. Cheapest way to capture distress the logs can't infer.
  - **Automatic signals for the review** ("distress detectors"): an action started with no result (send started, nothing saved); the same button tapped repeatedly; quick back-and-forth between screens; an error/permission dialog shown twice in a row (F11-style loop); Back that discarded picks; app leaving mid-task; a video stopped within seconds; long waits; anything that took much longer than usual; controller command sent but TV didn't react. A small review script (Claude runs it on the uploaded logs) lists these with context.
  - Optional automatic screenshots of Ferry's own screens at errors only (privacy: photos), off by default.
  - Privacy: logs stay on his devices and PC; file names are logged (needed); no photo content. Size cap + rotation.
- Still needed despite this: **automated emulator tests in CI** (cover regressions cheaply) and a **very short manual list only for rare, one-off things** (first install on a new TV, updates, permission setup) — perhaps 5 items, done once per build when convenient.
- Planned use: after the next build, ~a few days of normal use → user uploads the logs folder → Claude reviews and writes findings here.

## F31. Getting new builds onto the devices (user question, 2026-11-22)
- Today: CI builds a zip; user downloads it from GitHub, unzips on the PC, restarts Ferry; the phone/TV then see "Update available → Install" from the PC (C3).
- Proposal: **PC updates itself from GitHub Releases** (repo is public, no login needed): checks once a day (and on "Check now"); shows "Ferry 2.1 is available — Update" in the GUI; downloads, verifies, swaps files, restarts. Settings: auto-install PC updates yes/no. Then phone and TV get the new APK from the PC automatically (as now).
- Android always needs **one tap** to install an update for an app sideloaded like Ferry (no silent install without being a device owner); on newer Android (12+) an app can sometimes update itself without the prompt once it is the app's installer — uncertain on vivo, test. Fire OS 7 (Android 9): one tap always. Phone/TV can download the update in the background and show it ready to install, so the tap is the only step. (With F35 level 2 / ADB, the phone could install the TV update itself — no TV tap.)
- So per new build: zero steps on the PC (or one click), one tap on the phone, one OK on the TV.
- Release channel: the CI uploads each approved build as a GitHub Release (not every CI run). Signing key stays as now.

## F32. PC GUI wrapper + config file (user idea, 2026-11-22) — agreed direction
- User: consider a GUI for Ferry on the PC and a config file, for changing and saving settings, including which folders are available to the phone's destination drop-down (F26).
- Claude's thoughts: stdlib **tkinter** (ships with the normal Windows Python and with the embeddable bundle if we add it) → no new dependencies. Config as `ferry-config.json` next to ferry.py (human-readable, editable by hand, GUI writes it; keeps `ferry-state.json` for runtime state or merges into it).
- Window sections: **Status** (running, address, devices seen, recent transfers with speed — as today's console), **Folders** (inbox/default destination, allowed destination folders for the phone, outbox, folders the TV may browse — F18), **Devices** (approved phones/TVs, pairing requests, remove — F29/F34), **Add a device** (QR + TV address, replaces the QR at every start — F13), **Settings** (start with Windows, log verbosity, update checks — F31, port), **Logs** (open logs folder, "zip logs for Claude"), **About** (F25).
- Closing the window hides it; Ferry keeps running (F29).

## F33. Giving Ferry to a friend (user question, 2026-11-22)
- Mostly works already: nothing in the code is tied to his devices (folders are generic, the PC is found on whatever Wi-Fi); MIT licence (F25) makes sharing clean.
- Needed: (a) Python bundled (F27) so the friend needn't install it; (b) first-run setup in the PC GUI (choose folders, start with Windows) instead of assuming defaults; (c) README/guide without his device names/IPs; (d) updates from GitHub Releases (F31) work for the friend too; (e) phone-led install (F27) makes it a 2-minute setup.
- Same signing key for everyone is fine (updates only install over an app signed with the same key).

## F34. Several phones, PCs or TVs (user question, 2026-11-22)
- **Two phones + one PC**: the PC server already handles several connections; dedupe/never-overwrite works per file. Needed: device names (not model codes — F6) in the GUI and logs; optional per-phone subfolder in the inbox (e.g. `Downloads\Phone\Saurabh's vivo`); pairing approvals (F29); test two phones sending at once (speed shares the Wi-Fi).
- **Two TVs**: Play on TV currently remembers the last TV; needs a **TV chooser** when more than one is found (names like "Living room", "Bedroom", set on the TV or from the PC GUI). Both TVs can browse the PC outbox at the same time. Controller (F35) picks a TV the same way.
- **Two PCs** (e.g. a friend's laptop on the same Wi-Fi): phone needs a PC chooser and remembers the default; pairing keeps a visitor's phone from seeing your outbox unless you approve it.
- Testing: multi-device behaviour mostly in CI with emulators (two phone emulators + one TV emulator + PC program); real-device check during normal use (F30).

## F35. Full TV controller in the phone app (user idea, 2026-11-22)
- User: plan a full-featured controller for the TV inside the phone app, so he doesn't need the Fire TV remote at all.
- Today: T5 gives basic control from the phone during Play on TV (pause/next/stop).
- Claude's thoughts — two levels:
  - **Level 1: control Ferry on the TV** (no TV setup needed; uses Ferry's own connection). A "TV" tab on the phone: D-pad + OK + Back + Home-of-Ferry; play/pause, a **seek bar** with times (drag to any point), ±10 s, next/previous, slideshow speed; **choose what to play on the phone** (browse the PC outbox / phone gallery on the phone screen, tap → TV plays; F18 search fits here); **type with the phone keyboard** into TV text boxes (e.g. the address field); PDF page turning (F23); "Now playing" on the phone's lock screen/notification with controls (only while something plays — battery F24). Works only while Ferry is open on the TV.
  - **Level 2: control the whole Fire TV** (Home, other apps like YouTube/Netflix, opening Ferry on the TV from the phone, waking the stick). Needs **ADB debugging** turned on on the Fire TV once (Settings > My Fire TV > Developer options) and accepting a one-time "allow this phone" prompt on the TV. Then the phone sends key presses, opens apps, types text — and the same link lets the phone **install/update Ferry on the TV with no TV tap** (F27/F31). Uncertain/limited: **TV power and TV volume** go through HDMI-CEC/IR — may or may not work via the stick on his TV (test); a stick in deep sleep may not answer (test); Amazon could change ADB behaviour in updates.
  - Note: Amazon's own free "Amazon Fire TV" phone app already does whole-TV remote control; Level 2 duplicates it but integrates it with Ferry and the ADB install benefit.
- Recommendation: Level 1 in the next build or the one after (fits the current design: phone ↔ TV session already exists); Level 2 together with F27 (ADB install) in the later build, after a quick feasibility check on his stick (does ADB over Wi-Fi work; do volume/power keys reach the TV).
- Open questions for the user: Ferry only, or the whole Fire TV (other apps too)? Is TV volume/power from the phone important?
- Testing: emulator Android TV image in CI for Level 1; Level 2 feasibility needs his real stick.
- **User answers (2026-11-22):** for now **Ferry only (Level 1)**, especially **how videos play back**. **Zoom for everything Ferry displays** — photos, PDFs/documents **and videos** (zoom + pan). **TV volume control preferable, not essential. TV power not needed.** Level 2 (whole Fire TV via ADB) not planned now.
- Claude's note on volume: Ferry can change the stick's own media volume; whether that changes the TV's loudness depends on the TV/HDMI setup (on many Fire TV setups it does) — build it, check in the logs/normal use.

## F36. Session 5 decisions on process (user, 2026-11-22)
- **Two build stages: OK.**
- Before starting: **visit a few web pages for ideas from users of similar apps**; include worthwhile ones by Claude's judgement (record them).
- **Both stages hands-free** for the user: Claude asks everything needed first, then completes the work; decisions in between are Claude's, recorded (engineering/taste) for later review.
- **Token budget:** if work is interrupted because tokens ran out, **re-check after 24 hours** (not sooner), to leave tokens for his other work that day.
- Sub-agent reviews continue as per his standing process (≥2 independent reviewers per audit, different models, separate focus areas, no shared findings; final fixer; independent check of fixes). **Fable not available → use Opus and Sonnet** (final fixer: Opus).
- Answers: (1) **deliver Stage A, user uses it normally, log review, then Stage B**; (2) **pairing: one-time "Allow" on the PC** (safer); (3) licence line — user asks whether to include the version → Claude: version on the About screen ("Ferry 2.1.0 · © 2026 Saurabh Sawhney · MIT Licence · Built with Claude (Anthropic)"), not inside the LICENSE file (which stays standard: year + holder); (4) **nothing off-limits** — the Stage A/B lists + research ideas are agreed scope.
- **Vision statement with rules**: none existed (only `audit-brief.md`); drafted `VISION.md` for approval; every reviewer gets it.
- **Session timing:** hands-free work runs in a night window starting **02:00 IST** (done by ~07:00), and an interrupted run resumes at the next 02:00 IST — instead of 24 h after the session start.

---
## Test progress
- A1 OK (Ferry 2.0.0 PC started 18:14, Python 3.11.9). B1 installed via QR. B2 OK: "Connected to Saurabhs-PC". B3 lock done (F1). Battery setting done. Phone: app 2.0.102, vivo V2413, API 36.
- C1 OK on retry (camera photos, two albums via the phone's < key; date taken + GPS present on the PC). First try showed F3 and F5.
- C2 OK: "already on PC"; Send anyway re-uploads → PC "skipped" (no copies) — F9 is UX only.
- **C3 OK (with Ferry locked)**: 18:52:05–18:56:09, 105 picked, **102 videos saved, 3 already on PC, 5.91 GB in ~244 s, 24.3 MB/s overall**, per minute 20–29 MB/s, no pause (max gap 16 s = one 378 MB file), screen off during the send.
- **C4 OK** (covered by C3: 100+ files at once, 24 MB/s ≫ 10 MB/s target).
- **C5 FAIL** (F11): Share > Ferry loops on the location question; nothing sent.
- 2026-11-18: user jumped to Fire TV. C6, C7, D1 skipped for now, to do later.
- **E1 OK** with the `.apk` URL (F14). Outbox videos play on the TV (part of F1). Phone slideshow on TV OK (G5-ish). Phone Play on TV: 4K/8K videos fail with the HEVC message (F16), others play. Vertical videos play sideways/squashed (F17, outbox and phone).
- **E2–E4 OK**: TV Ferry home shows the PC outbox rows; video + slideshow play from them; remote-only. **F1 OK** (play/pause/jumps, sound), **F2 OK** (resume question). F19: gear icon unreachable. F3 (4K/HEVC) = F16.
- **2026-11-22: user decided to stop the manual checklist (F30).** Remaining checklist items (F4–F8, G1–G6, C6, C7, D1, H2, A2, unlocked screen-off send) to be covered by normal use + log review after the next build, plus CI tests; a short manual list only for rare one-off items.
