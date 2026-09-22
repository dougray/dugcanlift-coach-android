# Coach — Android

Native Android app (Jetpack Compose) for a coach to import a lifter's shared
link and review their training. Local to the device; no backend of its own —
matches Coach iOS's own "no backend, no accounts" design exactly.

## Build and test

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --rerun
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

Where Android Studio is not installed, `JAVA_HOME=/opt/homebrew/opt/openjdk@17`
works as well. The `JAVA_HOME` prefix is required — this machine's default `java` is not the
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

## Releases

A pushed `v*` tag runs `.github/workflows/release.yml`. Tests and lint run on
a GitHub-hosted runner; the `sign` job then waits for approval on the `release`
environment and runs on the self-hosted signer, where the keystore lives. It
builds `:app:assembleRelease :app:bundleRelease` in one Gradle run with the one
signing config, and publishes both to the tag's GitHub Release:

- **`coach-android.apk`** — the sideload download the website links to. Keep
  its name; checked with `apksigner verify --print-certs`.
- **`coach-android.aab`** — the App Bundle for Google Play, which accepts only
  `.aab`. An AAB has a JAR signature that `apksigner` does not read, so it is
  checked with `jarsigner -verify -strict` (only exit bit 4, "self-signed", is
  allowed) and `keytool -printcert -jarfile` (exactly one signer).

Both must carry `RELEASE_CERT_SHA256` or nothing is published, and
`SHA256SUMS` lists both files. Locally, with no `keystore.properties`,
`./gradlew :app:bundleRelease` builds an unsigned bundle; a `keystore.properties`
whose `storeFile` does not exist on this Mac fails `validateSigningRelease`
instead, so build from a clean checkout to check the bundle.

## Uploading to Google Play

```bash
gh release download v1.6 -p coach-android.aab
fastlane android upload aab:coach-android.aab            # internal testing, as a draft
fastlane android upload aab:coach-android.aab track:alpha # closed testing
```

`fastlane/Fastfile`'s one lane runs `supply` with the signed bundle from the
tag's GitHub Release (it never builds or signs) and everything under
`fastlane/metadata/android`: title, descriptions, graphics, screenshots, and
`changelogs/<versionCode>.txt`, which it refuses to go without. Options:
`track:internal|alpha|beta` (alpha is closed testing), `status:draft|completed`,
`validate:true` to have Play check the upload without committing it.
**Production is refused**; promote a tested release in Play Console. A release
arrives as a draft and is rolled out by hand there.

- **The key is never in this repo.** `fastlane/Appfile` reads the service
  account's JSON key from `PLAY_JSON_KEY_PATH`, falling back to
  `~/keystores/play-publisher.json`; `*.json` under `fastlane/` is gitignored.
  The service account needs release permission for this app under Play Console's
  Users and permissions.
- **The very first upload of a brand-new app goes through the browser.** The
  Publishing API refuses an app that has never had a release, so the first AAB is
  uploaded by hand in Play Console. Then `fastlane android upload listing_only:true`
  sends the text and graphics without a bundle (that versionCode is already
  taken), and every later version goes up with its `aab:`. Until the first
  release is published, Play also accepts only `status:draft`, which is why
  draft is the default.
- **Store screenshots**: phone shots are 1080×1920, no alpha, captioned and
  framed like the rest of the set, at most eight, numbered in carousel order with
  no gaps; renumber the set when inserting. Tablet shots are raw captures, 7-inch
  at `wm size 1080x1920` / `wm density 216` and 10-inch at `2560x1440` / `320`.
- **Tap-to-import depends on the signing key.** The listing says a client's link
  opens *in* Coach, which holds either way, but it opens *straight* into Coach
  only while `assetlinks.json` names the certificate Play signs with. Upload the
  existing key to Play App Signing, or add Play's certificate to the site file.

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
  decoder, including `fx`/`fe` (`ShareNutrientTotals`, `NutrientDetails`).
