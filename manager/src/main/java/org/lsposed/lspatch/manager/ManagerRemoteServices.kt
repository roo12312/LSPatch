package com.lspatch.android.manager

import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.github.libxposed.service.IXposedService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import com.lspatch.android.config.ConfigManager
import com.lspatch.android.lspApp
import com.lspatch.android.share.LSPConfig
import com.lspatch.android.share.remote.FrameworkInfo
import com.lspatch.android.share.remote.LSPatchModuleService
import com.lspatch.android.share.remote.LSPatchXposedService
import com.lspatch.android.share.remote.PreferenceChangeNotifier
import com.lspatch.android.share.remote.RemoteFileStore
import com.lspatch.android.share.remote.RemotePreferenceStore
import com.lspatch.android.share.remote.ScopeSource

/**
 * The manager-mode wiring of the shared remote-service layer — the manager standing in for Vector's
 * daemon as each module's service owner.
 *
 * <p>The stores live in the <b>manager's</b> own storage, so they are persistent and reached, over the
 * binder, by both a module's hook (its [LSPatchModuleService] on `LoadedModule.service`) and its
 * companion app (its [LSPatchXposedService], pushed to the companion's exported provider). One store,
 * two readers/writers: a companion's write is what the hook reads. A per-module [PreferenceChangeNotifier]
 * is shared between the two stubs so the write reaches the hook's subscription.</p>
 *
 * <p>Scope is real here — the apps patched with the module — unlike the fixed host package embedded mode
 * reports. Hot reload is driven by [ManagerHotReloadDriver] over the hosts' attached process channels.</p>
 */
object ManagerRemoteServices {

    private const val TAG = "LSPatch-XposedService"

    private val prefs by lazy { RemotePreferenceStore(lspApp) }
    private val files by lazy { RemoteFileStore(lspApp) }

    private val notifiers = ConcurrentHashMap<String, PreferenceChangeNotifier>()
    private val moduleServices = ConcurrentHashMap<String, LSPatchModuleService>()
    private val xposedServices = ConcurrentHashMap<String, LSPatchXposedService>()

    // The manager plays the daemon for hot reload, driving it into the hosts that attached a channel.
    private val hotReloadDriver = ManagerHotReloadDriver()

    // Off the binder thread: a push opens (and can start) the companion's provider process, so it must
    // not block the getModules() reply the host is waiting on.
    private val pushExecutor = Executors.newSingleThreadExecutor { Thread(it, "lspatch-companion-push") }

    private val frameworkInfo by lazy {
        FrameworkInfo(
            "LSPatch",
            "${LSPConfig.instance.VERSION_NAME} (${LSPConfig.instance.VERSION_CODE})",
            LSPConfig.instance.VERSION_CODE.toLong(),
            IXposedService.PROP_CAP_REMOTE,
        )
    }

    // A module's scope is the set of apps patched with it. Resolved at call time, so this holds no
    // reference into ConfigManager's init.
    private val scopeSource = ScopeSource { pkg -> runBlocking { ConfigManager.getAppsForModule(pkg) } }

    private fun notifier(pkg: String) = notifiers.getOrPut(pkg) { PreferenceChangeNotifier() }

    /** The read service that rides on `LoadedModule.service`. */
    fun moduleService(pkg: String): LSPatchModuleService =
        moduleServices.getOrPut(pkg) {
            LSPatchModuleService(pkg, IXposedService.PROP_CAP_REMOTE, prefs, files, notifier(pkg))
        }

    /** The full service pushed to the companion app. */
    fun xposedService(pkg: String): LSPatchXposedService =
        xposedServices.getOrPut(pkg) {
            LSPatchXposedService(pkg, frameworkInfo, prefs, files, notifier(pkg), scopeSource, hotReloadDriver)
        }

    /**
     * Pushes the module's service into its companion app's exported `XposedProvider`. A plain
     * `ContentResolver.call` reaches an exported provider without the privileged
     * `getContentProviderExternal` a rooted daemon uses; it quietly fails when the module ships no
     * companion (no such authority), which is the common case and not worth logging loudly.
     */
    fun pushToCompanion(pkg: String): Boolean {
        val uri = Uri.parse("content://$pkg${IXposedService.AUTHORITY_SUFFIX}")
        return runCatching {
            val extras = Bundle().apply { putBinder("binder", xposedService(pkg).asBinder()) }
            lspApp.contentResolver.call(uri, IXposedService.SEND_BINDER, null, extras) != null
        }.getOrElse {
            Log.d(TAG, "No companion to receive the service for $pkg: ${it.message}")
            false
        }
    }

    /** Best-effort push to each named module's companion, off the caller's thread. */
    fun pushToCompanionsAsync(pkgs: Collection<String>) {
        if (pkgs.isEmpty()) return
        val snapshot = pkgs.toList()
        pushExecutor.execute { snapshot.forEach { pushToCompanion(it) } }
    }
}
