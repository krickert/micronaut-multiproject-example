package com.krickert.search.engine.core;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Service registration details for Consul.
 * 
 * This contains all the information needed to register a module
 * with Consul for service discovery.
 */
public record ServiceRegistration(
    String serviceId,
    String serviceName,
    String host,
    int port,
    HealthCheckConfig healthCheck,
    Set<String> tags,
    Map<String, String> metadata
) {
    
    /**
     * Builder for ServiceRegistration.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String serviceId;
        private String serviceName;
        private String host;
        private int port;
        private HealthCheckConfig healthCheck;
        private Set<String> tags = Set.of();
        private Map<String, String> metadata = Map.of();
        
        public Builder serviceId(String serviceId) {
            this.serviceId = serviceId;
            return this;
        }
        
        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }
        
        public Builder host(String host) {
            this.host = host;
            return this;
        }
        
        public Builder port(int port) {
            this.port = port;
            return this;
        }
        
        public Builder healthCheck(HealthCheckConfig healthCheck) {
            this.healthCheck = healthCheck;
            return this;
        }
        
        public Builder tags(Set<String> tags) {
            this.tags = tags;
            return this;
        }
        
        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }
        
        public ServiceRegistration build() {
            return new ServiceRegistration(
                serviceId, serviceName, host, port,
                healthCheck, tags, metadata
            );
        }
    }
}