# Parallel File Downloader

A Java implementation of a parallel HTTP file downloader using byte range requests ([RFC 7233](https://datatracker.ietf.org/doc/html/rfc7233)).

## How it works

```
HEAD /file  →  Content-Length + Accept-Ranges: bytes
                        │
              ┌─────────┴──────────┐
         Split into N chunks  (ceiling division)
              │
    ┌─────────┼─────────┐
  [0-3MB]  [3-6MB]  [6-9MB]  ...   ← all start simultaneously
    │         │         │
    └────┬────┘         │           ← Semaphore caps concurrency at 8
         │              │
   RandomAccessFile.seek(offset).write(bytes)
         └──────────────┘
              │
        allOf().join()  →  file complete
```

1. **HEAD request** — retrieves `Content-Length` and verifies `Accept-Ranges: bytes`
2. **Split** — divides the file into chunks using ceiling division to guarantee exactly `MAX_PARALLEL_CHUNKS` pieces
3. **Parallel download** — all chunks start concurrently via `HttpClient.sendAsync()`
4. **Concurrency cap** — a `Semaphore` limits active connections to 8
5. **Direct-write** — each chunk is written at its byte offset via `RandomAccessFile.seek()` — no full-file RAM buffering
6. **Retry** — failed chunks are retried up to 3 times via `exceptionallyCompose()`

## Architecture

| Class | Responsibility |
|-------|---------------|
| `FileDownloader` | Orchestrates HEAD → split → parallel download → file assembly |
| `ProgressBar` | Thread-safe ANSI multi-line progress bar, one bar per chunk |
| `Main` | Entry point — wires `HttpClient` and kicks off the download |

## Live progress output

```
  Chunk  1  [████████████████████████████] 100% ✓  3.4 MB
  Chunk  2  [████████████░░░░░░░░░░░░░░░░]  43%
  Chunk  3  [██░░░░░░░░░░░░░░░░░░░░░░░░░░]   7%
  ...
  Total     [████████████████████░░░░░░░░]  72%  |  5/8 chunks  |  38.1 MB/s
```

Each bar updates in place using ANSI cursor-up sequences — works in any ANSI-compatible terminal.

## Configuration

| Constant | Default | Description |
|----------|---------|-------------|
| `MAX_PARALLEL_CHUNKS` | 8 | Maximum concurrent HTTP connections |
| `MAX_RETRIES` | 3 | Retry attempts per chunk on failure |
| `MIN_CHUNK_SIZE` | 1 MB | Minimum chunk size to avoid overhead on small files |

Chunk size is computed dynamically: `max(fileSize / MAX_PARALLEL_CHUNKS, MIN_CHUNK_SIZE)`

## Requirements

- Java 21+
- Maven 3.x
- HTTP server with `Accept-Ranges: bytes` support

## Build & run

```bash
mvn package
# edit Main.java to point at your URL + output path
mvn exec:java -Dexec.mainClass=com.downloader.Main
```

## Tests

```bash
mvn test
```

8 unit tests covering `getFileSize`, `splitIntoChunks`, `downloadChunkAsync` (including retry logic and Range header verification), and the full download pipeline. HTTP layer is mocked with Mockito — no network required.
