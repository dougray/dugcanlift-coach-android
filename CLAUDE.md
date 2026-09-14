# Coach — Android

Native Android app (Jetpack Compose) for a coach to import a lifter's shared
link and review their training. Local to the device; no backend of its own —
matches Coach iOS's own "no backend, no accounts" design exactly.

## Build and test

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --rerun
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

The `JAVA_HOME` prefix is required — this machine's default `java` is not the
one Android Studio's Gradle plugin expects, and the build fails with a
confusing toolchain error without it. `--rerun` on the test task is worth
using whenever you've touched timing-sensitive code (e.g. `RosterLoader`):
Gradle will happily report `UP-TO-DATE` for a test class it thinks it already
ran, which hides a flake instead of surfacing it. Always run these in the
foreground — nothing here is safe to background, and a 15-minute timeout is
routine for a from-scratch build.

## CI

`.github/workflows/ci.yml` runs the same unit tests, `lintDebug`, and
`assembleDebug` on GitHub-hosted `ubuntu-latest` for every PR and push to
`main`; the `main` ruleset requires that check to pass before a PR merges.
Never run CI or a runner on this Mac. Actions are pinned by commit SHA and
Dependabot bumps them weekly; the exact kit pin is deliberately excluded from
Dependabot.

There is no Compose UI test harness in this repo. Coordination and business
logic that would otherwise only be reachable from a `@Composable` gets
extracted into a plain Kotlin class so it can be unit tested directly — see
`RosterLoader` below for the pattern.

## Shared code lives in dugcanlift-kit-android

The share-link wire codec, local day keys, and the DUGCANLIFT palette live in
`dugcanlift-kit-android` (module `:liftcore`, package `com.dugcanlift.kit`) —
the same code LIFT Android, the iOS build, and the coach site's link decoder
depend on. A change there reaches every consumer, so it stays boring and
covered by the kit's own tests.

- `DayKey` — local `yyyy-MM-dd` day keys and `daysBetween` arithmetic, used by
  `Client.daysSinceLastLoggedDay`.
- `DclPalette` — the ARGB constants `ui/theme/Color.kt`'s `DclBg`/`DclAccent`/…
  wrap. Theme colors always come from here, never from fresh hex literals.
- `ShareLinkCodec` / `ShareDay` / `ShareFood` / … — the SHARE-FORMAT link
  decoder. See "The wire format is a contract" below before touching anything
  that reads a `Share*` type.

The app depends on it as `com.github.dougray:dugcanlift-kit-android`, pinned
to an **exact release tag** in `app/build.gradle.kts` — never a branch, never
`SNAPSHOT`, never a version range — resolved through JitPack. A range or a
branch dependency means Coach's behavior can change on a rebuild with no
commit in this repo to point at, which is exactly the failure mode exact
pins exist to rule out.

For local development against a kit checkout instead of the published tag,
put

```
kitPath=../dugcanlift-kit-android
```

in `local.properties` (gitignored, not committed) — path is relative to this
Gradle root. `settings.gradle.kts` turns that into an `includeBuild` with a
dependency substitution, so the local `:liftcore` project wins over the
JitPack artifact with no network resolution. Remove the line (or delete the
file) to go back to building against the pinned tag.

## The wire format is a contract with three independent encoders

`ShareLinkCodec` decodes SHARE-FORMAT links, but Coach does not control who
*produces* them: LIFT Android, LIFT iOS, and the LIFT web app (the PWA) each
ship their own encoder, written independently, and Coach must decode all
three correctly. When behavior here looks wrong, check whether it's actually
correct for two of the three encoders and merely surprising for the third
before "fixing" it — see `ShareLinkImporter.kt`'s comment for the concrete
case this bit us.

**Per-serving on the wire, as-eaten in the store — the easiest thing here to
get wrong.** `ShareFood` carries per-serving macros plus a `servings` count.
`ShareLinkImporter.toDay` multiplies on decode: `f.calories * f.servings`,
and likewise for protein/fat/carbs/fiber. `ClientFoodEntry.calories` etc. are
already-multiplied, as-eaten totals — that's what the rest of the app (Stats,
the fuel charts) assumes. LIFT iOS's encoder always sends `servings: 1` with
already-multiplied totals baked in, so multiplying is a no-op for its
payloads and the bug hides completely if you only test against an iOS-shared
link. LIFT Android's encoder sends true per-serving macros and a real
servings count, so skipping the multiply (or multiplying twice, e.g. if a
future refactor moves the multiply into a display layer that also
multiplies) is silently wrong only for Android-shared links. Test against
both.

## Replace-the-day merge rule

