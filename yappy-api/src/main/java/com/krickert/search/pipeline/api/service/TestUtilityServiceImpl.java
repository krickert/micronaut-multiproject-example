package com.krickert.search.pipeline.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.service.ConsulModuleRegistrationService.HealthCheckConfig;
import com.krickert.search.config.consul.service.ConsulModuleRegistrationService.HealthCheckType;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.krickert.search.config.consul.service.ConsulModuleRegistrationService;
import com.krickert.search.config.consul.schema.delegate.ConsulSchemaRegistryDelegate;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.OutputTarget;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.JsonConfigOptions;
import com.krickert.search.config.pipeline.model.GrpcTransportConfig;
import com.krickert.search.config.pipeline.model.TransportType;
import com.krickert.search.config.pipeline.model.StepType;
import com.krickert.search.pipeline.api.dto.*;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.HealthClient;
import org.kiwiproject.consul.model.health.ServiceHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Singleton
@Requires(notEnv = "production") // Disable in production
public class TestUtilityServiceImpl implements TestUtilityService {
    private static final Logger LOG = LoggerFactory.getLogger(TestUtilityServiceImpl.class);
    
    private final ConsulKvService kvService;
    private final ConsulSchemaRegistryDelegate schemaRegistry;
    private final ConsulModuleRegistrationService registrationService;
    private final ConsulBusinessOperationsService businessOpsService;
    private final ObjectMapper objectMapper;
    private final Consul consul;
    
    public TestUtilityServiceImpl(ConsulKvService kvService,
                                  ConsulSchemaRegistryDelegate schemaRegistry,
                                  ConsulModuleRegistrationService registrationService,
                                  ConsulBusinessOperationsService businessOpsService,
                                  ObjectMapper objectMapper,
                                  Consul consul) {
        this.kvService = kvService;
        this.schemaRegistry = schemaRegistry;
        this.registrationService = registrationService;
        this.businessOpsService = businessOpsService;
        this.objectMapper = objectMapper;
        this.consul = consul;
    }
    
    
    @Override
    public Mono<ModuleRegistrationResponse> registerModule(ModuleRegistrationRequest request) {
        return Mono.fromCallable(() -> {
            // Convert to Consul service health check
            HealthCheckConfig healthCheck;
            if (request.healthCheckPath() != null && !request.healthCheckPath().isEmpty()) {
                healthCheck = HealthCheckConfig.http(request.healthCheckPath());
            } else {
                // Default to gRPC health check for gRPC services
                healthCheck = HealthCheckConfig.grpc();
            }
            
            // Register with Consul
            List<String> tags = new ArrayList<>();
            tags.add("yappy-module");
            if (request.capabilities() != null) {
                tags.addAll(request.capabilities());
            }
            boolean success = registrationService.registerService(
                request.serviceId(),
                request.name(),
                request.host(),
                request.port(),
                tags,
                healthCheck
            );
            
            if (!success) {
                throw new RuntimeException("Failed to register module in Consul");
            }
            
            return new ModuleRegistrationResponse(
                request.serviceId(),
                "registered",
                Instant.now(),
                "consul-" + request.serviceId(),
                "Module registered successfully in test environment"
            );
        })
        .doOnSubscribe(s -> LOG.info("Test utility registering module: {}", request.serviceId()))
        .doOnError(e -> LOG.error("Failed to register module {}: {}", request.serviceId(), e.getMessage()));
    }
    
    @Override
    public Mono<Void> deregisterModule(String serviceId) {
        return Mono.fromRunnable(() -> {
            registrationService.deregisterService(serviceId);
        })
        .then()
        .doOnSubscribe(s -> LOG.info("Test utility deregistering module: {}", serviceId));
    }
    
    @Override
    public Flux<ModuleInfo> getAllModules() {
        return Mono.fromCallable(() -> {
            var services = consul.agentClient().getServices();
            List<ModuleInfo> modules = services.values().stream()
                .filter(service -> service.getTags().contains("yappy-module"))
                .map(service -> new ModuleInfo(
                    service.getId(),
                    service.getService(),
                    null, // description
                    "1.0.0", // version placeholder
                    1, // instances - we only know about this one
                    "healthy", // Consul reports healthy services
                    service.getTags(),
                    List.of(), // inputTypes - not available in Consul
                    List.of(), // outputTypes - not available in Consul
                    Map.of(), // configSchema - not available in Consul
                    Instant.now(), // registeredAt placeholder
                    Instant.now() // lastHealthCheck placeholder
                ))
                .collect(Collectors.toList());
            return modules;
        })
        .flatMapMany(Flux::fromIterable)
        .doOnSubscribe(s -> LOG.debug("Test utility listing all modules"));
    }
    
