package com.krickert.search.pipeline.kafka.serde;

import io.micronaut.context.annotation.Primary;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Default implementation of KafkaSerdeProvider that delegates to the appropriate KafkaSerdeFactory
 * based on the registry type configured in the application.
 */
@Singleton
@Primary
public class DefaultKafkaSerdeProvider implements KafkaSerdeProvider {
    private static final Logger log = LoggerFactory.getLogger(DefaultKafkaSerdeProvider.class);
    
    private final List<KafkaSerdeFactory> factories;
    private final Optional<KafkaSerdeFactory> defaultFactory;
    
    @Inject
    public DefaultKafkaSerdeProvider(List<KafkaSerdeFactory> factories) {
        this.factories = factories;
        
        // Find the default factory (Apicurio if available, otherwise Glue)
        this.defaultFactory = factories.stream()
                .filter(factory -> "apicurio".equals(factory.getRegistryType()))
                .findFirst()
                .or(() -> factories.stream()
                        .filter(factory -> "glue".equals(factory.getRegistryType()))
                        .findFirst());
        
        if (defaultFactory.isPresent()) {
            log.info("Using {} as the default KafkaSerdeFactory", defaultFactory.get().getClass().getSimpleName());
        } else if (!factories.isEmpty()) {
            log.warn("No preferred KafkaSerdeFactory found, will use the first available: {}", 
                    factories.get(0).getClass().getSimpleName());
        } else {
            log.error("No KafkaSerdeFactory implementations found. Serialization/deserialization will fail!");
        }
    }
    
    /**
     * Gets a configured key deserializer for the given pipeline.
     *
     * @param pipelineName The name of the pipeline (often the groupId).
     * @return A configured Key Deserializer instance.
     * @throws RuntimeException if configuration fails.
     */
    @Override
    public <K> Deserializer<K> getKeyDeserializer(String pipelineName) {
        KafkaSerdeFactory factory = getFactoryForPipeline(pipelineName);
        return factory.getKeyDeserializer(pipelineName);
    }
    
    /**
     * Gets a configured value deserializer for the given pipeline.
     *
     * @param pipelineName The name of the pipeline (often the groupId).
     * @return A configured Value Deserializer instance.
     * @throws RuntimeException if configuration fails.
     */
    @Override
    public <V>Deserializer<V> getValueDeserializer(String pipelineName) {
        KafkaSerdeFactory factory = getFactoryForPipeline(pipelineName);
        return factory.getValueDeserializer(pipelineName);
    }
    
    /**
     * Get the appropriate factory for the given pipeline.
     * This method can be extended to select a factory based on pipeline-specific configuration.
     * 
     * @param pipelineName The name of the pipeline.
     * @return The appropriate KafkaSerdeFactory.
     * @throws IllegalStateException if no factory is available.
     */
    private KafkaSerdeFactory getFactoryForPipeline(String pipelineName) {
        // For now, we just use the default factory
        // This could be extended to select a factory based on pipeline-specific configuration
        
        if (defaultFactory.isPresent()) {
            return defaultFactory.get();
        } else if (!factories.isEmpty()) {
            log.warn("Using first available factory for pipeline '{}': {}", 
                    pipelineName, factories.get(0).getClass().getSimpleName());
            return factories.get(0);
        } else {
            throw new IllegalStateException("No KafkaSerdeFactory implementations available");
        }
    }
}