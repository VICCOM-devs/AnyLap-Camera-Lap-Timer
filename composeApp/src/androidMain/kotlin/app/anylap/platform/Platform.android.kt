package app.anylap.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

actual object Platform {
    private lateinit var appContext: Context
    private var activityRef = WeakReference<Activity>(null)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    val context: Context get() = appContext

    actual val name: String = "Android"

    private val tone: ToneGenerator? by lazy { try { ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (t: Throwable) { null } }

    actual fun beep() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 160) } catch (t: Throwable) {}
    }

    actual fun monotonicNanos(): Long = SystemClock.elapsedRealtimeNanos()

    actual fun nowMillis(): Long = System.currentTimeMillis()

    actual val dataDir: String get() = appContext.filesDir.absolutePath

    actual val mediaDir: String
        get() = (appContext.getExternalFilesDir("sessions") ?: File(appContext.filesDir, "sessions")).apply { mkdirs() }.absolutePath

    actual val cacheDir: String get() = File(appContext.cacheDir, "shared").apply { mkdirs() }.absolutePath

    actual fun readText(path: String): String? = File(path).takeIf { it.exists() }?.readText()

    actual fun writeText(path: String, text: String) {
        val f = File(path)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    actual fun writeBytes(path: String, bytes: ByteArray) {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    actual fun readBytes(path: String): ByteArray? = File(path).takeIf { it.exists() }?.readBytes()

    actual fun exists(path: String): Boolean = File(path).let { it.exists() && it.length() > 0 }

    actual fun delete(path: String) {
        File(path).delete()
    }

    actual fun formatDate(epochMs: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))

    actual fun localDay(epochMs: Long): Long {
        val offset = TimeZone.getDefault().getOffset(epochMs)
        return Math.floorDiv(epochMs + offset, 86_400_000L)
    }

    actual fun shareText(text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        start(Intent.createChooser(send, title))
    }

    actual fun shareFile(path: String, mimeType: String, title: String) {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", File(path))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        start(Intent.createChooser(send, title))
    }

    private fun start(intent: Intent) {
        val a = activityRef.get()
        if (a != null) a.startActivity(intent)
        else appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    actual fun setCameraMode(enabled: Boolean) {
        val a = activityRef.get() ?: return
        val window = a.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}

actual class Speaker actual constructor() {
    private var ready = false
    private val tts = TextToSpeech(Platform.context) { status -> ready = status == TextToSpeech.SUCCESS }

    actual fun say(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    actual fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
