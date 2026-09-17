# Play Console answers — draft

A draft of what Coach 1.4 (versionCode 6) would enter in Play Console's **App
content** pages, taken from the code on `main` at the time of writing. Nothing
has been submitted. Re-check each answer against the code before using it: an
answer that was true when written and false when submitted is a policy
violation, not a typo.

## What the answers rest on

**Coach has no network access.** The manifest requests no permissions
(`app/src/main/AndroidManifest.xml`), and the merged manifest of a built APK
(`app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`)
adds only `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature-level
permission AndroidX declares for the app's own receivers. There is no
`android.permission.INTERNET`, so the process cannot open a socket.

`grep` over `app/src/main` finds no `java.net`, `HttpURLConnection`, OkHttp,
Ktor, Retrofit or `WebView`. Dependencies (`app/build.gradle.kts`) are AndroidX,
Compose and `dugcanlift-kit-android`; no Firebase, Play Services, analytics,
crash reporting or ads SDK.

This differs from Coach iOS in one notable way: the iPhone build's Cook
fetches TheMealDB and pasted recipe pages. **Coach Android has neither.** Its
"Paste a recipe" (`ui/CookScreen.kt`) parses text the coach pastes, on the
device. If either import is ever ported, every answer below changes and so does
the manifest.

**How data moves, all on the device or by the coach's own hand:**

| Path | What | Code |
|---|---|---|
| In | A client's log, inside the `#fragment` of a `https://www.dugcanlift.com/coach/` link the coach taps (App Link), shares into Coach, or pastes | `MainActivity.kt`, `data/LinkFragment.kt`, `data/ShareLinkImporter.kt`, manifest `VIEW` and `SEND` filters |
| In | A backup file the coach picks with the system file picker | `ui/ConnectScreen.kt` (`OpenDocument`), `data/BackupService.kt` |
| Stored | Clients as `filesDir/clients/<id>.json`; recipes and planned meals in `cook-library.json`; routines and sessions in `train-library.json`; `preserved-library.json`; coach name and email in SharedPreferences `connect`; `cook` and `coach_settings` preferences | `data/ClientRepository.kt`, `data/CookRepository.kt`, `data/TrainRepository.kt`, `ui/ConnectScreen.kt`, `data/AppearanceStore.kt` |
| Out | A backup file saved where the coach chooses | `ui/ConnectScreen.kt` (`CreateDocument`) |
| Out | Invite text containing the coach's own name and email, through the Android share sheet to an app the coach picks | `ui/ConnectScreen.kt` `inviteText`, `ACTION_SEND` |
| Out | A client's week of meals as a `https://www.dugcanlift.com/lift/#…` plan link, through the share sheet (typically the coach's email app) | `ui/CookScreen.kt` `onSend`, `data/CookPlanEncoder.kt` |

Train has no send on Android (`ui/TrainScreen.kt` has no `ACTION_SEND`).

**Cloud backup is off.** `android:allowBackup="false"`;
`res/xml/data_extraction_rules.xml` excludes everything from `cloud-backup` and
allows only `device-transfer` (a direct phone-to-phone copy);
`res/xml/backup_rules.xml` excludes everything for API 30 and below.

**Client route data is received, not collected.** A client's last route
(`ui/charts/RouteCanvas.kt`) arrives already trimmed by the client's LIFT and
only if they opted to send it. Coach draws it on a `Canvas`, with no map tiles,
and reads no location of its own — there is no location permission.

## Data safety form

- **Does your app collect or share any of the required user data types?** No.

  Play defines *collected* as transmitted off the device by the app, and
  *shared* as transferred to a third party. Coach cannot transmit anything
  (no `INTERNET`). The two outbound paths above are user-initiated hand-offs
  through the system share sheet or file picker, to a destination the coach
  chooses, which Play's guidance exempts from "sharing" when the user
  reasonably expects the transfer. Processing client logs on the device is not
  collection.

- **Is all user data encrypted in transit?** Not asked once the answer above is
  No. (If Play asks anyway: there is no transit by the app.)

- **Do you provide a way for users to request that their data is deleted?** Not
  asked once the answer above is No. Uninstalling the app deletes all of it,
  since none of it is backed up to the cloud.

- **Privacy policy URL:** https://www.dugcanlift.com/coach/privacy/ — being
  written in a parallel `dugcanlift-site` PR. It must be live before the listing
  is submitted. The app itself does not link to it yet (`grep -i privacy
  app/src/main` finds nothing); Play does not require an in-app link for an app
  that collects nothing, but LIFT's listing mentions one and Coach's cannot.

## Other App content pages

- **Ads:** No ads.
- **App access:** All functionality is available without an account or login.
  A reviewer with no client link sees an empty roster; Cook, Train and Connect
  work without one. Consider giving reviewers a sample share link in the
  access instructions so the client screen has something on it.
- **Target audience:** 18 and over (a tool for trainers). Not designed for
  children.
- **Health apps declaration:** Coach shows fitness and nutrition data
  (training volume, calories, bodyweight, steps, outdoor activities), so Play
  will likely ask. It uses no Health Connect or health permissions and is not a
  medical device. Answer from that.
- **Government, financial features, news:** None.
- **Data safety:** as above.

## Content rating (IARC questionnaire)

Suggested category: **Utility, productivity, communication or other** (or
Health & Fitness as the store category). Expected answers, all No: violence,
sexual content, profanity, drugs, gambling, horror. User interaction: users
cannot communicate with other users inside the app, the app shares no user's
current location, and there are no digital purchases. Likely result (IARC decides):
Everyone / PEGI 3.

## Still missing before a submission

- [x] **An Android App Bundle.** `.github/workflows/release.yml` now builds
  `bundleRelease` alongside `assembleRelease`, verifies both carry the release
  certificate, and attaches `coach-android.aab` to the GitHub Release next to
  `coach-android.apk`. That `.aab` is the file to upload to Play.
- [ ] **Play App Signing decision.** Play re-signs with its own key unless the
  existing release key is uploaded. The site's `.well-known/assetlinks.json`
  names only the current release certificate for `com.dugcanlift.coach`
  (`RELEASE_CERT_SHA256` in `release.yml`), so a Play-signed build would lose
  tap-to-import until Play's certificate is added there, and could not install
  over a sideloaded copy signed with the current key.
- [ ] **A Play Console developer account** (none exists). New personal accounts
  must run a closed test with at least 12 testers for 14 days before production.
- [ ] **Phone screenshots** — at least two. `fastlane/metadata/android/en-US/images/phoneScreenshots/`
  does not exist yet.
- [ ] **Feature graphic**, 1024 x 500 — required by Play, not present.
- [ ] **Privacy policy page live** at the URL above.
- [ ] **Store contact email** and **category** chosen in the console.
- [ ] Review `fastlane/metadata/android/en-US/images/icon.png`: it is the
  adaptive icon's layers (`res/values/ic_launcher_background.xml` colour and
  `res/mipmap-xxxhdpi/ic_launcher_foreground.png`) composited at 512 x 512. The
  largest foreground raster is 432 px, so it is slightly upscaled; a sharper
  source is `coach-ios/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024.png`.
