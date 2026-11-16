FERRY 2
Move photos, videos and files between your phone, your Fire TV and this PC over your
home Wi-Fi.
  - Phone -> PC: photos, videos and any other file. Originals are kept (name, date and
    photo location).
  - PC -> phone and Fire TV: anything you put in the "Ferry outbox" folder on the PC.
  - Phone -> Fire TV: play a video or a photo slideshow from the phone on the TV
    (the PC can be off for this).
Ferry works on your home Wi-Fi only. It has no passwords on purpose (see the Security
note at the end). Ferry for the PC needs Windows 10 or 11.

What is in this folder:
  Ferry.bat              starts Ferry on the PC (double-click it)
  app\Ferry.apk          the Ferry app for your phone and Fire TV
  Ferry self-test.bat    checks this PC if something doesn't work
  README.txt             this guide
  DEVICE-CHECKLIST.txt   a list of things to try on your phone, TV and PC

====================================================================================
1. START FERRY ON THE PC (once)
====================================================================================
1. Before you unzip: right-click the Ferry zip > Properties > tick "Unblock" > OK.
   (Skipping this makes Windows show a security warning every time.)
2. Right-click the zip > Extract All... and choose a folder, e.g. Documents\Ferry.
3. Python 3.8 or newer must be installed. If Ferry says it isn't, install it from
   https://www.python.org/downloads/ and tick "Add python.exe to PATH".
4. Double-click Ferry.bat. A black window opens with a QR code.
5. The first time, Windows Firewall asks about "Python". Tick "Private networks" and
   click "Allow access". (Missed it? See Troubleshooting.)

Keep the Ferry window open while you use Ferry. Close it (or press Ctrl+C) to stop.
(After Ctrl+C, Windows may ask "Terminate batch job (Y/N)?" - just press Y.)

Where files go on the PC:
  From the phone:     Downloads\Phone
  To phone and TV:    put files in the "Ferry outbox" folder on your Desktop

