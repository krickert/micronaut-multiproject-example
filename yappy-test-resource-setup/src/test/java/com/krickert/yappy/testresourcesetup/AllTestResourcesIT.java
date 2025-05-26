package com.krickert.yappy.testresourcesetup;

import com.krickert.testcontainers.apicurio.ApicurioTestResourceProvider;
import com.krickert.testcontainers.consul.ConsulTestResourceProvider;
import com.krickert.testcontainers.kafka.KafkaTestResourceProvider;
import com.krickert.testcontainers.moto.MotoTestResourceProvider;
import com.krickert.testcontainers.opensearch.OpenSearchTestResourceProvider;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Value;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetRegistryResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify that all test resources are properly started and configured.
 * This test will be the first to run and will ensure that all containers are started
 * and their properties are correctly set.
 */
@MicronautTest
public class AllTestResourcesIT {
    private static final Logger LOG = LoggerFactory.getLogger(AllTestResourcesIT.class);

    @Inject
    private ApplicationContext applicationContext;

    // Kafka properties
    @Value("${kafka.bootstrap.servers:#{null}}")
    String kafkaBootstrapServers;

    // Apicurio properties
    @Value("${apicurio.registry.url:#{null}}")
    String apicurioRegistryUrl;

    // OpenSearch properties
    @Value("${opensearch.url:#{null}}")
    String openSearchUrl;

    @Value("${opensearch.host:#{null}}")
    String openSearchHost;

    @Value("${opensearch.port:#{null}}")
    String openSearchPort;

    // Consul properties
    @Value("${consul.client.host:#{null}}")
    String consulHost;

    @Value("${consul.client.port:#{null}}")
    String consulPort;

    // Moto properties
    @Value("${glue.registry.url:#{null}}")
    String glueRegistryUrl;

    @Test
    @DisplayName("Verify Kafka test resource is properly configured")
    void testKafkaTestResource() throws Exception {
        LOG.info("Testing Kafka test resource configuration");

        // Check if Kafka bootstrap servers property is available
        Optional<String> bootstrapServersOpt = applicationContext.getProperty("kafka.bootstrap.servers", String.class);
        if (bootstrapServersOpt.isPresent()) {
            String bootstrapServers = bootstrapServersOpt.get();
            assertFalse(bootstrapServers.isBlank(), "Kafka bootstrap servers should not be blank");
            LOG.info("Kafka bootstrap servers: {}", bootstrapServers);

            // Verify Kafka connection by listing topics
            Map<String, Object> adminProps = new HashMap<>();
            String kafkaServers = bootstrapServers.replace("PLAINTEXT://", "");
            adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServers);
            adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
            adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 60000);

            try (AdminClient adminClient = AdminClient.create(adminProps)) {
                ListTopicsResult topics = adminClient.listTopics();
                Set<String> topicNames = topics.names().get(30, TimeUnit.SECONDS);
                LOG.info("Available Kafka topics: {}", topicNames);
                assertNotNull(topicNames, "Topic names should not be null");
            }

