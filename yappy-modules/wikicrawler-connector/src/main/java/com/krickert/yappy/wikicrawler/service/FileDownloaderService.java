package com.krickert.yappy.wikicrawler.service;

import com.krickert.search.model.wiki.DownloadFileRequest;
import com.krickert.search.model.wiki.DownloadedFile;
import com.krickert.search.model.wiki.ErrorCheck;
import com.krickert.search.model.wiki.ErrorCheckType;
import com.krickert.yappy.wikicrawler.component.FileDownloader;
import com.krickert.yappy.wikicrawler.service.storage.StorageService;
import com.google.protobuf.Timestamp;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Singleton
public class FileDownloaderService {

    private static final Logger LOG = LoggerFactory.getLogger(FileDownloaderService.class);
    private final StorageService storageService;
    private final FileDownloader fileDownloader;

    public FileDownloaderService(StorageService storageService, FileDownloader fileDownloader) {
        this.storageService = storageService;
        this.fileDownloader = fileDownloader;
    }

    public Mono<DownloadedFile> downloadFile(DownloadFileRequest request) {
        long downloadStartTimeEpoch = System.currentTimeMillis();
        LOG.info("Starting download for URL: {}, target file name: {}", request.getUrl(), request.getFileName());
        if (request.getExpectedFilesInDumpList() != null && !request.getExpectedFilesInDumpList().isEmpty()) {
            LOG.info("Expected files in dump: {}", request.getExpectedFilesInDumpList());
            // Further auditing logic can be added here or in a separate auditing service
        }

        return Mono.fromCallable(() -> {
            // Prepare the download directory
            Path targetDirectory;
            try {
                targetDirectory = storageService.prepareDownloadDirectory(request);
            } catch (IOException e) {
                LOG.error("Failed to prepare download directory for request: {}", request.getFileName(), e);
                throw e;
            }

            // Create paths for the files
            Path tempFilePath = targetDirectory.resolve(request.getFileName() + ".incomplete");
            Path finalFilePath = targetDirectory.resolve(request.getFileName());
            File tempFile = tempFilePath.toFile();
            File finalFile = finalFilePath.toFile();

            // Extract server name from URL
            String serverName;
            URL url;
            try {
                url = new URL(request.getUrl());
                serverName = url.getHost();
            } catch (MalformedURLException e) {
                LOG.error("Invalid URL provided: {}", request.getUrl(), e);
                throw e;
            }

            // Ensure temp file's parent directory exists
            try {
                Files.createDirectories(tempFilePath.getParent());
                // Delete temp file if it exists from a previous failed attempt
                Files.deleteIfExists(tempFilePath); 
            } catch (IOException e) {
                throw new RuntimeException("Error preparing temporary file: " + tempFilePath, e);
            }

            // Download the file with MD5 checksum validation
            try {
                fileDownloader.download(url, tempFile, request.getErrorCheck().getErrorCheck());

                // Move the file to its final location
                storageService.finalizeFile(tempFilePath, finalFilePath);

                // Get access URIs for the downloaded file
                List<String> accessUris = storageService.getAccessUris(finalFilePath);

                // Record the end time
                long downloadEndTimeEpoch = System.currentTimeMillis();

                // Build and return the DownloadedFile object
                return DownloadedFile.newBuilder()
                        .setFileName(request.getFileName())
                        .addAllAccessUris(accessUris)
                        .setErrorCheck(request.getErrorCheck())
                        .setFileDumpDate(request.getFileDumpDate())
                        .setServerName(serverName)
                        .setDownloadStart(Timestamp.newBuilder()
                                .setSeconds(downloadStartTimeEpoch / 1000)
                                .setNanos((int) (downloadStartTimeEpoch % 1000) * 1_000_000)
                                .build())
                        .setDownloadEnd(Timestamp.newBuilder()
                                .setSeconds(downloadEndTimeEpoch / 1000)
                                .setNanos((int) (downloadEndTimeEpoch % 1000) * 1_000_000)
                                .build())
                        .build();
            } catch (Exception e) {
                LOG.error("Error during file download or validation for {}: ", request.getFileName(), e);
                try {
                    Files.deleteIfExists(tempFilePath); 
                } catch (IOException cleanupEx) {
                    LOG.error("Failed to delete temporary file {} on error: ", tempFilePath, cleanupEx);
                }
                throw e;
            }
        })
        .subscribeOn(Schedulers.boundedElastic()) // Execute the download on a background thread
        .doOnError(e -> LOG.error("Download or processing failed for {}: {}", request.getFileName(), e.getMessage()));
    }
}
