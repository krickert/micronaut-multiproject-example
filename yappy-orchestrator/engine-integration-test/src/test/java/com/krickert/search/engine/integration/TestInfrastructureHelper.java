package com.krickert.search.engine.integration;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helper class to ensure test infrastructure is properly initialized.
 * Call TestInfrastructureHelper.ensureStarted(context) at the beginning of your test.
 */
public class TestInfrastructureHelper {
    
    private static final Logger LOG = LoggerFactory.getLogger(TestInfrastructureHelper.class);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    
    /**
     * Ensures all test containers are started by requesting their properties.
     * This method is idempotent - it only initializes once.
     * 
     * @param context The application context from your test
     */
    public static void ensureStarted(@NonNull ApplicationContext context) {
        if (INITIALIZED.compareAndSet(false, true)) {
            LOG.info("🚀 Initializing test infrastructure...");
            
            // Request properties to force container startup
            try {
                // Consul
                String consulHost = context.getProperty("consul.client.host", String.class).orElse("NOT_SET");
                String consulPort = context.getProperty("consul.client.port", String.class).orElse("0");
                if (!"NOT_SET".equals(consulHost)) {
                    LOG.info("✅ Consul: {}:{}", consulHost, consulPort);
                }
                
                // Kafka
                String kafka = context.getProperty("kafka.bootstrap.servers", String.class).orElse("NOT_SET");
                if (!"NOT_SET".equals(kafka)) {
                    LOG.info("✅ Kafka: {}", kafka);
                }
                
                // Apicurio
                String apicurio = context.getProperty("kafka.producers.default.apicurio.registry.url", String.class).orElse("NOT_SET");
                if (!"NOT_SET".equals(apicurio)) {
                    LOG.info("✅ Apicurio: {}", apicurio);
                }
                
                // Engine (may fail, which is ok)
                String engineHost = context.getProperty("engine.grpc.host", String.class).orElse("NOT_SET");
                String enginePort = context.getProperty("engine.grpc.port", String.class).orElse("0");
                if (!"NOT_SET".equals(engineHost)) {
                    LOG.info("✅ Engine: {}:{}", engineHost, enginePort);
                } else {
                    LOG.debug("Engine container not available (this may be expected)");
                }
                
                // OpenSearch (optional)
                String opensearch = context.getProperty("opensearch.url", String.class).orElse("NOT_SET");
                if (!"NOT_SET".equals(opensearch)) {
                    LOG.info("✅ OpenSearch: {}", opensearch);
                }
                
                // Give containers time to stabilize
                LOG.info("⏳ Waiting for containers to stabilize...");
                Thread.sleep(5000);
                
                LOG.info("✅ Test infrastructure ready!");
                
            } catch (Exception e) {
                LOG.warn("Error during test infrastructure initialization: {}", e.getMessage());
                // Don't fail - some containers might be optional
            }
        } else {
            LOG.debug("Test infrastructure already initialized");
        }
    }
    
    /**
     * Reset the initialization flag (useful for testing the helper itself)
     */
    static void reset() {
        INITIALIZED.set(false);
    }
}