    @Override
    public Mono<PipelineConfig> createSimplePipeline(String pipelineName, List<String> stepNames) {
        return Mono.fromCallable(() -> {
            Map<String, PipelineStepConfig> steps = new LinkedHashMap<>();
            
            // Create simple linear pipeline
            for (int i = 0; i < stepNames.size(); i++) {
                String stepName = stepNames.get(i);
                String stepId = "step-" + i;
                
                // Create processor info
                ProcessorInfo processor = new ProcessorInfo(stepName, null);
                
                // Create outputs for next step
                Map<String, OutputTarget> outputs = new HashMap<>();
                if (i < stepNames.size() - 1) {
                    String nextStepId = "step-" + (i + 1);
                    outputs.put(nextStepId, new OutputTarget(
                        nextStepId,
                        TransportType.GRPC,
                        new GrpcTransportConfig(nextStepId, null), // Use service discovery
                        null
                    ));
                }
                
                // Create step config
                PipelineStepConfig stepConfig = new PipelineStepConfig(
                    stepName,
                    StepType.PIPELINE,
                    null, // description
                    null, // customConfigSchemaId
                    null, // customConfig
                    null, // kafkaInputs
                    outputs.isEmpty() ? null : outputs,
                    null, // maxRetries
                    null, // retryBackoffMs
                    null, // maxRetryBackoffMs
                    null, // retryBackoffMultiplier
                    null, // stepTimeoutMs
                    processor
                );
                
                steps.put(stepId, stepConfig);
            }
            
            return new PipelineConfig(pipelineName, steps);
        })
        .doOnSubscribe(s -> LOG.info("Test utility creating simple pipeline: {}", pipelineName));
    }
    
    @Override
    public Mono<PipelineConfig> createPipeline(PipelineCreateRequest request) {
        return Mono.fromCallable(() -> {
            // If pipeline config is already provided, use it directly
            if (request.pipelineConfig() != null) {
                return request.pipelineConfig();
            }
            
            // Otherwise create a simple pipeline with just the name
            return new PipelineConfig(request.name(), new LinkedHashMap<>());
            
            /* TODO: If we need to support step-by-step creation, implement this:
            Map<String, PipelineStepConfig> steps = new LinkedHashMap<>();
            
            // Convert step definitions to pipeline config
            for (var step : request.steps()) {
                // Create processor info
                ProcessorInfo processor = new ProcessorInfo(step.module(), null);
                
                // Create outputs from next steps
                Map<String, PipelineStepConfig.OutputTarget> outputs = null;
                if (step.next() != null && !step.next().isEmpty()) {
                    outputs = new HashMap<>();
                    for (String nextStepId : step.next()) {
                        outputs.put(nextStepId, new PipelineStepConfig.OutputTarget(
                            nextStepId,
                            PipelineStepConfig.TransportType.GRPC,
                            new GrpcTransportConfig(nextStepId, null), // Use service discovery
                            null
                        ));
                    }
                }
                
                // Convert config map to JsonConfigOptions if needed
                PipelineStepConfig.JsonConfigOptions customConfig = null;
                if (step.config() != null && !step.config().isEmpty()) {
                    customConfig = new PipelineStepConfig.JsonConfigOptions(step.config());
                }
                
                // Create step config
                PipelineStepConfig stepConfig = new PipelineStepConfig(
                    step.id(),
                    StepType.PIPELINE,
                    null, // description
                    null, // customConfigSchemaId
                    customConfig,
                    null, // kafkaInputs
                    outputs,
                    null, // maxRetries
                    null, // retryBackoffMs
                    null, // maxRetryBackoffMs
                    null, // retryBackoffMultiplier
                    null, // stepTimeoutMs
                    processor
                );
                
                steps.put(step.id(), stepConfig);
            }
            
            PipelineConfig config = new PipelineConfig(request.name(), steps);
            return config;
            */
        })
        .flatMap(config -> {
            // Save to cluster if provided
            if (request.cluster() != null) {
                // TODO: Implement proper cluster config update with new PipelineClusterConfig structure
                LOG.warn("Test utility pipeline creation with cluster storage not yet implemented");
            }
            return Mono.just(config);
        })
        .doOnSubscribe(s -> LOG.info("Test utility creating pipeline: {}", request.name()));
    }
    
