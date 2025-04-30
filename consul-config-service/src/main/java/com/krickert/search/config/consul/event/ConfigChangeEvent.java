package com.krickert.search.config.consul.event;

import lombok.Data;

/**
 * Event class for configuration changes.
 * This event is published when configuration is changed in Consul KV store.
 */
@Data
public class ConfigChangeEvent {
    /**
     * -- GETTER --
     *  Gets the prefix of the key that was changed.
     */
    private final String keyPrefix;

    /**
     * Creates a new ConfigChangeEvent with the specified key prefix.
     *
     * @param keyPrefix the prefix of the key that was changed
     */
    public ConfigChangeEvent(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

}