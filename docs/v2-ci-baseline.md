# CI baseline findings (session 2)

- Android smoke build on GitHub Actions: **success** (AGP 8.7.3, Kotlin 2.0.21, Gradle 8.14.3, compileSdk 35, Compose BOM 2024.12.01, Media3 1.5.1). Dependency download works on the runner.
- v1 PC tests (103) pass on Linux 3.13 locally and in CI. **First ever Windows run: 6 failures, all test-side** (to fix in Stage 6):
  1. `test_second_bind_refused_and_probe` uses `exclusive=False`; Windows lets a second SO_REUSEADDR socket bind. Production uses SO_EXCLUSIVEADDRUSE (checked by `--selftest`). → use exclusive=IS_WIN in test.
  2. `test_permissions_follow_umask` — POSIX modes; skip on Windows.
  3. `test_undecodable_name_is_not_offered` — Windows can't create undecodable names; skip on Windows.
  4. `test_resolve_folders_linux` — bytes path decoding on Windows; skip on Windows.
  5. `test_launchers_static` — git checked out `ferry.sh` with CRLF → add `.gitattributes` (`*.sh text eol=lf`, `*.bat text eol=crlf`).
  6. `test_main_starts_and_stops_quickly` — on Windows folders resolve via SHGetKnownFolderPath, not HOME → assert on the resolved config instead / monkeypatch `win_known_folder`.
- `ubuntu-latest` (26.04) has no Python 3.8 → run 3.8 on `ubuntu-22.04`.
- Windows CI also showed the "network is Public" warning path working.