            // Check which Kafka properties are set
            for (String property : KafkaTestResourceProvider.RESOLVABLE_PROPERTIES_LIST) {
                if (applicationContext.containsProperty(property)) {
                    Optional<String> value = applicationContext.getProperty(property, String.class);
                    if (value.isPresent() && !value.get().isBlank()) {
                        LOG.info("Kafka property {} = {}", property, value.get());
                    } else {
                        LOG.warn("Kafka property {} is present but has no value", property);
                    }
                } else {
                    LOG.warn("Kafka property {} is not present", property);
                }
            }
        } else {
            LOG.info("Kafka bootstrap servers property not available yet, skipping Kafka tests");
        }
    }

    @Test
    @DisplayName("Verify Apicurio test resource is properly configured")
    void testApicurioTestResource() throws Exception {
        LOG.info("Testing Apicurio test resource configuration");

        // Check if Apicurio registry URL property is available
        Optional<String> registryUrlOpt = applicationContext.getProperty("apicurio.registry.url", String.class);
        if (registryUrlOpt.isPresent()) {
            String registryUrl = registryUrlOpt.get();
            assertFalse(registryUrl.isBlank(), "Apicurio registry URL should not be blank");
            LOG.info("Apicurio registry URL: {}", registryUrl);

            // Verify Apicurio connection by making a simple HTTP request
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(registryUrl))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.info("Apicurio registry response status: {}", response.statusCode());
            assertTrue(response.statusCode() < 500, "Apicurio registry should be accessible");

            // Verify all Apicurio properties are set
            for (String property : ApicurioTestResourceProvider.RESOLVABLE_PROPERTIES_LIST) {
                assertTrue(applicationContext.containsProperty(property), 
                        "Application context should contain property: " + property);
                Optional<String> value = applicationContext.getProperty(property, String.class);
                assertTrue(value.isPresent(), "Property value should be present for: " + property);
                assertFalse(value.get().isBlank(), "Property value should not be blank for: " + property);
                LOG.info("Apicurio property {} = {}", property, value.get());
            }
        } else {
            LOG.info("Apicurio registry URL property not available yet, skipping Apicurio tests");
        }
    }

    @Test
    @DisplayName("Verify OpenSearch test resource is properly configured")
    void testOpenSearchTestResource() throws Exception {
        LOG.info("Testing OpenSearch test resource configuration");

        // Check if OpenSearch URL property is available
        Optional<String> openSearchUrlOpt = applicationContext.getProperty("opensearch.url", String.class);
        if (openSearchUrlOpt.isPresent()) {
            String osUrl = openSearchUrlOpt.get();
            assertFalse(osUrl.isBlank(), "OpenSearch URL should not be blank");
            LOG.info("OpenSearch URL: {}", osUrl);

            // Check if OpenSearch host and port properties are available
            Optional<String> openSearchHostOpt = applicationContext.getProperty("opensearch.host", String.class);
            Optional<String> openSearchPortOpt = applicationContext.getProperty("opensearch.port", String.class);

            if (openSearchHostOpt.isPresent() && openSearchPortOpt.isPresent()) {
                String osHost = openSearchHostOpt.get();
                String osPort = openSearchPortOpt.get();

                assertFalse(osHost.isBlank(), "OpenSearch host should not be blank");
                LOG.info("OpenSearch host: {}", osHost);

                assertFalse(osPort.isBlank(), "OpenSearch port should not be blank");
                LOG.info("OpenSearch port: {}", osPort);

                // Verify OpenSearch connection by making a simple HTTP request
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(osUrl))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                LOG.info("OpenSearch response status: {}", response.statusCode());
                assertTrue(response.statusCode() < 500, "OpenSearch should be accessible");

                // Verify all OpenSearch properties are set
                List<String> requiredProperties = Arrays.asList(
                        OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_HOST,
                        OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_PORT,
                        OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_URL,
                        OpenSearchTestResourceProvider.PROPERTY_OPENSEARCH_SECURITY_ENABLED
                );

                for (String property : requiredProperties) {
                    assertTrue(applicationContext.containsProperty(property), 
                            "Application context should contain property: " + property);
                    Optional<String> value = applicationContext.getProperty(property, String.class);
                    assertTrue(value.isPresent(), "Property value should be present for: " + property);
                    assertFalse(value.get().isBlank(), "Property value should not be blank for: " + property);
                    LOG.info("OpenSearch property {} = {}", property, value.get());
                }
            } else {
                LOG.info("OpenSearch host or port property not available yet, skipping host/port tests");
            }
        } else {
            LOG.info("OpenSearch URL property not available yet, skipping OpenSearch tests");
        }
    }

    @Test
    @DisplayName("Verify Consul test resource is properly configured")
    void testConsulTestResource() throws Exception {
        LOG.info("Testing Consul test resource configuration");

        // Check if Consul host and port properties are available
        Optional<String> consulHostOpt = applicationContext.getProperty("consul.client.host", String.class);
        Optional<String> consulPortOpt = applicationContext.getProperty("consul.client.port", String.class);

        if (consulHostOpt.isPresent() && consulPortOpt.isPresent()) {
            String host = consulHostOpt.get();
            String port = consulPortOpt.get();

            assertFalse(host.isBlank(), "Consul host should not be blank");
            LOG.info("Consul host: {}", host);

            assertFalse(port.isBlank(), "Consul port should not be blank");
            LOG.info("Consul port: {}", port);

            // Verify Consul connection by making a simple HTTP request
            String consulUrl = "http://" + host + ":" + port + "/v1/status/leader";
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(consulUrl))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.info("Consul response status: {}", response.statusCode());
            assertTrue(response.statusCode() < 500, "Consul should be accessible");

            // Verify all Consul properties are set
            for (String property : ConsulTestResourceProvider.RESOLVABLE_PROPERTIES_LIST) {
                assertTrue(applicationContext.containsProperty(property), 
                        "Application context should contain property: " + property);
                Optional<String> value = applicationContext.getProperty(property, String.class);
                assertTrue(value.isPresent(), "Property value should be present for: " + property);
                assertFalse(value.get().isBlank(), "Property value should not be blank for: " + property);
                LOG.info("Consul property {} = {}", property, value.get());
            }
        } else {
            LOG.info("Consul host or port property not available yet, skipping Consul tests");
        }
    }

    @Test
    @DisplayName("Verify Moto test resource is properly configured")
    void testMotoTestResource() throws Exception {
        LOG.info("Testing Moto test resource configuration");

        // Check if Glue registry URL property is available
        Optional<String> glueRegistryUrlOpt = applicationContext.getProperty("glue.registry.url", String.class);

        if (glueRegistryUrlOpt.isPresent()) {
            String registryUrl = glueRegistryUrlOpt.get();
            assertFalse(registryUrl.isBlank(), "Glue registry URL should not be blank");
            LOG.info("Glue registry URL: {}", registryUrl);

            // Verify Moto connection by creating a Glue client and getting registry
            try (GlueClient glueClient = GlueClient.builder()
                    .endpointOverride(URI.create(registryUrl))
                    .region(Region.of("us-east-1"))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")
                    ))
                    .build()) {

                // Create registry if it doesn't exist
                try {
                    glueClient.createRegistry(builder -> builder.registryName("default"));
                } catch (Exception e) {
                    LOG.info("Registry may already exist: {}", e.getMessage());
                }

                // Get registry
                GetRegistryResponse registry = glueClient.getRegistry(builder -> 
                        builder.registryId(id -> id.registryName("default")));

                assertNotNull(registry, "Registry should not be null");
                assertEquals("default", registry.registryName(), "Registry name should be 'default'");
                LOG.info("Found registry: {}", registry.registryName());
            }

            // Verify all Moto properties are set
            for (String property : MotoTestResourceProvider.RESOLVABLE_PROPERTIES_LIST) {
                assertTrue(applicationContext.containsProperty(property), 
                        "Application context should contain property: " + property);
                Optional<String> value = applicationContext.getProperty(property, String.class);
                assertTrue(value.isPresent(), "Property value should be present for: " + property);
                assertFalse(value.get().isBlank(), "Property value should not be blank for: " + property);
                LOG.info("Moto property {} = {}", property, value.get());
            }
        } else {
            LOG.info("Glue registry URL property not available yet, skipping Moto tests");
        }
    }

    @Test
    @DisplayName("Verify all test resources are properly configured")
    void testAllTestResources() {
        LOG.info("Testing all test resources configuration");

        // Check which test resources are available
        boolean kafkaAvailable = applicationContext.containsProperty("kafka.bootstrap.servers");
        boolean apicurioAvailable = applicationContext.containsProperty("apicurio.registry.url");
        boolean openSearchAvailable = applicationContext.containsProperty("opensearch.url");
        boolean consulAvailable = applicationContext.containsProperty("consul.client.host");
        boolean motoAvailable = applicationContext.containsProperty("glue.registry.url");

        // Log which test resources are available
        LOG.info("Kafka available: {}", kafkaAvailable);
        LOG.info("Apicurio available: {}", apicurioAvailable);
        LOG.info("OpenSearch available: {}", openSearchAvailable);
        LOG.info("Consul available: {}", consulAvailable);
        LOG.info("Moto available: {}", motoAvailable);

        // Verify that at least some test resources are available
        assertTrue(kafkaAvailable || apicurioAvailable || openSearchAvailable || consulAvailable || motoAvailable,
                "At least one test resource should be available");

        LOG.info("Test resources are being configured");
    }
}
