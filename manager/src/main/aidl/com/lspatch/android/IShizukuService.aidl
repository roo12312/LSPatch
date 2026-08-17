package com.lspatch.android;

interface IShizukuService {
    // Executes a single program (no shell: no pipes, globs or redirects) and returns its output.
    String runShellCommand(String cmd) = 1;

    // Allows closing the service from the client side
    void destroy() = 2;

    // Runs [script] through `sh -c`, so globs, loops and redirects work — used to gather the export
    // archive (tombstones, anr, …). Output is tail-capped like runShellCommand.
    String runShellScript(String script) = 8;

    // --- Continuous log collection (shell UID owns the files; the app reads them back through
    // readLogPart, never touching the filesystem itself — cross-UID reads of /data/local/tmp are
    // not otherwise permitted). Appended with fresh ids; existing ids are never renumbered. ---

    // Starts a collector that fans one live logcat into two rotating, timestamped streams in
    // [logDir]: "verbose" (every line) and "framework" (lines from a uid in [relevantUids], plus
    // AndroidRuntime warnings/errors and fatals). Kills any collector already running first.
    boolean startLogCollector(String logDir, in int[] relevantUids) = 3;

    // Stops the running collector, if any.
    void stopLogCollector() = 4;

    // Whether the collector process is currently alive.
    boolean isLogCollectorRunning() = 5;

    // The rotating parts of one stream ([prefix] = "verbose" or "framework") in [logDir], oldest
    // first, each as "absolutePath\tsizeBytes".
    String[] listLogParts(String logDir, String prefix) = 6;

    // Reads a part file, keeping at most [maxChars] from the tail (the newest lines).
    String readLogPart(String path, int maxChars) = 7;

    // Starts a new log part on both streams without stopping the collector or deleting anything: the
    // current parts close and fresh ones open, so collection never has a gap. This is the rootless
    // equivalent of Vector's "start a new log". Returns false when no collector is running.
    boolean startNewLogPart() = 9;
}
