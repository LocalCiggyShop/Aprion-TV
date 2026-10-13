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
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import org.privateview.quietscreen.BuildConfig
import org.privateview.quietscreen.core.*

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
    private fun detail(code: String, source: UpdateSource? = null, priorCode: String? = null): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm Z", java.util.Locale.ROOT).format(java.util.Date())
        return "$code · ${source?.label ?: "Local app / Android installer"} · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android API ${Build.VERSION.SDK_INT} · TV time $date" +
            (priorCode?.let { " · Primary feed: $it" } ?: "")
    }
    private fun failed(error: Throwable, step: UpdateStep, source: UpdateSource? = null, priorCode: String? = null) {
        val diagnostic = UpdateDiagnostics.from(error, step)
        mutable.value = mutable.value.copy(message = diagnostic.message, detail = detail(diagnostic.code, source, priorCode) +
            ((error as? UpdateSignerFailure)?.safeDetail?.let { " · $it" } ?: ""), busy = false, ready = false, phase = UpdatePhase.IDLE)
    }
    private fun <T> save(operation: () -> T): T = try { operation() } catch (_: IOException) { throw UpdateFailure(UpdateProblem.STORAGE) }

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
        if (!installing) mutable.value = mutable.value.copy(busy = false, phase = UpdatePhase.IDLE)
    }

    fun cancelUpdate() {
        if (!mutable.value.cancellable || installing) return
        cancel()
        mutable.value = mutable.value.copy(message = "Update cancelled. Choose Check for updates to try again.", detail = null, ready = false)
    }

    fun check() {
        if (!foreground || job?.isCompleted == false || installing) return
        if (!configured) { mutable.value = mutable.value.copy(message = "This development build has no public update location."); return }
        if (context.packageName != "org.privateview.quietscreen") {
            mutable.value = mutable.value.copy(message = "This test build has no production update feed. Install the official Aprion TV APK for signed updates.",
                detail = detail("QS-U-TEST-BUILD"), busy = false, ready = false)
            return
        }
        transport.begin()
        job = scope.launch {
            var step = UpdateStep.FEED
            var source = UpdateSource.RAW
            var repaired = false
            var primary: UpdateDiagnostic? = null
            mutable.value = mutable.value.copy(message = "Checking for updates…", busy = true, ready = false, detail = null,
                phase = UpdatePhase.CHECKING, downloadedBytes = 0, totalBytes = 0)
            try {
                val available = withContext(Dispatchers.IO) {
                    val current = UpdateApk.installed(context)
                    fun verify(bytes: ByteArray) = UpdateManifest.verify(bytes, UpdateApk.key(current), context.packageName,
                        UpdateApk.version(current), Build.VERSION.SDK_INT, BuildConfig.UPDATE_OWNER, BuildConfig.UPDATE_REPO)
                    val feed = UpdateFeed(BuildConfig.UPDATE_OWNER, BuildConfig.UPDATE_REPO, BuildConfig.UPDATE_BRANCH, context.packageName)
                    val fetched = feed.fetch(onFallback = { primary = UpdateDiagnostics.from(it, UpdateStep.FEED) }) { url, route ->
                        ensureActive(); source = route
                        transport.read(url, true) { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(1024)
                        while (true) {
                            ensureActive()
                            val count = stream.read(buffer); if (count < 0) break
                            if (output.size() + count > UpdateManifest.MAX_ENVELOPE_BYTES) throw UpdateFailure(UpdateProblem.METADATA)
                            output.write(buffer, 0, count)
                        }
                        val result = output.toByteArray()
                        if (result.isEmpty()) throw UpdateFailure(UpdateProblem.METADATA)
                        result
                        }
                    }
                    val bytes = fetched.bytes
                    step = UpdateStep.MANIFEST
                    val candidate = verify(bytes)
                    if (candidate == null) { apk.delete(); envelope.delete(); null }
                    else {
                        repaired = UpdateCache.obtain(directory, bytes, verify = { file ->
                            step = UpdateStep.APK; ensureActive()
                            mutable.update { it.copy(message = "Verifying update ${candidate.versionName}…", phase = UpdatePhase.VERIFYING,
                                downloadedBytes = file.length().coerceIn(0, candidate.apkBytes), totalBytes = candidate.apkBytes) }
                            UpdateApk.verify(context, file, candidate)
                        }, download = { partial ->
                            step = UpdateStep.DOWNLOAD
                            // One retry obtains a fresh short-lived redirect if GitHub's CDN
                            // rejects an expired link. Every attempt retains the signed bounds.
                            for (attempt in 0..1) {
                                mutable.update { it.copy(message = if (attempt == 0) "Downloading update ${candidate.versionName}…" else "Retrying update download…",
                                    phase = UpdatePhase.DOWNLOADING, downloadedBytes = 0, totalBytes = candidate.apkBytes) }
                                try { transport.read(candidate.apkUrl, false) { stream ->
                                    val output = save { partial.outputStream() }
                                    try {
                                        val buffer = ByteArray(32768); var total = 0L; var lastReport = 0L
                                        while (true) {
                                            ensureActive()
                                            val count = stream.read(buffer); if (count < 0) break
                                            total += count
                                            if (total > candidate.apkBytes) throw UpdateFailure(UpdateProblem.SIZE)
                                            save { output.write(buffer, 0, count) }
                                            val now = android.os.SystemClock.elapsedRealtime()
                                            if (now - lastReport >= 250 || total == candidate.apkBytes) {
                                                mutable.update { it.copy(downloadedBytes = total) }; lastReport = now
                                            }
                                        }
                                        if (total != candidate.apkBytes) throw UpdateFailure(UpdateProblem.SIZE)
                                    } finally { save { output.close() } }
                                }; break }
                                catch (failure: UpdateFailure) {
                                    if (attempt != 0 || failure.problem != UpdateProblem.HTTP || failure.httpStatus !in setOf(401, 403)) throw failure
                                    partial.delete(); ensureActive()
                                }
                            }
                        }, checkpoint = { ensureActive() })
                        candidate
                    }
                }
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                mutable.value = mutable.value.copy(message = available?.let { "Update ${it.versionName} downloaded and verified." } ?: "You have the latest version.", busy = false, ready = available != null, phase = UpdatePhase.IDLE,
                    detail = detail(if (repaired) "QS-U-OK-CACHE-REPAIRED" else "QS-U-OK", source, primary?.code))
                if (available != null && foreground && mutable.value.automatic &&
                    prefs.getLong("attemptedVersion", 0) != available.versionCode && context.packageManager.canRequestPackageInstalls()) install()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed(error, step, source, primary?.code) }
        }
    }

    fun install() {
        if (!foreground || installing) return
        confirmation?.let { intent ->
            confirmation = null
            try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { failed(UpdateFailure(UpdateProblem.INSTALLER), UpdateStep.INSTALL) }
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            mutable.value = mutable.value.copy(message = "Allow updates from Aprion TV in Android settings, then return here.")
            try { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { failed(UpdateFailure(UpdateProblem.INSTALL_PERMISSION), UpdateStep.INSTALL) }
            return
        }
        installing = true
        job = scope.launch {
            mutable.value = mutable.value.copy(message = "Preparing installation…", busy = true, detail = null, phase = UpdatePhase.PREPARING)
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
                                mutable.value = mutable.value.copy(message = "Android is installing the update…", busy = true, phase = UpdatePhase.INSTALLING,
                                    downloadedBytes = candidate.apkBytes, totalBytes = candidate.apkBytes)
                            }
                            session.commit(callback.intentSender)
                        }
                    } catch (failure: Exception) { installer.abandonSession(id); throw failure }
                }
            } catch (cancelled: CancellationException) { installing = false; mutable.value = mutable.value.copy(busy = false, phase = UpdatePhase.IDLE); throw cancelled }
            catch (error: Exception) { installing = false; failed(error, UpdateStep.INSTALL) }
        }
    }

    internal fun result(intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val next = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                installing = false; confirmation = next
                mutable.value = mutable.value.copy(message = "Android requires approval to install the update.", busy = false, ready = true, phase = UpdatePhase.IDLE)
                if (foreground && next != null) install()
            }
            PackageInstaller.STATUS_SUCCESS -> { installing = false; apk.delete(); envelope.delete(); mutable.value = mutable.value.copy(message = "Update installed. Reopen Aprion TV.", busy = false, ready = false, phase = UpdatePhase.IDLE) }
            else -> {
                installing = false; confirmation = null
                val reason = when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateProblem.INSTALL_ABORTED
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateProblem.INSTALL_BLOCKED
                    PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateProblem.STORAGE
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateProblem.INSTALL_INCOMPATIBLE
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateProblem.INSTALL_CONFLICT
                    PackageInstaller.STATUS_FAILURE_INVALID -> UpdateProblem.INSTALL_INVALID
                    else -> UpdateProblem.INSTALLER
                }
                failed(UpdateFailure(reason), UpdateStep.INSTALL)
                mutable.value = mutable.value.copy(ready = apk.exists() && envelope.exists())
            }
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
