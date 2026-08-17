package com.lspatch.android.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import com.lspatch.android.share.LSPConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.lspatch.android.service.LogCollectorService
import com.lspatch.android.util.LSPPackageManager
import com.lspatch.android.util.ShizukuApi
import org.matrix.vector.ui.logs.LogContent
import org.matrix.vector.ui.logs.LogFacets
import org.matrix.vector.ui.logs.LogIndex
import org.matrix.vector.ui.logs.LogLevel
import org.matrix.vector.ui.logs.LogQuery
import org.matrix.vector.ui.logs.LogResetKind
import org.matrix.vector.ui.logs.LogRow
import org.matrix.vector.ui.logs.LogScanResult
import org.matrix.vector.ui.logs.LogSource
import org.matrix.vector.ui.logs.isThrowableHeader

/**
 * LSPatch's Shizuku-backed implementation of the shared Logs screen's [LogSource].
 *
 * Where Vector streams a rotating file from a root daemon, LSPatch has [LogCollectorService] keep a
 * shell-side collector running continuously (see [ShizukuService]): it fans one live logcat into two
 * rotating, timestamped stream files the shell user owns — `verbose` (every line) and `framework`.
 * This reads those parts back — so the screen's part chevrons are real rotations, and logs captured
 * while the screen was closed are still there — falling back to a one-shot `logcat -d` snapshot only
 * in the gap before the collector has produced anything.
 *
 * The framework stream is routed at collection time by uid: a line joins it when it comes from the
 * manager, a patched app or a module (their uids passed to the collector), or is an AndroidRuntime
 * warning/error or any fatal line. So the read side just parses the already-routed part — no per-line
 * package resolution — and the manager's own uid being in the set is why the stream is never empty.
 */
class LSPLogSource(private val context: Context) : LogSource {

    private val _wordWrap = MutableStateFlow(false)
    override val wordWrap: StateFlow<Boolean> = _wordWrap.asStateFlow()

    // Default on -- a trace opens under its line; turning the setting off routes it to the shared
    // trace screen the Logs page navigates to instead.
    private val _tracesInline = MutableStateFlow(true)
    override val tracesInline: StateFlow<Boolean> = _tracesInline.asStateFlow()

    override suspend fun parts(verbose: Boolean): List<String> =
        // Both streams are collected and rotated on disk now, so both page through real parts: the
        // verbose stream is every line, the framework stream is the uid/crash-routed subset the
        // collector wrote separately (see [ShizukuService]).
        ShizukuApi.listLogParts(LogCollectorService.LOG_DIR, streamPrefix(verbose)).map { it.first }

    override suspend fun open(verbose: Boolean, part: String?): Result<LogContent?> {
        val prefix = streamPrefix(verbose)
        val raw =
            withContext(Dispatchers.IO) {
                if (part != null) {
                    ShizukuApi.readLogPart(part, LIVE_MAX)
                } else {
                    val newest =
                        ShizukuApi.listLogParts(LogCollectorService.LOG_DIR, prefix).lastOrNull()?.first
                    val live = newest?.let { ShizukuApi.readLogPart(it, LIVE_MAX) }
                    // Before the collector has written anything (Shizuku just granted, service still
                    // spinning up), fall back to a one-shot snapshot so the screen is never blank.
                    if (!live.isNullOrBlank()) live
                    else ShizukuApi.runShellCommand(snapshotCommand(verbose))
                }
            } ?: return Result.failure(IOException("the Shizuku shell service is unavailable"))

        val entries =
            withContext(Dispatchers.Default) {
                val all = parseLogcat(raw)
                // A collected framework part is already the routed stream, so it needs no further
                // filtering; only the fallback snapshot — a plain tag-filtered logcat standing in for
                // it until the collector produces a part — is left as logcat filtered it.
                // Re-index densely: the window logic addresses entries by position, and the lazy list
                // keys on the entry's index, so the two must agree after any lines were dropped.
                all.mapIndexed { i, e -> if (e.index == i) e else e.copy(index = i) }
            }
        return Result.success(LogcatContent(entries))
    }

    override val canConfigureVerbose: Boolean = false

    override suspend fun isVerboseEnabled(): Boolean = false

    override suspend fun setVerboseEnabled(enabled: Boolean): Boolean = enabled

    override val canSaveArchive: Boolean = true
    override val archiveMimeType: String = "application/zip"

    override fun archiveName(): String =
        "lspatch-report-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip"

