# Releasing AnyLap

## Every release (2 minutes)

1. Merge everything into `main`, make sure CI is green.
2. Fill in **Unreleased** in `CHANGELOG.md` (`### sv` and `### en`).
3. `scripts/release.sh 0.4.0`

That commits the changelog and version bump, tags `v0.4.0` and pushes. The **Release** workflow then:

- runs the tests and builds a signed `.aab` + `.apk` (`versionCode` = 100 + workflow run number, never reused),
- uploads the bundle to Google Play on the **internal** track with the Swedish/English notes and the R8 mapping file,
- creates a GitHub release with the notes and the APK attached.

Promote internal → production in Play Console when the internal testers are happy (use a staged rollout for feature releases).
Then update the pipeline board / Play link on viccom.se (repo `VICCOM_Site`).

Need a signed build without releasing? **Actions → Release → Run workflow** on any branch; leave *track* empty to only build,
or pick a track to upload.

## iOS

Not automated yet (needs a Mac with the signing certificate). `scripts/release.sh` already bumps `MARKETING_VERSION`
in `iosApp/project.yml`; set `CURRENT_PROJECT_VERSION` to the Android `versionCode` from the workflow log, then
`cd iosApp && xcodegen`, Archive → Distribute → TestFlight. When a Mac runner is worth it, add a `fastlane match` +
`pilot` job to the release workflow.

## One-time setup (repository secrets)

Settings → Secrets and variables → Actions → *New repository secret*.

| Secret | What |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The **upload** keystore, base64: `base64 -w0 upload.jks` (macOS: `base64 -i upload.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | its store password |
| `ANDROID_KEY_ALIAS` | key alias (e.g. `upload`) |
| `ANDROID_KEY_PASSWORD` | key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Cloud service-account JSON key with access to the Play Console |

Without the keystore secrets the workflow still runs but signs with the debug key (not uploadable).
Without `PLAY_SERVICE_ACCOUNT_JSON` it builds and makes the GitHub release but skips Play.

### Upload keystore

If you don't have one yet:

```
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Keep `upload.jks` and its passwords in a password manager, never in the repo (`.gitignore` already excludes `*.jks`).
Enroll the app in **Play App Signing** (default for new apps) so Google holds the real app key and this upload key can be reset if lost.

### Play service account

1. Play Console → *Users and permissions* → *Invite new user* is for people; for CI go to
   Google Cloud Console → *IAM & Admin* → *Service accounts* → create one (e.g. `github-release`), add a **JSON key**.
2. Play Console → *Users and permissions* → invite the service account's email, give it **Release to testing tracks** and
   **Release to production** (or just testing, and promote by hand) for AnyLap.
3. Paste the JSON into `PLAY_SERVICE_ACCOUNT_JSON`.

The first ever upload of an app must be done by hand in Play Console (create the app, upload one bundle to any track);
after that the API works.

### versionCode history

Builds uploaded before this pipeline had `versionCode` 1. The workflow starts at 101 so there's never a clash;
if you ever reset the Actions run counter or move repos, raise the offset in `release.yml`.
