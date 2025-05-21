package com.krickert.search.pipeline.grpc.client;

import io.micronaut.context.annotation.ConfigurationProperties;
import java.util.Collections;
import java.util.Map;

@ConfigurationProperties("local.services")
public class LocalServicesConfig {

    private Map<String, Integer> ports = Collections.emptyMap(); // Initialize to prevent NullPointerExceptions

    public Map<String, Integer> getPorts() {
        return ports;
    }

    public void setPorts(Map<String, Integer> ports) {
        // It's good practice to make a defensive copy if the map could be modified elsewhere,
        // or ensure it's unmodifiable if it shouldn't change after setting.
        // For simplicity here, direct assignment is shown.
        this.ports = (ports != null) ? ports : Collections.emptyMap();
    }
}