package com.krickert.yappy.wikicrawler.client;

import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.StreamingHttpClient;
import reactor.core.publisher.Flux;
import java.nio.ByteBuffer;
import org.reactivestreams.Publisher;

@Client("\${file.download.base-url}") // Base URL will be configured
public interface RawFileDownloaderClient extends StreamingHttpClient {

    /**
     * Downloads a file from the given relative URL.
     * The base URL is configured externally.
     * @param url The relative path/URL of the file to download.
     * @return A Publisher of ByteBuffer chunks.
     */
    @Get("/{+url}")
    Publisher<ByteBuffer> downloadFile(String url);
}