`ShareLinkImporter.import` finds-or-creates the client by id, the incoming
goal (if any) replaces the stored one, and **each day in the wire payload
replaces the stored day with the same `dayKey` wholesale** — it does not
merge fields within a day. Days already on the client that the incoming
payload doesn't mention are left untouched. This is Coach iOS's rule,
matched rule for rule; don't "improve" it into a field-level merge without
checking iOS first, since a coach moving between Connect-synced phone and
Android backups depends on both sides agreeing on what an import does.

## App Links depend on the site

The `VIEW` intent filter in `AndroidManifest.xml` has `android:autoVerify=
"true"` for `https://www.dugcanlift.com/coach/#...`, which is what lets a
tapped link open Coach directly instead of a disambiguation sheet. That
verification depends entirely on `.well-known/assetlinks.json` being served,
correctly, by the live site (`dugcanlift-site`) — it is not something this
repo controls or can test in isolation. If tap-to-import silently falls back
to "open in Chrome" on a real device, check the site's `assetlinks.json`
before assuming the manifest or the intent filter is broken. The share-sheet
target (`SEND` / `text/plain`) has no such dependency and always works.

## The backup file is Coach iOS's file

`BackupCodec` reads and writes the same v2 backup file Coach iOS's own
Connect screen produces (`coach-ios/Sources/Shared/BackupCodec.swift`), so a
coach can move between phone and Android without losing anything. Two things
about it are easy to get backwards:

- **`lastImportedAt` is seconds since the Foundation reference date
  (2001-01-01), not Unix epoch seconds.** `FOUNDATION_EPOCH_OFFSET_SECONDS`
  (978,307,200) is added on read and subtracted on write. Getting the sign
  backwards shifts every client's import date by 31 years and the resulting
  number still looks like a plausible timestamp — it doesn't crash, it just
  quietly lies. Both directions are pinned by `BackupCodecTest`.
- **Every top-level key this codec does not model is carried opaquely** —
  `recipes`, `meals`, `routines`, `sessions`, and anything a newer Coach iOS
  file adds. Preservation is by *exclusion* (everything but `v` and `clients`),
  not by an enumerated list: a list only protects the keys someone remembered
  to add to it, and a `programs` array from a future iOS build would be read,
  dropped, and then written away permanently by the next Save Backup.
  `PreservedLibraryStore` merges over the union of both sides' keys for the
  same reason. `v` is still written as 2 — echoing back an unknown version
  would claim a compatibility this codec does not have.
- **The preserved-library cache is written atomically and its corruption is
  visible.** `PreservedLibraryStore.load` *throws* on an unreadable cache
  rather than returning null, because "no library" and "the library is on disk
  but unreadable" have opposite correct responses: the first exports a file
  with no library keys, the second must refuse to export at all. That file
  holds the one thing this app cannot regenerate.
- **The four library arrays are the concrete case today.**
  Coach Android has no models for Cook, Train, or session logs yet — this is
  a coach's iOS-only data. `BackupCodec.restore` preserves whichever
  unmodelled top-level keys are present as raw, undecoded `JSONObject`/
  `JSONArray` values (`RestoreResult.preservedLibrary`), and `export` writes
  them straight back out unchanged. **Never parse them into a model, and
  never drop them** — a v1 backup file (no library at all) must round-trip
  to "no library keys," not "library deleted," and a v2 file's library must
  survive an Android round trip byte-for-byte or a coach's recipes and
  routines vanish the next time they restore on iOS. Clients themselves
  restore by full **replace** (`ClientRepository.replaceAll`) — deliberately
  inconsistent with the library's carry-through, matching iOS's own split
  between "the roster is replaced" and "the library is never destroyed by an
  older file."

## Estimated one-rep max has no rep cap

`Stats.e1rm` is Epley (`weightLb * (1 + reps / 30.0)`) with **no ceiling on
`reps`** — a 20+ rep set still produces an estimate. This is deliberate
(Doug, 2026-09-13) and matches Coach iOS's behavior exactly. The web app
(`coach/app.js`) currently caps at 12 reps and disagrees with both native
apps; that's a known, filed discrepancy in the web app, not something to
"fix" by adding a cap here. If the web app is ever reconciled, it needs to
move toward native's no-cap behavior, not the other way around.

## Charts are hand-drawn, on purpose

`ui/charts/BarChart.kt` and `ui/charts/LineChart.kt` draw directly on a
Compose `Canvas` rather than pulling in a charting library. This is a
choice, not a placeholder waiting to be replaced — the charts are simple
(bars, a line, no zoom/pan/legend interaction) and a dependency buys nothing
here but bundle size and an API to track. Don't reach for a charting library
without a concrete need the Canvas approach can't meet.

## No Google Play Services

Hard policy for every app in this family: no GMS, no Firebase, no Play
Services dependency of any kind, ever.

