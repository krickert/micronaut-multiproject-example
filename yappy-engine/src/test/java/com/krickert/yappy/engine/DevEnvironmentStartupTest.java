package com.krickert.yappy.engine;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify the engine can start with DEV configuration
 * without test resources.
 */
public class DevEnvironmentStartupTest {

    @Test
    void testEngineStartsWithDevApicurioConfig() {
        // Create application context with dev-apicurio environment
        // and explicitly disable test resources
        EmbeddedServer server = ApplicationContext.run(
                EmbeddedServer.class,
                Map.of(
                        "micronaut.environments", "dev-apicurio",
                        "micronaut.test.resources.enabled", "false",
                        "micronaut.server.port", "-1" // Random port for test
                ),
                "dev-apicurio"
        );
        
        ApplicationContext context = server.getApplicationContext();

        try {
            // Verify context started
            assertTrue(context.isRunning(), "Application context should be running");

            // Verify test resources are disabled
            Environment env = context.getEnvironment();
            assertFalse(
                    env.getProperty("micronaut.test.resources.enabled", Boolean.class).orElse(true),
                    "Test resources should be disabled"
            );

            // Verify we're using dev-apicurio environment
            assertTrue(
                    env.getActiveNames().contains("dev-apicurio"),
                    "dev-apicurio environment should be active"
            );

            // Verify key configurations are loaded
            assertEquals(
                    "localhost",
                    env.getProperty("consul.client.host", String.class).orElse(""),
                    "Consul host should be localhost"
            );
            
            assertEquals(
                    "apicurio",
                    env.getProperty("kafka.schema.registry.type", String.class).orElse(""),
                    "Schema registry type should be apicurio"
            );

            assertEquals(
                    "http://localhost:8080/apis/registry/v3",
                    env.getProperty("apicurio.registry.url", String.class).orElse(""),
                    "Apicurio URL should be configured"
            );

            // Log success
            System.out.println("✓ Engine started successfully with DEV configuration!");
            System.out.println("✓ Test resources are disabled");
            System.out.println("✓ Using Apicurio schema registry");
            
        } finally {
            server.close();
        }
    }
}