    @Override
    public Mono<TestDataResponse> createTestData(TestDataRequest request) {
        return Mono.<TestDataResponse>fromCallable(() -> {
            String dataId = UUID.randomUUID().toString();
            Map<String, Object> metadata = new HashMap<>();
            String format = request.format() != null ? request.format() : "json";
            metadata.put("format", format);
            metadata.put("createdAt", Instant.now().toString());
            metadata.put("dataType", request.dataType());
            metadata.put("count", request.count());
            
            // Default size based on count
            int sizeBytes = request.count() * 1000;
            
            String content;
            switch (format) {
                case "text":
                    content = generateRandomText(sizeBytes);
                    break;
                case "json":
                    content = generateRandomJson(sizeBytes);
                    break;
                case "xml":
                    content = generateRandomXml(sizeBytes);
                    break;
                default:
                    content = generateRandomBytes(sizeBytes);
            }
            
            // Create test data item
            Map<String, Object> dataItem = new HashMap<>();
            dataItem.put("id", dataId);
            dataItem.put("format", format);
            dataItem.put("size", content.length());
            dataItem.put("contentType", "test/" + format);
            dataItem.put("content", content);
            
            Map<String, String> responseMetadata = new HashMap<>();
            responseMetadata.put("format", format);
            responseMetadata.put("dataType", request.dataType());
            responseMetadata.put("createdAt", Instant.now().toString());
            
            return new TestDataResponse(
                1, // count - we generated 1 item
                List.of(dataItem),
                responseMetadata
            );
        })
        .doOnSubscribe(s -> LOG.debug("Test utility creating test data: format={}, count={}", 
            request.format(), request.count()));
    }
    
