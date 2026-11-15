FERRY 1.0.2
Move files between your phone (or Fire TV) and this PC over your home Wi-Fi.
Phone -> PC: photos, videos, any file.   PC -> phone / Fire TV: anything you put in the outbox.
Nothing to install on the phone. Works on Windows 10/11 and Linux.

====================================================================================
QUICK START (WINDOWS)
====================================================================================
1. Before extracting: right-click the downloaded zip > Properties > tick "Unblock" > OK.
   (Skipping this makes Windows show a security warning every time.)
2. Right-click the zip > Extract All... and choose a folder, e.g. Documents\Ferry.
3. Make sure Python is installed (3.8 or newer). If Ferry says it isn't, install it from
   https://www.python.org/downloads/ and tick "Add python.exe to PATH".
4. Double-click Ferry.bat. A window opens with a QR code.
5. The first time, Windows Firewall asks about "Python". Tick "Private networks" and click
   Allow access. (If you missed it: see Troubleshooting.)
6. On your phone, open the camera and point it at the QR code. Tap the link.
7. Ferry opens in Chrome. Tap "Photos & videos" or "Any file", pick, and they're sent.

Your files arrive in:      Downloads\Phone
To send files TO the phone: put them in the "Ferry outbox" folder on your Desktop,
                            then open the "From PC" tab on the phone.

Keep the Ferry window open while you use it. Close it (or press Ctrl+C) to stop.
(After Ctrl+C, Windows may ask "Terminate batch job (Y/N)?" - just press Y.)

====================================================================================
ONE-TAP ACCESS FROM THE PHONE
====================================================================================
After scanning the QR code once: in Chrome tap the three dots (top right) >
"Add to Home screen". You now have a Ferry icon on your phone.

KEEP THE SAME ADDRESS (recommended)
The address in the QR code (like http://192.168.1.23:8765) comes from your router.
Sometimes the router gives the PC a new address, and your home-screen icon and Fire TV
bookmark stop working. Ferry tells you when this happens ("Your PC's address changed").
To stop it happening, reserve the address in your router once:
  1. Open your router's settings page (often http://192.168.1.1 or http://192.168.0.1;
     the address and password are usually on a sticker on the router).
  2. Find "DHCP reservation", "Address reservation", "Static lease" or "Fixed IP"
     (often under LAN, DHCP or Advanced).
  3. Pick this PC from the list and save. Restart the PC.

====================================================================================
SENDING FILES FROM THE PC TO THE PHONE
====================================================================================
1. Copy or drag files into the "Ferry outbox" folder on your Desktop.
2. On the phone, open Ferry and tap "From PC". Tap a file to download it, or
   "Download all". (Chrome may ask to allow multiple downloads - tap Allow.)
3. Files go to the phone's Downloads folder. Photos and videos also appear in Gallery.
Files stay in the outbox until you delete them, so you can send them to several devices.
Folders are not sent - put the files in directly, or zip the folder first.

====================================================================================
FIRE TV STICK
====================================================================================
Watch a video or install an app from the PC on your Fire TV.
One-time setup:
  1. Settings > My Fire TV > About > click your device name 7 times to turn on
     Developer Options. Then Settings > My Fire TV > Developer Options >
     Install unknown apps > turn on for "Downloader" (and "Silk Browser").
  2. Install "Downloader" from the Amazon Appstore.
  3. In Downloader (Browser tab) or the Silk browser, type the address shown in the
     Ferry window, e.g. http://192.168.1.23:8765 - and save it as a bookmark/favourite.
     From then on it's one click, no typing.
Using it: put the file in the outbox on the PC, open your bookmark on the TV, and choose
  "Play" (videos: .mp4, .m4v, .webm) or "Save" / "Install / Save" (apps).
Short links: the Ferry window shows a number for each outbox file, e.g.
  http://192.168.1.23:8765/1  - type it in Downloader to download/install that file.
  http://192.168.1.23:8765/new - always the newest file in the outbox.
  (Numbers can change when Ferry restarts - check the Ferry window.)
Tip: other video types (.mkv etc.) may not play in Silk - install VLC on the Fire TV,
  then save the file with Ferry and open it in VLC. The Fire TV has little storage,
  so delete big files after watching.

