package com.krickert.search.config.http;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.client.HttpClient;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Handles graceful shutdown of HTTP clients to prevent Netty executor terminated errors.
 * This class ensures that all HTTP clients are properly closed before the application shuts down.
 */
@Singleton
@Context // Ensure this bean is loaded eagerly
@Requires(property = "micronaut.server.netty.worker.shutdown-quiet-period")
public class HttpClientShutdownHandler {

    private static final Logger LOG = LoggerFactory.getLogger(HttpClientShutdownHandler.class);
    
    private final List<HttpClient> httpClients;

    /**
     * Creates a new HttpClientShutdownHandler with the specified HTTP clients.
     *
     * @param httpClients the HTTP clients to manage
     */
    @Inject
    public HttpClientShutdownHandler(List<HttpClient> httpClients) {
        this.httpClients = httpClients;
        LOG.info("HttpClientShutdownHandler initialized with {} HTTP clients", httpClients.size());
    }

    /**
     * Handles the ServerShutdownEvent by gracefully closing all HTTP clients.
     * This ensures that all resources are released properly before the application shuts down.
     *
     * @param event the server shutdown event
     */
    @EventListener
    public void onServerShutdown(ServerShutdownEvent event) {
        LOG.info("Server shutdown event received, closing {} HTTP clients", httpClients.size());
        
        for (HttpClient client : httpClients) {
            try {
                LOG.debug("Closing HTTP client: {}", client);
                client.close();
            } catch (Exception e) {
                LOG.warn("Error closing HTTP client: {}", client, e);
            }
        }
        
        // Add a small delay to allow resources to be released
        try {
            LOG.debug("Waiting for HTTP clients to release resources");
            TimeUnit.MILLISECONDS.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting for HTTP clients to release resources", e);
        }
        
        LOG.info("All HTTP clients closed");
    }
}