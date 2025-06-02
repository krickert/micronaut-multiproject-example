package com.krickert.search.pipeline.engine.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.search.config.service.model.ServiceAggregatedStatus;
import com.krickert.search.config.service.model.ServiceOperationalStatus;
import com.krickert.search.pipeline.status.ServiceStatusAggregator;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import io.micronaut.context.annotation.Property;
import org.kiwiproject.consul.AgentClient;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.kiwiproject.consul.model.agent.Registration;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for ServiceStatusAggregator using real test containers and real module contexts.
 * This test verifies the service status aggregation functionality with actual
 * Consul integration and module registration patterns that mirror production behavior.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@MicronautTest(environments = "test")
@Property(name = "app.config.cluster-name", value = ServiceStatusAggregatorIT.TEST_CLUSTER_NAME)
class ServiceStatusAggregatorIT implements TestPropertyProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(ServiceStatusAggregatorIT.class);
    
    // Module contexts that will be started during the test
    private ApplicationContext echoModuleContext;
    private final Map<String, String> resolvedModulePorts = new TreeMap<>();

    @Inject
    ServiceStatusAggregator serviceStatusAggregator;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    DynamicConfigurationManager dynamicConfigManager;

    @Inject
    ConsulBusinessOperationsService consulBusinessOpsService;

    @Inject
    ConsulKvService consulKvService;
    
    @Inject
    AgentClient agentClient;

    static final String TEST_CLUSTER_NAME = "test-status-aggregator-cluster";
    private static final String TEST_SERVICE_NAME = "echo-test-module";
    private static final String STATUS_KV_PREFIX = "yappy/status/services/";
    private static final String CLUSTER_CONFIG_PREFIX = "pipeline-configs/clusters/";
    
    private final List<String> registeredServiceIds = new ArrayList<>();
    
    @Override
    public @NonNull Map<String, String> getProperties() {
        LOG.info("TestPropertyProvider.getProperties() called - setting up module contexts");
        stopModuleContexts();

        Map<String, String> providedProperties = new TreeMap<>();

        // Start Echo Module Context
        String echoAppName = "echo-module-" + UUID.randomUUID().toString().substring(0, 8);
        LOG.info("Starting Echo module context (App Name: {})...", echoAppName);
        
        Map<String, Object> echoModuleProps = new TreeMap<>();
        echoModuleProps.put("micronaut.application.name", echoAppName);
        echoModuleProps.put("grpc.server.port", "${random.port}");
        echoModuleProps.put("grpc.server.health.enabled", true); // Enable gRPC health service
        echoModuleProps.put("grpc.services.echo.enabled", true);
        echoModuleProps.put("grpc.server.enabled", true); // Explicitly enable gRPC server
        
        // Modules don't know about Consul - the engine manages registration
        echoModuleProps.put("consul.client.enabled", false);

        ApplicationContextBuilder echoBuilder = ApplicationContext.builder()
                .environments("echo-module-env", "test")
                .propertySources(PropertySource.of("echo-module-config", echoModuleProps));
        
        try {
            echoModuleContext = echoBuilder.build().start();
            LOG.info("Echo module context started successfully.");

            // Get the resolved port
            Integer echoGrpcPortResolved = echoModuleContext.getEnvironment()
                    .getProperty("grpc.server.port", Integer.class)
                    .orElseThrow(() -> new IllegalStateException("Echo gRPC port not resolved"));
            
            LOG.info("Echo module resolved gRPC port: {}", echoGrpcPortResolved);
            providedProperties.put("local.services.ports.echo", echoGrpcPortResolved.toString());
            resolvedModulePorts.put("echo", echoGrpcPortResolved.toString());
            
        } catch (Exception e) {
            LOG.error("Failed to start Echo module context", e);
            throw new RuntimeException("Failed to start Echo module context", e);
        }

        return providedProperties;
    }

    @BeforeEach
    void setUp() throws InterruptedException {
        // Clean up any existing test data in Consul
        cleanupTestData().block();
        
        // Seed a test cluster configuration with our test module
        seedTestClusterConfig().block();
        
        // Force the DynamicConfigurationManager to reinitialize for our test cluster
        dynamicConfigManager.initialize(TEST_CLUSTER_NAME);
        
        // Wait a bit for the async initialization to complete
        Thread.sleep(1000);
        
        // Verify config was loaded
        assertTrue(dynamicConfigManager.getCurrentPipelineClusterConfig().isPresent(),
                "DynamicConfigurationManager should have loaded the test cluster configuration");
    }
    
    @AfterEach
    void tearDown() {
        // Deregister all services we registered during the test
        for (String serviceId : registeredServiceIds) {
            try {
                agentClient.deregister(serviceId);
                LOG.info("Deregistered test service: {}", serviceId);
            } catch (Exception e) {
                LOG.warn("Failed to deregister test service: {}", serviceId, e);
            }
        }
        registeredServiceIds.clear();
        
        // Don't stop module contexts here - they should remain running for all tests
        // Module contexts will be stopped when the test class completes
    }
    
    @AfterAll
    void cleanupAfterAllTests() {
        LOG.info("Cleaning up after all tests - stopping module contexts");
        if (echoModuleContext != null && echoModuleContext.isRunning()) {
            LOG.info("Stopping Echo module context...");
            echoModuleContext.stop();
            echoModuleContext = null;
        }
        resolvedModulePorts.clear();
    }

    private Mono<Void> cleanupTestData() {
        // Clean up status KV entries and cluster config
        return consulKvService.deleteKey(STATUS_KV_PREFIX + TEST_SERVICE_NAME)
                .then(consulKvService.deleteKey(CLUSTER_CONFIG_PREFIX + TEST_CLUSTER_NAME))
                .then();
    }

    private Mono<Void> seedTestClusterConfig() {
        // Create module configuration for our echo test module
        PipelineModuleConfiguration moduleConfig = new PipelineModuleConfiguration(
                "Echo Test Module",  // implementationName
                TEST_SERVICE_NAME,   // implementationId
                null,               // customConfigSchemaReference
                null                // customConfig
        );
        
        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineModuleMap(new PipelineModuleMap(Map.of(TEST_SERVICE_NAME, moduleConfig)))
                .build();
        
        try {
            String configJson = objectMapper.writeValueAsString(clusterConfig);
            return consulKvService.putValue(
                    CLUSTER_CONFIG_PREFIX + TEST_CLUSTER_NAME,
                    configJson
            ).then();
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    @Test
    void testAggregateAndStore_ServiceHealthy() throws InterruptedException {
        // Given: Register the echo module as the engine would
        String serviceId = "echo-instance-1";
        String echoPort = resolvedModulePorts.get("echo");
        assertNotNull(echoPort, "Echo port should be available from TestPropertyProvider");
        registerHealthyModule(serviceId, TEST_SERVICE_NAME, "localhost", Integer.parseInt(echoPort));
        
        // Wait for registration to propagate
        // TTL health check should be immediately healthy after pass()
        Thread.sleep(1000);
        
        // Debug: Verify echo module is accessible
        LOG.info("Verifying echo module is accessible on port: {}", echoPort);
        LOG.info("Echo module context running: {}", echoModuleContext != null && echoModuleContext.isRunning());
        
        // When: aggregateAndStoreServiceStatuses is called
        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        
        // Wait for async processing
        Thread.sleep(1500);
        
        // Then: Status should be stored in KV with ACTIVE_HEALTHY status
        StepVerifier.create(
                consulKvService.getValue(STATUS_KV_PREFIX + TEST_SERVICE_NAME)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .map(kvValue -> {
                            try {
                                return objectMapper.readValue(kvValue, ServiceAggregatedStatus.class);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
        )
        .assertNext(status -> {
            LOG.info("Retrieved status: {}", status);
            assertEquals(TEST_SERVICE_NAME, status.serviceName());
            assertEquals(ServiceOperationalStatus.ACTIVE_HEALTHY, status.operationalStatus());
            assertEquals(1, status.totalInstancesConsul());
            assertEquals(1, status.healthyInstancesConsul());
            assertTrue(status.statusDetail().contains("healthy instance(s) found"));
        })
        .expectComplete()
        .verify(Duration.ofSeconds(10));
    }

    @Test
    void testAggregateAndStore_NoInstances() throws InterruptedException {
        // Given: No service instances are registered
        
        // When: aggregateAndStoreServiceStatuses is called
        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        
        // Wait for async processing
        Thread.sleep(1000);
        
        // Then: Status should show AWAITING_HEALTHY_REGISTRATION
        StepVerifier.create(
                consulKvService.getValue(STATUS_KV_PREFIX + TEST_SERVICE_NAME)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .map(kvValue -> {
                            try {
                                return objectMapper.readValue(kvValue, ServiceAggregatedStatus.class);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
        )
        .assertNext(status -> {
            assertEquals(TEST_SERVICE_NAME, status.serviceName());
            assertEquals(ServiceOperationalStatus.AWAITING_HEALTHY_REGISTRATION, status.operationalStatus());
            assertEquals(0, status.totalInstancesConsul());
            assertEquals(0, status.healthyInstancesConsul());
            assertTrue(status.statusDetail().contains("No instances registered"));
        })
        .expectComplete()
        .verify(Duration.ofSeconds(5));
    }

    @Test
    void testAggregateAndStore_InstancesButNoneHealthy() throws InterruptedException {
        // Given: Register a module but don't let its health check pass
        String serviceId = "echo-unhealthy-instance-1";
        String echoPort = resolvedModulePorts.get("echo");
        assertNotNull(echoPort, "Echo port should be available from TestPropertyProvider");
        registerUnhealthyModule(serviceId, TEST_SERVICE_NAME, "localhost", Integer.parseInt(echoPort));
        
        // Wait for registration to propagate
        Thread.sleep(2000);
        
        // When: aggregateAndStoreServiceStatuses is called
        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        
        // Wait for async processing
        Thread.sleep(1500);
        
        // Then: Status should show UNAVAILABLE
        StepVerifier.create(
                consulKvService.getValue(STATUS_KV_PREFIX + TEST_SERVICE_NAME)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .map(kvValue -> {
                            try {
                                return objectMapper.readValue(kvValue, ServiceAggregatedStatus.class);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
        )
        .assertNext(status -> {
            assertEquals(TEST_SERVICE_NAME, status.serviceName());
            assertEquals(ServiceOperationalStatus.UNAVAILABLE, status.operationalStatus());
            assertTrue(status.totalInstancesConsul() > 0);
            assertEquals(0, status.healthyInstancesConsul());
            assertTrue(status.statusDetail().contains("none are healthy"));
        })
        .expectComplete()
        .verify(Duration.ofSeconds(10));
    }

    @Test
    void testUpdateServiceStatusToProxying() throws InterruptedException {
        // Given: A service that needs to be marked as proxying
        
        // When: updateServiceStatusToProxying is called
        serviceStatusAggregator.updateServiceStatusToProxying(TEST_SERVICE_NAME);
        
        // Wait for async processing
        Thread.sleep(500);
        
        // Then: Status should show ACTIVE_PROXYING
        StepVerifier.create(
                consulKvService.getValue(STATUS_KV_PREFIX + TEST_SERVICE_NAME)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .map(kvValue -> {
                            try {
                                return objectMapper.readValue(kvValue, ServiceAggregatedStatus.class);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
        )
        .assertNext(status -> {
            assertEquals(TEST_SERVICE_NAME, status.serviceName());
            assertEquals(ServiceOperationalStatus.ACTIVE_PROXYING, status.operationalStatus());
            assertTrue(status.isProxying());
            assertTrue(status.statusDetail().contains("accessed remotely"));
        })
        .expectComplete()
        .verify(Duration.ofSeconds(5));
    }

    @Test
    void testAggregateAndStore_WithConfigDigest() throws InterruptedException {
        // Given: Register a module with config digest tag
        String expectedDigest = "abc123def456";
        String serviceId = "echo-instance-with-digest";
        String echoPort = resolvedModulePorts.get("echo");
        assertNotNull(echoPort, "Echo port should be available from TestPropertyProvider");
        registerHealthyModuleWithDigest(serviceId, TEST_SERVICE_NAME, "localhost", 
                Integer.parseInt(echoPort), expectedDigest);
        
        // Wait for registration to propagate
        // TTL health check should be immediately healthy after pass()
        Thread.sleep(1000);
        
        // When: aggregateAndStoreServiceStatuses is called
        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        
        // Wait for async processing
        Thread.sleep(1500);
        
        // Then: Status should include the config digest
        StepVerifier.create(
                consulKvService.getValue(STATUS_KV_PREFIX + TEST_SERVICE_NAME)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .map(kvValue -> {
                            try {
                                return objectMapper.readValue(kvValue, ServiceAggregatedStatus.class);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
        )
        .assertNext(status -> {
            assertEquals(TEST_SERVICE_NAME, status.serviceName());
            assertEquals(expectedDigest, status.reportedModuleConfigDigest());
        })
        .expectComplete()
        .verify(Duration.ofSeconds(10));
    }

    /**
     * Register a healthy module with Consul, simulating what the engine would do
     * for its co-located modules.
     */
    private void registerHealthyModule(String serviceId, String serviceName, String host, int port) {
        Registration registration = ImmutableRegistration.builder()
                .id(serviceId)
                .name(serviceName)
                .address(host)
                .port(port)
                .tags(List.of("yappy-module", "yappy-module-implementation-id=" + serviceName))
                // Use TTL health check as a workaround
                .check(Registration.RegCheck.ttl(30L)) // 30 second TTL
                .build();
        
        agentClient.register(registration);
        registeredServiceIds.add(serviceId);
        LOG.info("Registered healthy module service: {} at {}:{}", serviceId, host, port);
        
        // Pass the TTL health check to make the service healthy
        try {
            // Wait a bit for registration to complete
            Thread.sleep(100);
            
            // Try different check ID formats
            String checkId = "service:" + serviceId;
            try {
                agentClient.pass(checkId, "Module is healthy");
                LOG.info("Passed TTL health check for: {}", checkId);
            } catch (Exception e1) {
                // Try with just the serviceId
                LOG.debug("Failed with 'service:' prefix, trying without prefix");
                checkId = serviceId;
                agentClient.pass(checkId, "Module is healthy");
                LOG.info("Passed TTL health check for: {}", checkId);
            }
        } catch (Exception e) {
            LOG.warn("Failed to pass TTL health check for service: {}", serviceId, e);
        }
    }
    
    /**
     * Register a module that will fail health checks
     */
    private void registerUnhealthyModule(String serviceId, String serviceName, String host, int port) {
        // Register with a TTL check that we won't pass, simulating an unhealthy module
        Registration registration = ImmutableRegistration.builder()
                .id(serviceId)
                .name(serviceName)
                .address(host)
                .port(port)
                .tags(List.of("yappy-module", "yappy-module-implementation-id=" + serviceName))
                .check(Registration.RegCheck.ttl(10L)) // TTL check that we won't pass
                .build();
        
        agentClient.register(registration);
        registeredServiceIds.add(serviceId);
        // Don't pass the health check - service will be unhealthy
        LOG.info("Registered module service without passing health check: {}", serviceId);
    }
    
    /**
     * Register a healthy module with a config digest tag
     */
    private void registerHealthyModuleWithDigest(String serviceId, String serviceName, 
            String host, int port, String configDigest) {
        Registration registration = ImmutableRegistration.builder()
                .id(serviceId)
                .name(serviceName)
                .address(host)
                .port(port)
                .tags(List.of(
                    "yappy-module", 
                    "yappy-module-implementation-id=" + serviceName,
                    "yappy-config-digest=" + configDigest
                ))
                // Use TTL health check as a workaround
                .check(Registration.RegCheck.ttl(30L)) // 30 second TTL
                .build();
        
        agentClient.register(registration);
        registeredServiceIds.add(serviceId);
        LOG.info("Registered healthy module with digest: {} at {}:{}", serviceId, host, port);
        
        // Pass the TTL health check to make the service healthy
        try {
            // Wait a bit for registration to complete
            Thread.sleep(100);
            
            // Try different check ID formats
            String checkId = "service:" + serviceId;
            try {
                agentClient.pass(checkId, "Module is healthy");
                LOG.info("Passed TTL health check for: {}", checkId);
            } catch (Exception e1) {
                // Try with just the serviceId
                LOG.debug("Failed with 'service:' prefix, trying without prefix");
                checkId = serviceId;
                agentClient.pass(checkId, "Module is healthy");
                LOG.info("Passed TTL health check for: {}", checkId);
            }
        } catch (Exception e) {
            LOG.warn("Failed to pass TTL health check for service: {}", serviceId, e);
        }
    }
    
    private void stopModuleContexts() {
        if (echoModuleContext != null && echoModuleContext.isRunning()) {
            LOG.info("Stopping Echo module context...");
            echoModuleContext.stop();
            echoModuleContext = null;
        }
    }
}