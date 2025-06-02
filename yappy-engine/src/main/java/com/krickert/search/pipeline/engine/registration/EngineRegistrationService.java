package com.krickert.search.pipeline.engine.registration;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.model.agent.ImmutableRegCheck;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.kiwiproject.consul.model.agent.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service responsible for registering the Yappy Engine instance with Consul.
 * This includes metadata about which modules are co-located with this engine.
 */
@Singleton
public class EngineRegistrationService implements ApplicationEventListener<StartupEvent> {
    
    private static final Logger LOG = LoggerFactory.getLogger(EngineRegistrationService.class);
    private static final String YAPPY_ENGINE_TAG = "yappy-engine";
    private static final String YAPPY_ENGINE_MODULE_TAG_PREFIX = "yappy-engine-module=";
    private static final String ENGINE_SERVICE_NAME = "yappy-engine";
    
    private final ConsulBusinessOperationsService consulBusinessOpsService;
    private final String engineInstanceId;
    private final String engineHost;
    private final int enginePort;
    private final int engineHealthCheckPort;
    private final List<String> colocatedModules;
    private final boolean autoRegisterEnabled;
    
    public EngineRegistrationService(
            ConsulBusinessOperationsService consulBusinessOpsService,
            @Value("${yappy.engine.instance-id:}") String engineInstanceId,
            @Value("${yappy.engine.host:}") String engineHost,
            @Value("${grpc.server.port:50051}") int enginePort,
            @Value("${yappy.engine.health-check-port:50051}") int engineHealthCheckPort,
            @Value("${yappy.engine.colocated-modules:}") List<String> colocatedModules,
            @Value("${yappy.engine.auto-register:true}") boolean autoRegisterEnabled
    ) {
        this.consulBusinessOpsService = consulBusinessOpsService;
        this.engineInstanceId = engineInstanceId.isEmpty() ? generateInstanceId() : engineInstanceId;
        this.engineHost = engineHost.isEmpty() ? getDefaultHost() : engineHost;
        this.enginePort = enginePort;
        this.engineHealthCheckPort = engineHealthCheckPort;
        this.colocatedModules = colocatedModules != null ? colocatedModules : new ArrayList<>();
        this.autoRegisterEnabled = autoRegisterEnabled;
        
        LOG.info("Engine registration service initialized. Instance ID: {}, Host: {}, Port: {}, " +
                "Health Check Port: {}, Co-located modules: {}, Auto-register: {}",
                this.engineInstanceId, this.engineHost, this.enginePort, 
                this.engineHealthCheckPort, this.colocatedModules, this.autoRegisterEnabled);
    }
    
    @Override
    public void onApplicationEvent(StartupEvent event) {
        if (autoRegisterEnabled) {
            LOG.info("Auto-registration enabled. Registering engine with Consul...");
            registerEngine()
                    .doOnSuccess(v -> LOG.info("Engine registered successfully with Consul"))
                    .doOnError(e -> LOG.error("Failed to register engine with Consul", e))
                    .subscribe();
        } else {
            LOG.info("Auto-registration disabled. Engine will not be registered with Consul automatically.");
        }
    }
    
    /**
     * Registers the engine instance with Consul, including metadata about co-located modules.
     */
    public Mono<Void> registerEngine() {
        try {
            Registration registration = buildEngineRegistration();
            LOG.debug("Registering engine with Consul: {}", registration);
            return consulBusinessOpsService.registerService(registration);
        } catch (Exception e) {
            LOG.error("Failed to build engine registration", e);
            return Mono.error(e);
        }
    }
    
    /**
     * Updates the engine's TTL health check (if using TTL checks).
     */
    @Scheduled(fixedDelay = "10s", initialDelay = "15s")
    public void updateHealthCheck() {
        if (!autoRegisterEnabled) {
            return;
        }
        // TODO: Implement TTL health check update if needed
        // For now, we're using gRPC health checks which are handled automatically
    }
    
    private Registration buildEngineRegistration() {
        ImmutableRegistration.Builder registrationBuilder = ImmutableRegistration.builder()
                .id(engineInstanceId)
                .name(ENGINE_SERVICE_NAME)
                .address(engineHost)
                .port(enginePort);
        
        // Configure gRPC health check
        ImmutableRegCheck.Builder checkBuilder = ImmutableRegCheck.builder()
                .grpc(engineHost + ":" + engineHealthCheckPort)
                .interval("10s")
                .timeout("5s")
                .deregisterCriticalServiceAfter("60s");
        
        registrationBuilder.check(checkBuilder.build());
        
        // Add tags
        List<String> tags = new ArrayList<>();
        tags.add(YAPPY_ENGINE_TAG);
        tags.add("version=" + getEngineVersion());
        
        // Add tags for each co-located module
        for (String moduleId : colocatedModules) {
            tags.add(YAPPY_ENGINE_MODULE_TAG_PREFIX + moduleId);
            LOG.debug("Adding co-located module tag: {}", YAPPY_ENGINE_MODULE_TAG_PREFIX + moduleId);
        }
        
        registrationBuilder.tags(tags);
        
        return registrationBuilder.build();
    }
    
    private String generateInstanceId() {
        return "yappy-engine-" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    private String getDefaultHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            LOG.warn("Could not determine local host address, using localhost", e);
            return "localhost";
        }
    }
    
    private String getEngineVersion() {
        // TODO: Get from build properties or manifest
        return "1.0.0";
    }
    
    /**
     * Gets the list of module implementation IDs that are co-located with this engine.
     */
    public List<String> getColocatedModules() {
        return new ArrayList<>(colocatedModules);
    }
    
    /**
     * Checks if a specific module is co-located with this engine.
     */
    public boolean hasColocatedModule(String moduleImplementationId) {
        return colocatedModules.contains(moduleImplementationId);
    }
}