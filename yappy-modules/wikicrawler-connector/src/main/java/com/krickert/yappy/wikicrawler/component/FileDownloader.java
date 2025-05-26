package com.krickert.yappy.wikicrawler.component;

import java.io.File;
import java.net.URL;

/**
 * Interface for downloading files from URLs.
 * This interface provides methods for downloading files with or without checksum validation.
 */
public interface FileDownloader {
    /**
     * Downloads a file from the specified URL to the destination file.
     *
     * @param url The URL to download from
     * @param dstFile The destination file
     * @return The downloaded file
     */
    File download(URL url, File dstFile);

    /**
     * Downloads a file from the specified URL to the destination file and validates its checksum.
     *
     * @param url The URL to download from
     * @param dstFile The destination file
     * @param md5 The expected MD5 checksum
     */
    void download(URL url, File dstFile, String md5);

    /**
     * Checks if the MD5 checksum of the file matches the expected value.
     *
     * @param md5Sum The expected MD5 checksum
     * @param theFile The file to check
     * @return true if the checksums match, false otherwise
     */
    boolean checkMd5(String md5Sum, File theFile);
}