====================================================================================
2. INSTALL THE APP ON YOUR PHONE (once)
====================================================================================
1. Start Ferry on the PC (section 1). The phone and the PC must be on the same Wi-Fi.
2. On the phone, open the camera and point it at the QR code in the Ferry window.
   Tap the link. (Or type the "App page" address shown in the Ferry window into
   Chrome, e.g. http://192.168.1.5:8765/app)
3. Tap "Download Ferry for Android". If Chrome warns about the file, tap
   "Download anyway".
4. Open the downloaded Ferry.apk (tap "Open" in Chrome's message, or find it in
   Files > Downloads).
5. If the phone says Chrome isn't allowed to install apps: tap Settings, turn on
   "Allow from this source", then press Back and tap Install.
   The phone may also run a security scan or ask for your vivo account password.
   That is normal for apps that don't come from a store: continue and install.
6. Open Ferry. The welcome screen explains Ferry. Tap "Allow" on the battery card and
   then "Allow" in Android's question, so vivo doesn't stop Ferry in the middle of a
   big transfer. Then tap Continue.
7. Ferry finds the PC by itself. At the top you see "Connected to <your PC>".
8. Do the vivo battery steps in section 4 once.

====================================================================================
3. INSTALL THE APP ON THE FIRE TV (once)
====================================================================================
Choose way A (easiest, no cables). Use way B only if way A doesn't work.
After this first install the Fire TV gets new versions of Ferry from the PC by itself.

WAY A: WITH THE "DOWNLOADER" APP
1. On the Fire TV, open the Appstore (or search with the magnifying glass), find
   "Downloader" (orange icon, by AFTVnews) and install it.
2. Allow Downloader to install apps:
   Fire OS 7 or 8 (most Fire TVs):
     Settings > My Fire TV > Developer options > Install unknown apps >
     Downloader: ON
     No "Developer options"? Settings > My Fire TV > About > select your Fire TV's
     name (the first line) and press OK 7 times, until it says you are a developer.
     Then press Back and Developer options is there.
   Fire OS 6 (older sticks):
     Settings > My Fire TV > Developer options > Apps from Unknown Sources: ON
3. Start Ferry on the PC. The Ferry window shows a line like
     Fire TV:   in Downloader enter 192.168.1.5:8765/app
4. Open Downloader. In the address box type exactly that address, e.g.
     192.168.1.5:8765/app
   and press Go. Downloader downloads Ferry.
5. Press Install, then Open (or Done). Downloader may offer to delete the downloaded
   file: press Delete (Ferry is installed, the file is not needed any more).
6. Ferry opens on the TV and finds the PC by itself. Tip: in "Your Apps & Channels",
   move Ferry to the front so it's one press away.

WAY B: FROM THE PC WITH ADB (only if way A doesn't work)
1. On the Fire TV: Settings > My Fire TV > Developer options > ADB debugging: ON
   (no Developer options? see way A, step 2).
2. Find the TV's address: Settings > My Fire TV > About > Network > IP Address,
   e.g. 192.168.1.30.
3. On the PC, download "SDK Platform-Tools for Windows" from
   https://developer.android.com/tools/releases/platform-tools
   and unzip it, e.g. to C:\platform-tools.
4. Open the C:\platform-tools folder, click the address bar, type cmd and press
   Enter. A Command Prompt opens there. Type (with your TV's address):
     adb connect 192.168.1.30
5. The TV asks "Allow USB debugging?". Tick "Always allow from this computer" and
   press OK. Then type (with the folder where you unzipped Ferry):
     adb install "C:\Users\<you>\Documents\Ferry\app\Ferry.apk"
   It says "Success". Ferry is now in the TV's apps.
6. You can turn ADB debugging off again afterwards.

====================================================================================
4. VIVO BATTERY SETTINGS (once, important)
====================================================================================
vivo phones stop apps in the background to save battery. That can stop a big
transfer when the screen turns off. Do these two steps once:
1. Open Recents and lock Ferry (pull the Ferry card down or tap the lock).
   (Recents = the list of open apps: swipe up from the bottom and hold.)
2. Settings > Battery > Background power consumption > Ferry > Allow.
The same steps are in Ferry > Settings > "Still stopping?". If a transfer was stopped,
Ferry tells you the next time you open it.

====================================================================================
5. EVERYDAY USE
====================================================================================
ON THE PHONE
  Send photos & videos   Pick photos and videos (Recent or Albums; your picks are kept
                         when you switch albums), then tap Send. The first time,
                         Android asks for permission: choose "Allow all".
  Share > Ferry          In Gallery, Files or any app: select, tap Share, choose Ferry.
  Send other files       Documents, APKs, anything: pick them in Android's file chooser.
                         (For photos use Send photos & videos: it keeps the location.)
  From PC                Files from the PC's "Ferry outbox". Tap a file to save it in
                         Download/Ferry on the phone, or tap "Download all". Then
                         "Open" (or "Install" for apps).
  Play on TV             Open Ferry on the TV first. Then choose Videos or Photo
                         slideshow, pick, and tap Play on TV. The Remote screen has
                         play/pause, next, -10 s / +10 s, seconds per photo and Stop.
                         The phone's screen may go off while it plays.
Sending carries on with the screen off. You see progress and the speed in the
notification. Files that are already on the PC are not sent again ("Already on PC").
The PC never overwrites a file: a different file with the same name is saved as
"name (1).jpg". If the PC goes away, Ferry waits and carries on when it is back.

ON THE FIRE TV (with the remote)
  Videos        OK plays. Left/Right: back/forward 10 s. Rewind/Fast-forward keys:
                30 s. Play/Pause key or OK: pause. Up: show the time bar. Back: stop.
                If you stopped part-way, Ferry offers "Resume from ..." next time.
  Photos        "Slideshow" shows all photos, a new one every 5 seconds. Left/Right:
                previous/next. OK: pause. Or press OK on one photo to look at it.
  Apps          OK on an app (.apk) installs it on the TV.
  Other files   Shown for information; open these on your phone or PC.
New files in the outbox appear on the TV by themselves within a few seconds.
To play something from the phone, leave Ferry open on its home screen on the TV.

====================================================================================
6. UPDATING FERRY
====================================================================================
1. Close the Ferry window on the PC.
2. Unzip the new Ferry zip into the same folder as before and replace the files.
   (Your settings are kept.)
3. Start Ferry.bat again.
4. The next time you open Ferry on the phone and on the TV, it says
   "New Ferry 2.0.x is ready -> Update". Tap it (on the TV press OK), then Install.
   The first time, allow Ferry to install apps: Ferry opens the setting for you.
   On some Fire TVs (Fire OS 8) Ferry restarts after you allow this. Then simply
   press OK on Update (or on the app) once more.

====================================================================================
7. TROUBLESHOOTING
====================================================================================
"PC not found" (phone or TV):
  - Is the Ferry window open on the PC?
  - Are the phone/TV and the PC on the SAME Wi-Fi? Guest networks block this.
  - Windows must treat your Wi-Fi as Private: Settings > Network & Internet > Wi-Fi >
    (your network) > Network profile: Private. The Ferry window warns if it's Public.
  - Firewall: Windows Security > Firewall & network protection > Allow an app
    through firewall > make sure "Python" is ticked for Private. Ferry uses port 8765
    (TCP for files, UDP for finding the PC). If you made your own firewall rule for
    TCP 8765 only, add UDP 8765 too.
  - Some routers have "AP isolation" or "client isolation" on: turn it off.
  - Still not found? Type the PC's address once: on the phone Ferry > Settings >
    PC address; on the TV press "Enter address". The address is the one in the Ferry
    window, e.g. 192.168.1.5 (without http:// and without :8765). Ferry remembers it.

"Your PC has an older Ferry":
  The PC runs an old Ferry. Unzip the new Ferry zip on the PC (section 6) and start
  Ferry.bat again.

The phone can't install the app ("blocked" / "not allowed"):
  Allow the app that opens the APK (Chrome, Files or Ferry) to install apps:
  Settings > Apps > (that app) > Install unknown apps > Allow. Then try again.

The Fire TV can't install ("unknown sources" / "not allowed"):
  See section 3, way A, step 2. For updates and apps from the outbox, Ferry needs the
  same switch: Settings > My Fire TV > Developer options > Install unknown apps >
  Ferry: ON (Fire OS 6: Apps from Unknown Sources: ON).

"This TV can't play this video format" / "...can't play this video's sound":
  The Fire TV can't decode that video or its sound (shown in brackets, e.g. DTS).
  Convert the video on the PC (e.g. with HandBrake, preset "Fast 1080p30"), or play
  it on the PC.

"Can't show this photo":
  The photo is damaged or in a format this TV can't show (older Fire TVs can't show
  HEIC photos). The slideshow moves on to the next photo.

Transfers stop when the phone screen is off:
  Do the vivo battery steps (section 4).

"Nothing found" when you tap Play on TV:
  On the TV, open Ferry and leave it on its home screen, then tap "Find again".
  The phone and the TV must be on the same Wi-Fi.

Where are my files?
  On the PC:       Downloads\Phone (from the phone)
  On the phone:    Files > Downloads > Ferry (from the PC); photos and videos also
                   appear in the Gallery.
  To the phone/TV: Desktop\Ferry outbox on the PC. Files stay there until you delete
                   them. Folders are not offered: put the files in directly, or zip
                   the folder.

Odd files named ".ferry-....part" in Downloads\Phone:
  Unfinished transfers (e.g. Ferry was closed while a file was arriving). Safe to
  ignore; Ferry deletes them the next time it starts.

Desktop is in OneDrive:
  Ferry warns about this: files in the outbox also upload to OneDrive. Online-only
  files (cloud icon) must be made available offline first (right-click > Always keep
  on this device).

"Port 8765 is used by another program":
  Start Ferry on another port: open a Command Prompt in the Ferry folder and run
      Ferry.bat --port 8800
  The app still finds the PC by itself. If it doesn't, type the address with the
  new port once (e.g. 192.168.1.5:8800 in Ferry > Settings on the phone).

Logs (useful when you ask for help):
  - ferry-log.txt in the Ferry folder on the PC lists every transfer and request,
    with times and errors (ferry-log.1.txt is the older part).
  - On the phone: Ferry > Settings > Share diagnostics log.
  - Check this PC: double-click "Ferry self-test.bat". It saves ferry-selftest.txt.
  - If Ferry on the PC crashes, details are saved in ferry-error.log.

====================================================================================
8. KEEP THE SAME PC ADDRESS (recommended)
====================================================================================
Your router gives the PC its address (like 192.168.1.5). Ferry finds the PC even
when that address changes. But if you ever type the address by hand (Settings,
Downloader), a fixed address saves trouble later. Reserve it once:
  1. Open your router's settings page (often http://192.168.1.1 or
     http://192.168.0.1; the address and password are usually on a sticker on the
     router).
  2. Find "DHCP reservation", "Address reservation", "Static lease" or "Fixed IP"
     (often under LAN, DHCP or Advanced).
  3. Pick this PC from the list and save. Restart the PC.

====================================================================================
9. SECURITY NOTE
====================================================================================
While Ferry runs on the PC, anyone on your Wi-Fi can send files to your PC (they land
in Downloads\Phone) and can download what is in the Ferry outbox. While Ferry is open
on the TV, anyone on your Wi-Fi can play videos or photos on it. Nothing else on your
PC, phone or TV is reachable. Only use Ferry on your home network.

====================================================================================
10. ADVANCED OPTIONS (Command Prompt in the Ferry folder)
====================================================================================
  --port N          use another port (default 8765)
  --no-open         never open the outbox folder
  --inbox PATH      save files from the phone in PATH (exactly this folder)
  --outbox PATH     offer the files in PATH to the phone and TV (exactly this folder)
  --selftest        check this PC (same as "Ferry self-test.bat")
  --version, --help
Example:  Ferry.bat --outbox "D:\To phone"

Uninstall: delete the Ferry folder on the PC, and uninstall the Ferry app on the
phone and TV. Your Downloads\Phone and Ferry outbox folders are not touched.

====================================================================================
11. CREDITS AND LICENCES
====================================================================================
Ferry is MIT licensed (LICENSE.txt). QR codes are made with the QR Code generator
library by Project Nayuki (MIT, LICENSE-qrcodegen.txt), lightly modified for Python 3.8.
