package com.downloader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Parallel HTTP file downloader using byte range requests (RFC 7233).
 *
 * <p>How it works:
 * <ol>
 *   <li>A HEAD request retrieves the file size and verifies the server supports range requests.</li>
 *   <li>The file is split into fixed-size chunks, each described by a [from, to] byte range.</li>
 *   <li>All chunks are downloaded in parallel using Java's async {@link HttpClient}.</li>
 *   <li>A {@link Semaphore} caps the number of concurrent connections at {@value MAX_PARALLEL_CHUNKS}.</li>
 *   <li>Each chunk is written directly to its correct offset in the output file via
 *       {@link RandomAccessFile} — no full-file RAM buffering required.</li>
 *   <li>Failed chunks are retried up to {@value MAX_RETRIES} times before failing.</li>
 * </ol>
 */
public class FileDownloader {

    /** Maximum number of chunk downloads running at the same time. */
    private static final int  MAX_PARALLEL_CHUNKS = 8;

    /** How many times a single chunk download is retried on failure before giving up. */
    private static final int  MAX_RETRIES         = 3;

    /** Minimum chunk size: 1 MB. Prevents tiny chunks on small files. */
    private static final long MIN_CHUNK_SIZE = 1024 * 1024;

    private final HttpClient httpClient;

    /**
     * Creates a downloader backed by the given {@link HttpClient}.
     * Pass a custom client to configure timeouts, proxies, or SSL settings.
     */
    public FileDownloader(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // ── Step 1: HEAD request ──────────────────────────────────────────────────

    /**
     * Sends a HEAD request to {@code url} and returns the file size in bytes.
     *
     * <p>The server response must include:
     * <ul>
     *   <li>{@code Accept-Ranges: bytes} — confirms byte range support.</li>
     *   <li>{@code Content-Length} — provides the total file size.</li>
     * </ul>
     *
     * @param url the resource URL
     * @return total file size in bytes
     * @throws IOException if range requests are not supported or Content-Length is missing
     * @throws InterruptedException if the thread is interrupted while waiting for the response
     */
    public long getFileSize(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

        String acceptRanges = response.headers().firstValue("Accept-Ranges").orElse("");
        long contentLength = response.headers()
                .firstValueAsLong("Content-Length")
                .orElseThrow(() -> new IOException("Server did not return Content-Length"));

        System.out.println("HEAD " + url);
        System.out.println("  Accept-Ranges: " + acceptRanges);
        System.out.println("  Content-Length: " + contentLength);

        if (!acceptRanges.equalsIgnoreCase("bytes")) {
            throw new IOException(
                    "Server does not support byte range requests (Accept-Ranges: bytes missing)");
        }

        return contentLength;
    }

    // ── Step 2: Split into ranges ─────────────────────────────────────────────

    /**
     * Divides a file of {@code fileSize} bytes into consecutive chunks of at most
     * {@code chunkSize} bytes each.
     *
     * <p>The last chunk is smaller if the file size is not an exact multiple of {@code chunkSize}.
     * Example — 5000 bytes with chunkSize 2000:
     * <pre>
     *   [0, 1999], [2000, 3999], [4000, 4999]
     * </pre>
     *
     * @param fileSize  total number of bytes in the file
     * @param chunkSize maximum size of each chunk in bytes
     * @return ordered list of {@code [from, to]} pairs (both inclusive)
     */
    public List<long[]> splitIntoChunks(long fileSize, long chunkSize) {
        List<long[]> chunks = new ArrayList<>();
        long from = 0;
        while (from < fileSize) {
            long to = Math.min(from + chunkSize - 1, fileSize - 1);
            chunks.add(new long[]{from, to});
            from = to + 1;
        }
        return chunks;
    }

    // ── Step 3: Download a single chunk ───────────────────────────────────────

    /**
     * Downloads the byte range [{@code from}, {@code to}] from {@code url} asynchronously.
     *
     * <p>The semaphore limits concurrent connections. Once the response arrives, the bytes
     * are written directly to the correct position in {@code raf} — avoiding the need to
     * hold all chunks in memory simultaneously.
     *
     * <p>If the request fails, it is retried up to {@value MAX_RETRIES} times. Each retry
     * increments {@code attempt}; once the limit is reached, the future completes exceptionally.
     *
     * @param url         resource URL
     * @param from        first byte of the range (inclusive)
     * @param to          last byte of the range (inclusive)
     * @param chunkIndex  zero-based index of this chunk (for progress bar)
     * @param raf         pre-allocated output file; writes are position-safe via {@code synchronized}
     * @param semaphore   limits the number of concurrent HTTP connections
     * @param completed   shared counter incremented after each successful chunk
     * @param total       total number of chunks (used for progress display)
     * @param progressBar progress bar updated after each successful chunk
     * @param attempt     current attempt number (starts at 1)
     * @return a future that completes when the chunk has been written to disk
     */
    public CompletableFuture<Void> downloadChunkAsync(
            String url, long from, long to,
            int chunkIndex,
            RandomAccessFile raf,
            Semaphore semaphore,
            AtomicInteger completed, int total,
            ProgressBar progressBar,
            int attempt) {

        // Block until a connection slot is available
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }

        // Build the GET request with the Range header (RFC 7233, §2.1)
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Range", "bytes=" + from + "-" + to)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    // Validate that the server honoured the Range request (RFC 7233 §4.1)
                    if (response.statusCode() != 206) {
                        throw new RuntimeException(
                                "Expected 206 Partial Content for Range bytes=" + from + "-" + to
                                + " but server returned " + response.statusCode());
                    }
                    return response.body();
                })
                .thenAccept(bytes -> {
                    try {
                        // Seek to the chunk's offset and write — synchronized because
                        // RandomAccessFile is not thread-safe
                        synchronized (raf) {
                            raf.seek(from);
                            raf.write(bytes);
                        }
                        completed.incrementAndGet();
                        progressBar.update(chunkIndex, bytes.length, true);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                // Release the semaphore slot exactly once per acquire, before any retry.
                // Placing whenComplete here (before exceptionallyCompose) ensures the slot
                // is freed whether the stage succeeded or failed, and the recursive retry
                // will independently acquire its own slot — preventing double-release.
                .whenComplete((v, e) -> semaphore.release())
                .exceptionallyCompose(e -> {
                    if (attempt < MAX_RETRIES) {
                        return downloadChunkAsync(url, from, to, chunkIndex, raf, semaphore,
                                completed, total, progressBar, attempt + 1);
                    }
                    return CompletableFuture.failedFuture(
                            new IOException("Chunk bytes=" + from + "-" + to
                                    + " failed after " + MAX_RETRIES + " attempts", e));
                });
    }

    // ── Step 4: Orchestrate the full download ─────────────────────────────────

    /**
     * Downloads the file at {@code url} to {@code outputPath}.
     * Chunk size is chosen dynamically: fileSize / MAX_PARALLEL_CHUNKS,
     * but at least MIN_CHUNK_SIZE (1 MB) to avoid excessive overhead on small files.
     *
     * @see #download(String, String, long)
     */
    public void download(String url, String outputPath) throws IOException, InterruptedException {
        long fileSize = getFileSize(url);
        // Ceiling division ensures exactly MAX_PARALLEL_CHUNKS chunks (no leftover mini-chunk)
        long chunkSize = Math.max(
                (fileSize + MAX_PARALLEL_CHUNKS - 1) / MAX_PARALLEL_CHUNKS,
                MIN_CHUNK_SIZE);
        download(url, outputPath, fileSize, chunkSize);
    }

    /**
     * Downloads the file at {@code url} to {@code outputPath} in parallel chunks.
     *
     * <ol>
     *   <li>Queries file size via HEAD.</li>
     *   <li>Splits the file into chunks of {@code chunkSize} bytes.</li>
     *   <li>Starts all chunk downloads concurrently (capped at {@value MAX_PARALLEL_CHUNKS}).</li>
     *   <li>Waits for all chunks to complete, then closes the output file.</li>
     * </ol>
     *
     * <p>The output file is pre-allocated to its final size so each chunk can be written
     * at the correct offset without waiting for preceding chunks to finish.
     *
     * @param url        resource URL
     * @param outputPath path of the file to write
     * @param chunkSize  size of each chunk in bytes
     * @throws IOException          if the server does not support range requests, or I/O fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void download(String url, String outputPath, long chunkSize)
            throws IOException, InterruptedException {
        long fileSize = getFileSize(url);
        download(url, outputPath, fileSize, chunkSize);
    }

    private void download(String url, String outputPath, long fileSize, long chunkSize)
            throws IOException, InterruptedException {

        List<long[]> ranges = splitIntoChunks(fileSize, chunkSize);

        System.out.printf("File size: %d bytes → %d chunks × %d bytes  (max %d parallel)%n",
                fileSize, ranges.size(), chunkSize, MAX_PARALLEL_CHUNKS);

        Semaphore     semaphore   = new Semaphore(MAX_PARALLEL_CHUNKS);
        AtomicInteger completed   = new AtomicInteger(0);
        int           total       = ranges.size();
        ProgressBar   progressBar = new ProgressBar(total, fileSize,
                ranges.toArray(new long[0][]));

        // Pre-allocate the output file to its final size so all chunks can be
        // written at their correct offsets in parallel without any ordering constraint
        try (RandomAccessFile raf = new RandomAccessFile(outputPath, "rw")) {
            raf.setLength(fileSize);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < ranges.size(); i++) {
                long[] range = ranges.get(i);
                int chunkIndex = i;
                futures.add(downloadChunkAsync(
                        url, range[0], range[1], chunkIndex,
                        raf, semaphore, completed, total, progressBar, 1));
            }

            // Block until every chunk future has completed (successfully or exceptionally)
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }
}
