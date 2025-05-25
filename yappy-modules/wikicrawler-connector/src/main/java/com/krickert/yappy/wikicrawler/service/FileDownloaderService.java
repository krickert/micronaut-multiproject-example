package com.krickert.yappy.wikicrawler.service;

import com.krickert.search.model.wiki.DownloadFileRequest;
import com.krickert.search.model.wiki.DownloadedFile;
import com.krickert.search.model.wiki.ErrorCheck;
import com.krickert.search.model.wiki.ErrorCheckType;
import com.krickert.yappy.wikicrawler.client.RawFileDownloaderClient;
import com.krickert.yappy.wikicrawler.service.storage.StorageService;
import com.google.protobuf.Timestamp;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Singleton
public class FileDownloaderService {

    private static final Logger LOG = LoggerFactory.getLogger(FileDownloaderService.class);
    private final StorageService storageService;
    private final RawFileDownloaderClient fileDownloaderClient;

    public FileDownloaderService(StorageService storageService, RawFileDownloaderClient fileDownloaderClient) {
        this.storageService = storageService;
        this.fileDownloaderClient = fileDownloaderClient;
    }

    public Mono<DownloadedFile> downloadFile(DownloadFileRequest request) {
        long downloadStartTimeEpoch = System.currentTimeMillis();
        LOG.info("Starting download for URL: {}, target file name: {}", request.getUrl(), request.getFileName());
        if (request.getExpectedFilesInDumpList() != null && !request.getExpectedFilesInDumpList().isEmpty()) {
            LOG.info("Expected files in dump: {}", request.getExpectedFilesInDumpList());
            // Further auditing logic can be added here or in a separate auditing service
        }

        Path targetDirectory;
        try {
            targetDirectory = storageService.prepareDownloadDirectory(request);
        } catch (IOException e) {
            LOG.error("Failed to prepare download directory for request: {}", request.getFileName(), e);
            return Mono.error(e);
        }
        
        Path tempFilePath = targetDirectory.resolve(request.getFileName() + ".incomplete");
        Path finalFilePath = targetDirectory.resolve(request.getFileName());

        String relativeUrlPath;
        String serverName;
        try {
            URL fullUrl = new URL(request.getUrl());
            relativeUrlPath = fullUrl.getPath();
            if (relativeUrlPath.startsWith("/")) {
                relativeUrlPath = relativeUrlPath.substring(1);
            }
            serverName = fullUrl.getHost();
        } catch (MalformedURLException e) {
            LOG.error("Invalid URL provided: {}", request.getUrl(), e);
            return Mono.error(e);
        }
        
        AtomicLong totalBytesWritten = new AtomicLong(0);

        // Ensure temp file's parent directory exists before starting the flux
        try {
            Files.createDirectories(tempFilePath.getParent());
            // Delete temp file if it exists from a previous failed attempt
            Files.deleteIfExists(tempFilePath); 
        } catch (IOException e) {
            return Mono.error(new RuntimeException("Error preparing temporary file: " + tempFilePath, e));
        }

        return reactor.core.publisher.Flux.from(fileDownloaderClient.downloadFile(relativeUrlPath))
            .publishOn(Schedulers.boundedElastic()) // Offload file writing
            .map(byteBuffer -> {
                try {
                    // Convert ByteBuffer to byte array. Note: If direct buffer, .array() might not be available.
                    // Assuming heap buffer from client or that .array() works.
                    byte[] bytes = new byte[byteBuffer.remaining()];
                    byteBuffer.get(bytes);

                    Files.write(tempFilePath, bytes, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
                    totalBytesWritten.addAndGet(bytes.length);
                    return bytes.length; // Or some other status, actual byte data not needed downstream of map
                } catch (IOException e) {
                    LOG.error("Error writing chunk to temporary file {}: ", tempFilePath, e);
                    throw new RuntimeException("Error writing to temporary file: " + tempFilePath, e);
                }
            })
            .then(Mono.defer(() -> { // defer ensures this part runs after Flux completes
                LOG.info("Finished writing {} bytes to temporary file {}", totalBytesWritten.get(), tempFilePath);
                // Now validate the completed temporary file
                try (InputStream is = Files.newInputStream(tempFilePath)) {
                    MessageDigest md = getMessageDigest(request.getErrorCheck().getErrorCheckType());
                    DigestInputStream dis = new DigestInputStream(is, md);
                    byte[] buffer = new byte[8192];
                    //noinspection StatementWithEmptyBody
                    while (dis.read(buffer) != -1) {
                        // Reading through the stream to update the digest
                    }
                    String calculatedChecksum = bytesToHex(md.digest());
                    String expectedChecksum = request.getErrorCheck().getErrorCheck();

                    if (!calculatedChecksum.equalsIgnoreCase(expectedChecksum)) {
                        Files.deleteIfExists(tempFilePath); // Clean up
                        String errorMessage = String.format("Checksum validation failed for %s. Expected: %s, Calculated: %s",
                                request.getFileName(), expectedChecksum, calculatedChecksum);
                        LOG.error(errorMessage);
                        return Mono.error(new IOException(errorMessage));
                    }

                    LOG.info("Checksum validated successfully for {}: {}", request.getFileName(), calculatedChecksum);
                    storageService.finalizeFile(tempFilePath, finalFilePath);

                    List<String> accessUris = storageService.getAccessUris(finalFilePath);
                    long downloadEndTimeEpoch = System.currentTimeMillis();

                    return Mono.just(DownloadedFile.newBuilder()
                            .setFileName(request.getFileName())
                            .addAllAccessUris(accessUris)
                            .setErrorCheck(request.getErrorCheck())
                            .setFileDumpDate(request.getFileDumpDate())
                            .setServerName(serverName)
                            .setDownloadStart(Timestamp.newBuilder().setSeconds(downloadStartTimeEpoch / 1000).setNanos((int) (downloadStartTimeEpoch % 1000) * 1_000_000).build())
                            .setDownloadEnd(Timestamp.newBuilder().setSeconds(downloadEndTimeEpoch / 1000).setNanos((int) (downloadEndTimeEpoch % 1000) * 1_000_000).build())
                            .build());

                } catch (IOException | NoSuchAlgorithmException e) {
                    LOG.error("Error during file validation or finalization for {}: ", request.getFileName(), e);
                    try {
                        Files.deleteIfExists(tempFilePath); 
                    } catch (IOException cleanupEx) {
                        LOG.error("Failed to delete temporary file {} on error: ", tempFilePath, cleanupEx);
                    }
                    return Mono.error(e);
                }
            }))
            .doOnError(e -> LOG.error("Download or processing failed for {}: {}", request.getFileName(), e.getMessage()));
    }

    private MessageDigest getMessageDigest(ErrorCheckType type) throws NoSuchAlgorithmException {
        switch (type) {
            case MD5:
                return MessageDigest.getInstance("MD5");
            case SHA1:
                return MessageDigest.getInstance("SHA-1");
            case SHA256:
                return MessageDigest.getInstance("SHA-256");
            default:
                throw new NoSuchAlgorithmException("Unsupported checksum type: " + type);
        }
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
