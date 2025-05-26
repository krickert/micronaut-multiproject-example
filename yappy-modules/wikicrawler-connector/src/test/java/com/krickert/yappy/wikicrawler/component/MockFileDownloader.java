package com.krickert.yappy.wikicrawler.component;

import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.core.io.scan.ClassPathResourceLoader;
import jakarta.inject.Singleton;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Optional;

import static org.apache.commons.codec.digest.DigestUtils.md5Hex;

/**
 * A mock implementation of FileDownloader that reads files from test resources
 * instead of making HTTP requests.
 */
@Singleton
@Primary
@Requires(env = Environment.TEST)
public class MockFileDownloader implements FileDownloader {
    private static final Logger LOG = LoggerFactory.getLogger(MockFileDownloader.class);
    private final ClassPathResourceLoader resourceLoader;

    public MockFileDownloader() {
        this.resourceLoader = new ResourceResolver().getLoader(ClassPathResourceLoader.class).get();
    }

    @Override
    public File download(URL url, File dstFile) {
        LOG.info("Mock downloading file from URL: {} to {}", url, dstFile.getAbsolutePath());
        
        // Try to find a resource based on the URL path
        String fileName = url.getPath();
        if (fileName.contains("/")) {
            fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
        }
        
        Optional<URL> resourceUrl = resourceLoader.getResource("classpath:" + fileName);
        
        if (resourceUrl.isEmpty()) {
            // If not found directly, try the compressed wiki dump
            resourceUrl = resourceLoader.getResource("classpath:enwiki-20221101-pages-articles2.xml-short.xml.bz2");
            
            if (resourceUrl.isEmpty()) {
                // If still not found, try the sample wiki dump
                resourceUrl = resourceLoader.getResource("classpath:sample-wiki-dump.xml");
                
                if (resourceUrl.isEmpty()) {
                    throw new RuntimeException("Could not find any test resource file for URL: " + url);
                }
            }
        }
        
        try {
            FileUtils.copyFile(new File(resourceUrl.get().getFile()), dstFile);
            LOG.info("Successfully copied mock file to {}", dstFile.getAbsolutePath());
        } catch (IOException e) {
            LOG.error("Error copying mock file", e);
            throw new RuntimeException(e);
        }
        
        return dstFile;
    }

    @Override
    public void download(URL url, File dstFile, String md5) {
        LOG.info("Mock downloading file from URL: {} to {} with MD5: {}", url, dstFile.getAbsolutePath(), md5);
        
        // Download the file
        download(url, dstFile);
        
        // Verify the MD5 checksum
        if (!checkMd5(md5, dstFile)) {
            LOG.warn("MD5 checksum mismatch for downloaded file. Expected: {}", md5);
            // In a real implementation, we might retry or throw an exception
            // For the mock, we'll just log a warning
        }
    }

    @Override
    public boolean checkMd5(String md5Sum, File theFile) {
        try {
            if (theFile == null || !theFile.exists()) {
                LOG.error("File is null or does not exist. MD5 cannot match");
                return false;
            }
            
            String calculatedMd5 = md5Hex(new FileInputStream(theFile));
            
            if (md5Sum.equalsIgnoreCase(calculatedMd5)) {
                LOG.info("MD5 checksum matches for file: {}", theFile.getAbsolutePath());
                return true;
            } else {
                LOG.info("MD5 checksum mismatch for file: {}. Expected: {}, Actual: {}", 
                        theFile.getAbsolutePath(), md5Sum, calculatedMd5);
                return false;
            }
        } catch (IOException e) {
            LOG.error("IOException occurred while checking MD5", e);
            return false;
        }
    }
}