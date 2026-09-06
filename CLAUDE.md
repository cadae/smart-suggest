# Contributor notes

Smart Suggest is one Android application module using Kotlin, Compose, Glance,
WorkManager, and SQLite. Read README.md, docs/design.md and docs/release-review.md first.

## Build and verify

Use the Gradle wrapper with JDK 17, SDK platform 36 and build-tools 35.0.0:

```sh
./gradlew testDebugUnitTest assembleDebug assembleRelease lintRelease
```

Dependency versions live in gradle/libs.versions.toml. Do not upgrade the Android Gradle
Plugin or SDK as an incidental cleanup. Check the merged APK permissions after dependency
changes; INTERNET must remain absent unless the privacy architecture is explicitly changed.

## Preserve existing installations

Do not run adb uninstall, clear app data, or replace signing keys on a user's installation.
Use adb install -r with the same key. Backups and device transfers are disabled, so removing
app data destroys learned history. Both build variants deliberately use local debug signing
for compatibility. Store distribution requires a separate signing decision.

Keep the application ID and widget-provider class names stable. Existing widgets and
WorkManager jobs refer to those names. Database upgrades must retain sessions and taps.

## Changes

Explain non-obvious behavior in comments. Missing evidence must remain unknown rather than
be treated as a negative observation. Preserve coroutine cancellation. Replay is debug-only;
use it to assess model changes and report launcher-preceded moments separately.

Do not commit private diagnostic reports, device data, credentials, or personal machine
paths. Use synthetic fixtures in tests. Commit identities should use a public pseudonym
and a privacy-preserving email address. Run scripts/check-public-tree.py and Gitleaks before
publication. Do not claim device behavior was verified merely because a build passed.
