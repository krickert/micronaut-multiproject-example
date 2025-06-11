package com.krickert.yappy.modules.opensearchsink.config;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating OpenSearchCredentialsProvider instances based on configuration.
 */
@Factory
public class OpenSearchCredentialsFactory {

    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchCredentialsFactory.class);

    /**
     * Creates a BasicAuthCredentialsProvider if username and password are provided.
     *
     * @param config the OpenSearch sink configuration
     * @return a BasicAuthCredentialsProvider
     */
    @Singleton
    @Requires(condition = HasCredentialsCondition.class)
    public OpenSearchCredentialsProvider basicAuthCredentialsProvider(OpenSearchSinkConfig config) {
        LOG.info("Creating BasicAuthCredentialsProvider");
        return new BasicAuthCredentialsProvider(
                config.username(),
                config.password(),
                config.useSsl() != null && config.useSsl()
        );
    }

    /**
     * Creates a NoAuthCredentialsProvider if no username or password is provided.
     *
     * @param config the OpenSearch sink configuration
     * @return a NoAuthCredentialsProvider
     */
    @Singleton
    @Requires(condition = NoCredentialsCondition.class)
    public OpenSearchCredentialsProvider noAuthCredentialsProvider(OpenSearchSinkConfig config) {
        LOG.info("Creating NoAuthCredentialsProvider");
        return new NoAuthCredentialsProvider(
                config.useSsl() != null && config.useSsl()
        );
    }

    /**
     * Condition that checks if username and password are provided.
     */
    public static class HasCredentialsCondition implements io.micronaut.context.condition.Condition {
        @Override
        public boolean matches(io.micronaut.context.condition.ConditionContext context) {
            OpenSearchSinkConfig config = context.getBean(OpenSearchSinkConfig.class);
            return config.username() != null && !config.username().isEmpty()
                    && config.password() != null && !config.password().isEmpty();
        }
    }

    /**
     * Condition that checks if username or password is not provided.
     */
    public static class NoCredentialsCondition implements io.micronaut.context.condition.Condition {
        @Override
        public boolean matches(io.micronaut.context.condition.ConditionContext context) {
            OpenSearchSinkConfig config = context.getBean(OpenSearchSinkConfig.class);
            return config.username() == null || config.username().isEmpty()
                    || config.password() == null || config.password().isEmpty();
        }
    }
}