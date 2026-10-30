@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.anylap.platform

import app.anylap.OrientationState
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.currentLocale
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.QuartzCore.CACurrentMediaTime
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIInterfaceOrientationMaskLandscapeRight
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS
import platform.UIKit.popoverPresentationController
import platform.UIKit.setNeedsUpdateOfSupportedInterfaceOrientations
import platform.posix.memcpy

actual object Platform {
    // iOS is not patched per change (house rule): no beep yet.
    actual fun beep() {}

    // iOS is not patched per change (house rule): crops are uploaded as BMP until there is a device to test on.
    actual fun encodeJpeg(width: Int, height: Int, argb: IntArray, quality: Int): ByteArray? = null

    actual val name: String = "iOS"

    actual fun monotonicNanos(): Long = (CACurrentMediaTime() * 1_000_000_000.0).toLong()

    actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    private fun dir(kind: ULong): String =
        NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).first() as String

    private fun ensure(path: String): String {
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        return path
    }

    actual val dataDir: String get() = ensure(dir(NSApplicationSupportDirectory) + "/AnyLap")

    actual val mediaDir: String get() = ensure(dir(NSDocumentDirectory) + "/sessions")

    actual val cacheDir: String get() = ensure(NSTemporaryDirectory() + "shared")

    actual fun readText(path: String): String? =
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)

    actual fun writeText(path: String, text: String) {
        val ns = NSString.create(string = text)
        ns.writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    actual fun writeBytes(path: String, bytes: ByteArray) {
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        data.writeToFile(path, atomically = true)
    }

    actual fun readBytes(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val size = data.length.toInt()
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        return out
    }

    actual fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    actual fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    actual fun formatDate(epochMs: Long, pattern: String): String {
        val f = NSDateFormatter()
        f.locale = NSLocale.currentLocale
        f.dateFormat = pattern
        return f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))
    }

    actual fun localDay(epochMs: Long): Long {
        val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
        val offset = NSTimeZone.localTimeZone.secondsFromGMTForDate(date)
        return Math.floorDiv(epochMs / 1000 + offset, 86_400L)
    }

    actual val canScanQr: Boolean get() = false
    actual fun scanQr(onResult: (String?) -> Unit) = onResult(null)

    actual fun shareText(text: String, title: String) = present(listOf(text))

    actual fun shareFile(path: String, mimeType: String, title: String) = present(listOf(NSURL.fileURLWithPath(path)))

    private fun present(items: List<Any>) {
        val top = topViewController() ?: return
        val vc = UIActivityViewController(activityItems = items, applicationActivities = null)
        vc.popoverPresentationController?.sourceView = top.view
        top.presentViewController(vc, animated = true, completion = null)
    }

    private fun keyWindow(): UIWindow? {
        val scene = UIApplication.sharedApplication.connectedScenes.firstOrNull() as? UIWindowScene ?: return null
        return scene.windows.firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
            ?: scene.windows.firstOrNull() as? UIWindow
    }

    private fun topViewController(): UIViewController? {
        var vc = keyWindow()?.rootViewController ?: return null
        while (true) vc = vc.presentedViewController ?: break
        return vc
    }

    actual fun setCameraMode(enabled: Boolean) {
        OrientationState.landscape = enabled
        UIApplication.sharedApplication.idleTimerDisabled = enabled
        val scene = UIApplication.sharedApplication.connectedScenes.firstOrNull() as? UIWindowScene
        val mask = if (enabled) UIInterfaceOrientationMaskLandscapeRight else UIInterfaceOrientationMaskPortrait
        scene?.requestGeometryUpdateWithPreferences(UIWindowSceneGeometryPreferencesIOS(interfaceOrientations = mask), errorHandler = null)
        keyWindow()?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }
}

private object Math {
    fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }
}

actual class Speaker actual constructor() {
    private val synth = AVSpeechSynthesizer()

    actual fun say(text: String) {
        synth.speakUtterance(AVSpeechUtterance.speechUtteranceWithString(text))
    }

    actual fun shutdown() {
        synth.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }
}
