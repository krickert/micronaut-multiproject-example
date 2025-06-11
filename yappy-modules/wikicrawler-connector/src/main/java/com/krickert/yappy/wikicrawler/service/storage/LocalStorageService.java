package com.krickert.yappy.wikicrawler.service.storage;

import com.krickert.search.model.wiki.DownloadFileRequest;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Singleton
public class LocalStorageService implements StorageService {

    private static final Logger LOG = LoggerFactory.getLogger(LocalStorageService.class);
    private final Path baseStoragePath; // Configurable base path, e.g., "/path/to/dumps" or "data/dumps"

    // TODO: Make baseStoragePath configurable via Micronaut configuration
    public LocalStorageService() {
        // For now, use a default relative path. This should be made configurable.
        this.baseStoragePath = Paths.get("downloaded_wikidumps");
        try {
            Files.createDirectories(this.baseStoragePath);
        } catch (IOException e) {
            LOG.error("Failed to create base storage directory: {}", this.baseStoragePath, e);
            // Or throw a custom exception to be handled by the application
        }
    }

    public LocalStorageService(Path baseStoragePath) {
        this.baseStoragePath = baseStoragePath;
        try {
            Files.createDirectories(this.baseStoragePath);
        } catch (IOException e) {
            LOG.error("Failed to create base storage directory: {}", this.baseStoragePath, e);
        }
    }

    @Override
    public Path prepareDownloadDirectory(DownloadFileRequest request) throws IOException {
        String dumpDate = request.getFileDumpDate(); // Expects "YYYYMMDD"
        LocalDate date = LocalDate.parse(dumpDate, DateTimeFormatter.BASIC_ISO_DATE);
        Path dateSpecificDir = baseStoragePath.resolve(date.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        Files.createDirectories(dateSpecificDir);
        return dateSpecificDir;
    }
    
    @Override
    public Path storeFile(DownloadFileRequest request, InputStream inputStream, String finalFileName) throws IOException {
        Path targetDir = prepareDownloadDirectory(request);
        Path finalPath = targetDir.resolve(finalFileName);
        Files.copy(inputStream, finalPath, StandardCopyOption.REPLACE_EXISTING);
        LOG.info("Stored file {} in directory {}", finalFileName, targetDir);
        return finalPath;
    }

    @Override
    public List<String> getAccessUris(Path storedFilePath) {
        if (storedFilePath == null) {
            return Collections.emptyList();
        }
        // Ensure the path is absolute for correct URI formation
        Path absolutePath = storedFilePath.toAbsolutePath();
        return Collections.singletonList(absolutePath.toUri().toString());
    }

    @Override
    public Path finalizeFile(Path temporaryPath, Path finalPath) throws IOException {
        Files.move(temporaryPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
        LOG.info("Moved file from {} to {}", temporaryPath, finalPath);
        return finalPath;
    }
}
