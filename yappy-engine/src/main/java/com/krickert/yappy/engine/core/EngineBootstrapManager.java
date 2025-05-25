package com.krickert.yappy.engine.core;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

@Singleton
@Requires(notEnv = "test") // Don't run this during tests to avoid interference
public class EngineBootstrapManager implements ApplicationEventListener<ApplicationConfigurationEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(EngineBootstrapManager.class);

    private final String bootstrapFilePath;
    private final Path resolvedBootstrapPath;

    // Property keys from BootstrapConfigServiceImpl - should ideally be shared constants
    private static final String YAPPY_BOOTSTRAP_CONSUL_HOST = "yappy.bootstrap.consul.host";
    private static final String YAPPY_BOOTSTRAP_CONSUL_PORT = "yappy.bootstrap.consul.port";
    private static final String YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN = "yappy.bootstrap.consul.acl_token"; // Though not directly used for enabling client

    public EngineBootstrapManager(@Value("${yappy.engine.bootstrap-file.path:~/.yappy/engine-bootstrap.properties}") String bootstrapFilePath) {
        this.bootstrapFilePath = bootstrapFilePath;
        this.resolvedBootstrapPath = Paths.get(this.bootstrapFilePath.replace("~", System.getProperty("user.home")));
        LOG.info("EngineBootstrapManager initialized. Bootstrap file path configured to: {}, resolved to: {}", this.bootstrapFilePath, this.resolvedBootstrapPath);
    }

    @Override
    public void onApplicationEvent(ApplicationConfigurationEvent event) {
        LOG.info("ApplicationConfigurationEvent received. Checking for bootstrap configuration at: {}", resolvedBootstrapPath);

        if (Files.exists(resolvedBootstrapPath)) {
            Properties props = new Properties();
            try (InputStream input = new FileInputStream(resolvedBootstrapPath.toFile())) {
                props.load(input);
                String host = props.getProperty(YAPPY_BOOTSTRAP_CONSUL_HOST);
                String portStr = props.getProperty(YAPPY_BOOTSTRAP_CONSUL_PORT);

                if (host != null && !host.isEmpty() && portStr != null && !portStr.isEmpty()) {
                    LOG.info("Bootstrap file found with Consul configuration (Host: {}, Port: {}). Attempting to enable Consul features.", host, portStr);
                    
                    System.setProperty("consul.client.host", host); // More specific than defaultZone for host
                    System.setProperty("consul.client.port", portStr); // More specific for port
                    // System.setProperty("consul.client.defaultZone", host + ":" + portStr); // Default way if specific host/port props aren't used

                    System.setProperty("consul.client.registration.enabled", "true");
                    System.setProperty("consul.client.config.enabled", "true");
                    System.setProperty("micronaut.config-client.enabled", "true"); // Enable Micronaut's config client for Consul
                    System.setProperty("yappy.consul.configured", "true");

                    // Optional: ACL token if needed for client operations at startup (usually config client)
                    String aclToken = props.getProperty(YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN);
                    if (aclToken != null && !aclToken.isEmpty()) {
                        System.setProperty("consul.client.acl-token", aclToken);
                         LOG.info("Consul ACL token found and set for client configuration.");
                    }

                    LOG.info("Consul features and YAPPY configuration flag programmatically enabled based on bootstrap file.");
                    LOG.info("System properties set: consul.client.host={}, consul.client.port={}, consul.client.registration.enabled=true, consul.client.config.enabled=true, yappy.consul.configured=true, micronaut.config-client.enabled=true", host, portStr);

                } else {
                    LOG.info("Bootstrap file found at {}, but Consul host/port not fully configured. Engine starting in Setup Mode. UI available at /setup.", resolvedBootstrapPath);
                    // yappy.consul.configured remains false by default (as per application.yml)
                }
            } catch (IOException e) {
                LOG.warn("Could not read bootstrap file at {}: {}. Engine likely starting in Setup Mode.", resolvedBootstrapPath, e.getMessage(), e);
                // yappy.consul.configured remains false
            }
        } else {
            LOG.info("Bootstrap file not found at {}. Engine starting in Setup Mode. UI available at /setup.", resolvedBootstrapPath);
            // yappy.consul.configured remains false
        }
    }
}
