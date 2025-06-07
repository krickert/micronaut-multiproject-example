package com.krickert.search.config.consul.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.AgentClient;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.model.agent.ImmutableRegCheck;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.kiwiproject.consul.model.agent.Registration;
import org.kiwiproject.consul.model.health.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Service for managing Consul service registrations.
 * Provides low-level Consul operations for service registration and health checks.
 * This service is configuration-agnostic and can be used by any component
 * that needs to register services with Consul.
 */
@Singleton
@Requires(property = "consul.client.enabled", value = "true")
public class ConsulModuleRegistrationService {
    
    private static final Logger LOG = LoggerFactory.getLogger(ConsulModuleRegistrationService.class);
    
    private final Consul consul;
    private final ObjectMapper objectMapper;
    
    @Inject
    public ConsulModuleRegistrationService(Consul consul, ObjectMapper objectMapper) {
        this.consul = consul;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Registers a service with Consul.
     * 
     * @param serviceId Unique service ID
     * @param serviceName Service name for discovery
     * @param host Service host
     * @param port Service port
     * @param tags Service tags
     * @param healthCheck Health check configuration
     * @return true if registration successful
     */
    public boolean registerService(
            String serviceId,
            String serviceName,
            String host,
            int port,
            List<String> tags,
            HealthCheckConfig healthCheck) {
        
        try {
            LOG.info("Registering service '{}' (ID: {}) with Consul", serviceName, serviceId);
            
            ImmutableRegistration.Builder builder = ImmutableRegistration.builder()
                .id(serviceId)
                .name(serviceName)
                .address(host)
                .port(port);
            
            if (tags != null && !tags.isEmpty()) {
                builder.tags(tags);
            }
            
            if (healthCheck != null) {
                ImmutableRegCheck check = buildHealthCheck(healthCheck, host, port);
                builder.check(check);
            }
            
            Registration registration = builder.build();
            
            AgentClient agentClient = consul.agentClient();
            agentClient.register(registration);
            
            LOG.info("Successfully registered service '{}' with Consul", serviceId);
            return true;
            
        } catch (Exception e) {
            LOG.error("Failed to register service '{}': {}", serviceId, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Deregisters a service from Consul.
     */
    public boolean deregisterService(String serviceId) {
        try {
            AgentClient agentClient = consul.agentClient();
            agentClient.deregister(serviceId);
            LOG.info("Deregistered service '{}' from Consul", serviceId);
            return true;
        } catch (Exception e) {
            LOG.error("Failed to deregister service '{}': {}", serviceId, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Gets all services matching a tag filter.
     */
    public Map<String, ServiceInfo> getServicesByTag(String tag) {
        Map<String, ServiceInfo> services = new HashMap<>();
        
        try {
            AgentClient agentClient = consul.agentClient();
            Map<String, Service> allServices = agentClient.getServices();
            
            allServices.forEach((id, service) -> {
                if (service.getTags().contains(tag)) {
                    services.put(id, new ServiceInfo(
                        id,
                        service.getService(),
                        service.getAddress(),
                        service.getPort(),
                        service.getTags()
                    ));
                }
            });
            
        } catch (Exception e) {
            LOG.error("Failed to get services by tag '{}': {}", tag, e.getMessage(), e);
        }
        
        return services;
    }
    
    /**
     * Gets a specific service by ID.
     */
    public Optional<ServiceInfo> getService(String serviceId) {
        try {
            AgentClient agentClient = consul.agentClient();
            Map<String, Service> services = agentClient.getServices();
            
            Service service = services.get(serviceId);
            if (service != null) {
                return Optional.of(new ServiceInfo(
                    serviceId,
                    service.getService(),
                    service.getAddress(),
                    service.getPort(),
                    service.getTags()
                ));
            }
        } catch (Exception e) {
            LOG.error("Failed to get service '{}': {}", serviceId, e.getMessage(), e);
        }
        
        return Optional.empty();
    }
    
    /**
     * Canonicalizes JSON by sorting keys and removing whitespace.
     * Useful for generating consistent digests.
     */
    public String canonicalizeJson(String jsonString) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(jsonString);
        
        // Create a sorted version
        ObjectNode sortedNode = objectMapper.createObjectNode();
        List<String> fieldNames = new ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        Collections.sort(fieldNames);
        
        for (String fieldName : fieldNames) {
            sortedNode.set(fieldName, node.get(fieldName));
        }
        
        // Convert to canonical string (sorted, no extra whitespace)
        return objectMapper.writeValueAsString(sortedNode);
    }
    
    /**
     * Generates an MD5 digest of a string.
     * Useful for creating configuration digests.
     */
    public String generateMd5Digest(String input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }
    
    /**
     * Validates a JSON string is well-formed.
     */
    public boolean isValidJson(String jsonString) {
        try {
            objectMapper.readTree(jsonString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    private ImmutableRegCheck buildHealthCheck(HealthCheckConfig config, String host, int port) {
        ImmutableRegCheck.Builder builder = ImmutableRegCheck.builder();
        
        String interval = config.intervalSeconds() + "s";
        String timeout = config.timeoutSeconds() + "s";
        String deregisterAfter = config.deregisterAfterSeconds() + "s";
        
        switch (config.type()) {
            case HTTP:
                String url = String.format("http://%s:%d%s", host, port, config.endpoint());
                builder.http(url).interval(interval).timeout(timeout);
                break;
                
            case HTTPS:
                String httpsUrl = String.format("https://%s:%d%s", host, port, config.endpoint());
                builder.http(httpsUrl).interval(interval).timeout(timeout);
                break;
                
            case GRPC:
                String grpcAddress = host + ":" + port;
                builder.grpc(grpcAddress).interval(interval).timeout(timeout);
                break;
                
            case TCP:
                String tcpAddress = host + ":" + port;
                builder.tcp(tcpAddress).interval(interval).timeout(timeout);
                break;
                
            case TTL:
                builder.ttl(config.ttlSeconds() + "s");
                break;
        }
        
        builder.deregisterCriticalServiceAfter(deregisterAfter);
        return builder.build();
    }
    
    /**
     * Health check configuration.
     */
    public record HealthCheckConfig(
        HealthCheckType type,
        String endpoint,
        long intervalSeconds,
        long timeoutSeconds,
        long deregisterAfterSeconds,
        long ttlSeconds
    ) {
        public static HealthCheckConfig http(String endpoint) {
            return new HealthCheckConfig(HealthCheckType.HTTP, endpoint, 10, 5, 60, 0);
        }
        
        public static HealthCheckConfig grpc() {
            return new HealthCheckConfig(HealthCheckType.GRPC, "", 10, 5, 60, 0);
        }
        
        public static HealthCheckConfig tcp() {
            return new HealthCheckConfig(HealthCheckType.TCP, "", 10, 5, 60, 0);
        }
        
        public static HealthCheckConfig ttl(long ttlSeconds) {
            return new HealthCheckConfig(HealthCheckType.TTL, "", 0, 0, 60, ttlSeconds);
        }
    }
    
    public enum HealthCheckType {
        HTTP, HTTPS, GRPC, TCP, TTL
    }
    
    /**
     * Service information.
     */
    public record ServiceInfo(
        String id,
        String name,
        String address,
        int port,
        List<String> tags
    ) {
        public String getTagValue(String prefix) {
            return tags.stream()
                .filter(tag -> tag.startsWith(prefix))
                .map(tag -> tag.substring(prefix.length()))
                .findFirst()
                .orElse(null);
        }
        
        public boolean hasTag(String tag) {
            return tags.contains(tag);
        }
    }
}