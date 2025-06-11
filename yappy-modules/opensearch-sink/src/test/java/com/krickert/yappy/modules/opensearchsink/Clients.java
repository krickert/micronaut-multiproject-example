package com.krickert.yappy.modules.opensearchsink;

import com.krickert.search.engine.SinkServiceGrpc;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.testcontainers.opensearch.OpenSearchTestResourceProvider;
import com.krickert.yappy.modules.opensearchsink.config.OpenSearchSinkConfig;
import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import jakarta.inject.Named;
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
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

@Factory
public class Clients {
    private static final Logger LOG = LoggerFactory.getLogger(Clients.class);

    @Bean
    SinkServiceGrpc.SinkServiceBlockingStub pipeStepProcessorBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME)
            ManagedChannel channel) {
        return SinkServiceGrpc.newBlockingStub(
                channel
        );
    }

    @Bean
    SinkServiceGrpc.SinkServiceStub serviceStub(
            @GrpcChannel(GrpcServerChannel.NAME)
            ManagedChannel channel) {
        return SinkServiceGrpc.newStub(
                channel
        );
    }

    // Directly inject properties from the OpenSearchTestResourceProvider
    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_HOST)
    String openSearchHost;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PORT)
    String openSearchPort;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_URL)
    String openSearchUrl;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_USERNAME)
    String openSearchUsername;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PASSWORD)
    String openSearchPassword;

    @Property(name = OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_SECURITY_ENABLED)
    String openSearchSecurityEnabled;

    @Bean
    OpenSearchSinkConfig openSearchSinkConfig() {
        // The tests in this class assert that openSearchHost, openSearchPort,
        // and openSearchSecurityEnabled are non-null and that openSearchPort is a valid integer.
        // openSearchUsername and openSearchPassword can be null if security is not enabled.
        // Converts String to Integer
        // Converts String to Boolean
        // Other fields of OpenSearchSinkConfig (e.g., indexName, bulkSize)
        // are not set from the properties injected in this specific test class.
        // They will default to null as their types are objects (String, Integer, Boolean).
        return OpenSearchSinkConfig.builder()
                .hosts(openSearchHost)
                .port(Integer.parseInt(openSearchPort)) // Converts String to Integer
                .username(openSearchUsername)
                .password(openSearchPassword)
                .useSsl(Boolean.parseBoolean(openSearchSecurityEnabled)) // Converts String to Boolean
                // Other fields of OpenSearchSinkConfig (e.g., indexName, bulkSize)
                // are not set from the properties injected in this specific test class.
                // They will default to null as their types are objects (String, Integer, Boolean).
                .build();
    }


}
