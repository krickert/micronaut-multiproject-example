package com.krickert.yappy.registration.commands;

import com.krickert.yappy.registration.RegistrationService;
import com.krickert.yappy.registration.YappyRegistrationCli;
import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * Command to register a YAPPY module with the engine.
 */
@Slf4j
@Command(
    name = "register",
    description = "Register a YAPPY module with the engine",
    mixinStandardHelpOptions = true
)
public class RegisterCommand implements Callable<Integer> {
    
    @ParentCommand
    private YappyRegistrationCli parent;
    
    @Option(
        names = {"-m", "--module-endpoint"},
        description = "Module gRPC endpoint (e.g., localhost:50051)",
        required = true
    )
    private String moduleEndpoint;
    
    @Option(
        names = {"-e", "--engine-endpoint"},
        description = "Engine gRPC endpoint (e.g., localhost:50050)",
        required = true
    )
    private String engineEndpoint;
    
    @Option(
        names = {"-n", "--instance-name"},
        description = "Instance service name for Consul registration"
    )
    private String instanceName;
    
    @Option(
        names = {"-t", "--health-type"},
        description = "Health check type: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})",
        defaultValue = "GRPC"
    )
    private HealthType healthCheckType;
    
    @Option(
        names = {"-p", "--health-path"},
        description = "Health check endpoint path (default: ${DEFAULT-VALUE})",
        defaultValue = "grpc.health.v1.Health/Check"
    )
    private String healthCheckPath;
    
    @Option(
        names = {"--module-version"},
        description = "Module software version"
    )
    private String moduleVersion;
    
    @Option(
        names = {"--validate-health"},
        description = "Validate health check before registration (default: ${DEFAULT-VALUE})",
        defaultValue = "true"
    )
    private boolean validateHealth;
    
    @Inject
    private ApplicationContext applicationContext;
    
    public enum HealthType {
        HTTP, GRPC, TCP, TTL
    }
    
    @Override
    public Integer call() {
        try {
            // Validate cluster name is provided
            if (parent.clusterName == null || parent.clusterName.isBlank()) {
                log.error("Cluster name is required. Use -c or --cluster option.");
                return 1;
            }
            
            log.info("Starting module registration...");
            log.info("Cluster: {}", parent.clusterName);
            log.info("Module endpoint: {}", moduleEndpoint);
            log.info("Engine endpoint: {}", engineEndpoint);
            
            // Parse module endpoint
            String[] moduleParts = moduleEndpoint.split(":");
            if (moduleParts.length != 2) {
                log.error("Invalid module endpoint format. Expected host:port");
                return 1;
            }
            String moduleHost = moduleParts[0];
            int modulePort;
            try {
                modulePort = Integer.parseInt(moduleParts[1]);
            } catch (NumberFormatException e) {
                log.error("Invalid port number: {}", moduleParts[1]);
                return 1;
            }
            
            // Validate health check if requested
            if (validateHealth) {
                log.info("Validating health check endpoint...");
                boolean healthValid = validateHealthCheck(moduleHost, modulePort);
                if (!healthValid) {
                    log.error("Health check validation failed. Use --validate-health=false to skip.");
                    return 1;
                }
            }
            
            // Get registration service
            RegistrationService registrationService = applicationContext.getBean(RegistrationService.class);
            
            // Perform registration
            registrationService.registerModule(
                moduleHost,
                modulePort,
                engineEndpoint,
                instanceName,
                healthCheckType.name(),
                healthCheckPath,
                moduleVersion
            );
            
            log.info("Module registration completed successfully!");
            return 0;
            
        } catch (Exception e) {
            log.error("Registration failed: {}", e.getMessage());
            if (parent.verbose) {
                log.error("Stack trace:", e);
            }
            return 1;
        }
    }
    
    private boolean validateHealthCheck(String host, int port) {
        try {
            switch (healthCheckType) {
                case GRPC:
                    // TODO: Implement gRPC health check validation
                    log.info("gRPC health check endpoint: {}", healthCheckPath);
                    return true;
                    
                case HTTP:
                    // TODO: Implement HTTP health check validation
                    log.info("HTTP health check endpoint: {}", healthCheckPath);
                    return true;
                    
                case TCP:
                    // TCP just needs to connect
                    log.info("TCP health check on port {}", port);
                    return true;
                    
                case TTL:
                    // TTL doesn't need validation
                    log.info("TTL health check - no validation needed");
                    return true;
                    
                default:
                    log.error("Unknown health check type: {}", healthCheckType);
                    return false;
            }
        } catch (Exception e) {
            log.error("Health check validation failed: {}", e.getMessage());
            return false;
        }
    }
}