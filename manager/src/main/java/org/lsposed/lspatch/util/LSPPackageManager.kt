package com.lspatch.android.util

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstallerHidden.SessionParamsHidden
import android.content.pm.PackageManager
import android.content.pm.PackageManagerHidden
import android.net.Uri
import android.os.Parcelable
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.appiconloader.AppIconLoader
import com.lspatch.android.config.ConfigManager
import com.lspatch.android.data.model.ModuleBinding
import com.lspatch.android.data.model.ModuleOrigin
import com.lspatch.android.lspApp
import com.lspatch.android.share.Constants
import org.matrix.vector.ui.module.ModuleDetection
import java.io.File
import java.io.IOException
import java.text.Collator
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object LSPPackageManager {

    private const val TAG = "LSPPackageManager"
    private const val SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS"

    const val STATUS_USER_CANCELLED = -2

    private const val INSTALL_ACTION = "com.lspatch.android.action.INSTALL_RESULT"

    @Parcelize
    class AppInfo(val app: ApplicationInfo, val label: String, val isModule: Boolean = false) : Parcelable {
        val isXposedModule: Boolean
            get() = isModule

        // An LSPatch build carries its Base64 PatchConfig in the manifest's "lspatch" meta-data; its
        // presence is the marker, read the same way whether the ApplicationInfo comes from an
        // installed package or from a package archive on disk.
        val isLSPatched: Boolean
            get() = app.metaData?.containsKey("lspatch") == true
    }

    // A module is either a legacy one (manifest xposedminversion / assets/xposed_init) or a modern
    // one, marked only by META-INF/xposed/java_init.list. Only the APK scan sees the modern kind, so
    // it is computed once at fetch time rather than in a property getter.
    private fun isModuleApk(app: ApplicationInfo): Boolean {
        if (app.metaData?.get("xposedminversion") != null) return true
        val sourceDir = app.sourceDir ?: return false
        return runCatching {
            java.util.zip.ZipFile(sourceDir).use { zip ->
                zip.getEntry("META-INF/xposed/java_init.list") != null ||
                        zip.getEntry("assets/xposed_init") != null
            }
        }.getOrDefault(false)
    }

    var appList by mutableStateOf(listOf<AppInfo>())
        private set

    @SuppressLint("StaticFieldLeak")
    private val iconLoader = AppIconLoader(lspApp.resources.getDimensionPixelSize(android.R.dimen.app_icon_size), false, lspApp)
    private val appIcon = mutableMapOf<String, ImageBitmap>()

    suspend fun fetchAppList() {
        withContext(Dispatchers.IO) {
            val pm = lspApp.packageManager
            val collection = mutableListOf<AppInfo>()
            pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach {
                val label = pm.getApplicationLabel(it)
                collection.add(AppInfo(it, label.toString(), isModuleApk(it)))
                appIcon[it.packageName] = iconLoader.loadIcon(it).asImageBitmap()
            }
            collection.sortWith(compareBy(Collator.getInstance(Locale.getDefault()), AppInfo::label))
            val modules = buildMap {
                collection.forEach { if (it.isXposedModule) put(it.app.packageName, it.app.sourceDir) }
            }
            ConfigManager.updateModules(modules)
            appList = collection
        }
    }

    fun getIcon(appInfo: AppInfo) = appIcon[appInfo.app.packageName]!!

    // Module icons for a patched app, keyed by the patched app's package so re-scrolling the list
    // does not re-open the apk or re-extract embedded modules. A Local patch draws on the manager's
    // installed-module icons; an Integrated patch has its modules baked into the apk, so their icons
    // are only recoverable by extracting each entry and loading it as an archive.
    private val moduleIconsCache = mutableMapOf<String, List<ImageBitmap>>()

    /**
     * The icons of the modules a patched app reaches, mirroring how a module row shows the apps it
     * reaches. Local (manager-backed) patches resolve modules from the live scope and reuse the
     * already-loaded installed icons; Integrated patches read the apks baked under
     * [Constants.EMBEDDED_MODULES_ASSET_PATH]. A module whose icon can't be loaded is skipped.
     */
    suspend fun moduleIconsFor(appInfo: AppInfo, useManager: Boolean): List<ImageBitmap> {
        val pkg = appInfo.app.packageName
        moduleIconsCache[pkg]?.let { return it }
        val icons =
            if (useManager) {
                withContext(Dispatchers.IO) {
                    ConfigManager.getModulesForApp(pkg).mapNotNull { module ->
                        // Prefer the already-loaded installed icon; fall back to loading it on demand
                        // for a module that installed after the app list was last fetched.
                        appIcon[module.pkgName] ?: runCatching {
                            val info = lspApp.packageManager.getApplicationInfo(module.pkgName, 0)
                            iconLoader.loadIcon(info).asImageBitmap()
                        }.getOrNull()
                    }
                }
            } else {
                // Derived from the same enumeration the detail page reads, so a row's reach band and
                // the page it opens can never disagree about which modules an app carries.
                embeddedModulesOf(appInfo).mapNotNull { it.icon }
            }
        moduleIconsCache[pkg] = icons
        return icons
    }

    /** Drops the cached module reach for [packageName], after its scope or its apk has changed. */
    fun invalidateModuleIcons(packageName: String) {
        moduleIconsCache.remove(packageName)
        embeddedModulesCache.remove(packageName)
    }

    // Keyed by package and by the host apk's timestamp+length: re-patching an app replaces its apk,
    // and a cache that ignored that would keep showing the module set the app used to carry.
    private val embeddedModulesCache = mutableMapOf<String, Pair<String, List<ModuleBinding>>>()

    /**
     * The modules baked into an Integrated patched app, read out of its own apk.
     *
     * The entry name under [Constants.EMBEDDED_MODULES_ASSET_PATH] *is* the module's package name --
     * the loader depends on exactly that when it enumerates them at runtime -- so the set can be
     * listed without installing anything. Each entry is extracted once and inspected as an archive,
     * which is what lets an embedded module render with the same name, version, API badge and
     * description an installed one gets.
     */
    suspend fun embeddedModulesOf(appInfo: AppInfo): List<ModuleBinding> {
        val pkg = appInfo.app.packageName
        val apkPath = appInfo.app.sourceDir ?: return emptyList()
        val stamp = runCatching { File(apkPath).let { "${it.lastModified()}:${it.length()}" } }.getOrDefault("")
        embeddedModulesCache[pkg]?.let { (cachedStamp, cached) -> if (cachedStamp == stamp) return cached }

        val bindings = withContext(Dispatchers.IO) {
            runCatching {
                val outDir = lspApp.cacheDir.resolve("embedded-modules").resolve(pkg)
                    .also { it.mkdirs() }
                java.util.zip.ZipFile(apkPath).use { zip ->
                    zip.entries().asSequence()
                        .filter {
                            !it.isDirectory &&
                                it.name.startsWith(Constants.EMBEDDED_MODULES_ASSET_PATH) &&
                                it.name != Constants.EMBEDDED_MODULES_ASSET_PATH
                        }
                        .mapNotNull { entry ->
                            val fileName = entry.name.substringAfterLast('/')
                            if (fileName.isEmpty()) return@mapNotNull null
                            runCatching {
                                val tmp = outDir.resolve(fileName)
                                zip.getInputStream(entry).use { input ->
                                    tmp.outputStream().use { output -> input.copyTo(output) }
                                }
                                bindingFromArchive(tmp, ModuleOrigin.Embedded)
                                    // The entry name is authoritative for the package: it is what the
                                    // loader keys on, so a manifest disagreeing with it would still
                                    // load under the name written here.
                                    ?.copy(packageName = fileName.removeSuffix(".apk"))
                            }.getOrNull()
                        }
                        .toList()
                }
            }.getOrDefault(emptyList())
        }
        embeddedModulesCache[pkg] = stamp to bindings
        return bindings
    }

    /** Every installed Xposed module, as bindings a patch can embed or a scope can enable. */
    suspend fun installedModuleBindings(): List<ModuleBinding> = withContext(Dispatchers.IO) {
        appList.filter { it.isXposedModule }.map { info ->
            val pm = lspApp.packageManager
            val manifest = runCatching { ModuleDetection.inspect(info.app, pm) }.getOrNull()
            val pkgInfo = runCatching { pm.getPackageInfo(info.app.packageName, 0) }.getOrNull()
            ModuleBinding(
                packageName = info.app.packageName,
                label = info.label,
                versionName = pkgInfo?.versionName,
                versionCode = pkgInfo?.longVersionCode ?: 0L,
                manifest = manifest,
                icon = appIcon[info.app.packageName],
                apkPath = info.app.sourceDir,
                origin = ModuleOrigin.Installed,
            )
        }
    }

    /** Reads a module apk the user picked from storage. Null when it is not a module at all. */
    suspend fun moduleBindingFromFile(apk: File): ModuleBinding? = withContext(Dispatchers.IO) {
        bindingFromArchive(apk, ModuleOrigin.Picked)?.takeIf { it.manifest?.isModule == true }
    }

    /**
     * Inspects an apk sitting on disk rather than installed.
     *
     * [PackageManager.GET_META_DATA] is load-bearing, not defensive: `ModuleDetection.inspect`
     * decides a module is legacy by looking for the `xposedminversion` key in `metaData`, so
     * without the flag every legacy module comes back as "not a module" and silently disappears
     * from the list it is supposed to be in.
     */
    private fun bindingFromArchive(apk: File, origin: ModuleOrigin): ModuleBinding? {
        val pm = lspApp.packageManager
        val pkgInfo = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_META_DATA)
            ?: return null
        val info = pkgInfo.applicationInfo ?: return null
        // getPackageArchiveInfo leaves these unset; both the icon loader and the module inspector
        // need them to resolve resources and entries out of the archive.
        info.sourceDir = apk.absolutePath
        info.publicSourceDir = apk.absolutePath
        return ModuleBinding(
            packageName = info.packageName ?: pkgInfo.packageName,
            label = runCatching { pm.getApplicationLabel(info).toString() }
                .getOrDefault(pkgInfo.packageName),
            versionName = pkgInfo.versionName,
            versionCode = pkgInfo.longVersionCode,
            manifest = runCatching { ModuleDetection.inspect(info, pm) }.getOrNull(),
            icon = runCatching { iconLoader.loadIcon(info).asImageBitmap() }.getOrNull(),
            apkPath = apk.absolutePath,
            origin = origin,
        )
    }

    suspend fun cleanTmpApkDir() {
        withContext(Dispatchers.IO) {
            lspApp.tmpApkDir.listFiles()?.forEach(File::delete)
        }
    }

    /**
     * Installs [apks] as one package, through Shizuku's shell installer or the platform one.
     *
     * The single install path in the app. It replaces four near-identical session bodies that
     * differed only in which installer they opened and where they read from -- and, in two of them,
     * in a filter for [Constants.PATCH_FILE_SUFFIX] that made restoring an original app impossible,
     * since an apk recovered from inside a patched one is named `base.apk`.
     *
     * Every file goes into one session, so an app and its splits install atomically or not at all.
     * Lengths are declared exactly: these are plain files, unlike the storage-access documents the
     * old paths read, which reported their size only sometimes.
     */
    suspend fun installFiles(apks: List<File>, useShizuku: Boolean): Pair<Int, String?> {
        Log.i(TAG, "Install ${apks.size} apk(s), shizuku=$useShizuku")
        var status = PackageInstaller.STATUS_FAILURE
        var message: String? = null
        withContext(Dispatchers.IO) {
            runCatching {
                if (apks.isEmpty()) throw IOException("No apk to install")
                apks.firstOrNull { !it.exists() || it.length() == 0L }?.let {
                    throw IOException("${it.name} is missing or empty")
                }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                if (useShizuku) {
                    var flags = Refine.unsafeCast<SessionParamsHidden>(params).installFlags
                    flags = flags or PackageManagerHidden.INSTALL_ALLOW_TEST or PackageManagerHidden.INSTALL_REPLACE_EXISTING
                    Refine.unsafeCast<SessionParamsHidden>(params).installFlags = flags
                    ShizukuApi.createPackageInstallerSession(params).use { session ->
                        apks.forEach { apk -> session.writeApk(apk) }
                        var result: Intent? = null
                        suspendCoroutine { cont ->
                            val adapter = IntentSenderHelper.IIntentSenderAdaptor { intent ->
                                result = intent
                                cont.resume(Unit)
                            }
                            session.commit(IntentSenderHelper.newIntentSender(adapter))
                        }
                        result?.let {
                            status = it.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                            message = it.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        } ?: throw IOException("Intent is null")
                    }
                } else {
                    val installer = lspApp.packageManager.packageInstaller
                    val sessionId = installer.createSession(params)
                    installer.openSession(sessionId).use { session ->
                        apks.forEach { apk -> session.writeApk(apk) }
                        val result = awaitUserAction("$INSTALL_ACTION.$sessionId", sessionId) { sender ->
                            session.commit(sender)
                        }
                        status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                        message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    }
                }
            }.onFailure {
                status = PackageInstaller.STATUS_FAILURE
                message = it.message + "\n" + it.stackTraceToString()
            }
        }
        return Pair(status, message)
    }

    private fun PackageInstaller.Session.writeApk(apk: File) {
        Log.d(TAG, "Add ${apk.name}")
        apk.inputStream().use { input ->
            openWrite(apk.name, 0, apk.length()).use { output ->
                input.copyTo(output)
                fsync(output)
            }
        }
    }

    /**
     * Uninstalls [packageName] through the platform installer (OS confirmation UI). Used, without
     * Shizuku, to clear a differently-signed original before a system install can replace it.
     */
    suspend fun uninstallBySystem(packageName: String): Pair<Int, String?> {
        Log.i(TAG, "Perform system uninstall of $packageName")
        val context = lspApp
        var status = PackageInstaller.STATUS_FAILURE
        var message: String? = null
        withContext(Dispatchers.IO) {
            runCatching {
                val result = awaitUserAction("$INSTALL_ACTION.uninstall.$packageName", packageName.hashCode()) { sender ->
                    context.packageManager.packageInstaller.uninstall(packageName, sender)
                }
                status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            }.onFailure {
                status = PackageInstaller.STATUS_FAILURE
                message = it.message + "\n" + it.stackTraceToString()
            }
        }
        return Pair(status, message)
    }

    /**
     * Installs a single downloaded apk — a store module or the manager's own self-update.
     *
     * Prefers Shizuku, which installs silently through the shell installer just as patched-app
     * installs do, so no confirmation dialog is needed. Only when Shizuku is unavailable does it fall
     * back to the platform installer, which shows the OS confirm dialog.
     */
    suspend fun installApk(apk: File): Pair<Int, String?> =
        installFiles(listOf(apk), ShizukuApi.isPermissionGranted)

    /**
     * Whether [packageName] is installed and is NOT already an LSPatch build — read through the app's
     * own PackageManager (no Shizuku), so it is usable on the system-install fallback path.
     */
    fun isInstalledWithoutPatch(packageName: String): Boolean {
        return try {
            val info = lspApp.packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
            info.applicationInfo?.metaData?.containsKey("lspatch") != true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Drives one platform-installer action (install/uninstall) to completion. Registers a result
     * receiver, hands the installer a broadcast [IntentSender] via [commit], transparently launches
     * the OS confirmation dialog on STATUS_PENDING_USER_ACTION, and resumes with the terminal Intent.
     */
    private suspend fun awaitUserAction(
        action: String,
        requestCode: Int,
        commit: (IntentSender) -> Unit
    ): Intent = suspendCoroutine { cont ->
        val context = lspApp
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                    return
                }
                runCatching { context.unregisterReceiver(this) }
                cont.resume(intent)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val pending = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            commit(pending.intentSender)
        } catch (t: Throwable) {
            // Nothing will fire the receiver now; unregister it and fail instead of hanging forever.
            runCatching { context.unregisterReceiver(receiver) }
            cont.resumeWithException(t)
        }
    }

    suspend fun uninstall(packageName: String): Pair<Int, String?> {
        var status = PackageInstaller.STATUS_FAILURE
        var message: String? = null
        withContext(Dispatchers.IO) {
            runCatching {
                var result: Intent? = null
                suspendCoroutine { cont ->
                    val adapter = IntentSenderHelper.IIntentSenderAdaptor { intent ->
                        result = intent
                        cont.resume(Unit)
                    }
                    val intentSender = IntentSenderHelper.newIntentSender(adapter)
                    ShizukuApi.uninstallPackage(packageName, intentSender)
                }
                result?.let {
                    status = it.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    message = it.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                } ?: throw IOException("Intent is null")
            }.onFailure {
                status = PackageInstaller.STATUS_FAILURE
                message = "Exception happened\n$it"
            }
        }
        return Pair(status, message)
    }

    suspend fun getAppInfoFromApks(apks: List<Uri>): Result<List<AppInfo>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                // Expand each selection into plain apk files first: a selection may be a single apk,
                // or an app bundle (.xapk/.apks/.apkm) — a zip whose entries are the base and split
                // apks of one app. A plain apk is itself a zip, but its entries are the manifest, dex
                // and resources, never a nested *.apk, so that presence cleanly tells the two apart.
                val apkFiles = mutableListOf<File>()
                for (uri in apks) {
                    val src = DocumentFile.fromSingleUri(lspApp, uri)
                        ?: throw IOException("DocumentFile is null")
                    val copied = lspApp.tmpApkDir.resolve(src.name ?: "selected")
                    val input = lspApp.contentResolver.openInputStream(uri)
                        ?: throw IOException("InputStream is null")
                    input.use {
                        copied.outputStream().use { output -> input.copyTo(output) }
                    }
                    val extracted = extractBundle(copied)
                    if (extracted != null) {
                        // The container zip itself is not an apk to patch; keep only its contents.
                        apkFiles.addAll(extracted)
                        copied.delete()
                    } else {
                        apkFiles.add(copied)
                    }
                }

                var primary: ApplicationInfo? = null
                val splits = mutableListOf<String>()
                val appInfos = apkFiles.mapNotNull { dst ->
                    val appInfo = lspApp.packageManager.getPackageArchiveInfo(
                        dst.absolutePath, PackageManager.GET_META_DATA
                    )?.applicationInfo
                    appInfo?.sourceDir = dst.absolutePath
                    if (appInfo == null) {
                        splits.add(dst.absolutePath)
                        return@mapNotNull null
                    }
                    if (primary == null) {
                        primary = appInfo
                    }
                    val label = lspApp.packageManager.getApplicationLabel(appInfo).toString()
                    AppInfo(appInfo, label, isModuleApk(appInfo))
                }
                // TODO: Check selected apks are from the same app
                primary?.splitSourceDirs = splits.toTypedArray()
                if (appInfos.isEmpty()) throw IOException("No apks")
                appInfos
            }.recoverCatching { t ->
                cleanTmpApkDir()
                Log.e(TAG, "Failed to load apks", t)
                throw t
            }
        }
    }

    /**
     * The apks inside an app bundle, or null when [file] is a plain apk (or not a readable zip).
     *
     * A bundle (.xapk/.apks/.apkm) is a zip carrying a base apk and its splits; a plain apk never
     * holds a nested `*.apk` entry, so the absence of one means "use the file as-is". Each contained
     * apk is extracted into tmpApkDir under its own basename for the split-install path to pick up.
     */
    private fun extractBundle(file: File): List<File>? =
        runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val apkEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.substringAfterLast('/').endsWith(".apk", ignoreCase = true) }
                    .toList()
                if (apkEntries.isEmpty()) {
                    null
                } else {
                    apkEntries.map { entry ->
                        val out = lspApp.tmpApkDir.resolve(entry.name.substringAfterLast('/'))
                        zip.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        out
                    }
                }
            }
        }.getOrNull()

    fun getLaunchIntentForPackage(packageName: String): Intent? {
        val intentToResolve = Intent(Intent.ACTION_MAIN)
        intentToResolve.addCategory(Intent.CATEGORY_INFO)
        intentToResolve.setPackage(packageName)
        var ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)

        if (ris.size <= 0) {
            intentToResolve.removeCategory(Intent.CATEGORY_INFO)
            intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER)
            intentToResolve.setPackage(packageName)
            ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)
        }

        if (ris.size <= 0) return null

        return Intent(intentToResolve)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setClassName(
                ris[0].activityInfo.packageName,
                ris[0].activityInfo.name
            )
    }

    fun getSettingsIntent(packageName: String): Intent? {
        val intentToResolve = Intent(Intent.ACTION_MAIN)
        intentToResolve.addCategory(SETTINGS_CATEGORY)
        intentToResolve.setPackage(packageName)
        val ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)

        if (ris.size <= 0) return getLaunchIntentForPackage(packageName)

        return Intent(intentToResolve)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setClassName(
                ris[0].activityInfo.packageName,
                ris[0].activityInfo.name
            )
    }
}
