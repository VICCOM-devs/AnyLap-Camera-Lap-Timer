package app.anylap.update

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

/** iOS has no in-app update API: the gate in `anylap_app_updates` links to the App Store. */
actual object UpdatePlatform {
    actual val channel: String = "appstore"
    actual val language: String get() = NSLocale.currentLocale.languageCode
    actual suspend fun storeUpdateBuild(): Int? = null
    actual fun startStoreUpdate(immediate: Boolean): Boolean = false
    actual fun completeStoreUpdate() {}
    actual fun installApk(path: String): String? = "Not available on iOS."
}
