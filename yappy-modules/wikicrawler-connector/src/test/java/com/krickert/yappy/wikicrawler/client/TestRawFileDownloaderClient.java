package com.krickert.yappy.wikicrawler.client;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import org.reactivestreams.Publisher;

import java.nio.ByteBuffer;

/**
 * Test-specific interface for RawFileDownloaderClient.
 * This interface is used in test environments and doesn't extend StreamingHttpClient
 * to avoid having to implement all its methods.
 */
@Requires(env = Environment.TEST)
public interface TestRawFileDownloaderClient {

    /**
     * Downloads a file from the given relative URL.
     * The base URL is configured externally.
     * @param url The relative path/URL of the file to download.
     * @return A Publisher of ByteBuffer chunks.
     */
    Publisher<ByteBuffer> downloadFile(String url);
}