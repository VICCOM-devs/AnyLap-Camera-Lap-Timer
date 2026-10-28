# Releasing AnyLap (Android / Google Play)

**Signed Play bundle:** push to any `release/*` branch (or a `v1.2.3` tag). `.github/workflows/release.yml` then
builds `bundleRelease` + `assembleRelease` (R8), signs with the upload key, refuses a debug-signed bundle, and puts
`anylap-<version>-<code>.aab` (+ `.apk`, R8 mapping) on a GitHub pre-release `play-<version>-<code>`.
Download the .aab there and upload it in Play Console (Testing → Closed testing).

- versionName = `appVersionName` in `gradle.properties` (tag builds: the tag). One semver for all platforms.
- versionCode = **1000 + release-workflow run number** – monotonic, never reused, above every test build
  (`build.yml` uses its own run number). `BuildInfo.BUILD` is set to the same number for the update gate.
- Release builds are the `play` update channel (Play In-App Updates); debug builds are the `test` channel.

## One-time: upload key → repository secrets

```
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```
(Windows: `& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" …`). Back up `upload.jks` + password
twice, never commit it. Base64: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload.jks")) | Set-Clipboard`.

GitHub → Settings → Secrets and variables → Actions:
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (`upload`), `ANDROID_KEY_PASSWORD`.
Play App Signing (default) keeps the real app key at Google, so a lost upload key can be reset.
