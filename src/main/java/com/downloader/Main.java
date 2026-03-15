package com.downloader;

import java.net.http.HttpClient;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: file-downloader <url> <output-path>");
            System.err.println("Example: file-downloader https://example.com/file.zip /tmp/file.zip");
            System.exit(1);
        }

        String url        = args[0];
        String outputPath = args[1];

        HttpClient httpClient = HttpClient.newHttpClient();
        FileDownloader downloader = new FileDownloader(httpClient);

        long start = System.currentTimeMillis();
        downloader.download(url, outputPath);
        long end = System.currentTimeMillis();

        System.out.println("Done in " + (end - start) + " ms");
        System.out.println("Saved to: " + outputPath);
    }
}
