package com.downloader;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * End-to-end integration test.
 *
 * <p>Spins up a real in-process HTTP server (Java built-in {@link HttpServer}) that correctly
 * handles HEAD and byte-range GET requests, then verifies the assembled output file
 * matches the original content byte-for-byte.
 *
 * <p>No mocking — the full stack is exercised: HTTP client, range request, semaphore,
 * RandomAccessFile seek/write, and CompletableFuture orchestration.
 */
class FileDownloaderIntegrationTest {

    @Test
    void download_endToEnd_matchesOriginalFile(@TempDir Path tempDir) throws Exception {
        // 5 MB of deterministic pseudo-random content
        byte[] original = new byte[5 * 1024 * 1024];
        new Random(42).nextBytes(original);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/file", exchange -> {
            try {
                if ("HEAD".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                    exchange.getResponseHeaders().set("Content-Length", String.valueOf(original.length));
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    // Parse "bytes=from-to"
                    String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
                    String[] bounds = rangeHeader.substring("bytes=".length()).split("-");
                    int from = Integer.parseInt(bounds[0]);
                    int to   = Integer.parseInt(bounds[1]);

                    byte[] chunk = Arrays.copyOfRange(original, from, to + 1);
                    exchange.getResponseHeaders().set("Content-Range",
                            "bytes " + from + "-" + to + "/" + original.length);
                    exchange.sendResponseHeaders(206, chunk.length);
                    exchange.getResponseBody().write(chunk);
                }
            } finally {
                exchange.close();
            }
        });

        server.start();
        try {
            Path output = tempDir.resolve("downloaded");
            new FileDownloader(HttpClient.newHttpClient())
                    .download("http://localhost:" + port + "/file", output.toString());

            assertArrayEquals(original, Files.readAllBytes(output));
        } finally {
            server.stop(0);
        }
    }
}