    /**
     * A bug-report archive, the LSPatch answer to Vector's daemon report: a zip gathering everything
     * a report can act on, pulled through the Shizuku shell. It carries the whole collected history —
     * both the verbose and the framework stream, part by part — plus the crash artefacts a shell user
     * can reach with adb-level rights: tombstones, ANR traces, and the manager's own process state.
     * There is no separate one-shot logcat (the rotations already are the log) and no dmesg. Every
     * shell capture is best-effort and tail-capped for the Binder limit, so an unreadable one (a
     * tombstone dir a stricter build denies) is simply omitted rather than failing the export.
     */
    override suspend fun saveArchive(uri: Uri, verbose: Boolean): Result<Unit> =
        runCatching {
            withContext(Dispatchers.IO) {
                val out =
                    context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("could not open the document to write")
                ZipOutputStream(out.buffered()).use { zip ->
                    val c = LSPConfig.instance
                    zip.setComment(
                        "LSPatch ${c.VERSION_NAME} (${c.VERSION_CODE}) API ${c.API_CODE} " +
                            "Vector ${c.CORE_VERSION_NAME} ${c.CORE_VERSION_HASH}"
                    )

                    suspend fun entry(name: String, body: String?) {
                        if (body.isNullOrEmpty()) return
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(body.toByteArray())
                        zip.closeEntry()
                    }

                    fun fileEntry(name: String, file: File) {
                        if (!file.exists() || file.length() == 0L) return
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }

                    // A directory captured file-by-file into a zip folder, the way Vector's daemon
                    // addDir does it — one entry per file under [prefix], preserving the structure —
                    // rather than flattening everything into one blob. The shell lists and reads each
                    // file (the app cannot reach these paths cross-UID). Empty when it has no rights.
                    suspend fun addDir(prefix: String, dir: String) {
                        val names =
                            ShizukuApi.runShellScript("ls -1 $dir 2>/dev/null").orEmpty()
                                .lineSequence()
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .toList()
                        for (name in names) {
                            entry("$prefix/$name", ShizukuApi.runShellScript("cat '$dir/$name' 2>/dev/null"))
                        }
                    }

                    entry("device.txt", deviceReport())
                    entry("packages.txt", packageReport())
                    // A verbatim copy of the module/scope database (the app owns it, so no shell is
                    // needed), the way Vector's report ships modules_config.db. The -wal/-shm side
                    // files go too, so the copy can be replayed to the exact committed + pending state.
                    val db = context.getDatabasePath(CONFIG_DB)
                    fileEntry("database/${db.name}", db)
                    fileEntry("database/${db.name}-wal", File("${db.path}-wal"))
                    fileEntry("database/${db.name}-shm", File("${db.path}-shm"))
                    // Both streams' collected rotations, oldest first — the report's own history.
                    for (prefix in listOf("verbose", "framework")) {
                        ShizukuApi.listLogParts(LogCollectorService.LOG_DIR, prefix).forEach { (path, _) ->
                            entry("logs/${path.substringAfterLast('/')}", ShizukuApi.readLogPart(path, LIVE_MAX))
                        }
                    }
                    // Native crash dumps, as their own folder. (ANR traces are omitted: /data/anr is
                    // not readable at the shell's rights — it needs a root dumpstate/bugreport.)
                    addDir("tombstones", "/data/tombstones")
                    // "self": the manager's own live process state, each file on its own like Vector's
                    // proc/<pid> folder, so a report shows what the app was doing.
                    val pid = android.os.Process.myPid()
                    entry("self/status", ShizukuApi.runShellScript("cat /proc/$pid/status 2>/dev/null"))
                    entry("self/cmdline", ShizukuApi.runShellScript("tr '\\0' ' ' < /proc/$pid/cmdline 2>/dev/null"))
                    entry("self/maps", ShizukuApi.runShellScript("cat /proc/$pid/maps 2>/dev/null"))
                    entry("getprop.txt", ShizukuApi.runShellCommand("getprop"))
                    entry("ps.txt", ShizukuApi.runShellCommand("ps -A -o PID,PPID,USER,NAME"))
                }
            }
        }

