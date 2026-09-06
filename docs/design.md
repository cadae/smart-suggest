# Design notes

These notes describe the implementation without retaining personal device logs or usage
examples. Constants and detailed algorithms live in the source files.

## Ranking

`RankConfig` defines the model parameters. For a candidate app:

```text
score = usageMass^0.6 × product(contextLift^weight) × instantFactors × feedbackFactors
usageMass = sum(2^(-age / 3 days) × (1 + ln(1 + dwellMinutes)))
contextLift = clamp(P(context | app) / P(context), 1/4, 4)^(n / (n + 8))
```

Compressing usage mass lets context reorder frequently used apps. Multipliers may be below
one, so a context mismatch can demote an app. A missing, disabled, universally present, or
universally absent signal is neutral. Only sessions with known context enter that signal's
denominator; an unanswered question is not a negative observation.

`UsageReader` reconstructs foreground sessions from activity events, merges adjacent sessions
of the same package within 30 seconds, and caps duration at four hours. `SessionLog` stores
those sessions because platform retention may be shorter than the 21-day model window.
Sync rewrites the recent tail so an initially open session can acquire its final duration.

Time-of-day matching uses a Gaussian around local clock time. Weekday matching partially
credits the same weekday/weekend type. Headphones and charging compare live device state
with historical context samples. Calendar meetings contribute both a historical context
lift and an immediate boost when an event names an installed meeting app.

Instant terms cover continuity, app-to-app sequences, overdue use, outstanding notifications,
and novelty. Restored catalogs are guarded against treating every app as newly installed.
Feedback uses taps, position-adjusted impressions, and stickiness. The shown-but-not-tapped
factor only demotes; promotion belongs to the tap term, avoiding duplicate credit.

The cooldown is a penalty, not a candidate filter. An app can remain available even when
it was used very recently. Exploration swaps the final visible slot with a rotating member
of the next candidate pool. The demoted app stays in the deeper cache so larger widgets
can still use it. Hidden apps and excluded system apps are filtered before selection.

## Disabled location experiments

`Permissions.LOCATION_DECLARED` is false and the manifest omits location permissions.
The location, WiFi, usual-place and motion terms are retained in source but inactive.
They require an explicit feature and permission decision before being enabled.

When enabled experimentally, uncertain coordinates cannot define a precise place. Location
matching accounts for accuracy; motion requires distance beyond the fixes' error bounds.
A WiFi-known flag distinguishes a confirmed absence of WiFi from an inaccessible identity.
WiFi names are hashed for equality, which is pseudonymization rather than encryption.
Usual-place anchors are dwell-weighted clusters. A shared exponent budget of 1.4 limits
correlated location signals so four observations of the same context do not count as four
independent votes. Context is matched only within a bounded time window.

## Scheduling and cache

`Scheduler` owns one periodic and one immediate WorkManager name. Event requests use KEEP
so a burst cannot continually cancel work. User-visible changes use a delayed REPLACE
request to debounce repeated edits. `RefreshWorker` serializes runs with a process-wide
mutex, then checks the two-minute minimum gap. It rethrows coroutine cancellation and
isolates context sampling, pruning, ranking and widget-update failures.

The periodic interval is 15 minutes. Android may defer it in Doze; the interval is not a
real-time guarantee. Runtime unlock/screen/audio callbacks work only while the process
exists. An enabled notification listener can help keep that process available, but its
binding must follow the notification toggle. Boot and package replacement request a
refresh. Notification arrivals/removals, charging, timezone and unlock events can request
optional event refreshes. A widget tap records feedback without rearranging the grid while
the selected app is opening.

`SuggestionCache` stores a deeper ranking than any one widget needs. Drawing a widget reuses
a nonempty snapshot up to twice the refresh interval and otherwise computes a replacement
without crediting impressions. Only refresh work decides whether to credit impressions,
with forced rapid refreshes and screen-off runs excluded. Screen-on is only an approximation
of whether the home screen is visible.

## Widget layout

Each size has its own Glance widget class and receiver. Existing host bindings depend on
those names. Icons scale with the launcher's available cell width and are rasterized by
the app. Background transparency is explicit; host padding and the widget's own background
are separate. The settings list handles status and navigation bar insets.

`LaunchTrampolineActivity` is not exported. Explicit widget intents select an app and slot;
the trampoline launches the app before asynchronously recording feedback. A failed feedback
write must not crash the launch.

## Storage and optional signals

`Store` is a process-wide SQLiteOpenHelper over six tables: sessions, context samples, taps,
impressions, notification posts and pending notifications. Schema version 4 clears only
legacy pending rows, whose raw keys could include app-provided tags. New pending rows use
full SHA-256 key digests; post and removal lookups apply the same transformation. The
listener rebuilds the pending set on connection. The migration does not erase other
history and is not a forensic erasure guarantee for old filesystem copies.

Notifications and calendar are off by default. Both the app setting and OS access are
checked before using these signals. Notification writes recheck the toggle on the I/O
executor. Save a changed toggle before requesting a rebind, so an immediate connection
sees the new state. Switching off requests an unbind and clears pending rows on the same
executor as notification writes. Calendar event text is processed in memory, with a
bounded historical cache and fresh query for imminent events.

Pruning uses the newest stored observation to guard against a transient incorrect future
clock. This protects history but means retention is not an absolute wall-clock deadline.
Database exports and debug replay reports contain personal behavior and belong outside
the repository. Backup and transfer exclusions are intentional.

## Evaluation and known limits

`Replay` is compiled behind `BuildConfig.DEBUG`; release shrinking should remove it.
It evaluates chronological predictions against later app launches and reports Hit@1,
Hit@4, Hit@8, reciprocal rank, reachability, baselines and term ablations. Launcher-preceded
moments must be reported separately: aggregate app switching is a different situation
from selecting an app on the home screen.

- The existing evaluation identified a home-screen recency baseline outperforming the
  full model. This remains unresolved. Do not raise continuity weights without replay.
- Daily impression buckets can expose later-in-the-day information in historical replay.
  Notification dismissals, historical launcher visibility, and some live signals cannot
  be reconstructed exactly. Treat replay as an approximation.
- Repeated taps in one sitting are not deduplicated. Feedback can overcredit rapid taps.
- New or unused apps have little evidence. Exploration and novelty only partly address it.
- Context coverage depends on how long sampling has run; missing coverage cannot be fixed
  by attributing distant samples to unrelated sessions.
- Cold widget computations are outside the worker's mutex. Concurrent callers can update
  the shared snapshot independently. The two-minute limit covers worker runs only.
- The settings screen currently uses a light Material theme in dark mode too.
- Release builds use debug signing for local testing. Public source availability does not
  imply that an APK is prepared for store distribution.
