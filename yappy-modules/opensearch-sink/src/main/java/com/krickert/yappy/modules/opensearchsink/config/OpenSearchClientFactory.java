package com.krickert.yappy.modules.opensearchsink.config;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.RestHighLevelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Factory for creating and configuring the OpenSearch client.
 */
@Factory
public class OpenSearchClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchClientFactory.class);

    @Inject
    private OpenSearchSinkConfig config;

    @Inject
    private OpenSearchCredentialsProvider credentialsProvider;

    /**
     * Creates and configures a RestHighLevelClient for OpenSearch.
     *
     * @return the configured RestHighLevelClient
     */
    @Bean
    @Singleton
    public RestHighLevelClient openSearchClient() {
        String hosts = config.hosts();
        int port = config.port();
        boolean useSsl = config.useSsl() != null && config.useSsl();

        LOG.info("Creating OpenSearch client with hosts: {}, port: {}, useSsl: {}", hosts, port, useSsl);

        // Parse hosts
        String[] hostArray = hosts.split(",");
        HttpHost[] httpHosts = Arrays.stream(hostArray)
                .map(host -> {
                    String scheme = useSsl ? "https" : "http";
                    String uri = scheme + "://" + host.trim() + ":" + port;
                    try {
                        return HttpHost.create(uri);
                    } catch (Exception e) {
                        LOG.error("Failed to create HttpHost from URI {}: {}", uri, e.getMessage(), e);
                        return null;
                    }
                })
                .filter(host -> host != null)
                .toArray(HttpHost[]::new);

        // Create builder
        RestClientBuilder builder = RestClient.builder(httpHosts);

        // Configure credentials using the injected provider
        builder = credentialsProvider.configureCredentials(builder);

        return new RestHighLevelClient(builder);
    }
}
