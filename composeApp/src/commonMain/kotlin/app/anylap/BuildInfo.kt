package app.anylap

/**
 * Filled in by CI. `BUILD` = the build number (test builds: build.yml run number; Play builds: versionCode),
 * 0 for local builds. `VERSION` = the version name ("0.4.0"); "dev" locally.
 */
object BuildInfo {
    const val BUILD = 0
    const val VERSION = "dev"

    /** What the user sees everywhere the version is shown: "0.4.0 (1005)", "0.4.0 · test 98", or "dev". */
    val label: String
        get() = when {
            BUILD == 0 -> VERSION
            BUILD >= 1000 -> "$VERSION ($BUILD)"
            else -> "$VERSION · test $BUILD"
        }
}