- `PlanLinkCodec` (decode only — `CookPlanEncoder` is Coach's own encoder),
  `RecipeNutrition`, `ShareNutrients` (the `fe`/`ux` tuple and its rounding).
  See "The wire format is a contract" below before touching anything
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

## Outdoor: all-time parts follow the newest send, not the day

A day's `o` (runs, walks, hikes) is part of the day and is replaced with it, like
everything else in a day. The top-level `ob` (bests) and `lr` (last route) are
all-time, so they follow SHARE-FORMAT "Outdoor" and Coach web's `absorb` instead:
a payload whose `z` is at least `Client.exportedAtEpochSec` replaces both, and an
**absent** one clears it -- a client who turned route sharing off expects the
route gone. An older link opened late changes neither. Name, unit, platform and
goal follow the same rule (an absent goal keeps the stored one), as they do on
Coach iOS and Coach web; days do not -- each link is the truth for the days it
covers, whenever it arrives.

The route is drawn on a `Canvas` (`ui/charts/RouteCanvas.kt`, LIFT Android's
projection), never on map tiles: a tile server would learn where the client
runs. `outdoor-share-link.txt` / `outdoor-share-expected.json` were written by
LIFT web -- never regenerate them from this code or the kit.

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
  anything outside `v`, `clients`, `recipes`, `meals`, `routines` and
  `sessions` (`ENVELOPE_KEYS`): the web build's `plans`/`workouts`/`settings`,
  and anything a newer Coach iOS file adds. Preservation is by *exclusion*,
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
- **The library is modelled now, and still lossless.** Coach Android has
  Cook (`data/Cook.kt`, `CookRepository`) and Train (`data/Train.kt`,
  `TrainRepository`), so `recipes`, `meals`, `routines` and `sessions` decode
  into models. Being modelled is not permission to be lossy: `recipeFromJson`
  and `plannedMealFromJson` keep every key they have no field for
  (`unknownKeys`, `nutritionUnknownKeys`) and write it back, the same
  preservation-by-exclusion rule one layer down. A v1 backup file (no library
  at all) must round-trip to "no library keys," not "library deleted." Clients
  themselves restore by full **replace** (`ClientRepository.replaceAll`) —
  deliberately inconsistent with the library, matching iOS's own split between
  "the roster is replaced" and "the library is never destroyed by an older
  file."

## Saturated fat, sugar and sodium

Tracked and shown, **never targeted**: no goal, no bar, no colour. Spec:
SHARE-FORMAT "Saturated fat, sugar and sodium" (`fx`, `fe`), PLAN-FORMAT `ux`,
BACKUP-FORMAT `nutritionPerServing`. Kit 1.4.0 decodes the wire.

- **Import.** A day's `fx` is stored as `TrainingDay.nutrientTotals`
  (`DayNutrientTotals`) exactly as sent — never re-added from the foods. `fe`
  is per serving on the wire and **as eaten in the store**, multiplied by
  servings like the macros beside it (see "Per-serving on the wire" above).
  Null stays null: an unrecorded value is never zero, and a totals object with
  nothing known is stored as no totals. Days are replaced whole, so a resend
  without `fx` clears the day's totals.
- **Display** (`ui/NutrientDisplay.kt`, pure and tested). A day's line says its
  coverage when partial — "Sodium 1,840 mg · from 3 of 5 foods" — because a
  partial total is a floor, not a day. The 7-day and 4-week averages
  (`Stats.nutrientAverages`) count only days that recorded each nutrient and
  always say how many days, and how many of those were partial.
- **Recipes.** The three are `RecipeNutrition.saturatedFatG` / `sugarG` /
  `sodiumMg`. Earlier builds kept them in `nutritionUnknownKeys`; they are
  promoted on read, and a non-numeric value stays in the bag rather than being
  dropped. A recipe can hold them with **no macros entered** — the five macros
  are then placeholder zeros, the shape iOS's `NutritionFacts` has, and
  `RecipeNutrition.hasMacros` is false: the editor reopens them blank and
  `CookPlanEncoder` omits `u` (never zeros) while still sending `ux`.
- **`ux`** is built with the kit's `ShareNutrients.itemRow`: per serving,
  grams to one decimal and sodium whole (half-up), only trailing nulls
  trimmed, omitted when none is known. Coach web's `encodePlan` does not write
  `ux` yet; the spec is the authority, not that encoder.

**Backup field names — Coach iOS must match these exactly.** They are the
client-file names too, since the backup reuses `TrainingDay.toJson`:

```jsonc
// on a day (clients[].days[]), omitted when nothing was recorded
"nutrientTotals": { "saturatedFatG": 21.5, "sugarG": null, "sodiumMg": 2310,
                    "foods": 6, "withSaturatedFat": 4, "withSugar": 0, "withSodium": 6 }
// on a food (clients[].days[].foodEntries[]), AS EATEN, each key omitted when unknown
"saturatedFatG": 3.1, "sugarG": 2, "sodiumMg": 540
```

and on a recipe's `nutritionPerServing` / a meal's `snapshotNutrition`,
`saturatedFatG`, `sugarG`, `sodiumMg` per serving, omitted when unknown. Older
files without any of these load with them unknown.

## Per-limb sets: left, right, and both

A set may name a limb. **Null is both**, which is what every set written before this means and what
every set of a two-sided lift means now, so absence is never guessed at and a side is never inferred
from an exercise's name. Spec: SHARE-FORMAT "flags" and "The imbalance figure", BACKUP-FORMAT
`side`, and LIFT web's `lift/sides.js`, which is the canonical rule all four apps port.

- **The wire is the kit's, not this app's.** Kit 1.5.0 reads the set tuple's flags bits 1-2 into
  `ShareSet.side`; Coach maps it to its own `SetSide` in `ShareLinkImporter` and never touches a
  bit. If that ever changes, **mask, never compare**: `flags == 1` was a correct warmup test while
  warmup was the only bit and calls a left-side warmup (`3`) a working set today. Bits 1-2 holding
  `3` reads as both, not as a side a decoder invented, and `0`..`5` are all legal from some encoder
  (LIFT Android has no warmup flag and writes only `0`, `2`, `4`). `PerLimbSetsTest` pins every
  value against hand-built raw payloads rather than this app's own encoder.
- **The backup is a named field**, `"side": "left" | "right"`, **omitted when both** -- not
  `"both"`, not null. A roster with no per-limb sets therefore writes the file it always wrote, and
  an unrecognised string restores as both rather than failing the import, the leniency
  BACKUP-FORMAT asks for everywhere. Coach iOS must match this spelling exactly.
- **Side is in the grouping key.** `Stats.liftKey` is `"name|equipment|side"` (empty for both), for
  the same reason equipment joined it: reading the bits and then charting as before gives a *worse*
  chart than ignoring them, because the two limbs are now genuinely interleaved and the line
  zig-zags set for set. `Stats.matchKey` is the first two thirds -- one lift, whichever limbs.
- **`SideBalance` is a port of `sides.js`, function for function** (by way of LIFT Android's own
  `SideBalance.kt`), and a port rather than a second opinion on purpose: four apps printing
  different percentages from one log is worse than any of them printing a slightly better number.
  Mean of each side's last three sessions, three a side for a figure, four for a trend, half a
  percentage point of movement before the gap has done anything. If the rule changes it changes in
  `sides.js` first and is ported again. `SideBalanceTest` carries LIFT Android's cases.
- **The wording is Coach web's `imbalanceLines`, word for word** -- `Right ahead by 5.3%` over
  `Mean estimated 1RM of the last 3 sessions each · gap closing`, `Sides level` when they match,
  and `—` over `Needs 3 sessions a side · 2 left, 2 right so far` below the threshold. It lives in
  `ClientDisplay.imbalanceLines`, not on `SideImbalance`: three Coach builds printing different
  *sentences* from one log is the same failure as printing different numbers. One decimal, trailing
  `.0` dropped -- `5%`, never `5.0%` -- which is what the browser's
  `Math.round(percent * 1000) / 10` prints.
- **The figure appears only when both limbs exist** (`LiftProgression.hasBothLimbs`, web's
  `if (left && right)`). A client who has only ever logged one limb gets a named series and no
  figure -- no standing reminder of a limb they never said they were training. "Needs 3 sessions a
  side" is for a client who trains both and is short on one.
- **The series are web's `splitSessions`: left, right, then the unmarked sets**, and the unmarked
  ones are *drawn*, not dropped -- muted beside a limb, the ordinary accent when they are the only
  line (`seriesColour`). `LiftProgression.sided` decides whether the lines are named at all. A
  series with fewer than two points is left off the chart and the legend, because one point is not
  a trend -- but it still counts in the session counts, because the client did train it.
- **Every e1RM series is one point per day**, the day's best working set (`Stats.perLiftE1rm`) --
  including a two-sided lift's, which did once plot every set. A point on a chart and a "session"
  in the imbalance rule have to be the same thing, and the same lift must not change shape
  depending on whether its client happens to log limbs. `Stats.sideSessions` reads straight off
  that series, so the number under a chart and the chart itself cannot disagree.
- **Tracked and shown, never targeted**, the discipline saturated fat, sugar and sodium are held
  to: no threshold, no colour, no advice. The line states the gap and its direction and stops.
- **Volume, set counts and the weekly summary count both sides**, unchanged -- they only ever
  needed weight and reps, which is why a Coach build predating the bits decodes them correctly and
  only the chart was wrong.
- **A coach's prescription can carry sides too** -- see "Per-side prescriptions" below.

## Per-side prescriptions

A routine's exercise can be **each side** (`RoutineExercise.eachSide`: every set done on both
sides, so "3 x 8 each side" stays three sets) and a set can name **one side**
(`PrescribedSet.side`, done on that side once -- an extra set on the left, rehab side only).
PLAN-FORMAT "Sides"; spec `dugcanlift-wip-backups/coach-per-side-prescriptions-spec.md`. The rules
are `PrescriptionSides`, a port of Coach web's `coach/prescriptions.js` -- change them there first.

