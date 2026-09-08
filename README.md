# Smart Suggest

An Android home-screen widget that suggests apps from usage patterns and current context.
Ranking runs on the device. There are no ads, analytics, billing, or network requests.

## Getting started

Requires Android 12 or later. Build with JDK 17, Android SDK platform 36 and build-tools
35.0.0. Set `ANDROID_HOME` to your SDK directory, or set `sdk.dir` in the ignored
`local.properties` file. The checked-in Gradle wrapper downloads its pinned distribution
and verifies its SHA-256 checksum; a separate Gradle installation is unnecessary.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open Smart Suggest, grant usage access, and add a widget from the app or your launcher's
widget picker. Available sizes are 2×2, 2×3, 4×2, 4×3 and 4×5. New installations need
usage history before suggestions appear.

Local debug and release builds use the local Android debug signing key. CI releases use
a separate, persistent signing key. The release variant enables R8 and resource shrinking;
it is not configured for store distribution. Updating an installation requires the same key.
Do not uninstall to resolve a signature mismatch: uninstalling destroys the learned history.
The application ID remains `com.lukecao.suggest` for compatibility with existing installs.

## Downloadable APKs

Download the APK from [the latest release](https://github.com/cadae/smart-suggest/releases/latest).
Each successful `Release APK` workflow run publishes an APK and `SHA256SUMS`. The workflow
runs when `main` is updated and can also be started manually from the Actions tab on `main`.
Pending updates may be coalesced while another release is running.

CI APKs use one persistent signing key, so a newer CI release can update an older one.
They cannot update installations signed with a local debug key. Do not uninstall an existing
copy to work around that mismatch: its learned history would be lost.

Builds run unit tests and release lint, verify the APK signature, and reject APKs that are
debuggable or request `INTERNET`. Releases use tags such as `build-1`, version names such as
`1.0.1`, and increasing Android version codes (`1000 + workflow run number`). Reruns retain
the same tag and version. Keep this workflow's run numbering intact; if replacing the
workflow, ensure new version codes exceed every previously distributed build.

The repository needs two Actions secrets: `ANDROID_RELEASE_KEYSTORE_B64` (the base64-encoded
PKCS12 keystore, with alias `release`) and `ANDROID_RELEASE_KEYSTORE_PASSWORD` (the password
for both the store and key). Missing secrets fail the build rather than generating a new
identity. Keep a secure backup outside the repository: losing this key prevents updates to
existing CI installations. Never publish it as an artifact or commit it to Git.

The workflow restores signing material only in the runner's temporary directory and removes
it after building. Configuration caching is disabled for the signing build. Only the
publication job receives repository write permission; the build job has read access.

## How it works

The ranker combines decayed usage with contextual multipliers: time of day, weekday,
recent apps, app sequences, headphones, charging, and feedback from widget taps.
An exploration slot rotates through lower-ranked candidates. A missing signal contributes
a neutral multiplier rather than a penalty.

A WorkManager job refreshes every 15 minutes, subject to Android's background scheduling.
Optional event triggers can refresh sooner. A two-minute gap limits repeated ranking,
while changes to settings and installed apps request a forced, debounced refresh.

Notification and calendar signals are optional and off by default. Location-related code
is retained for experimentation, but location permissions are not declared and the feature
is disabled in this build. See [the design notes](docs/design.md) for the model, scheduling,
storage, and known limitations.

## Privacy

- The application does not declare `INTERNET` and has no network client. This restricts
  direct network access; it is not a guarantee against a compromised device, debugging
  tools, or data shared by other Android components.
- Usage sessions, context samples, taps, and notification metadata live in private app
  storage. Records are pruned during refresh with a nominal 21-day lookback. Android
  scheduling and clock changes can delay pruning.
- Notification metadata consists of the posting package, time, and a SHA-256 digest used
  to match outstanding notifications to removals. Raw platform keys can contain tags,
  so they are not persisted. Digests are pseudonymous identifiers, not encryption.
  Notification titles, text, images, and senders are not inspected.
- Calendar fields are scanned in memory for meeting links. Only derived time windows and
  package names are cached in memory; calendar fields are not written to the database.
- Cloud backup and device transfer are disabled. A new installation starts learning again.
- Disabling an optional signal stops its use. Disabling notifications also requests that
  Android unbind the listener and clears outstanding-notification records. Older post
  timestamps remain until normal pruning.

## Development

```sh
./gradlew testDebugUnitTest assembleDebug assembleRelease lintRelease
python3 scripts/check-public-tree.py
# Install Gitleaks separately, then scan all Git refs:
gitleaks git . --log-opts='--all --full-history' --redact
```

Never commit signing keys, credential properties, SDK paths, database exports, device
logs, or screenshots containing personal content. The ignore rules cover common file
formats, but do not replace reviewing changes before committing.

[Contributor notes](CLAUDE.md) describe implementation constraints.
[Release review](docs/release-review.md) records the current checks and remaining work.
No project license has been selected yet.
