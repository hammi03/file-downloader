package com.downloader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class FileDownloaderTest {

    private HttpClient mockHttpClient;
    private FileDownloader downloader;

    @BeforeEach
    void setUp() {
        mockHttpClient = Mockito.mock(HttpClient.class);
        downloader = new FileDownloader(mockHttpClient);
    }

    // ── getFileSize ────────────────────────────────────────────────────────────

    @Test
    void getFileSize_returnsContentLength() throws Exception {
        mockHeadResponse(Map.of(
                "Accept-Ranges",  List.of("bytes"),
                "Content-Length", List.of("5000")
        ));

        assertEquals(5000L, downloader.getFileSize("http://localhost:8080/test.txt"));
    }

    @Test
    void getFileSize_throwsWhenNoContentLength() throws Exception {
        mockHeadResponse(Map.of("Accept-Ranges", List.of("bytes")));

        assertThrows(IOException.class, () ->
                downloader.getFileSize("http://localhost:8080/test.txt"));
    }

    @Test
    void getFileSize_throwsWhenAcceptRangesMissing() throws Exception {
        mockHeadResponse(Map.of("Content-Length", List.of("5000")));

        assertThrows(IOException.class, () ->
                downloader.getFileSize("http://localhost:8080/test.txt"));
    }

    // ── splitIntoChunks ────────────────────────────────────────────────────────

    @Test
    void splitIntoChunks_correctRanges() {
        List<long[]> chunks = downloader.splitIntoChunks(5000, 2000);

        assertEquals(3, chunks.size());
        assertArrayEquals(new long[]{0,    1999}, chunks.get(0));
        assertArrayEquals(new long[]{2000, 3999}, chunks.get(1));
        assertArrayEquals(new long[]{4000, 4999}, chunks.get(2));
    }

    @Test
    void splitIntoChunks_fileSizeExactMultiple() {
        List<long[]> chunks = downloader.splitIntoChunks(4000, 2000);

        assertEquals(2, chunks.size());
        assertArrayEquals(new long[]{0,    1999}, chunks.get(0));
        assertArrayEquals(new long[]{2000, 3999}, chunks.get(1));
    }

    // ── downloadChunkAsync ─────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void downloadChunkAsync_writesChunkAtCorrectPosition(@TempDir Path tempDir) throws Exception {
        byte[] fakeData = "HELLO".getBytes();
        mockAsyncResponse(fakeData);

        Path outputFile = tempDir.resolve("out.txt");
        try (RandomAccessFile raf = new RandomAccessFile(outputFile.toFile(), "rw")) {
            raf.setLength(10);
            downloader.downloadChunkAsync(
                    "http://localhost:8080/test.txt", 0, 4, 0,
                    raf, new Semaphore(1), new AtomicInteger(0), 1,
                    new ProgressBar(1, 10, new long[][]{{0, 4}}), 1
            ).join();
        }

        byte[] written = Files.readAllBytes(outputFile);
        assertArrayEquals(fakeData, java.util.Arrays.copyOfRange(written, 0, 5));
    }

    @Test
    @SuppressWarnings("unchecked")
    void downloadChunkAsync_setsCorrectRangeHeader(@TempDir Path tempDir) throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        doReturn(CompletableFuture.completedFuture(mockByteResponse("hello".getBytes())))
                .when(mockHttpClient).sendAsync(captor.capture(), any());

        Path outputFile = tempDir.resolve("out.txt");
        try (RandomAccessFile raf = new RandomAccessFile(outputFile.toFile(), "rw")) {
            raf.setLength(2048);
            downloader.downloadChunkAsync(
                    "http://localhost:8080/test.txt", 1024, 2047, 0,
                    raf, new Semaphore(1), new AtomicInteger(0), 1,
                    new ProgressBar(1, 2048, new long[][]{{1024, 2047}}), 1
            ).join();
        }

        String rangeHeader = captor.getValue().headers().firstValue("Range").orElse("");
        assertEquals("bytes=1024-2047", rangeHeader);
    }

    @Test
    @SuppressWarnings("unchecked")
    void downloadChunkAsync_retriesOnFailure(@TempDir Path tempDir) throws Exception {
        // First call fails, second succeeds
        var failure = CompletableFuture.<HttpResponse<byte[]>>failedFuture(new IOException("timeout"));
        var success = CompletableFuture.completedFuture(mockByteResponse("OK".getBytes()));

        doReturn(failure).doReturn(success)
                .when(mockHttpClient).sendAsync(any(HttpRequest.class), any());

        Path outputFile = tempDir.resolve("out.txt");
        try (RandomAccessFile raf = new RandomAccessFile(outputFile.toFile(), "rw")) {
            raf.setLength(2);
            downloader.downloadChunkAsync(
                    "http://localhost:8080/test.txt", 0, 1, 0,
                    raf, new Semaphore(1), new AtomicInteger(0), 1,
                    new ProgressBar(1, 2, new long[][]{{0, 1}}), 1
            ).join();
        }

        assertArrayEquals("OK".getBytes(), Files.readAllBytes(outputFile));
    }

    // ── download (full pipeline) ───────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void download_combinesChunksInCorrectOrder(@TempDir Path tempDir) throws Exception {
        mockHeadResponse(Map.of(
                "Accept-Ranges",  List.of("bytes"),
                "Content-Length", List.of("6")
        ));

        var chunk1 = CompletableFuture.completedFuture(mockByteResponse("AB".getBytes()));
        var chunk2 = CompletableFuture.completedFuture(mockByteResponse("CD".getBytes()));
        var chunk3 = CompletableFuture.completedFuture(mockByteResponse("EF".getBytes()));

        doReturn(chunk1).doReturn(chunk2).doReturn(chunk3)
                .when(mockHttpClient).sendAsync(any(HttpRequest.class), any());

        Path outputFile = tempDir.resolve("output.txt");
        downloader.download("http://localhost:8080/test.txt", outputFile.toString(), 2);

        assertArrayEquals("ABCDEF".getBytes(), Files.readAllBytes(outputFile));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void mockHeadResponse(Map<String, List<String>> headerMap) throws Exception {
        HttpResponse<Void> mockResponse = Mockito.mock(HttpResponse.class);
        HttpHeaders headers = HttpHeaders.of(headerMap, (a, b) -> true);
        when(mockResponse.headers()).thenReturn(headers);
        doReturn(mockResponse).when(mockHttpClient).send(any(HttpRequest.class), any());
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> mockByteResponse(byte[] data) {
        HttpResponse<byte[]> mockResponse = Mockito.mock(HttpResponse.class);
        when(mockResponse.body()).thenReturn(data);
        return mockResponse;
    }

    @SuppressWarnings("unchecked")
    private void mockAsyncResponse(byte[] data) {
        doReturn(CompletableFuture.completedFuture(mockByteResponse(data)))
                .when(mockHttpClient).sendAsync(any(HttpRequest.class), any());
    }
}