    /** Build, framework and device facts — the header of any report. */
    private fun deviceReport(): String {
        val c = LSPConfig.instance
        return buildString {
            appendLine("LSPatch: ${c.VERSION_NAME} (${c.VERSION_CODE})")
            appendLine("Xposed API: ${c.API_CODE}")
            appendLine("Vector: ${c.CORE_VERSION_NAME} (${c.CORE_VERSION_CODE}) ${c.CORE_VERSION_HASH}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Fingerprint: ${Build.FINGERPRINT}")
        }
    }

    /** Every patched app and module by package name, so a report names exactly what is in play. */
    private fun packageReport(): String {
        val modules = LSPPackageManager.appList.filter { it.isModule }
        val patched =
            LSPPackageManager.appList.filter { it.app.metaData?.containsKey("lspatch") == true }
        return buildString {
            appendLine("Modules (${modules.size}):")
            if (modules.isEmpty()) appendLine("  (none)")
            else modules.forEach { appendLine("  ${it.app.packageName}  ${it.label}") }
            appendLine()
            appendLine("Applications (${patched.size}):")
            if (patched.isEmpty()) appendLine("  (none)")
            else patched.forEach { appendLine("  ${it.app.packageName}  ${it.label}") }
        }
    }

    // ROTATE, like Vector -- not a destructive clear. The old clear stopped the collector, wiped both
    // streams and ran `logcat -c`, then restarted; the restart raced the wipe and framework collection
    // could come back empty (the reported "clear stops the framework log" bug). Starting a new part
    // keeps the collector running, so there is never a gap.
    override val resetKind: LogResetKind = LogResetKind.ROTATE

    override suspend fun reset(verbose: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            // Non-destructive: the collector closes the current parts and opens fresh ones, deleting
            // nothing -- the closed parts stay as chevrons until the rotation cap prunes them. If no
            // collector is running yet (Shizuku just granted), starting it opens fresh parts, the same
            // fresh-slate outcome.
            if (ShizukuApi.isLogCollectorRunning()) {
                ShizukuApi.startNewLogPart()
            } else {
                ShizukuApi.startLogCollector(
                    LogCollectorService.LOG_DIR,
                    LogCollectorService.relevantUids(context),
                )
            }
        }

    override fun setWordWrap(enabled: Boolean) {
        _wordWrap.value = enabled
    }

    override fun setTracesInline(inline: Boolean) {
        _tracesInline.value = inline
    }

    /** The collector's file prefix for each stream — the two it fans logcat into. */
    private fun streamPrefix(verbose: Boolean): String = if (verbose) "verbose" else "framework"

    private fun snapshotCommand(verbose: Boolean): String =
        if (verbose) "logcat -d -v threadtime -t 4000"
        else "logcat -d -b main -b crash -v threadtime -s $LOG_FILTERSPEC"

    private companion object {
        /** Per-read tail cap, matching the shell service's own Binder-safe limit. */
        const val LIVE_MAX = 400_000

        /** The Room database of modules and their scopes; copied into the export. */
        const val CONFIG_DB = "modules_config.db"

        // Filter for the one-shot fallback snapshot only: a chatty device pushes the sparse
        // framework/crash lines out of any recent `-t N` window, so the source-side tag filter keeps
        // logcat from ever buffering the noise. The collector itself captures everything and the
        // framework stream is derived on read.
        val LOG_FILTERSPEC =
            buildList {
                    for (tag in
                        listOf(
                            "LSPatch",
                            "LSPlant",
                            "LSPosed",
                            "XposedBridge",
                            "Xposed",
                            "VectorNative",
                            "VectorModuleManager",
                            "VectorLifecycleManager",
                            "VectorBootstrap",
                            "VectorContext",
                            "VectorStartup",
                            "VectorService",
                            "VectorDaemon",
                        )) add("$tag:V")
                    add("AndroidRuntime:E") // uncaught-exception stack traces
                    add("libc:F") // native fatal signals
                    add("DEBUG:F") // tombstone dumps
                }
                .joinToString(" ")
    }
}

/**
 * A [LogContent] over an in-memory `threadtime` snapshot.
 *
 * The snapshot is bounded and already parsed, so this satisfies the windowed reader contract
 * trivially: the "index" is a dense line map, a window is a slice of the list, and a scan is one
 * pass over it. `threadtime` carries no multi-line writev the way the daemon's own framing does, so
 * each element is already one logical entry — a plain line, or a crash [parseLogcat] folded, whose
 * frames live in its [LogRow.Entry.continuation] and whose own line stays its header. Either way one
 * index addresses one entry, so [entryStart] is the identity: a window boundary lands on an entry
 * that already carries its whole trace.
 */
