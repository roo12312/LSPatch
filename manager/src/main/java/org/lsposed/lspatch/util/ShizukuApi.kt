package com.lspatch.android.util

import android.content.ComponentName
import android.content.Context
import android.content.IntentSender
import android.content.ServiceConnection
import android.content.pm.*
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import android.os.SystemProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import com.lspatch.android.IShizukuService
import com.lspatch.android.ShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object ShizukuApi {

    @Volatile
    private var userService: IShizukuService? = null

    // This allows us to "await" the service connection
    private var userServiceDeferred = CompletableDeferred<IShizukuService>()

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = IShizukuService.Stub.asInterface(service)
            userService = binder
            userServiceDeferred.complete(binder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            userService = null
            userServiceDeferred = CompletableDeferred()
        }
    }

    private fun IBinder.wrap() = ShizukuBinderWrapper(this)
    private fun IInterface.asShizukuBinder() = this.asBinder().wrap()

    private val iPackageManager: IPackageManager by lazy {
        IPackageManager.Stub.asInterface(SystemServiceHelper.getSystemService("package").wrap())
    }

    private val iPackageInstaller: IPackageInstaller by lazy {
        IPackageInstaller.Stub.asInterface(iPackageManager.packageInstaller.asShizukuBinder())
    }

    private val packageInstaller: PackageInstaller by lazy {
        val userId = Process.myUserHandle().hashCode()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Refine.unsafeCast(PackageInstallerHidden(iPackageInstaller, "com.android.shell", null, userId))
        } else {
            Refine.unsafeCast(PackageInstallerHidden(iPackageInstaller, "com.android.shell", userId))
        }
    }

    var isBinderAvailable = false
    var isPermissionGranted by mutableStateOf(false)

    fun init(context: Context) {
        Shizuku.addBinderReceivedListenerSticky {
            isBinderAvailable = true
            isPermissionGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (isPermissionGranted) {
                // Trigger the service binding as soon as we have permission
                bindUserService(context)
            }
        }
        Shizuku.addBinderDeadListener {
            isBinderAvailable = false
            isPermissionGranted = false
            userService = null
            userServiceDeferred = CompletableDeferred()
        }
    }

    private fun bindUserService(context: Context) {
        if (userService != null) return
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShizukuService::class.java.name))
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(true)
            // Version the service by the app's version code: on an upgrade Shizuku tears down the old
            // instance and starts a fresh one, so a rebuilt ShizukuService (new AIDL, new collector)
            // actually takes effect instead of the app binding to a stale cached process.
            .version(com.lspatch.android.share.LSPConfig.instance.VERSION_CODE)

        try {
            Shizuku.bindUserService(args, userServiceConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun createPackageInstallerSession(params: PackageInstaller.SessionParams): PackageInstaller.Session {
        val sessionId = packageInstaller.createSession(params)
        val iSession = IPackageInstallerSession.Stub.asInterface(iPackageInstaller.openSession(sessionId).asShizukuBinder())
        return Refine.unsafeCast(PackageInstallerHidden.SessionHidden(iSession))
    }

    fun isPackageInstalledWithoutPatch(packageName: String): Boolean {
        val userId = Process.myUserHandle().hashCode()
        val app = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            iPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA.toLong(), userId)
        } else {
            iPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA, userId)
        }
        return (app != null) && (app.metaData?.containsKey("lspatch") != true)
    }

    fun uninstallPackage(packageName: String, intentSender: IntentSender) {
        packageInstaller.uninstall(packageName, intentSender)
    }

    /** Runs a shell command through the Shizuku user service (shell UID); null if unavailable. */
    suspend fun runShellCommand(command: String): String? {
        val service = userService ?: withTimeoutOrNull(3000) { userServiceDeferred.await() } ?: return null
        return try {
            service.runShellCommand(command)
        } catch (e: Exception) {
            null
        }
    }

    /** The user service, awaiting the binding for up to 3s; null if Shizuku is not available. */
    private suspend fun awaitService(): IShizukuService? =
        userService ?: withTimeoutOrNull(3000) { userServiceDeferred.await() }

    // --- Continuous log collection (see [ShizukuService]). The shell UID owns the rotating part
    // files; the app reads them back through these wrappers rather than the filesystem. ---

    /** Starts the fan-out log collector; [relevantUids] select the framework stream. */
    suspend fun startLogCollector(logDir: String, relevantUids: IntArray): Boolean {
        val service = awaitService() ?: return false
        return try {
            service.startLogCollector(logDir, relevantUids)
        } catch (e: Exception) {
            false
        }
    }

    /** Runs a shell script (`sh -c`) — globs/loops/redirects allowed; null if unavailable. */
    suspend fun runShellScript(script: String): String? {
        val service = awaitService() ?: return null
        return runCatching { service.runShellScript(script) }.getOrNull()
    }

    suspend fun stopLogCollector() {
        awaitService()?.let { runCatching { it.stopLogCollector() } }
    }

    /** Rolls both streams to a fresh part without stopping collection or deleting anything. */
    suspend fun startNewLogPart(): Boolean {
        val service = awaitService() ?: return false
        return runCatching { service.startNewLogPart() }.getOrDefault(false)
    }

    suspend fun isLogCollectorRunning(): Boolean {
        val service = awaitService() ?: return false
        return runCatching { service.isLogCollectorRunning() }.getOrDefault(false)
    }

    /** One stream's parts as (absolutePath, sizeBytes), oldest first; empty when none/unavailable. */
    suspend fun listLogParts(logDir: String, prefix: String): List<Pair<String, Long>> {
        val service = awaitService() ?: return emptyList()
        return runCatching {
            service.listLogParts(logDir, prefix).mapNotNull { row ->
                val parts = row.split('\t')
                if (parts.size == 2) parts[0] to (parts[1].toLongOrNull() ?: 0L) else null
            }
        }.getOrDefault(emptyList())
    }

    /** Reads a collected part, keeping at most [maxChars] from its tail; null if unavailable. */
    suspend fun readLogPart(path: String, maxChars: Int): String? {
        val service = awaitService() ?: return null
        return runCatching { service.readLogPart(path, maxChars) }.getOrNull()
    }

    suspend fun performDexOptMode(packageName: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34+
            // Wait up to 3 seconds for the service to connect if it hasn't yet
            val service = userService ?: withTimeoutOrNull(3000) {
                userServiceDeferred.await()
            } ?: return false

            return try {
                val command = "cmd package compile -m verify -f $packageName"
                val output = service.runShellCommand(command)
                // Return true if output contains "Success"
                output.contains("Success")
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        } else {
            // Legacy reflection-based method for older versions
            return try {
                iPackageManager.performDexOptMode(
                    packageName,
                    SystemProperties.getBoolean("dalvik.vm.usejitprofiles", false),
                    "verify",
                    true,
                    true,
                    null
                )
            } catch (e: Exception) {
                false
            }
        }
    }
}