    @Override
    public Mono<HealthCheckResponse> checkServiceHealth(String serviceName, String host, int port) {
        return Mono.fromCallable(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                // Check service health in Consul
                HealthClient healthClient = consul.healthClient();
                List<ServiceHealth> healthChecks = healthClient.getHealthyServiceInstances(serviceName).getResponse();
                
                // Find the specific instance
                ServiceHealth targetHealth = healthChecks.stream()
                    .filter(h -> h.getNode().getAddress().equals(host) && h.getService().getPort() == port)
                    .findFirst()
                    .orElse(null);
                
                String status = targetHealth != null ? "healthy" : "unhealthy";
                String error = targetHealth == null ? "Service not found in Consul" : null;
                
                long responseTime = System.currentTimeMillis() - startTime;
                
                return new HealthCheckResponse(
                    serviceName,
                    status,
                    Instant.now(),
                    Duration.ofMillis(responseTime),
                    "1.0.0", // Version would come from service metadata
                    Map.of("host", host, "port", port),
                    error
                );
            } catch (Exception e) {
                long responseTime = System.currentTimeMillis() - startTime;
                return new HealthCheckResponse(
                    serviceName,
                    "error",
                    Instant.now(),
                    Duration.ofMillis(responseTime),
                    null,
                    Map.of("host", host, "port", port),
                    e.getMessage()
                );
            }
        })
        .doOnSubscribe(s -> LOG.debug("Test utility checking health for {} at {}:{}", serviceName, host, port));
    }
    
    @Override
    public Mono<HealthCheckResponse> waitForHealthy(String serviceName, String host, int port, long timeoutSeconds) {
        return checkServiceHealth(serviceName, host, port)
            .repeatWhen(repeat -> repeat
                .delayElements(Duration.ofSeconds(1))
                .take(timeoutSeconds))
            .filter(response -> "healthy".equals(response.status()))
            .next()
            .switchIfEmpty(Mono.fromCallable(() -> 
                new HealthCheckResponse(
                    serviceName,
                    "timeout",
                    Instant.now(),
                    Duration.ofSeconds(timeoutSeconds),
                    null,
                    Map.of("host", host, "port", port),
                    "Service did not become healthy within " + timeoutSeconds + " seconds"
                )
            ))
            .doOnSubscribe(s -> LOG.info("Test utility waiting for {} to be healthy at {}:{} (timeout: {}s)", 
                serviceName, host, port, timeoutSeconds));
    }
    
    @Override
    public Flux<TestDocument> generateTestDocuments(TestDataGenerationRequest request) {
        return Flux.range(0, request.count())
            .<TestDocument>map(i -> {
                String docId = UUID.randomUUID().toString();
                String title = "Test Document " + i;
                String content = generateDocumentContent(request.documentType(), getSizeKb(request.sizeCategory()));
                
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("type", request.documentType());
                metadata.put("index", i);
                metadata.put("generated", true);
                
                if (request.parameters() != null) {
                    metadata.putAll(request.parameters());
                }
                
                // Convert metadata to String map
                Map<String, String> stringMetadata = new HashMap<>();
                for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                    stringMetadata.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
                
                return new TestDocument(
                    docId,
                    title,
                    content,
                    "application/" + request.documentType(), // contentType
                    content.length(), // sizeBytes
                    Instant.now(),
                    stringMetadata
                );
            })
            .delayElements(Duration.ofMillis(10)) // Prevent overwhelming
            .doOnSubscribe(s -> LOG.info("Test utility generating {} test documents of type {}", 
                request.count(), request.documentType()));
    }
    
    @Override
    public Mono<EnvironmentStatus> verifyEnvironment() {
        return Mono.fromCallable(() -> {
            List<EnvironmentStatus.ServiceStatus> services = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            
            // Check Consul
            try {
                consul.agentClient().ping();
                services.add(new EnvironmentStatus.ServiceStatus(
                    "consul",
                    "infrastructure",
                    "running",
                    "1.16.0",
                    "consul-agent:8500",
                    true
                ));
            } catch (Exception e) {
                services.add(new EnvironmentStatus.ServiceStatus(
                    "consul",
                    "infrastructure",
                    "stopped",
                    null,
                    "consul-agent:8500",
                    false
                ));
                warnings.add("Consul is not healthy: " + e.getMessage());
            }
            
            // Check registered modules
            try {
                var moduleCount = consul.agentClient().getServices().values().stream()
                    .filter(s -> s.getTags().contains("yappy-module"))
                    .count();
                
                if (moduleCount == 0) {
                    warnings.add("No YAPPY modules registered in Consul");
                }
                
                services.add(new EnvironmentStatus.ServiceStatus(
                    "yappy-modules",
                    "application",
                    moduleCount > 0 ? "running" : "stopped",
                    null,
                    "various",
                    moduleCount > 0
                ));
            } catch (Exception e) {
                warnings.add("Could not check module status: " + e.getMessage());
            }
            
            // Overall status
            boolean allHealthy = services.stream()
                .allMatch(s -> "healthy".equals(s.status()) || "ready".equals(s.status()));
            String overallStatus = allHealthy ? "ready" : (warnings.isEmpty() ? "degraded" : "warning");
            
            return new EnvironmentStatus(
                overallStatus,
                Instant.now(),
                services,
                warnings,
                allHealthy
            );
        })
        .doOnSubscribe(s -> LOG.info("Test utility verifying environment"));
    }
    
    @Override
    public Mono<Void> seedKvStore(String key, Object value) {
        // TODO: Add comprehensive audit logging for this dangerous operation
        // TODO: Add security checks before allowing KV access
        LOG.warn("⚠️ DANGEROUS: Direct Consul KV access used - seedKvStore for key: {}", key);
        LOG.warn("User: {}, Time: {}, Operation: seedKvStore, Key: {}", 
            "test-user", Instant.now(), key);
        
        return Mono.fromRunnable(() -> {
            try {
                String jsonValue = objectMapper.writeValueAsString(value);
                kvService.putValue(key, jsonValue).block();
                LOG.info("Successfully seeded KV store with key: {}", key);
            } catch (Exception e) {
                throw new RuntimeException("Failed to seed KV store: " + e.getMessage(), e);
            }
        })
        .then()
        .doOnError(e -> LOG.error("Failed to seed KV store for key {}: {}", key, e.getMessage()));
    }
    
    @Override
    public Mono<Void> cleanKvStore(String prefix) {
        // TODO: Add comprehensive audit logging for this dangerous operation
        // TODO: Add security checks before allowing KV access
        LOG.warn("⚠️ DANGEROUS: Direct Consul KV access used - cleanKvStore for prefix: {}", prefix);
        LOG.warn("User: {}, Time: {}, Operation: cleanKvStore, Prefix: {}", 
            "test-user", Instant.now(), prefix);
        
        return Mono.fromRunnable(() -> {
            try {
                // Get all keys with prefix
                List<String> keys = kvService.getKeysWithPrefix(prefix).block();
                LOG.info("Found {} keys to delete with prefix: {}", keys.size(), prefix);
                
                // Delete each key
                for (String key : keys) {
                    kvService.deleteKey(key).block();
                }
                
                LOG.info("Successfully cleaned {} keys from KV store with prefix: {}", keys.size(), prefix);
            } catch (Exception e) {
                throw new RuntimeException("Failed to clean KV store: " + e.getMessage(), e);
            }
        })
        .then()
        .doOnError(e -> LOG.error("Failed to clean KV store for prefix {}: {}", prefix, e.getMessage()));
    }
    
    @Override
    public Mono<Void> seedSchemas() {
        return Mono.fromRunnable(() -> {
            try {
                // Register common test schemas
                Map<String, String> schemas = Map.of(
                    "test-document", "{ \"type\": \"object\", \"properties\": { \"title\": { \"type\": \"string\" }, \"content\": { \"type\": \"string\" } } }",
                    "test-metadata", "{ \"type\": \"object\", \"properties\": { \"tags\": { \"type\": \"array\" } } }",
                    "pipeline-config", "{ \"type\": \"object\", \"properties\": { \"name\": { \"type\": \"string\" }, \"steps\": { \"type\": \"array\" } } }"
                );
                
                for (Map.Entry<String, String> entry : schemas.entrySet()) {
                    schemaRegistry.saveSchema(entry.getKey(), entry.getValue()).block();
                    LOG.debug("Registered test schema: {}", entry.getKey());
                }
                
                LOG.info("Successfully seeded {} test schemas", schemas.size());
            } catch (Exception e) {
                throw new RuntimeException("Failed to seed schemas: " + e.getMessage(), e);
            }
        })
        .then()
        .doOnSubscribe(s -> LOG.info("Test utility seeding schemas"));
    }
    
    @Override
    public Mono<Void> registerSchema(String schemaId, String schemaContent) {
        return Mono.fromRunnable(() -> {
            try {
                schemaRegistry.saveSchema(schemaId, schemaContent).block();
                LOG.info("Successfully registered schema: {}", schemaId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to register schema: " + e.getMessage(), e);
            }
        })
        .then()
        .doOnSubscribe(s -> LOG.debug("Test utility registering schema: {}", schemaId));
    }
    
    @Override
    public Mono<ValidationResult> validateAgainstSchema(String schemaId, String jsonContent) {
        return Mono.fromCallable(() -> {
            try {
                // Get schema from registry
                String schema = schemaRegistry.getSchemaContent(schemaId).block();
                if (schema == null) {
                    return new ValidationResult(
                        false,
                        List.of(new ValidationResult.ValidationMessage(
                            "error",
                            null,
                            "Schema not found: " + schemaId,
                            "schema-exists"
                        )),
                        Map.of("content", jsonContent),
                        "schema:" + schemaId
                    );
                }
                
                // Parse JSON to validate structure
                objectMapper.readTree(jsonContent);
                
                // For now, return success if JSON is valid
                // Real implementation would use JSON Schema validator
                return new ValidationResult(
                    true,
                    List.of(), // Empty list of ValidationMessage objects
                    Map.of(
                        "content", jsonContent,
                        "schema", schema
                    ),
                    "schema:" + schemaId
                );
            } catch (Exception e) {
                return new ValidationResult(
                    false,
                    List.of(new ValidationResult.ValidationMessage(
                        "error",
                        null,
                        "Validation error: " + e.getMessage(),
                        "json-parse"
                    )),
                    Map.of("content", jsonContent),
                    "schema:" + schemaId
                );
            }
        })
        .doOnSubscribe(s -> LOG.debug("Test utility validating content against schema: {}", schemaId));
    }
    
    // Helper methods
    private String generateRandomText(int sizeBytes) {
        String[] words = {"lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing", "elit"};
        StringBuilder sb = new StringBuilder();
        while (sb.length() < sizeBytes) {
            sb.append(words[ThreadLocalRandom.current().nextInt(words.length)]).append(" ");
        }
        return sb.toString().trim();
    }
    
    private String generateRandomJson(int sizeBytes) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", UUID.randomUUID().toString());
        data.put("timestamp", Instant.now().toString());
        data.put("data", generateRandomText(Math.max(sizeBytes - 100, 10)));
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
    
    private String generateRandomXml(int sizeBytes) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<document>\n<content>" + 
               generateRandomText(Math.max(sizeBytes - 50, 10)) + 
               "</content>\n</document>";
    }
    
    private String generateRandomBytes(int sizeBytes) {
        byte[] bytes = new byte[sizeBytes];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
    
    private int getSizeKb(String sizeCategory) {
        return switch (sizeCategory) {
            case "small" -> 1;
            case "large" -> 100;
            default -> 10; // medium
        };
    }
    
    private String generateDocumentContent(String type, int sizeKb) {
        int sizeBytes = sizeKb * 1024;
        switch (type) {
            case "pdf":
                return "PDF-MOCK-CONTENT-" + generateRandomText(sizeBytes);
            case "html":
                return "<html><body>" + generateRandomText(sizeBytes) + "</body></html>";
            case "json":
                return generateRandomJson(sizeBytes);
            case "xml":
                return generateRandomXml(sizeBytes);
            default:
                return generateRandomText(sizeBytes);
        }
    }
}