class LogcatContent(private val entries: List<LogRow.Entry>) : LogContent {

    override suspend fun index(): LogIndex =
        LogIndex(LongArray(entries.size + 1) { it.toLong() }, droppedLeading = 0)

    override suspend fun readRows(index: LogIndex, lines: IntArray): List<LogRow> {
        val rows = ArrayList<LogRow>(lines.size + 8)
        var lastDate: String? = null
        for (line in lines) {
            val entry = entries.getOrNull(line) ?: continue
            if (entry.date != lastDate) {
                lastDate = entry.date
                rows.add(LogRow.DayBreak(line, entry.date))
            }
            rows.add(entry)
        }
        return rows
    }

    override fun entryStart(index: LogIndex, line: Int): Int = line

    override suspend fun scan(
        index: LogIndex,
        query: LogQuery,
        onProgress: (Float) -> Unit,
    ): LogScanResult {
        val matches = if (query.isActive) ArrayList<Int>() else null
        val tags = HashMap<String, Int>()
        val levels = HashMap<LogLevel, Int>()
        val total = entries.size.coerceAtLeast(1)
        entries.forEachIndexed { i, entry ->
            tags[entry.tag] = (tags[entry.tag] ?: 0) + 1
            levels[entry.level] = (levels[entry.level] ?: 0) + 1
            if (query.matches(entry)) matches?.add(i)
            if (i and 0x1FF == 0) onProgress(i.toFloat() / total)
        }
        onProgress(1f)
        return LogScanResult(
            matches = matches?.toIntArray(),
            facets =
                LogFacets(
                    tags = tags.entries.sortedByDescending { it.value }.map { it.key to it.value },
                    levels = levels,
                ),
        )
    }

    override fun close() {}
}

// "MM-DD HH:MM:SS.mmm  PID  TID L Tag: message" — the threadtime format.
private val THREADTIME =
    Regex("""^(\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?):\s?(.*)$""")

/**
 * Parses a `logcat -v threadtime` dump into entries, dropping anything that is not a log line.
 *
 * `threadtime` reprints the prefix on every physical line, so a crash arrives as dozens of separate
 * entries — one `E AndroidRuntime: FATAL EXCEPTION…` header and a run of `\tat …` / `Caused by:` /
 * `… N more` lines, or a native tombstone under `DEBUG`/`libc`. Left flat they show as dozens of
 * rows and the shared backtrace UI, which folds an entry's [LogRow.Entry.continuation] into a
 * foldable trace, never sees one. So a second pass folds each crash block into a single entry whose
 * header line stays the message and whose following lines become the continuation — the shape
 * Vector's own `LogFile` produces, so the same StackTrace renderer applies unchanged.
 */
fun parseLogcat(raw: String): List<LogRow.Entry> {
    // Pass one: every physical threadtime line, still flat.
    val flat = ArrayList<LogRow.Entry>()
    for (line in raw.lineSequence()) {
        val match = THREADTIME.matchEntire(line) ?: continue
        flat +=
            LogRow.Entry(
                index = flat.size,
                date = match.groupValues[1],
                time = match.groupValues[2],
                uid = 0, // threadtime carries no uid
                pid = match.groupValues[3].toIntOrNull() ?: 0,
                tid = match.groupValues[4].toIntOrNull() ?: 0,
                level = LogLevel.of(match.groupValues[5][0]),
                tag = match.groupValues[6].trim(),
                message = match.groupValues[7],
            )
    }
    return foldCrashes(flat)
}

/** The tags that carry a crash dump, each line of which threadtime reprints under the same tag. */
private fun isCrashTag(tag: String): Boolean =
    tag == "AndroidRuntime" || tag == "DEBUG" || tag == "libc"

private val JAVA_MORE = Regex("""^\s*\.\.\. \d+ more$""")
private val NATIVE_FRAME = Regex("""^\s*#\d+\s+pc\b.*""")

// Flush-left lines a native tombstone prints between its indented registers and frames.
private val NATIVE_MARKERS =
    listOf(
        "*** ",
        "Build fingerprint:",
        "Revision:",
        "ABI:",
        "Timestamp:",
        "Process uptime:",
        "Cmdline:",
        "pid:",
        "signal ",
        "Abort message:",
        "backtrace:",
        "stack:",
        "memory near",
        "code around",
    )

