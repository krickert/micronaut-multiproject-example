package com.krickert.search.pipeline.module;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages module configuration schemas.
 * For now, this is a simple in-memory implementation.
 * In the future, this can be integrated with a full schema registry.
 */
@Singleton
@Requires(beans = ModuleDiscoveryService.class)
public class ModuleSchemaRegistryService {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleSchemaRegistryService.class);
    private static final String MODULE_SCHEMA_PREFIX = "module-config-";
    
    private final ModuleSchemaValidator schemaValidator;
    
    // Cache of registered schemas (in-memory for now)
    private final Map<String, RegisteredSchema> schemaCache = new ConcurrentHashMap<>();
    private int schemaIdCounter = 1000; // Start schema IDs at 1000
    
    @Inject
    public ModuleSchemaRegistryService(ModuleSchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
    }
    
    /**
     * Registers a module's configuration schema in the schema registry.
     * 
     * @param moduleName The name of the module
     * @param schemaJson The JSON schema for the module's configuration
     * @return Mono indicating success/failure
     */
    public Mono<SchemaRegistrationResult> registerModuleSchema(String moduleName, String schemaJson) {
        String subjectName = MODULE_SCHEMA_PREFIX + moduleName;
        
        return Mono.fromCallable(() -> {
            // First validate the schema
            ModuleSchemaValidator.ValidationResult validation = schemaValidator.validateSchema(schemaJson);
            if (!validation.isValid()) {
                LOG.error("Invalid schema for module {}: {}", moduleName, validation.getMessage());
                return SchemaRegistrationResult.failed(moduleName, validation.getMessage());
            }
            
            // For now, store in memory
            int schemaId = schemaIdCounter++;
            
            // Extract default configuration
            String defaultConfig = schemaValidator.extractDefaults(schemaJson);
            
            // Cache the registration
            RegisteredSchema registered = new RegisteredSchema(
                    moduleName,
                    subjectName,
                    schemaId,
                    schemaJson,
                    defaultConfig
            );
            schemaCache.put(moduleName, registered);
            
            LOG.info("Successfully registered schema for module {} with ID {}", moduleName, schemaId);
            return SchemaRegistrationResult.success(moduleName, schemaId, subjectName);
        });
    }
    
    /**
     * Validates a configuration against a module's registered schema.
     * 
     * @param moduleName The module name
     * @param configuration The configuration to validate
     * @return Validation result
     */
    public ModuleSchemaValidator.ValidationResult validateModuleConfiguration(
            String moduleName, String configuration) {
        
        RegisteredSchema registered = schemaCache.get(moduleName);
        if (registered == null) {
            return ModuleSchemaValidator.ValidationResult.invalid(
                    "No schema found for module: " + moduleName);
        }
        
        return schemaValidator.validateConfiguration(configuration, registered.schemaJson);
    }
    
    /**
     * Gets the default configuration for a module based on its schema.
     * 
     * @param moduleName The module name
     * @return Default configuration JSON string
     */
    public String getDefaultConfiguration(String moduleName) {
        RegisteredSchema registered = schemaCache.get(moduleName);
        if (registered != null) {
            return registered.defaultConfig;
        }
        
        return "{}";
    }
    
    /**
     * Gets the schema for a module.
     * 
     * @param moduleName The module name
     * @return The schema JSON string, or null if not found
     */
    public String getModuleSchema(String moduleName) {
        RegisteredSchema registered = schemaCache.get(moduleName);
        if (registered != null) {
            return registered.schemaJson;
        }
        
        return null;
    }
    
    /**
     * Checks if a module has a registered schema.
     */
    public boolean hasSchema(String moduleName) {
        return schemaCache.containsKey(moduleName);
    }
    
    // Inner classes
    
    private static class RegisteredSchema {
        final String moduleName;
        final String subjectName;
        final int schemaId;
        final String schemaJson;
        final String defaultConfig;
        
        RegisteredSchema(String moduleName, String subjectName, int schemaId, 
                        String schemaJson, String defaultConfig) {
            this.moduleName = moduleName;
            this.subjectName = subjectName;
            this.schemaId = schemaId;
            this.schemaJson = schemaJson;
            this.defaultConfig = defaultConfig;
        }
    }
    
    public static class SchemaRegistrationResult {
        private final boolean success;
        private final String moduleName;
        private final String message;
        private final Integer schemaId;
        private final String subjectName;
        
        private SchemaRegistrationResult(boolean success, String moduleName, 
                                       String message, Integer schemaId, String subjectName) {
            this.success = success;
            this.moduleName = moduleName;
            this.message = message;
            this.schemaId = schemaId;
            this.subjectName = subjectName;
        }
        
        public static SchemaRegistrationResult success(String moduleName, int schemaId, String subjectName) {
            return new SchemaRegistrationResult(true, moduleName, 
                    "Schema registered successfully", schemaId, subjectName);
        }
        
        public static SchemaRegistrationResult failed(String moduleName, String message) {
            return new SchemaRegistrationResult(false, moduleName, message, null, null);
        }
        
        // Getters
        public boolean isSuccess() { return success; }
        public String getModuleName() { return moduleName; }
        public String getMessage() { return message; }
        public Integer getSchemaId() { return schemaId; }
        public String getSubjectName() { return subjectName; }
    }
}