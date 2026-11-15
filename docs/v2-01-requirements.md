# Ferry v2: Requirements (Stage 1, agreed 2026-11-14/15, rev 1)

**Ferry v2** replaces the browser page with a **native Android app** (one APK with a phone layout and a Fire TV layout). The **Windows PC program stays** and is extended. Goal unchanged: **simplicity and speed, zero-to-no typing.**

Items marked **[H]** were decided by Claude during the hands-free session; they are listed in `v2-decisions-log.md` for the user's review.

## 1. Devices
| Device | Role |
|---|---|
| Windows 10 PC (Saurabhs-PC, 192.168.1.5) | Ferry PC program (Python, stdlib only). Inbox `Downloads\Phone`, outbox `Desktop\Ferry outbox`, port 8765 |
| vivo X200 Pro (recent Android) | Ferry app, **phone layout** |
| Fire TV Stick (Fire OS 6/7/8) | Ferry app, **TV layout** (D-pad / remote) |
| Linux PC | **Not supported in v2** (user: Windows only). Code should not deliberately break Linux, but it isn't tested or documented |

## 2. Functional requirements

### 2.1 Phone app
| ID | Requirement |
|---|---|
| P1 | **Send to PC, manual only.** No auto-send. Two ways in: (a) choose inside the app, (b) **Share → Ferry** from the Gallery, Files or any app (single and multiple items) |
| P2 | **Originals preserved**: original file name, modified/taken date, and **GPS location** (ACCESS_MEDIA_LOCATION + original MediaStore bytes). Byte-for-byte identical to the file on the phone |
| P3 | In-app chooser for photos/videos that lets the user **select across albums/folders without losing the selection**, newest first. **[H]** A second "Other files" option opens the system file chooser for documents, APKs etc. |
| P4 | **Uploads continue with the screen off / app in background** (foreground service with a progress notification). Progress, speed and a final summary ("12 saved · 3 already on PC") shown in the app and in the notification |
| P5 | **Duplicate pre-check** against the PC (as v1): files already on the PC are skipped without re-uploading. PC never overwrites; same name/different content → `name (1).ext` |
| P6 | **Resilient**: if Wi-Fi drops or the PC goes away, the app pauses and resumes the queue when the PC is back (retry a file from its start; no partial files on the PC) |
| P7 | **Browse the PC outbox** and **download** files to **`Downloads/Ferry`** on the phone (all file types). No http/"insecure download" warnings |
| P8 | **Finds the PC automatically** on home Wi-Fi (no QR, no typing). **[H]** Fallback if discovery fails: type the PC address once (remembered) |
| P9 | **Phone → Fire TV directly (PC not needed)**: pick **videos** on the phone and **play them on the TV**; pick **photos** on the phone and show them as a **slideshow on the TV**. **[H]** The files stream from the phone; nothing is copied to the TV |
| P10 | Guides the user **once** to exempt Ferry from vivo battery optimisation (and to allow the needed permissions), with a plain explanation |

### 2.2 Fire TV app
| ID | Requirement |
|---|---|
| T1 | **Remote-friendly UI** (D-pad, OK, Back, play/pause, rewind/fast-forward keys). No pointer needed |
| T2 | **Play videos** from the PC outbox with a proper player: pause, seek ±10 s / fast-forward and rewind **work reliably** (fixes the Silk freeze), including large 4K/HEVC files the TV can decode. **[H]** Remembers position per video if you stop part-way |
| T3 | **Photo slideshow** of outbox photos: automatic (default **[H]** 5 s) with next/previous on the remote and pause; also view photos one by one |
| T4 | **Install APKs** from the outbox (one press → Android installer). No Downloader needed after the first install |
| T5 | **Receives "play on TV" from the phone** (P9): video playback and photo slideshow, controllable from the TV remote **[H]** and basic control from the phone (pause/next/stop) |
| T6 | TV does **not** send files (out of scope) |

### 2.3 PC program
| ID | Requirement |
|---|---|
| C1 | Keeps the v1 `/api/v1` upload/check/outbox behaviour (dedupe, never overwrite, fsync, mtime restore, Range downloads) |
| C2 | **Auto-discovery**: answers the app's discovery on the LAN (UDP, port 8765 as reserved in v1) |
| C3 | **App updates via the PC**: the PC program carries the current Ferry APK; the app checks the version when it connects and offers "Update available → Install" |
| C4 | **Browser page retired.** **[H]** The root page becomes a minimal "Get the Ferry app" page with a download link and the short address `http://<ip>:8765/app`, used for the **first install** on the phone (browser) and on the Fire TV (Downloader or similar). The QR code in the console points to this page |
| C5 | **Detailed log file** `ferry-log.txt`: every request with time, device/IP, method, path, Range, status, bytes, duration, outcome and errors; rotated (≈5 MB + previous). Console stays concise |
| C6 | **README rewritten** for the app (install on phone, install on Fire TV, battery settings, troubleshooting) |
| C7 | Still launched by double-click (`Ferry.bat`); still stdlib-only Python ≥ 3.8 |

## 3. Non-functional
- Speed limited by Wi-Fi (~20–24 MB/s measured phone ↔ PC); small-file batches should do better than v1's ~5 MB/s **[H]** (target ≥ 10 MB/s for a batch of phone photos).
- Low memory: stream from/to disk; no whole-file buffering.
- Never leave partial or corrupt files; never overwrite.
- Clear, friendly messages; English only **[H]**.
- **Security: none, by choice** (home network only, no pairing, plain HTTP on the LAN). Anyone on the Wi-Fi can send to the PC and read the outbox while Ferry runs, and can cast to the TV while the app is open.
- Minimum Android **[H]**: Android 7.1 (API 25) so older Fire TV Sticks work; target the current Android API level.
- Builds and automated tests run on **GitHub Actions** in the private repo `DrSaurabh/ferry`; the APK is sideloaded.

## 4. Out of scope (v2)
Auto-send of new photos; PC → device push; TV sending files (to PC or phone); TV → phone; Linux support; pairing/encryption; the browser upload/outbox page; iOS; Play Store publishing; renaming numeric picker names (not needed: the app keeps original names, and the browser is retired).

## 5. Acceptance (checked by the user on real devices after the build)
1. Phone: send 20 photos (picked from two albums) → PC has them with original names, dates and GPS; resend → "20 already on PC".
2. Phone: a 1 GB+ video uploads with the screen off.
3. Share → Ferry from Gallery works.
4. Phone: download a file from the outbox → it's in Downloads/Ferry, no warnings.
5. TV: play the 241.9 MB test video from the outbox; pause 30 s, fast-forward, rewind → no freeze.
6. TV: slideshow of outbox photos; next/previous with the remote.
7. TV: install an APK from the outbox.
8. Phone with PC off: play a phone video and a photo slideshow on the TV.
9. App update: put a newer APK on the PC → phone and TV offer the update.
10. `ferry-log.txt` shows each transfer.
