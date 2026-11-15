# Ferry v2: phase progress (plan §0.1)

Phase · step · commit · run id · what is left
Ph0a · tests portable + .gitattributes + pc.yml matrix · d343705 · pc 43690282909 green (4/4) · fast-forward main
Ph0a · main fast-forwarded · b5b3463 · main pc 43690413274 + android 43690413300 green · DONE
Ph0b · app-build + phone + api25 green, tv probes pass, tv screenshot pull failed · b54d39d · android 43692232469 · fix TV pull, try gh workflow run, merge
Ph0b · all four android jobs green on attempt 1 · 198449b · android 43699613247 · fast-forward main
Ph0b · main run 43700532978 red (api25 snapshot hang) · 64bc35e · fixing on branch (B11)
Ph0b · all green on attempt 1 after B11/B12 · 7eadd7c · android 43704071449 · fast-forward main again
Ph0b · main fast-forwarded · 6f16f1e · main android 43704843235 green (all 4, attempt 1) · DONE
Ph1 · ferry.py 2.0.0 + all §2.13/§2.14 tests, pc 4/4 + app-build green · 9931a4e · pc 43715028378, android 43715028407 · [emu] run on branch, then fast-forward main
Ph1 · emulator suites green against ferry.py 2.0 (dispatched) · d7ec0a9 · android 43715299033 · fast-forward main
Ph1 · main fast-forwarded · c3b4bf9 · main pc 43716087530 + android 43716087462 green (all 4 jobs) · DONE
Ph2a · :core foundation green (core-jvm ubuntu+windows 32/32, app-build, emu api25); scan acceptance seen red · a2c0799 · android 43720112113 · emulators=all on branch, then fast-forward main
Ph2a · all emulator suites green on branch (dispatched) · 2edae8f · android 43720897064 · fast-forward main
Ph2a · main fast-forwarded · aa1e70c · main android 43721978778 green (all 6 jobs, attempt 1) · DONE
Ph2b · engine + unit + integration tests green (core-jvm ×2, app-build, emulator-api25) · 015fb2a · android 43725217915 · look-ahead red check, merge
Ph2b · look-ahead-off probe red as required (growingFileIsNeverSavedTruncated) · 470d4f2 · android 43725763301 · reverted in 1666a2d
Ph2b · all jobs incl. three emulator suites green · 1666a2d · android 43726151903 · fast-forward main
Ph2b · main fast-forwarded · a8913d3 · main android 43727118110 green (all 6 jobs, attempt 1) · DONE
Ph3a · server, downloads, update, log; all jobs incl. three emulator suites green · 7f57112 · android 43740946058 · fast-forward main
Ph3a · main fast-forwarded · 1005e37 · main android 43742299970 green (all 6 jobs) · DONE
Ph3b · discovery + cast + tests green (core-jvm ×2, app-build, emulator-api25) · 86794a7 · android 43744835236 · all-emulator run, fast-forward main
Ph3b · main fast-forwarded · d0da3d5 · main android 43747308064 green (all 6 jobs) · DONE
Ph4 · app skeleton, connection, settings, manifest/APK checks, package + dist jobs; all jobs incl. three emulator suites green · ea01b1f · android 43754052469 · fast-forward main, check package + dist
Ph4 · main fast-forwarded · 7c9f876 · main android 43755657517 green (all 8 jobs incl. package + dist; Ferry-2.0.48 on branch dist) · DONE
