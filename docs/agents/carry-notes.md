# Coordinator carry-over notes for later phases
## For Ph10 (wording / screenshot tour)
- TV "not found" message says "this phone" on the TV (Ph4).
- Toast "Sending 1 files" — singular (Ph5b).
- Location-choice dialog is wired but no instrumented test drives it; include it in the screenshot tour (Ph5b).
- New strings from B46/B53 need the wording pass.
## For Ph7
- Storage failure mid-download is treated as a dropped connection (3 tries → connection_kept_dropping); free-space check before each download is the guard (Ph3a).
- Update banner tap is a no-op until Ph7 (Ph4).
## For Ph8a
- TV focus memory (Ph4).
- TV APK cards + install dialog: APK folder files/apk and the install flow (ApkInstaller) are ready (Ph7, B59).
- Picker + permission strings (Ph6) need the wording pass; system permission dialogs belong in the screenshot tour (Ph6).
## For Ph9
- Update banner 'busy' rule must also count casting (Ph7).
- Picker's two cast modes exist but Play on TV button shows "Coming in this build" (Ph6).
## CI reliability (any phase touching run_instrumented.sh; else Stage 7)
- Ph6: on main run 63 the 2nd (cold-boot) phone attempt had no Wi-Fi route to 10.0.2.2 → every class "PC not found". Fix idea: wait for a Wi-Fi route before the first class. Also an earlier run's 2nd attempt failed ScreenOffUploadTest/CancelAndWaitingTest (1st attempt passed).
- CI: Ph7 added a Wi-Fi-route wait + dismissal of system/ANR dialogs in run_instrumented.sh.
- Ph10 wording: B60 strings.
## Added after Ph8a
- Ph8b: the temporary player screen (B68) is to be replaced; Photo and Slideshow cards show "Coming in this build".
- Ph9: a cast must keep the TV screen on (Ph8a keeps it on for 30 min after a key press and while the player is showing).
- Ph10: on the TV, the "Allow Ferry to install apps" dialog focuses Cancel first; `tv_storage_full` (B71) needs the wording pass. B72: on Fire OS 8, allowing unknown-app installs kills Ferry's process; the README should say "press OK on the APK again".
- CI flake: FromPcDownloadTest failed once on attempt 1 (10 s text wait); the build log has a suggested fix if it recurs.
## Added after Ph8b
- Ph9: player and viewer keep the TV screen on while showing; a cast must too. Update banner busy rule must count casting.
- Ph10: wording pass for B75 strings (`cant_play`, `resume_from`, `start_over`) and "Can't show this photo"; screenshot tour's "paused" shot of small.mp4 is at the very end — pause mid-video instead.
- Tests: the test tool's Back press can take several seconds during playback; read positions from what the app recorded, not from before the key press.
