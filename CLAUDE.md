# Coach — Android

Native Android app (Jetpack Compose) for a coach to import a lifter's shared
link and review their training. Local to the device; no backend of its own.

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

The app depends on it as `com.github.dougray:dugcanlift-kit-android`, pinned to
an **exact release tag** (never a branch or `SNAPSHOT`) in `app/build.gradle.kts`,
resolved through JitPack.

For local development against a kit checkout instead of the published tag, put

```
kitPath=../dugcanlift-kit-android
```

in `local.properties` (gitignored, not committed) — path is relative to this
Gradle root. `settings.gradle.kts` turns that into an `includeBuild` with a
dependency substitution, so the local `:liftcore` project wins over the
JitPack artifact with no network resolution. Remove the line (or delete the
file) to go back to building against the pinned tag.

## No Google Play Services

Hard policy for every app in this family: no GMS, no Firebase, no Play
Services dependency of any kind.

## Data model and storage

`data/Models.kt` defines `Goal`, `ExerciseSet`, `ClientFoodEntry`,
`TrainingDay`, and `Client` — plain data classes with `toJson()`/`fromJson()`
companions over `org.json`, field names matching the Kotlin property names.

`data/ClientRepository.kt` stores one client per file at
`clients/<id>.json`, written atomically (`.tmp` then rename) so saving one
client never touches another's file and a crash mid-write never leaves a
half-written file behind. A corrupt file is skipped, not fatal — `all()`
returns every other client rather than crashing the roster.

`Client.daysSinceLastLoggedDay(today)` counts from the most recent day that
was actually **logged** — has sets, food totals, or food entries — not
merely imported; `lastImportedAtEpochMs` plays no part in it.