- **The editor** (`RoutineEditor`): under the text box, each exercise shows what it asks for
  (`3 × 30 × 8 each side + 1 L`), an "Each side" toggle, and -- once each side, once a set names a
  side, or after "Set a side" -- a row per set reading `30 × 8 L` with Both / L / R. A bench press
  looks as it always did. The box cannot hold sides, so `RoutineEditing` keeps them beside it,
  keyed `name|equipment#occurrence`, and puts them back on save.
- **"Each side" starts ticked by LIFT's unilateral-name guess** (the same term list as LIFT
  Android's `PerSideLogging` and both `sides.js`), and the coach's own answer is remembered per lift
  (`coach_settings`, `each_side|name|equipment`) and wins from then on. A saved exercise keeps what
  it stored; no guess touches it. A named set on a two-sided lift is allowed: it means a single-arm
  variation of that set.
- **The backup is Coach web's and Coach iOS's spelling**: `eachSide: true` on an exercise, omitted
  when false; `side: "left" | "right"` on a set, omitted when both. A routine without sides writes
  the object it always did; an unknown side string reads as both and is not written back.
- **The wire is `TrainPlanEncoder`**: `b: 1` (never `0`) and a sixth tuple position with the flags
  bits (`2` left, `4` right); a both-sides set writes no sixth position, and only trailing nulls are
  trimmed. Train's Send calls it (see "Sending a week from Train"), and it is checked against
  `fixtures/web-plan-per-side.txt` and `fixtures/web-plan-link.txt` (both written by Coach web's own
  encoder -- never regenerate them) and read back through the kit's `PlanLinkCodec`, the decoder
  LIFT Android ships. Pounds on the wire, rounded to six places only to drop kilogram round-trip
  noise.