====================================================================================
LINUX
====================================================================================
1. Extract the zip. In a terminal, in the Ferry folder:  ./ferry.sh
   (If ferry.sh isn't executable:  chmod +x ferry.sh)
2. Optional: add Ferry to your app menu:  python3 ferry.py --install-desktop
3. If you use a firewall (ufw):  sudo ufw allow 8765/tcp
Files arrive in ~/Downloads/Phone. The outbox is ~/Desktop/Ferry outbox.

====================================================================================
TROUBLESHOOTING
====================================================================================
The phone can't open the page ("This site can't be reached"):
  - Is the Ferry window still open?
  - Are the phone and PC on the SAME Wi-Fi? Guest networks block this - use the main one.
  - Windows must treat your Wi-Fi as Private: Settings > Network & Internet > Wi-Fi >
    (your network) > set Network profile to Private. Ferry warns you if it's Public.
  - Firewall: Windows Security > Firewall & network protection > Allow an app through
    firewall > make sure "Python" is ticked for Private.
  - Did the PC's address change? Scan the QR code again (see "Keep the same address").
  - Some routers have "AP isolation" / "client isolation" turned on - turn it off.

"Port 8765 is used by another program" / "Windows has reserved port 8765":
  Start Ferry on another port. Open a Command Prompt in the Ferry folder and run:
      Ferry.bat --port 8800
  (Then scan the new QR code.)

The QR code won't scan:
  Make the Ferry window bigger or zoom in (Ctrl + mouse wheel), or type the address
  shown under it into Chrome. A large QR code is also available on the PC at
  http://localhost:8765/qr

Uploads stop when the phone screen turns off:
  Keep the screen on while sending. If it does turn off, Ferry continues automatically
  when you turn the screen back on (that file restarts from the beginning).

Odd files named ".ferry-....part" in the Phone folder:
  These are unfinished transfers (for example, Ferry was closed while a file was
  arriving). They are safe to ignore; Ferry deletes them the next time it starts.

Location (GPS) missing from photos:
  The "Photos & videos" picker may remove location for privacy. Use "Any file" >
  DCIM > Camera to send untouched originals.

Desktop is in OneDrive:
  Ferry warns about this: files in the outbox also upload to OneDrive. Online-only
  files (cloud icon) must be made available offline first (right-click > Always keep
  on this device).

Check this PC:  double-click "Ferry self-test.bat". It checks everything Ferry needs and
  saves the results in ferry-selftest.txt.
Check your phone / TV: open http://<the address>:8765/diag and follow DEVICE-CHECKLIST.txt.
If Ferry crashes, details are saved in ferry-error.log in the Ferry folder.

====================================================================================
SECURITY NOTE
====================================================================================
While Ferry is running, anyone on your Wi-Fi can send files to your PC (they land in
Downloads\Phone) and can download whatever is in the Ferry outbox. Nothing else on
your PC is reachable. Only run Ferry on your home network, and close it when done.

====================================================================================
UPDATING AND UNINSTALLING
====================================================================================
Update: extract the new zip over the old Ferry folder (replace files). Your settings
  (ferry-state.json) are kept.
Uninstall: delete the Ferry folder. Your Downloads\Phone and Ferry outbox folders are
  not touched - delete them if you want. Linux: also delete
  ~/.local/share/applications/ferry.desktop. Optional (Windows): remove the "Python"
  rule in Windows Defender Firewall > Allow an app.

====================================================================================
ADVANCED OPTIONS (Command Prompt / terminal)
====================================================================================
  --port N          use another port (default 8765)
  --no-open         never open the outbox folder
  --inbox PATH      save files from the phone in PATH (exactly this folder)
  --outbox PATH     offer the files in PATH to the phone/TV (exactly this folder)
  --selftest        check this PC (same as "Ferry self-test.bat")
  --install-desktop Linux: add Ferry to the app menu
  --version, --help
Example:  Ferry.bat --outbox "D:\To phone"

====================================================================================
CREDITS AND LICENCES
====================================================================================
Ferry is MIT licensed (LICENSE.txt). QR codes are made with the QR Code generator
library by Project Nayuki (MIT, LICENSE-qrcodegen.txt), lightly modified for Python 3.8.
