package com.krickert.yappy.wikicrawler.service.storage;

import com.krickert.search.model.wiki.DownloadFileRequest;
import com.krickert.search.model.wiki.DownloadedFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

public interface StorageService {

    /**
     * Stores the downloaded file content.
     *
     * @param request The original download request.
     * @param inputStream The input stream of the file content.
     * @param finalFileName The final name of the file after download and validation.
     * @return The Path where the file was stored.
     * @throws java.io.IOException If an I/O error occurs.
     */
    Path storeFile(DownloadFileRequest request, InputStream inputStream, String finalFileName) throws java.io.IOException;

    /**
     * Forms the access URIs for the stored file.
     *
     * @param storedFilePath The path to the stored file.
     * @return A list of access URIs (e.g., "file:///path/to/file").
     */
    List<String> getAccessUris(Path storedFilePath);

    /**
     * Prepares the target directory for a download.
     *
     * @param request The download request containing date information.
     * @return The Path to the directory where the file should be initially saved (e.g., with .incomplete).
     * @throws java.io.IOException If an I/O error occurs during directory creation.
     */
    Path prepareDownloadDirectory(DownloadFileRequest request) throws java.io.IOException;

    /**
     * Finalizes the file storage, e.g., by moving from a temporary location or renaming.
     *
     * @param temporaryPath Path of the file with .incomplete extension.
     * @param finalPath Path where the file should be finally.
     * @return The Path to the finalized file.
     * @throws java.io.IOException If an I/O error occurs.
     */
    Path finalizeFile(Path temporaryPath, Path finalPath) throws java.io.IOException;
}
