package com.downloader;

import java.net.http.HttpClient;

public class Main {

    public static void main(String[] args) throws Exception {
        String url        = "http://localhost:8080/testfile.txt";
        String outputPath = "C:/Users/hammi/file-downloader/downloaded.txt";

        HttpClient httpClient = HttpClient.newHttpClient();
        FileDownloader downloader = new FileDownloader(httpClient);

        System.out.println("Starte parallelen Download...");

        long start = System.currentTimeMillis();
        downloader.download(url, outputPath); // chunk size computed automatically
        long end = System.currentTimeMillis();

        System.out.println("Fertig in " + (end - start) + " ms");
        System.out.println("Gespeichert unter: " + outputPath);
    }
}