package com.krickert.search.engine.routing;

import com.krickert.search.grpc.ModuleInfo;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Interface for connecting to and communicating with processing modules.
 */
public interface ModuleConnector {
    
    /**
     * Sends a document to a module for processing.
     * 
     * @param moduleInfo The module connection information
     * @param pipeStream The stream containing the document to process
     * @param stepName The name of the step being executed
     * @param stepConfig The configuration for this step
     * @return The processed document response
     */
    Mono<ProcessResponse> processDocument(
        ModuleInfo moduleInfo,
        PipeStream pipeStream,
        String stepName,
        Map<String, Object> stepConfig
    );
    
    /**
     * Checks if a module is available and ready to process requests.
     * 
     * @param moduleInfo The module connection information
     * @return True if the module is available
     */
    Mono<Boolean> isModuleAvailable(ModuleInfo moduleInfo);
    
    /**
     * Gets the service registration information from a module.
     * 
     * @param moduleInfo The module connection information
     * @return The service registration data including capabilities
     */
    Mono<ServiceRegistrationInfo> getServiceRegistration(ModuleInfo moduleInfo);
    
    /**
     * Closes the connection to a specific module.
     * 
     * @param moduleId The ID of the module to disconnect from
     */
    void disconnectModule(String moduleId);
    
    /**
     * Closes all module connections.
     */
    void disconnectAll();
    
    /**
     * Service registration information from a module.
     */
    record ServiceRegistrationInfo(
        String moduleName,
        String jsonConfigSchema,
        Map<String, String> capabilities
    ) {}
}