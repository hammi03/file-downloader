package com.downloader;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe multi-line terminal progress bar using ANSI escape codes.
 *
 * <p>Renders one animated bar per chunk plus a total summary line:
 * <pre>
 *   Chunk 1 [        0 -  3541243]  [████████████████████████████] 100%  ✓  3.4 MB
 *   Chunk 2 [ 3541244 -  7082487]  [████████████░░░░░░░░░░░░░░░░]  43%
 *   Chunk 3 [ 7082488 - 10623731]  [██░░░░░░░░░░░░░░░░░░░░░░░░░░]   7%
 *   ...
 *   Total:  [████████████████████░░░░░░░░]  72%  |  5/8 chunks  |  24.1 MB/s
 * </pre>
 *
 * <p>Uses ANSI escape sequences to move the cursor upward and overwrite previous lines
 * on each update. Works in IntelliJ's terminal and most Unix/Windows terminals.
 *
 * <p>All methods are {@code synchronized} — safe to call from multiple threads.
 */
public class ProgressBar {

    // ── ANSI escape codes ──────────────────────────────────────────────────────
    private static final String RESET     = "\033[0m";
    private static final String BOLD      = "\033[1m";
    private static final String GREEN     = "\033[32m";
    private static final String CYAN      = "\033[36m";
    private static final String YELLOW    = "\033[33m";
    private static final String CLEAR_LINE = "\033[2K";
    private static final String CURSOR_UP  = "\033[1A";

    private static final int    BAR_WIDTH = 28;
    private static final String FILL      = "█";
    private static final String EMPTY     = "░";

    // ── State ──────────────────────────────────────────────────────────────────

    private final int      total;
    private final long     fileSize;
    private final long     startTime;

    /** Per-chunk: how many bytes have been received so far. */
    private final long[]   chunkReceived;

    /** Per-chunk: total bytes expected (to - from + 1). */
    private final long[]   chunkSize;

    /** Per-chunk: whether the chunk is done. */
    private final boolean[] chunkDone;

    private final AtomicLong totalReceived = new AtomicLong(0);
    private int completedCount = 0;

    /** True after the first render — used to know how many lines to move up. */
    private boolean firstRender = true;

    /**
     * @param total    total number of chunks
     * @param fileSize total file size in bytes
     * @param ranges   list of [from, to] pairs, one per chunk
     */
    public ProgressBar(int total, long fileSize, long[][] ranges) {
        this.total         = total;
        this.fileSize      = fileSize;
        this.startTime     = System.currentTimeMillis();
        this.chunkReceived = new long[total];
        this.chunkDone     = new boolean[total];
        this.chunkSize     = new long[total];

        for (int i = 0; i < total; i++) {
            this.chunkSize[i] = ranges[i][1] - ranges[i][0] + 1;
        }

        // Print empty placeholder lines so we have space to overwrite
        for (int i = 0; i < total + 1; i++) {
            System.out.println();
        }
    }

    /**
     * Called when {@code bytes} have been received for chunk {@code chunkIndex}.
     * Re-renders all chunk bars and the total summary line in place.
     *
     * @param chunkIndex zero-based index of the chunk
     * @param bytes      number of bytes received in this update
     * @param done       true if this chunk has been fully written to disk
     */
    public synchronized void update(int chunkIndex, long bytes, boolean done) {
        chunkReceived[chunkIndex] += bytes;
        totalReceived.addAndGet(bytes);
        if (done) {
            chunkDone[chunkIndex] = true;
            completedCount++;
        }

        render();
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void render() {
        // Move cursor up to overwrite previous output (total lines + 1 summary line)
        if (!firstRender) {
            for (int i = 0; i < total + 1; i++) {
                System.out.print(CURSOR_UP + "\r" + CLEAR_LINE);
            }
        }
        firstRender = false;

        // One line per chunk
        for (int i = 0; i < total; i++) {
            System.out.println(formatChunkLine(i));
        }

        // Summary line
        System.out.print(formatTotalLine());
        System.out.flush();

        // Final newline when all chunks are done
        if (completedCount == total) {
            System.out.println();
        }
    }

    private String formatChunkLine(int i) {
        double pct    = chunkSize[i] > 0 ? (double) chunkReceived[i] / chunkSize[i] : 0;
        int    filled = (int) (pct * BAR_WIDTH);
        String bar    = FILL.repeat(filled) + EMPTY.repeat(BAR_WIDTH - filled);
        String status = chunkDone[i] ? GREEN + " ✓  " + formatBytes(chunkReceived[i]) + RESET : "";

        return String.format(CYAN + "  Chunk %2d" + RESET
                        + "  [%s] %3d%%%s",
                i + 1, bar, (int) (pct * 100), status);
    }

    private String formatTotalLine() {
        double pct     = (double) totalReceived.get() / fileSize;
        int    filled  = (int) (pct * BAR_WIDTH);
        String bar     = FILL.repeat(filled) + EMPTY.repeat(BAR_WIDTH - filled);
        long   elapsed = System.currentTimeMillis() - startTime;
        double mbps    = elapsed > 0
                ? (totalReceived.get() / 1024.0 / 1024.0) / (elapsed / 1000.0) : 0;

        if (completedCount == total) {
            return String.format(
                    BOLD + GREEN + "  Total     [%s] 100%%  |  %d/%d chunks  |  %.1f MB/s  |  %.2f s" + RESET,
                    bar, completedCount, total, mbps, elapsed / 1000.0);
        }

        return String.format(
                BOLD + "  Total     [%s] %3d%%  |  %d/%d chunks  |  %.1f MB/s" + RESET,
                bar, (int) (pct * 100), completedCount, total, mbps);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        if (bytes >= 1024)        return String.format("%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
