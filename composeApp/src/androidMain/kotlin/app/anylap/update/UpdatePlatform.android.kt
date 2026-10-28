package app.anylap.update

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.anylap.platform.Platform
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale

actual object UpdatePlatform {
    private var activityRef = WeakReference<Activity>(null)
    private var info: AppUpdateInfo? = null

    /** MainActivity.onResume: keeps the activity for the Play flow and checks for updates. */
    fun onResume(activity: Activity) {
        activityRef = WeakReference(activity)
        Updates.onResume()
    }

    private val manager: AppUpdateManager by lazy {
        AppUpdateManagerFactory.create(Platform.context).also { m ->
            m.registerListener(InstallStateUpdatedListener { s ->
                if (s.installStatus() == InstallStatus.DOWNLOADED) Updates.onStoreDownloaded()
            })
        }
    }

    /** Test builds are debuggable (signed with the shared test key); everything else updates through Play. */
    actual val channel: String
        get() = if (Platform.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "test" else "play"

    actual val language: String get() = Locale.getDefault().language

    actual suspend fun storeUpdateBuild(): Int? {
        val i = manager.requestAppUpdateInfo()
        info = i
        if (i.installStatus() == InstallStatus.DOWNLOADED) Updates.onStoreDownloaded()
        return when (i.updateAvailability()) {
            UpdateAvailability.UPDATE_AVAILABLE, UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> i.availableVersionCode()
            else -> null
        }
    }

    actual fun startStoreUpdate(immediate: Boolean): Boolean {
        val i = info ?: return false
        val a = activityRef.get() ?: return false
        val type = if (immediate) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        if (!i.isUpdateTypeAllowed(type)) return false
        return try {
            manager.startUpdateFlow(i, a, AppUpdateOptions.defaultOptions(type))
            true
        } catch (t: Throwable) { false }
    }

    actual fun completeStoreUpdate() {
        try { manager.completeUpdate() } catch (_: Throwable) {}
    }

    actual fun installApk(path: String): String? {
        val ctx = activityRef.get() ?: Platform.context
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            // one-time: let AnyLap install its own test builds
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return Updates.t(
                "Tillåt AnyLap att installera appar, gå tillbaka och tryck Uppdatera igen.",
                "Allow AnyLap to install apps, come back and tap Update again.",
            )
        }
        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(path))
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            null
        } catch (t: Throwable) {
            Updates.t("Kunde inte öppna installationen.", "Couldn't open the installer.")
        }
    }
}