## Data model and storage

`data/Models.kt` defines `Goal`, `ExerciseSet`, `ClientFoodEntry`,
`TrainingDay`, and `Client` — plain data classes with `toJson()`/`fromJson()`
companions over `org.json`, field names matching the Kotlin property names.

**A `dayKey` is validated where it enters the app** (`requireDayKey`). A key
`ISO_LOCAL_DATE` rejects — `"2026-9-3"`, `""` — parses fine as JSON and then
throws out of `Client.daysSinceLastLoggedDay` and `Stats.weeklyBuckets`, both
of which run inside composition; since the roster is the only route to Connect,
that is an unrecoverable crash loop. Those two functions are also non-throwing
now, as a second line of defence, and `Roster.buildViewState` guards each
client separately so one bad client costs a label, not the roster.

`data/ClientRepository.kt` stores one client per file at
`clients/<id>.json`, written atomically (`.tmp` then `Files.move`, via
`data/AtomicFile.kt`) so saving one client never touches another's file and a
crash mid-write never leaves a half-written file behind. Three rules there are
load-bearing:

- **A corrupt file is skipped, never destroyed.** `all()` returns every other
  client rather than crashing the roster, but `load()` also reports what it
  could not read, and anything that *writes* the roster out must use `load()`:
  an export sourced from `all()` silently omits the damaged client, and the
  next restore then deletes it for good. `replaceAll` moves a file it cannot
  decode into `<root>/unreadable/` instead of deleting it.
- **`replaceAll` is transactional.** Every client is serialised and staged to a
  `.new` file before anything is moved into place, so a failure (full disk, a
  value that will not serialise) leaves the previous roster completely intact.
  It used to delete everything first and save in a loop, which turned a failure
  on client four of ten into seven destroyed clients under a message blaming
  the *file*.
- **The id is not trusted as a file name.** `c.i` comes off an untrusted link
  with no charset constraint. An id that is not `[A-Za-z0-9_-]{1,64}` is hashed
  to a single-segment name (`~<sha256>.json`); ids that already match keep
  their existing `<id>.json`, so nothing stored by an earlier build moves. The
  nav route encodes it too — see `Routes.encodeClientId`.

`Client.daysSinceLastLoggedDay(today)` counts from the most recent day that
was actually **logged** — has sets, food totals, or food entries — not
merely imported; `lastImportedAtEpochMs` plays no part in it.

## Roster loads are serialized through `RosterLoader`

`RosterScreen` has two places that reload the roster off the main thread:
the initial `LaunchedEffect(Unit)` and the post-import reload after a
tap-to-import or a pasted link. Both used to write `clients` directly, and
on a cold launch through an App Link or the share sheet those two loads
start at effectively the same time with no ordering between their
`Dispatchers.IO` reads — whichever finished last won, which could silently
overwrite a just-imported client with the pre-import roster while the
snackbar still reported success.

`data/RosterLoader.kt` fixes this: every call to `refresh()` claims a
strictly increasing generation number before doing the actual load, and a
load's result is only applied if no higher generation has already been
applied. A load issued first can still finish last — it just loses instead
of winning. Both `RosterScreen` call sites go through the same `RosterLoader`
instance now. If you add a third place that reloads the roster, route it
through the same loader rather than writing `clients` directly, or the same
class of bug comes back.

## Backup and restore live in `BackupService`, not in Connect

`ConnectScreen`'s two file-picker callbacks do nothing but launch
`data/BackupService.kt` and render its `BackupOutcome`. Everything else — the
roster read, the JSON serialise, the content-resolver stream (opened *inside*
the service's `withContext(Dispatchers.IO)`, so the SAF call is off the main
thread too), `replaceAll`, and the library-cache update — happens there. Two
earlier rounds moved exactly this work off the main thread in `RosterScreen`
and `ClientScreen`; the backup path landed afterwards and put it back in the
one place that does the most I/O. Keep it out of the Composable: it is also
the only way any of it can be tested, since this repo has no Compose harness.

Each failure stage reports what actually failed. "That doesn't look like a
valid backup file." covers the decode, and *only* the decode — a write that
failed says so and says the existing roster is unchanged, and a library-cache
problem after a successful client restore does not claim the file was bad.

## Imports are serialised

`ShareLinkImporter.import` is read-modify-write over a whole client file, and
`RosterScreen` dispatches one per user action — a pasted link importing while a
share-sheet link arrives is two coroutines reading the same client and both
writing. It takes a lock for the whole operation. `Files.move` makes each write
atomic; it does nothing about a lost update. `RosterLoader` serialises the
roster *reads*; this serialises the *writes*, which are the ones that lose
data.
