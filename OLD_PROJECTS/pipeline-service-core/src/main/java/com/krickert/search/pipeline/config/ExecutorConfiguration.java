package com.krickert.search.pipeline.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Factory;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Configuration for the executor service used by the pipeline services.
 * This allows configuring the executor service through properties with reasonable defaults.
 */
@Factory
public class ExecutorConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ExecutorConfiguration.class);

    /**
     * Creates and configures the executor service for dynamic Kafka consumers.
     * 
     * @param config The executor configuration properties
     * @return The configured executor service
     */
    @Singleton
    @Named("dynamic-kafka-consumer-executor")
    public ExecutorService dynamicKafkaExecutor(DynamicKafkaExecutorConfig config) {
        log.info("Creating dynamic Kafka consumer executor with type: {}, threads: {}, namePrefix: {}",
                config.getType(), config.getThreads(), config.getNamePrefix());
        
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r);
            t.setName(config.getNamePrefix() + "-" + t.getId());
            return t;
        };
        
        switch (config.getType().toLowerCase()) {
            case "fixed":
                return Executors.newFixedThreadPool(config.getThreads(), threadFactory);
            case "cached":
                return Executors.newCachedThreadPool(threadFactory);
            case "single":
                return Executors.newSingleThreadExecutor(threadFactory);
            default:
                log.warn("Unknown executor type: {}. Using fixed thread pool with {} threads.", 
                        config.getType(), config.getThreads());
                return Executors.newFixedThreadPool(config.getThreads(), threadFactory);
        }
    }
    
    /**
     * Configuration properties for the dynamic Kafka consumer executor.
     */
    @Getter
    @Setter
    @ConfigurationProperties("micronaut.executors.dynamic-kafka-consumer-executor")
    @Serdeable
    @Introspected
    public static class DynamicKafkaExecutorConfig {
        /**
         * The type of executor to use. Valid values are "fixed", "cached", or "single".
         * Default is "fixed".
         */
        private String type = "fixed";
        
        /**
         * The number of threads to use for the executor. Only used for "fixed" type.
         * Default is 10.
         */
        private int threads = 10;
        
        /**
         * The prefix to use for thread names.
         * Default is "dynamic-kafka-consumer".
         */
        private String namePrefix = "dynamic-kafka-consumer";
    }
}