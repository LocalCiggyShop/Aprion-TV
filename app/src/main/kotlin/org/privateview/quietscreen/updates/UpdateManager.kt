package org.privateview.quietscreen.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import org.privateview.quietscreen.BuildConfig
import org.privateview.quietscreen.core.UpdateManifest

data class UpdateStatus(val message: String = "Updates are checked automatically when you open the app.",
    val busy: Boolean = false, val ready: Boolean = false, val automatic: Boolean = true)

class UpdateManager private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transport = UpdateTransport(BuildConfig.UPDATE_OWNER, BuildConfig.UPDATE_REPO, BuildConfig.UPDATE_BRANCH)
    private val mutable = MutableStateFlow(UpdateStatus(automatic = prefs.getBoolean("automatic", true)))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var foreground = false
    private var confirmation: Intent? = null
    private var installing = false
    private val directory = File(context.cacheDir, "updates").apply { mkdirs() }
    private val apk = File(directory, "candidate.apk")
    private val envelope = File(directory, "candidate.qsu")
    private val configured get() = UpdateManifest.validateRepository(BuildConfig.UPDATE_OWNER, BuildConfig.UPDATE_REPO)

    fun automatic(enabled: Boolean) {
        prefs.edit().putBoolean("automatic", enabled).apply()
        mutable.value = mutable.value.copy(automatic = enabled)
        if (!enabled) cancel() else enterForeground()
    }

    fun enterForeground() {
        foreground = true
        if (confirmation != null) { mutable.value = mutable.value.copy(message = "Confirm the update to continue.", ready = true); return }
        if (installing) return
        val age = System.currentTimeMillis() - prefs.getLong("lastCheck", 0)
        if (mutable.value.automatic && (age < 0 || age >= 6 * 60 * 60 * 1000L || envelope.exists())) check()
    }

    fun leaveForeground() { foreground = false; cancel() }
    private fun cancel() {
        transport.cancel(); job?.cancel()
        if (!installing) mutable.value = mutable.value.copy(busy = false)
    }

    fun check() {
        if (!foreground || job?.isCompleted == false || installing) return
        if (!configured) { mutable.value = mutable.value.copy(message = "This development build has no public update location."); return }
        transport.begin()
        job = scope.launch {
            mutable.value = mutable.value.copy(message = "Checking for updates…", busy = true)
            try {
                val available = withContext(Dispatchers.IO) {
                    val current = UpdateApk.installed(context)
                    fun verify(bytes: ByteArray) = UpdateManifest.verify(bytes, UpdateApk.key(current), context.packageName,
                        UpdateApk.version(current), Build.VERSION.SDK_INT, BuildConfig.UPDATE_OWNER, BuildConfig.UPDATE_REPO)
                    val feed = "https://raw.githubusercontent.com/${BuildConfig.UPDATE_OWNER}/${BuildConfig.UPDATE_REPO}/${BuildConfig.UPDATE_BRANCH}/updates/${context.packageName}.qsu"
                    val bytes = transport.read(feed, true) { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(1024)
                        while (true) {
                            val count = stream.read(buffer); if (count < 0) break
                            require(output.size() + count <= UpdateManifest.MAX_ENVELOPE_BYTES)
                            output.write(buffer, 0, count)
                        }
                        val result = output.toByteArray()
                        require(result.size <= UpdateManifest.MAX_ENVELOPE_BYTES)
                        result
                    }
                    val candidate = verify(bytes)
                    if (candidate == null) { apk.delete(); envelope.delete(); null }
                    else {
                        val cached = envelope.exists() && envelope.readBytes().contentEquals(bytes) && apk.exists()
                        if (!cached) {
                            apk.delete(); envelope.delete()
                            val partial = File(directory, "candidate.part")
                            try {
                                transport.read(candidate.apkUrl, false) { stream ->
                                    partial.outputStream().use { output ->
                                        val buffer = ByteArray(32768); var total = 0L
                                        while (true) {
                                            ensureActive()
                                            val count = stream.read(buffer); if (count < 0) break
                                            total += count; require(total <= candidate.apkBytes)
                                            output.write(buffer, 0, count)
                                        }
                                        require(total == candidate.apkBytes)
                                    }
                                }
                                UpdateApk.verify(context, partial, candidate)
                                require(partial.renameTo(apk))
                                envelope.writeBytes(bytes)
                            } finally { partial.delete() }
                        }
                        UpdateApk.verify(context, apk, candidate)
                        candidate
                    }
                }
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                mutable.value = mutable.value.copy(message = available?.let { "Update ${it.versionName} downloaded and verified." } ?: "You have the latest version.", busy = false, ready = available != null)
                if (available != null && foreground && mutable.value.automatic &&
                    prefs.getLong("attemptedVersion", 0) != available.versionCode && context.packageManager.canRequestPackageInstalls()) install()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Never expose network URLs, CDN tokens, certificate details or raw server errors.
                mutable.value = mutable.value.copy(message = "Update check failed. Try again later.", busy = false, ready = false)
            }
        }
    }

    fun install() {
        if (!foreground || installing) return
        confirmation?.let { intent ->
            confirmation = null
            try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { mutable.value = mutable.value.copy(message = "Installation could not open. Try again.") }
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            mutable.value = mutable.value.copy(message = "Allow updates from QuietScreen in Android settings, then return here.")
            try { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { mutable.value = mutable.value.copy(message = "Enable installation from QuietScreen in your TV's security settings.") }
            return
        }
        installing = true
        job = scope.launch {
            mutable.value = mutable.value.copy(message = "Preparing installation…", busy = true)
            try {
                withContext(Dispatchers.IO) {
                    val current = UpdateApk.installed(context)
                    require(envelope.length() in 1..UpdateManifest.MAX_ENVELOPE_BYTES.toLong())
                    val candidate = UpdateManifest.verify(envelope.readBytes(), UpdateApk.key(current), context.packageName,
                        UpdateApk.version(current), Build.VERSION.SDK_INT, BuildConfig.UPDATE_OWNER, BuildConfig.UPDATE_REPO) ?: error("UPDATE_OLD")
                    UpdateApk.verify(context, apk, candidate)
                    ensureActive()
                    val installer = context.packageManager.packageInstaller
                    // Recover only sessions created by this app, after process death or a cancelled confirmation.
                    installer.mySessions.forEach { installer.abandonSession(it.sessionId) }
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                        setAppPackageName(context.packageName); setSize(candidate.apkBytes)
                        if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                        if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                    }
                    val id = installer.createSession(params)
                    try {
                        installer.openSession(id).use { session ->
                            session.openWrite("base.apk", 0, candidate.apkBytes).use { output ->
                                apk.inputStream().use { input -> input.copyTo(output) }; session.fsync(output)
                            }
                            ensureActive()
                            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                            val callback = PendingIntent.getBroadcast(context, id, Intent(context, UpdateInstallReceiver::class.java), flags)
                            prefs.edit().putLong("attemptedVersion", candidate.versionCode).apply()
                            withContext(Dispatchers.Main.immediate) {
                                mutable.value = mutable.value.copy(message = "Android is installing the update…", busy = true)
                            }
                            session.commit(callback.intentSender)
                        }
                    } catch (failure: Exception) { installer.abandonSession(id); throw failure }
                }
            } catch (cancelled: CancellationException) { installing = false; mutable.value = mutable.value.copy(busy = false); throw cancelled }
            catch (_: Exception) { installing = false; mutable.value = mutable.value.copy(message = "Update installation failed. Try again.", busy = false) }
        }
    }

    internal fun result(intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val next = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                installing = false; confirmation = next
                mutable.value = mutable.value.copy(message = "Android requires approval to install the update.", busy = false, ready = true)
                if (foreground && next != null) install()
            }
            PackageInstaller.STATUS_SUCCESS -> { installing = false; apk.delete(); envelope.delete(); mutable.value = mutable.value.copy(message = "Update installed. Reopen QuietScreen.", busy = false, ready = false) }
            else -> { installing = false; confirmation = null; mutable.value = mutable.value.copy(message = "Update was not installed. You can try again.", busy = false, ready = true) }
        }
    }

    companion object {
        @Volatile private var instance: UpdateManager? = null
        fun get(context: Context): UpdateManager = instance ?: synchronized(this) {
            instance ?: UpdateManager(context.applicationContext).also { instance = it }
        }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { UpdateManager.get(context).result(intent) }
}
