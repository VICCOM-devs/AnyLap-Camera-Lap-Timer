# AnyLap – camera lap timer

Lap timing for RC cars and runners using only a phone camera. No transponders, no stickers on cars. Android and iPhone from one codebase. Made by [VICCOM](https://viccom.se).

## Project layout

| Path | What it is |
|---|---|
| `composeApp/src/commonMain` | **Shared code (both platforms)**: UI (Compose Multiplatform), race engine, stats, data storage, bib PDFs and the whole vision engine (motion, tracking, finish-line crossings, colour fingerprints, bib markers). Pure Kotlin. |
| `composeApp/src/androidMain` | Android: Camera2 camera, MediaPlayer replays, text-to-speech, sharing. |
| `composeApp/src/iosMain` | iOS: AVFoundation camera (up to 240 fps), AVPlayer replays, speech, sharing. |
| `composeApp/src/commonTest` | Vision tests on generated frames: crossing timing, speed, colour ID, markers. |
| `iosApp` | Thin SwiftUI wrapper. The Xcode project is generated with XcodeGen from `project.yml`. |

App id / bundle id: `se.viccom.anylap` (permanent).

## Build

**Android:** open the folder in Android Studio and run `composeApp`. From the command line: `./gradlew :composeApp:assembleDebug`.

**iOS** (Mac with Xcode 16+):
```
brew install xcodegen
cd iosApp && xcodegen && open AnyLap.xcodeproj
```
Choose your team under Signing & Capabilities, then run on your iPhone.

**CI:** every push builds the Android APK (with tests) and the iOS app. Download the APK from the run's artifacts.

## How timing works

1. Each frame is shrunk to 480 px wide. A background model marks pixels whose brightness *or colour* changed.
2. Moving regions are tracked from frame to frame. When a track's centre crosses the drawn line, the exact time is worked out *between* frames, so timing is accurate to a few ms even at 60 fps.
3. **Who it was:**
   - A bib marker (runners) is the strongest evidence.
   - Otherwise the colour fingerprint is compared with the calibrated racers.
   - If only one racer is in the session, every crossing is theirs.
   - If the app is unsure, the crossing goes to a tray for the user to assign.
4. **Speed** comes from the camera's field of view and the camera-to-line distance.

Bib markers are AnyLap's own 6×6 design (50 ids, at least 4 cells apart under any rotation). **Never change `Markers.CODES`**: printed bibs depend on them.
