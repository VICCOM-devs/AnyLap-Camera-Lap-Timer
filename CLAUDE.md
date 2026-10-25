# AnyLap – notes for Claude

Compose Multiplatform app (Android + iOS from one codebase) by VICCOM. Read `README.md` for the layout and how timing works.

- Shared code in `composeApp/src/commonMain`; only cameras, players, TTS, sharing, files go through `expect`/`actual` in `platform/`.
- No DI framework, no ViewModel layer, no navigation library: `ui/Navigation.kt` has the stack `Navigator`, `data/Repository.kt` holds state as `StateFlow`s and persists JSON.
- Theme tokens in `ui/theme/Theme.kt` (`RT.*`), shared widgets in `ui/components/`. Screens use `ScreenScaffold`.
- Never change `Markers.CODES` or the app id `se.viccom.anylap`.
- Vision/engine logic is pure Kotlin with tests in `commonTest` on generated frames; add a test when touching it.
- Before saying work is done: `./gradlew :composeApp:testDebugUnitTest :composeApp:assembleDebug`.
- Releasing: `RELEASING.md`. Add user-facing notes to `CHANGELOG.md` → Unreleased (sv + en) in the same PR as the feature.

Account skills that apply here: viccom-mobile-stack, viccom-ui, viccom-compose-rules, viccom-cross-platform, viccom-release.
