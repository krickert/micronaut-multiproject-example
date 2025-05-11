// src/test/java/com/krickert/search/config/consul/util/ConsulTestUtil.java
package com.krickert.search.config.consul.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kiwiproject.consul.KeyValueClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConsulTestUtil {
    private static final Logger LOG = LoggerFactory.getLogger(ConsulTestUtil.class);
    private final KeyValueClient kvClient;
    private final ObjectMapper objectMapper;

    public ConsulTestUtil(KeyValueClient kvClient, ObjectMapper objectMapper) {
        this.kvClient = kvClient;
        this.objectMapper = objectMapper;
    }

    public void seedKv(String key, Object object) throws JsonProcessingException {
        String jsonValue = objectMapper.writeValueAsString(object);
        if (!kvClient.putValue(key, jsonValue)) {
            throw new RuntimeException("Failed to seed Consul KV store for key: " + key);
        }
        LOG.debug("Seeded Consul KV: {} = {}", key, jsonValue.length() > 100 ? jsonValue.substring(0, 100) + "..." : jsonValue);
    }

    public void deleteKv(String key) {
        kvClient.deleteKey(key);
        LOG.debug("Deleted Consul KV: {}", key);
    }

    public void deleteAllKv(String prefix) {
        kvClient.deleteKeys(prefix);
        LOG.debug("Deleted all Consul KV under prefix: {}", prefix);
    }
}