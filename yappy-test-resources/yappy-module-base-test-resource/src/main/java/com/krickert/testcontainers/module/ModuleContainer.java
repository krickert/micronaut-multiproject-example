package com.krickert.testcontainers.module;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Container implementation for YAPPY modules.
 * Provides common configuration and helper methods for module containers.
 */
public class ModuleContainer extends GenericContainer<ModuleContainer> {
    
    private final String moduleName;
    
    /**
     * Creates a new module container
     * @param dockerImageName The Docker image to use
     * @param moduleName The name of the module (e.g., "chunker", "tika-parser")
     */
    public ModuleContainer(DockerImageName dockerImageName, String moduleName) {
        super(dockerImageName);
        this.moduleName = moduleName;
    }
    
    /**
     * Gets the module name
     */
    public String getModuleName() {
        return moduleName;
    }
    
    /**
     * Gets the gRPC endpoint URL for external access
     */
    public String getGrpcEndpoint() {
        return getHost() + ":" + getMappedPort(50051);
    }
    
    /**
     * Gets the HTTP endpoint URL for external access
     */
    public String getHttpEndpoint() {
        return "http://" + getHost() + ":" + getMappedPort(8080);
    }
    
    /**
     * Gets the internal gRPC endpoint (for container-to-container communication)
     */
    public String getInternalGrpcEndpoint() {
        return moduleName + ":50051";
    }
    
    /**
     * Gets the internal HTTP endpoint (for container-to-container communication)
     */
    public String getInternalHttpEndpoint() {
        return "http://" + moduleName + ":8080";
    }
}