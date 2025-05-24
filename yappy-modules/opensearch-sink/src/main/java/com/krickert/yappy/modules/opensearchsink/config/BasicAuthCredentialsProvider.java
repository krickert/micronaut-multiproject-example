package com.krickert.yappy.modules.opensearchsink.config;

import org.apache.hc.core5.ssl.SSLContexts;
import org.opensearch.client.RestClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Implementation of OpenSearchCredentialsProvider that uses basic authentication
 * with username and password.
 */
public class BasicAuthCredentialsProvider implements OpenSearchCredentialsProvider {

    private static final Logger LOG = LoggerFactory.getLogger(BasicAuthCredentialsProvider.class);

    private final String username;
    private final String password;
    private final boolean useSsl;

    /**
     * Create a new BasicAuthCredentialsProvider with the given username, password, and SSL setting.
     *
     * @param username the username for authentication
     * @param password the password for authentication
     * @param useSsl   whether to use SSL
     */
    public BasicAuthCredentialsProvider(String username, String password, boolean useSsl) {
        this.username = username;
        this.password = password;
        this.useSsl = useSsl;
    }

    @Override
    public RestClientBuilder configureCredentials(RestClientBuilder builder) {
        LOG.info("Configuring OpenSearch client with basic authentication");

        // Create the Authorization header value
        String auth = username + ":" + password;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        String authHeader = "Basic " + encodedAuth;

        // Set the default headers with Authorization
        builder.setDefaultHeaders(new org.apache.hc.core5.http.Header[] {
            new org.apache.hc.core5.http.message.BasicHeader("Authorization", authHeader)
        });

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
