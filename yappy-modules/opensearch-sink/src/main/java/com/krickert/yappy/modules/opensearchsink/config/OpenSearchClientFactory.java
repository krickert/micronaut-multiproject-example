package com.krickert.yappy.modules.opensearchsink.config;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.reactor.ssl.TlsDetails;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Factory for creating and configuring the OpenSearch client.
 */
@Factory
public class OpenSearchClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchClientFactory.class);
    // Directly inject properties from the OpenSearchTestResourceProvider
    @Property(name = "opensearch.host")
    String openSearchHost;

    @Property(name = "opensearch.port")
    String openSearchPort;

    @Property(name ="opensearch.url")
    String openSearchUrl;

    @Property(name = "opensearch.username")
    String openSearchUsername;

    @Property(name = "opensearch.password")
    String openSearchPassword;

    @Property(name = "opensearch.security.enabled")
    String openSearchSecurityEnabled;
    /**
     * Creates an OpenSearchClient for testing.
     * This uses the Java client (not the REST client) as required.
     *
     * @return the OpenSearchClient instance
     */
    @Bean
    OpenSearchClient openSearchClientTest() {
        LOG.info("[DEBUG_LOG] Creating OpenSearch client for testing");

        boolean useSsl = Boolean.parseBoolean(openSearchSecurityEnabled);
        String protocol = useSsl ? "https" : "http";
        int port = Integer.parseInt(openSearchPort);

        LOG.info("[DEBUG_LOG] OpenSearch connection details: {}://{}:{}, SSL: {}",
                protocol, openSearchHost, port, useSsl);

        try {
            // Create the HttpHost
            HttpHost host = new HttpHost(protocol, openSearchHost, port);

            // Build the ApacheHttpClient5Transport
            ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(host);

            // Configure authentication if credentials are provided
            if (openSearchUsername != null && !openSearchUsername.isEmpty()
                    && openSearchPassword != null && !openSearchPassword.isEmpty()) {
                LOG.info("[DEBUG_LOG] Configuring basic authentication for OpenSearch");

                // Create credentials provider
                final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                        new AuthScope(host),
                        new UsernamePasswordCredentials(openSearchUsername, openSearchPassword.toCharArray())
                );

                // Create SSL context if SSL is enabled
                final SSLContext sslContext = SSLContextBuilder.create()
                        .loadTrustMaterial(null, (chains, authType) -> true)
                        .build();

                // Configure the client with credentials and SSL
                builder.setHttpClientConfigCallback(httpClientBuilder -> {
                    final TlsStrategy tlsStrategy = ClientTlsStrategyBuilder.create()
                            .setSslContext(sslContext)
                            // See https://issues.apache.org/jira/browse/HTTPCLIENT-2219
                            .setTlsDetailsFactory(sslEngine ->
                                    new TlsDetails(sslEngine.getSession(), sslEngine.getApplicationProtocol())
                            )
                            .build();

                    final PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder
                            .create()
                            .setTlsStrategy(tlsStrategy)
                            .build();

                    return httpClientBuilder
                            .setDefaultCredentialsProvider(credentialsProvider)
                            .setConnectionManager(connectionManager);
                });
            }

            // Build the transport and client
            OpenSearchTransport transport = builder.build();
            OpenSearchClient client = new OpenSearchClient(transport);

            LOG.info("[DEBUG_LOG] OpenSearch client created successfully");
            return client;
        } catch (NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
            LOG.error("[DEBUG_LOG] Error creating OpenSearch client", e);
            throw new RuntimeException("Failed to create OpenSearch client", e);
        }
    }
}
