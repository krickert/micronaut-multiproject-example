package com.krickert.yappy.modules.opensearchsink.config;

import org.opensearch.client.RestClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of OpenSearchCredentialsProvider that doesn't use any authentication.
 * This is suitable for development environments or when security is disabled.
 */
public class NoAuthCredentialsProvider implements OpenSearchCredentialsProvider {

    private static final Logger LOG = LoggerFactory.getLogger(NoAuthCredentialsProvider.class);
    
    private final boolean useSsl;
    
    /**
     * Create a new NoAuthCredentialsProvider with the given SSL setting.
     *
     * @param useSsl whether to use SSL
     */
    public NoAuthCredentialsProvider(boolean useSsl) {
        this.useSsl = useSsl;
    }
    
    /**
     * Create a new NoAuthCredentialsProvider with SSL disabled.
     */
    public NoAuthCredentialsProvider() {
        this(false);
    }

    @Override
    public RestClientBuilder configureCredentials(RestClientBuilder builder) {
        LOG.info("Configuring OpenSearch client with no authentication");
        
        // No authentication configuration needed
        
        // Configure SSL if enabled
        if (useSsl) {
            LOG.info("SSL is enabled, but SSL configuration is not implemented yet");
            // SSL configuration will be added later if needed
        }
        
        return builder;
    }

    @Override
    public boolean isUseSsl() {
        return useSsl;
    }
}