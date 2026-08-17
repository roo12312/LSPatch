package com.lspatch.android.data.repository

import android.os.Build
import com.lspatch.android.config.Configs
import com.lspatch.android.config.MyKeyStore
import com.lspatch.android.data.model.LogLine
import com.lspatch.android.data.model.PatchRequest
import com.lspatch.android.data.model.PatchTarget
import com.lspatch.android.lspApp
import com.lspatch.android.share.LSPConfig
import com.lspatch.android.util.ShizukuApi
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a patch log has to say before it says anything about the patch.
 *
 * A report that begins at "Parsing original apk..." is unusable for diagnosis at a distance: almost
 * every question asked of a failed patch -- which build of LSPatch, which Android, was Shizuku
 * connected, was it a split app, which modules were embedded, was a custom key in use -- is about
 * the state the patch ran in, not about the patch. All of it is known at the moment the job starts,
 * so all of it is written down then, and the report is self-contained from the first line.
 */
object PatchReport {

    private val wallClock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.ROOT)

    /** The environment and the request, as the opening lines of a job's log. */
    fun preamble(request: PatchRequest): List<String> = buildList {
        add("=== LSPatch patch report ===")
        add("Started      ${wallClock.format(Date())}")
        add("")
        add("-- Environment --")
        val core = LSPConfig.instance
        add("LSPatch      ${core.VERSION_NAME} (${core.VERSION_CODE})")
        add("Xposed API   ${core.API_CODE}")
        add("Vector core  ${core.CORE_VERSION_NAME} (${core.CORE_VERSION_HASH.orEmpty()})")
        add("Android      ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("ABI          ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        add("Device       ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        add("Build        ${Build.FINGERPRINT}")
        add(
            "Shizuku      " + if (ShizukuApi.isPermissionGranted) {
                "granted, API " + runCatching { Shizuku.getVersion() }.getOrNull()
            } else if (ShizukuApi.isBinderAvailable) "running, not granted" else "unavailable"
        )
        add("Installer    ${if (ShizukuApi.isPermissionGranted) "Shizuku shell" else "platform (user confirms)"}")
        // Free space matters: a patched apk is roughly the size of the original plus the framework,
        // and the failure when there is no room for it is not obviously a disk failure.
        add("Free space   ${freeSpace()}")
        add("")
        add("-- Request --")
        add("Origin       ${request.origin}")
        add("Package      ${request.packageName}")
        add("Label        ${request.label}")
        add("Target       ${targetKind(request.target)}")
        add("Mode         ${request.mode}")
        add("Sig bypass   lv${request.sigBypassLevel}")
        add("Debuggable   ${request.debuggable}")
        add("Version code ${request.versionCodeOverride?.toString() ?: "app's own"}")
        add("Inject dex   ${request.injectDex}")
        add("Added perms  ${if (request.addedPermissions.isEmpty()) "none" else request.addedPermissions.joinToString(", ")}")
        add("Keystore     ${if (MyKeyStore.useDefault) "built-in" else "custom (${MyKeyStore.file.name})"}")
        add("Verbose      ${Configs.detailPatchLogs}")
        add("")
        add("-- Input apks (${request.target.apkPaths.size}) --")
        request.target.apkPaths.forEach { path ->
            val file = File(path)
            add("  ${file.name}  ${sizeOf(file)}${if (file.exists()) "" else "  (MISSING)"}")
            add("    $path")
        }
        val modules = request.effectiveModules
        add("")
        add("-- Modules to embed (${modules.size}) --")
        if (modules.isEmpty()) {
            add("  (none)")
        } else {
            modules.forEach { module ->
                val file = File(module.apkPath)
                add("  ${module.packageName}  [${module.origin}]  ${sizeOf(file)}${if (file.exists()) "" else "  (MISSING)"}")
            }
        }
        add("")
        add("-- Patch --")
    }

    /** The opening lines of a restore, which runs no patch but fails in the same places. */
    fun restorePreamble(label: String, packageName: String): List<String> = buildList {
        add("=== LSPatch restore report ===")
        add("Started      ${wallClock.format(Date())}")
        add("")
        add("-- Environment --")
        val core = LSPConfig.instance
        add("LSPatch      ${core.VERSION_NAME} (${core.VERSION_CODE})")
        add("Android      ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("Device       ${Build.MANUFACTURER} ${Build.MODEL}")
        add(
            "Shizuku      " + if (ShizukuApi.isPermissionGranted) "granted"
            else if (ShizukuApi.isBinderAvailable) "running, not granted" else "unavailable"
        )
        add("Free space   ${freeSpace()}")
        add("")
        add("-- Restore --")
        add("Package      $packageName")
        add("Label        $label")
        add("")
    }

    /** The closing lines: what was produced, and how long the whole thing took. */
    fun outcome(produced: List<File>, elapsedMs: Long): List<String> = buildList {
        add("")
        add("-- Output (${produced.size}) --")
        produced.forEach { add("  ${it.name}  ${sizeOf(it)}") }
        add("")
        add("Patched in ${"%.1f".format(elapsedMs / 1000.0)}s")
    }

    /**
     * A failure, with every cause in the chain.
     *
     * The message of the outermost exception is usually the least specific thing about it -- the
     * useful sentence is two `caused by` links down, and a report that keeps only the top one
     * routinely throws it away.
     */
    fun failure(t: Throwable): List<String> = buildList {
        add("")
        add("-- Failed --")
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 8) {
            val prefix = if (depth == 0) "" else "Caused by: "
            add("$prefix${current::class.java.name}: ${current.message}")
            depth++
            current = current.cause?.takeIf { it !== current }
        }
        add("")
        add(t.stackTraceToString().trimEnd())
    }

    /** The whole record as one shareable document. */
    fun render(lines: List<LogLine>): String =
        lines.joinToString("\n") { line ->
            // Lines from the preamble carry no elapsed time worth showing; the patch's own do.
            if (line.elapsedMs <= 0L) line.text else "[${line.stamp}] ${line.text}"
        }

    private fun targetKind(target: PatchTarget): String = when (target) {
        is PatchTarget.InstalledApp -> "installed app"
        is PatchTarget.ApkFiles -> "apk(s) from storage"
        is PatchTarget.RecoveredOrigin -> "original recovered from the patched app"
    }

    private fun sizeOf(file: File): String =
        runCatching {
            if (!file.exists()) "-" else "%.2f MB".format(file.length() / 1024.0 / 1024.0)
        }.getOrDefault("?")

    private fun freeSpace(): String =
        runCatching {
            "%.1f GB".format(lspApp.noBackupFilesDir.usableSpace / 1024.0 / 1024.0 / 1024.0)
        }.getOrDefault("?")
}