/**
 * Whether [message] continues the crash its header began, rather than starting something new.
 *
 * Deliberately generous, because the tag is already pinned to one crash stream and the pid to one
 * process: a run of same-tag, same-pid lines under `AndroidRuntime`/`DEBUG`/`libc` is a single dump.
 * Indented lines (frames, registers, memory) are caught by their leading whitespace; the flush-left
 * links of a Java chain and the headings of a native dump are named out.
 */
/**
 * Whether [message] is a frame of a Java stack trace.
 *
 * Stricter than [continuesCrash], and deliberately so: this is the test applied under *ordinary*
 * tags, where a leading space means nothing in particular and swallowing every indented line would
 * fold unrelated output into whatever happened to precede it. Only the shapes `Throwable` actually
 * prints are accepted.
 */
private fun continuesJavaTrace(message: String): Boolean {
    val trimmed = message.trimStart()
    if (trimmed.startsWith("at ")) return true
    if (JAVA_MORE.matches(message)) return true
    if (trimmed.startsWith("Caused by:") || trimmed.startsWith("Suppressed:")) return true
    return false
}

private fun continuesCrash(message: String): Boolean {
    if (message.isBlank()) return true
    if (message[0] == '\t' || message[0] == ' ') return true // frames, registers, memory dumps
    if (message.startsWith("at ")) return true
    if (JAVA_MORE.matches(message)) return true
    if (message.startsWith("Caused by:") || message.startsWith("Suppressed:")) return true
    if (message.startsWith("Process:")) return true // AndroidRuntime's second line
    if (isThrowableHeader(message)) return true // the thrown type, whether or not obfuscation kept its suffix
    if (NATIVE_FRAME.matches(message)) return true
    return NATIVE_MARKERS.any(message::startsWith)
}

/**
 * Folds each trace or crash block into one entry, then re-indexes densely.
 *
 * Two rules, because there are two kinds of block. A **crash-tagged** line (`AndroidRuntime`,
 * `DEBUG`, `libc`) opens a dump whose continuation is generous: the tag already pins it to one crash
 * stream, so indented frames, registers and native headings all belong to it.
 *
 * Every **other** tag can still print a stack trace -- `Log.w(TAG, msg, throwable)` puts the message
 * and then its `at …` frames under that tag, and a great deal of the manager's own diagnostics are
 * logged exactly that way. Those fold under the stricter [continuesJavaTrace] test, and only when a
 * trace actually follows -- either the next line is already a frame, or, for a **banner** (a line that
 * is not itself a throwable header, the notice a logger prints above the exception it is about), the
 * next line is a throwable header and the one after it a frame. A banner then absorbs that header and
 * its frames, so the whole notice reads as one entry; a header-led trace does not absorb a *second*
 * top-level header, so two back-to-back traces stay two entries.
 *
 * Dense re-indexing keeps position and [LogRow.index] in step, which the windowed reader and the lazy
 * list both rely on.
 */
private fun foldCrashes(flat: List<LogRow.Entry>): List<LogRow.Entry> {
    val out = ArrayList<LogRow.Entry>(flat.size)
    var i = 0
    while (i < flat.size) {
        val head = flat[i]
        val crash = isCrashTag(head.tag)
        fun sameStream(row: LogRow.Entry?) = row != null && row.tag == head.tag && row.pid == head.pid
        val next = flat.getOrNull(i + 1)
        val banner = !crash && !isThrowableHeader(head.message)
        val opensTrace = sameStream(next) &&
            (continuesJavaTrace(next!!.message) ||
                (banner &&
                    isThrowableHeader(next.message) &&
                    sameStream(flat.getOrNull(i + 2)) &&
                    continuesJavaTrace(flat[i + 2].message)))
        if (!crash && !opensTrace) {
            out += head
            i++
            continue
        }
        val continues: (String) -> Boolean =
            when {
                crash -> ::continuesCrash
                banner -> { msg -> continuesJavaTrace(msg) || isThrowableHeader(msg) }
                else -> ::continuesJavaTrace
            }
        val continuation = ArrayList<String>()
        var j = i + 1
        while (j < flat.size) {
            val row = flat[j]
            if (row.tag != head.tag || row.pid != head.pid || !continues(row.message)) break
            continuation += row.message
            j++
        }
        out += if (continuation.isEmpty()) head else head.copy(continuation = continuation)
        i = j
    }
    return out.mapIndexed { idx, e -> if (e.index == idx) e else e.copy(index = idx) }
}
