package com.krickert.search.pipeline.kafka.serde;

import io.micronaut.context.annotation.Primary;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Default implementation of KafkaSerdeProvider that delegates to the appropriate KafkaSerdeFactory
 * based on the registry type configured in the application.
 */
@Singleton
@Primary
public class DefaultKafkaSerdeProvider implements KafkaSerdeProvider {
    private static final Logger log = LoggerFactory.getLogger(DefaultKafkaSerdeProvider.class);
    
    private final List<KafkaSerdeFactory> factories;

    @Inject
    public DefaultKafkaSerdeProvider(List<KafkaSerdeFactory> factories) {
        assert factories.getFirst() != null;

        this.factories = factories;
    }
    
    /**
     * Gets a configured key deserializer for the given pipeline.
     *
     * @return A configured Key Deserializer instance.
     * @throws RuntimeException if configuration fails.
     */
    @Override
    public <K> Deserializer<K> getKeyDeserializer() {
        return factories.getFirst().getKeyDeserializer();
    }
    
    /**
     * Gets a configured value deserializer for the given pipeline.
     *
     * @return A configured Value Deserializer instance.
     * @throws RuntimeException if configuration fails.
     */
    @Override
    public <V>Deserializer<V> getValueDeserializer() {
        return factories.getFirst().getValueDeserializer();
    }

    /**
     * Gets a configured key deserializer for the given pipeline.
     *
     * @return A configured Key Deserializer instance.
     * @throws RuntimeException if configuration fails.
     */
    @Override
    public <K> Serializer<K> getKeySerializer() {
        return factories.getFirst().getKeySerializer();
    }

    /**
     * Gets a configured value deserializer for the given pipeline.
     *
     * @return A configured Value Deserializer instance.
     * @throws RuntimeException if configuration fails.
     */
    @Override
    public <V> Serializer<V> getValueSerializer() {
        return factories.getFirst().getValueSerializer();
    }

}