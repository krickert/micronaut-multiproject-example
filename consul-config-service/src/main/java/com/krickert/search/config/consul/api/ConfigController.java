package com.krickert.search.config.consul.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.event.ConfigChangeNotifier;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.jackson.serialize.JacksonObjectSerializer;
import io.micronaut.runtime.context.scope.Refreshable;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/**
 * REST controller for managing configuration in Consul KV store.
 * Provides endpoints for reading, writing, and deleting configuration values.
 */
@Controller("/config")
public class ConfigController {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigController.class);

    private final ConsulKvService consulKvService;
    private final ApplicationEventPublisher eventPublisher;
    private final ConfigChangeNotifier configChangeNotifier;
    private final JacksonObjectSerializer serializer = new JacksonObjectSerializer(new ObjectMapper());
    /**
     * Creates a new ConfigController with the specified services.
     *
     * @param consulKvService the service for interacting with Consul KV store
     * @param eventPublisher the publisher for application events
     * @param configChangeNotifier the notifier for configuration changes
     */
    @Inject
    public ConfigController(ConsulKvService consulKvService, 
                           ApplicationEventPublisher eventPublisher,
                           ConfigChangeNotifier configChangeNotifier) {
        this.consulKvService = consulKvService;
        this.eventPublisher = eventPublisher;
        this.configChangeNotifier = configChangeNotifier;
        LOG.info("ConfigController initialized");
    }

    /**
     * Gets a configuration value from Consul KV store.
     *
     * @param keyPath the path to the key
     * @return the value if found, or 404 if not found
     */
    @Get(value = "/{keyPath:.+}", produces = MediaType.TEXT_PLAIN)
    public Mono<HttpResponse<String>> getConfig(String keyPath) {
        LOG.info("GET request for key: {}", keyPath);
        String fullPath = consulKvService.getFullPath(keyPath);

        return consulKvService.getValue(fullPath)
                .map(optionalValue -> {
                    if (optionalValue.isPresent()) {
                        LOG.debug("Found value for key: {}", fullPath);
                        return HttpResponse.ok(optionalValue.get());
                    } else {
                        LOG.debug("No value found for key: {}", fullPath);
                        return HttpResponse.notFound();
                    }
                });
    }

    /**
     * Puts a plain text configuration value into Consul KV store.
     *
     * @param keyPath the path to the key
     * @param value the value to put
     * @return 200 OK if successful, 500 Internal Server Error otherwise
     */
    @Put(value = "/{keyPath:.+}", consumes = MediaType.TEXT_PLAIN)
    public Mono<HttpResponse<?>> updateConfigRaw(String keyPath, @Body String value) {
        LOG.info("PUT request for key: {} with raw value", keyPath);
        String fullPath = consulKvService.getFullPath(keyPath);

        return consulKvService.putValue(fullPath, value)
                .flatMap(success -> {
                    if (success) {
                        LOG.debug("Successfully updated key: {}", fullPath);
                        // Notify about configuration change
                        configChangeNotifier.notifyConfigChange(fullPath);
                        return Mono.just(HttpResponse.ok());
                    } else {
                        LOG.error("Failed to update key: {}", fullPath);
                        return Mono.just(HttpResponse.serverError("Failed to update Consul KV"));
                    }
                });
    }

    /**
     * Puts a JSON configuration value into Consul KV store.
     *
     * @param keyPath the path to the key
     * @param value the value to put as a Map
     * @return 200 OK if successful, 400 Bad Request if invalid JSON, 500 Internal Server Error otherwise
     */
    @Put(value = "/{keyPath:.+}", consumes = MediaType.APPLICATION_JSON)
    public Mono<HttpResponse<?>> updateConfigJson(String keyPath, @Body Map<String, Object> value) {
        LOG.info("PUT request for key: {} with JSON value", keyPath);
        String fullPath = consulKvService.getFullPath(keyPath);

        try {
            // Convert Map to JSON string
            String jsonValue = serializer.serialize(value).toString();

            return consulKvService.putValue(fullPath, jsonValue)
                    .flatMap(success -> {
                        if (success) {
                            LOG.debug("Successfully updated key: {}", fullPath);
                            // Notify about configuration change
                            configChangeNotifier.notifyConfigChange(fullPath);
                            return Mono.just(HttpResponse.ok());
                        } else {
                            LOG.error("Failed to update key: {}", fullPath);
                            return Mono.just(HttpResponse.serverError("Failed to update Consul KV"));
                        }
                    });
        } catch (Exception e) {
            LOG.error("Error processing JSON for key: {}", keyPath, e);
            return Mono.just(HttpResponse.badRequest("Invalid JSON payload"));
        }
    }

    /**
     * Deletes a configuration key from Consul KV store.
     *
     * @param keyPath the path to the key
     * @return 204 No Content if successful, 500 Internal Server Error otherwise
     */
    @Delete("/{keyPath:.+}")
    public Mono<HttpResponse<?>> deleteConfig(String keyPath) {
        LOG.info("DELETE request for key: {}", keyPath);
        String fullPath = consulKvService.getFullPath(keyPath);

        return consulKvService.deleteKey(fullPath)
                .flatMap(success -> {
                    if (success) {
                        LOG.debug("Successfully deleted key: {}", fullPath);
                        // Notify about configuration change
                        configChangeNotifier.notifyConfigChange(fullPath);
                        return Mono.just(HttpResponse.noContent());
                    } else {
                        LOG.error("Failed to delete key: {}", fullPath);
                        return Mono.just(HttpResponse.serverError("Failed to delete key from Consul KV"));
                    }
                });
    }

    /**
     * Triggers a refresh of all @Refreshable beans.
     *
     * @return 200 OK
     */
    @Post("/refresh")
    public HttpResponse<?> refresh() {
        LOG.info("Refresh request received");
        eventPublisher.publishEvent(new RefreshEvent());
        return HttpResponse.ok();
    }
}
