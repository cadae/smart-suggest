# Public-release review

Reviewed 6 September 2026. This is a focused source/privacy review, not a penetration test
or a certification that every possible secret has been detected.

## Repository privacy

The original reachable history contained 28 commits. Gitleaks detected no credentials;
manual inspection found personal author/committer identities and identifying operational
documentation. Password examples were placeholders or Android's standard debug password.
No signing keystore, private key, credential properties, or database export was tracked.

The local history was rebuilt with neutral contributor identities. Historical Markdown
notes were removed, commit bodies were dropped, and public documentation was added anew.
Code-changing history and commit subjects were retained; documentation-only commits became
empty and were pruned. The resulting main branch has 13 historical commits plus this
cleanup commit. The older local build-toolchain branch was sanitized too. Tool-generated
snapshot refs were removed because they retained original trees.

The application ID `com.lukecao.suggest` is an explicitly retained compatibility exception.
It remains visible in source paths, code, and history. A public repository also exposes its
hosting account; this cleanup is not account anonymity.

The publication input scan checks current tracked/unignored files and every reachable
blob, commit and tag. Gitleaks was run separately over the sanitized history and complete
publication tree with redacted output. Both found zero remaining matches. Ignore rules now
cover signing material, environment files, device databases, logs and compiled artifacts.
Private audit evidence and the original-history backup are stored outside the repository.

**Remote publication is a separate step.** Until rewritten main is force-pushed, the hosting
service still has the old history. Rewriting branch refs does not erase other people's
clones, forks, cached commit pages, or server-retained objects. Follow
[GitHub's sensitive-data removal guidance](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)
for any remaining server-side copies before treating them as purged. At audit time the
remote was private, with one branch and no releases, pull requests or Actions artifacts.

## Code findings addressed

| Priority | Finding | Change |
| --- | --- | --- |
| P1 | Pending notification keys included platform notification tags, which can contain app-supplied identifiers despite the metadata-only privacy description. | Store full SHA-256 digests for inserts, reconciliation and removal. Schema v4 clears legacy pending rows and preserves learned history. Hashing is pseudonymization, not encryption. |
| P2 | Switching notifications off did not unbind a connected listener; switching on requested rebind before saving the new setting. | Save first, then reconcile binding; clear pending rows on disable and recheck the toggle before queued post writes. |
| P2 | The settings list had a status-bar inset but no navigation-bar inset under enforced edge-to-edge layout. | Include the navigation-bar bottom inset in scroll content padding. |

The notification-key format was checked against
[Android's StatusBarNotification implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/service/notification/StatusBarNotification.java),
which includes the app-supplied tag in the key. The lifecycle change follows the
[notification listener binding API](https://developer.android.com/reference/android/service/notification/NotificationListenerService#requestUnbind()).

The review also covered manifest exports, backup/transfer exclusions, database access,
worker cancellation and scheduling, widget launch intents, optional permission gates,
and the debug-only evaluation entry point.

## Validation

- `testDebugUnitTest`: 3 tests passed, covering the standard SHA-256 vector, post/removal
  identity, tag privacy, and distinct tags/users including Unicode.
- `assembleDebug` and `assembleRelease`: passed after the changes.
- `lintRelease`: passed with 0 errors and 25 warnings. Warnings concern newer dependencies,
  Kotlin/style suggestions, the test dependency declaration and cursor checks. The seven
  flagged cursor reads use Kotlin `use` blocks, which close on normal and exceptional exits.
- Built release permissions: no `INTERNET`, location, advertising-ID or billing permission.
  `ACCESS_NETWORK_STATE` remains for WorkManager; it does not grant socket access.
- Sanitized current files and all Git history: Gitleaks and the publication-input scan passed.

## Remaining release work

No Android device was connected during this audit. Verify the notification off/on lifecycle,
v3-to-v4 database migration, widget placement/taps, and navigation insets on a test device.
Unit tests validate the key transformation, not Android binding or database migration.
Use a disposable fixture for migration tests, not a user's only history database.

The ranker's known home-screen baseline gap, repeated-tap feedback, cold-cache concurrency,
and light-only theme remain documented in design.md. They were not changed in this cleanup.
Debug signing is intentional for local builds and unsuitable as a store-release plan.
A repository license has not been selected. Public visibility alone does not grant an
open-source license.