- Tracked and prescribed, never judged: nothing comments on an extra left set.

## Sending a week from Train

Train's Schedule shows one client's week -- seven days from a start the coach moves with
Earlier/Later, `PlanWeek`, Coach iOS's own -- and sends it: `TrainPlanSend.build` picks what goes
and `TrainPlanEncoder.encode` writes it, `PlanEnvelope` wraps it, and the screen hands the link to
the system chooser as a plain-text `ACTION_SEND`, the mechanism Cook's Send already used.

- **One client, one week, training only.** Three rules and the other two builds disagree about two
  of them, so they are choices, not accidents. Coach web's `encodePlan` sends *every* session and
  meal a client has, from either screen, in one link; Coach iOS sends the week on screen, training
  from Train and food from Cook. Android follows iOS: each screen sends what it shows, the coach
  can see the whole of it before they send it, and Cook's Send is left exactly as it was rather
  than gaining a silent second payload.
- **Only the routines that week books are inlined**, and a booking whose routine is gone is
  dropped, never pointed at whichever template happens to sit at that index -- `x` indexes into
  `w`. The day says "Removed workout" (iOS's name) and the note counts them, Coach web's warning in
  the same place.
- **Nothing bookable means no button**, not a button that ships an import prompt offering nothing.
  The note then says so.
- **The note is Coach web's `updateTrainPlanSize`**: what the link contains and about how much
  email it is, and over `PlanEnvelope.RISKY_LINK_LENGTH` (16,000 characters, PLAN-FORMAT "Size")
  that some mail apps will break it. It is measured on the finished link, after DEFLATE.
- **`TrainPlanSend` is a plain value with no Compose in it**, for the reason `RosterLoader` is one:
  there is no Compose harness here, and what a coach ships to a client is not a thing to leave in a
  lambda. The screen computes it into `remember`, keyed on the client, the week and the library's
  revision, so the button, the note and the tap cannot read three different answers.
- **Kilograms out, pounds on the wire.** `TrainPlanEncoder.kgToLb` does it and
  `TrainPlanSendTest` pins 100 kg leaving as 220.46 lb, in the decoded payload and in the raw
  tuple. Nothing fails when the conversion goes missing; the client just trains 2.2x wrong.
- Booking used to be "today or nowhere" -- every session landed on `DayKey.today()`. The week is
  what made a Send mean anything.

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

## Large screens

Layout follows the **window's width**, never the device: Material 3's classes,
compact < 600 dp, medium 600–840, expanded ≥ 840 (`ui/adaptive/WindowLayout.kt`).
`ProvideWindowLayout` measures the window once at the root and provides
`LocalWindowWidth`; every decision is a plain function on `AdaptiveLayout`, pinned
by `WindowLayoutTest`. Put a new width rule there, not in a composable.

- **Compact is the phone app, unchanged**: bottom bar on the roster, Back on every
  pushed screen, one column everywhere. Every grid returns one column below 600 dp
  of *available* width and the compact code paths are the old ones, so check a
  change there against main's screenshots, not just "looks fine".
- **Medium and expanded**: a `NavigationRail` (Roster/Train/Cook/Connect) replaces
  the bottom bar, and Train/Cook/Connect lose Back — they are peers, navigated with
  `popUpTo(ROSTER)`. A rail at expanded too, not a drawer: the drawer's 240 dp
  would come out of the client pane.
- **Expanded roster is list + detail.** `selectedClientId` (saveable, in
  `CoachNavHost`) is "the client open", in either form; `reconcileRoster` moves it
  between the detail pane and `client/{id}` when the width crosses 840 dp, and
  clears it when a phone Back returns to the roster. The last two-pane-ness it
  compares against is saveable too (`rosterTwoPaneToRemember`), and is not
  overwritten until the restored back stack has a route: otherwise a density
  change that also crosses 840 dp reads as a Back and loses the open client.
  `RosterSelectionRecreationTest` (Robolectric) pins both directions.
- **The client page measures its pane** (`BoxWithConstraints`), never
  `LocalConfiguration.screenWidthDp`: as a pane, the screen's width is wrong. Charts
  are at least one column wide, floored to whole dp as `screenWidthDp` was. From a
  600 dp page it is two columns, capped at `MAX_CONTENT_DP` and centred.
- **Folds**: `MainActivity` reads a separating *vertical* `FoldingFeature` through
  `WindowInfoTracker` (`androidx.window`, already transitive via Material 3) and the
  list/detail split moves onto it when both sides stay usable. Nothing else avoids a
  hinge: Cook/Train grids and a horizontal (table-top) fold are not handled.
- Rotation, resizing and folding do not recreate the activity (`configChanges`);
  a theme or density change still does, so user state is `rememberSaveable` —
  an open editor is saved by id, its fields as strings.
- Rail icons are drawn in `AdaptiveComponents.kt`; do not add material-icons for four
  glyphs.

To check on the one phone AVD: `adb shell wm size 2560x1600 && adb shell wm density
320` (tablet landscape), `1600x2560` (portrait), `1767x2208` at 420 (foldable
inner), then **always** `wm size reset` and `wm density reset`.

## Removing a client

Remove this client (foot of the client page) and a roster row's long-press menu
both open `RemoveClientDialog`, which names the client and counts what goes
before anything does; `data/ClientRemoval.kt` does the work. It deletes the
client's file under `ShareLinkImporter`'s import lock, then the planned meals and
booked sessions carrying that client's id -- invisible and unsendable once the
client is gone, yet still written into every backup. Recipes and routines stay.
An unreadable cook or train library is left untouched (rewriting it would
destroy it) and the message says what stayed. Coach web removes only the
client; the privacy policy promises the client's data leaves the device, which
is why Android goes further. On a phone the client screen returns to the roster;
in two panes the selection clears. `ClientRemovalTest` and
`RemoveClientFlowTest` pin both halves.

**The dialog hands its outcome back on the main thread**, explicitly
(`withContext(Dispatchers.Main.immediate)`), because `onDone` navigates and
`NavController` moves each `NavBackStackEntry`'s `Lifecycle`, which throws when it
is touched off the main thread. Returning from `withContext(Dispatchers.IO)` only
lands on the main thread when the surrounding scope's dispatcher re-dispatches
there -- `AndroidUiDispatcher.Main` does, so a device was never at risk, but a
Compose test's dispatcher sometimes resumes the coroutine on the IO worker
instead. That is how `RemoveClientFlowTest` came to fail on a CI runner
("Method setCurrentState must be called on the main thread") while passing here.
Any callback that can navigate belongs in the same explicit hop.
