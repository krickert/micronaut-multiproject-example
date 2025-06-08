package com.krickert.yappy.registration;

import com.krickert.yappy.registration.commands.*;
import io.micronaut.configuration.picocli.PicocliRunner;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Main CLI for YAPPY module registration and validation operations.
 */
@Slf4j
@Command(
    name = "yappy-register",
    description = "YAPPY module registration and validation tool",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    subcommands = {
        RegisterCommand.class,
        ValidateCommand.class,
        HealthCheckCommand.class,
        QueryCommand.class,
        CommandLine.HelpCommand.class
    }
)
public class YappyRegistrationCli implements Runnable {
    
    @Option(
        names = {"-v", "--verbose"},
        description = "Enable verbose logging",
        scope = CommandLine.ScopeType.INHERIT
    )
    public boolean verbose;
    
    @Option(
        names = {"-c", "--cluster"},
        description = "Cluster name (required for most operations)",
        scope = CommandLine.ScopeType.INHERIT
    )
    public String clusterName;
    
    @Option(
        names = {"--consul-host"},
        description = "Consul host (default: ${DEFAULT-VALUE})",
        defaultValue = "${CONSUL_HOST:localhost}",
        scope = CommandLine.ScopeType.INHERIT
    )
    public String consulHost;
    
    @Option(
        names = {"--consul-port"},
        description = "Consul port (default: ${DEFAULT-VALUE})",
        defaultValue = "${CONSUL_PORT:8500}",
        scope = CommandLine.ScopeType.INHERIT
    )
    public int consulPort;
    
    public static void main(String[] args) {
        PicocliRunner.run(YappyRegistrationCli.class, args);
    }
    
    @Override
    public void run() {
        // Show help when no subcommand is specified
        CommandLine.usage(this, System.out);
    }
}