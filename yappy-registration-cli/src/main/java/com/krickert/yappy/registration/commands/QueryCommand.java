package com.krickert.yappy.registration.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Empty;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ServiceRegistrationData;
import com.krickert.yappy.registration.YappyRegistrationCli;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Command to query module information.
 */
@Slf4j
@Command(
    name = "query",
    description = "Query module registration information",
    mixinStandardHelpOptions = true
)
public class QueryCommand implements Callable<Integer> {
    
    @ParentCommand
    private YappyRegistrationCli parent;
    
    @Parameters(
        index = "0",
        description = "Module gRPC endpoint (e.g., localhost:50051)"
    )
    private String moduleEndpoint;
    
    @Option(
        names = {"--timeout"},
        description = "Timeout in seconds (default: ${DEFAULT-VALUE})",
        defaultValue = "5"
    )
    private int timeout;
    
    @Option(
        names = {"--validate-schema"},
        description = "Validate the module's JSON schema if present",
        defaultValue = "false"
    )
    private boolean validateSchema;
    
    @Inject
    private ObjectMapper objectMapper;
    
    @Override
    public Integer call() {
        // Parse endpoint
        String[] parts = moduleEndpoint.split(":");
        if (parts.length != 2) {
            log.error("Invalid module endpoint format. Expected host:port");
            return 1;
        }
        
        String host = parts[0];
        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            log.error("Invalid port number: {}", parts[1]);
            return 1;
        }
        
        ManagedChannel channel = null;
        try {
            log.info("Querying module at {}:{}", host, port);
            
            channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build();
            
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                PipeStepProcessorGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(timeout, TimeUnit.SECONDS);
            
            log.info("Calling GetServiceRegistration...");
            ServiceRegistrationData registrationData = stub.getServiceRegistration(Empty.getDefaultInstance());
            
            log.info("✓ Successfully retrieved module information!");
            log.info("");
            log.info("Module Name: {}", registrationData.getModuleName());
            
            if (registrationData.hasJsonConfigSchema()) {
                log.info("Has Custom Config Schema: Yes");
                
                if (parent.verbose || validateSchema) {
                    log.info("");
                    log.info("JSON Schema:");
                    
                    // Pretty print the schema
                    try {
                        var schemaNode = objectMapper.readTree(registrationData.getJsonConfigSchema());
                        String prettySchema = objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(schemaNode);
                        log.info("{}", prettySchema);
                    } catch (Exception e) {
                        // Fall back to raw display
                        log.info("{}", registrationData.getJsonConfigSchema());
                    }
                    
                    if (validateSchema) {
                        log.info("");
                        log.info("Validating schema...");
                        
                        try {
                            // Use the same validation as in ValidateCommand
                            var schemaNode = objectMapper.readTree(registrationData.getJsonConfigSchema());
                            log.info("✓ Schema is valid JSON");
                            
                            // Could add more comprehensive schema validation here
                            
                        } catch (Exception e) {
                            log.error("✗ Schema validation failed: {}", e.getMessage());
                            return 1;
                        }
                    }
                }
            } else {
                log.info("Has Custom Config Schema: No");
            }
            
            return 0;
            
        } catch (Exception e) {
            log.error("Failed to query module: {}", e.getMessage());
            if (parent.verbose) {
                log.error("Stack trace:", e);
            }
            return 1;
        } finally {
            if (channel != null) {
                channel.shutdown();
                try {
                    channel.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    channel.shutdownNow();
                }
            }
        }
    }
}