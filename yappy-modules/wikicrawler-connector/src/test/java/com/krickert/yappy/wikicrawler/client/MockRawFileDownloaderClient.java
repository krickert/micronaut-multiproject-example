package com.krickert.yappy.wikicrawler.client;

import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.core.io.scan.ClassPathResourceLoader;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * A mock implementation of RawFileDownloaderClient that reads files from test resources
 * instead of making HTTP requests.
 */
@Singleton
@Primary
@Requires(env = Environment.TEST)
public class MockRawFileDownloaderClient implements RawFileDownloaderClient {
    private static final Logger LOG = LoggerFactory.getLogger(MockRawFileDownloaderClient.class);
    private static final int BUFFER_SIZE = 8192; // 8KB chunks

    private final ClassPathResourceLoader resourceLoader;

    public MockRawFileDownloaderClient() {
        this.resourceLoader = new ResourceResolver().getLoader(ClassPathResourceLoader.class).get();
    }

    @Override
    public Publisher<ByteBuffer> downloadFile(String url) {
        LOG.info("Mock downloading file from URL: {}", url);

        // Extract the file name from the URL
        String fileName = url;
        if (fileName.contains("/")) {
            fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
        }

        // Try to find the resource in the test resources directory
        Optional<URL> resourceUrl = resourceLoader.getResource("classpath:" + fileName);

        if (resourceUrl.isEmpty()) {
            // If not found directly, try looking for it in the test resources directory
            resourceUrl = resourceLoader.getResource("classpath:enwiki-20221101-pages-articles2.xml-short.xml.bz2");

            if (resourceUrl.isEmpty()) {
                // If still not found, try the sample wiki dump
                resourceUrl = resourceLoader.getResource("classpath:sample-wiki-dump.xml");

                if (resourceUrl.isEmpty()) {
                    LOG.error("Could not find any test resource file for URL: {}", url);
                    return Flux.error(new IOException("Resource not found: " + url));
                }
            }
        }

        try {
            InputStream inputStream = resourceUrl.get().openStream();

            return Flux.create(emitter -> {
                try {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;

                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        byte[] chunk = new byte[bytesRead];
                        System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                        emitter.next(ByteBuffer.wrap(chunk));
                    }

                    emitter.complete();
                } catch (IOException e) {
                    emitter.error(e);
                } finally {
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                        LOG.error("Error closing input stream", e);
                    }
                }
            });
        } catch (IOException e) {
            LOG.error("Error opening resource stream", e);
            return Flux.error(e);
        }
    }
}
