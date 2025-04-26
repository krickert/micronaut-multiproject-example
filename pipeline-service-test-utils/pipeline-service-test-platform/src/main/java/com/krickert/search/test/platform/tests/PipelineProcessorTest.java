package com.krickert.search.test.platform.tests; // Use your correct package

// Core Micronaut / Test Imports
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers; // Keep if ConsulContainer uses it

// Project Specific Imports
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeRequest;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.config.PipelineConfig;
import com.krickert.search.pipeline.config.PipelineConfigManager;
import com.krickert.search.pipeline.config.ServiceConfiguration;
import com.krickert.search.pipeline.service.PipelineServiceProcessor;
import com.krickert.search.test.consul.ConsulContainer; // Your Consul container class
import com.krickert.search.test.kafka.AbstractKafkaTest; // The static-init base class

// Standard Java Imports
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects; // If needed for custom logic

// Static imports for Assertions
import static org.junit.jupiter.api.Assertions.*;

/**
 * Base class for pipeline processor tests using static Kafka/SchemaRegistry setup
 * (controlled by system property 'schema.registry.type') and injected Consul.
 */
@MicronautTest(environments = {"test"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers(disabledWithoutDocker = true) // Keep if ConsulContainer uses @Container
public abstract class PipelineProcessorTest extends AbstractKafkaTest {

    private static final Logger log = LoggerFactory.getLogger(PipelineProcessorTest.class);

    // --- Injected Dependencies ---
    // These beans are created by the Micronaut context AFTER TestPropertyProvider runs
    @Inject
    protected PipelineConfigManager pipelineConfigManager;

    @Inject
    protected ConsulContainer consulContainer; // Assuming this is a Bean or handled by @Testcontainers

    @Inject
    protected Environment environment;

    // --- Abstract Methods (to be implemented by subclasses) ---

    /**
     * Get the processor implementation to test.
     * @return the pipeline processor to test
     */
    protected abstract PipelineServiceProcessor getProcessor();

    /**
     * Get the expected PipeStream result after processing.
     * @return the expected PipeStream
     */
    protected abstract PipeStream expectedResult();

    // --- Configurable Test Parameters (with defaults) ---

    protected String getPipelineName() {
        return "test-pipeline";
    }

    protected String getServiceName() {
        return "test-service";
    }

    protected List<String> getInputTopics() {
        return List.of("input-topic");
    }

    protected List<String> getOutputTopics() {
        return List.of("output-topic");
    }

    protected PipeDoc getTestDocument() {
        return PipeDoc.newBuilder()
                .setId("test-doc-" + System.currentTimeMillis()) // Ensure unique ID for test runs
                .setTitle("Test Document Title")
                .setBody("This is the body of the test document.")
                .build();
    }

    // --- TestPropertyProvider Implementation (from AbstractKafkaTest) ---
    // This override adds Consul properties to the ones provided by the base class's static setup

    @Override
    public @NonNull Map<String, String> getProperties() {
        // Get Kafka + Schema Registry properties from the static setup in AbstractKafkaTest
        Map<String, String> properties = new HashMap<>(super.getProperties());

        // Add ConsulContainer properties if it's ready and provides them
        // Check if consulContainer injection has happened and if it's running
        if (consulContainer != null && consulContainer.isRunning() && consulContainer instanceof io.micronaut.test.support.TestPropertyProvider) {
            // If ConsulContainer itself provides properties via the interface
            properties.putAll(((io.micronaut.test.support.TestPropertyProvider) consulContainer).getProperties());
            log.debug("Added Consul properties from TestPropertyProvider implementation.");
        } else if (consulContainer != null && consulContainer.isRunning() && consulContainer.getProperties() != null) {
            // If ConsulContainer has a simple getProperties() method
            properties.putAll(consulContainer.getProperties());
            log.debug("Added Consul properties via getProperties() method.");
        }
        else if (consulContainer == null) {
            log.warn("ConsulContainer was null when getProperties() was called. Consul properties not added.");
        } else {
            log.warn("ConsulContainer was not running or getProperties() returned null when getProperties() was called. Consul properties not added.");
        }

        log.info("Providing combined properties. Keys: {}", properties.keySet());
        return properties;
    }

    // --- Test Setup (@BeforeEach) ---

    @BeforeEach
    void setUp() {
        // 1. Check essential injected components are present
        assertNotNull(pipelineConfigManager, "PipelineConfigManager should be injected.");
        assertNotNull(consulContainer, "ConsulContainer should be injected.");
        assertNotNull(environment, "Environment should be injected.");

        // 2. Ensure Consul container is running (crucial dependency for this test class)
        assertTrue(consulContainer.isRunning(), "Consul container must be running for test setup.");
        log.info("Consul check passed. Endpoint: {}", consulContainer.getEndpoint());

        // 3. Kafka and Schema Registry are assumed running due to static init in AbstractKafkaTest
        log.info("Kafka and Schema Registry assumed running due to static initialization in base class.");
        log.info("Kafka Bootstrap Servers (from base class): {}", getBootstrapServers()); // Inherited helper method
        log.info("Schema Registry Type (from base class): {}", AbstractKafkaTest.chosenRegistryType); // Access static field

        // 4. Configure the PipelineManager for this specific test
        pipelineConfigManager.setPipelines(new HashMap<>()); // Clear previous configs

        PipelineConfig pipeline = new PipelineConfig(getPipelineName());
        Map<String, ServiceConfiguration> services = new HashMap<>();
        ServiceConfiguration service = new ServiceConfiguration(getServiceName());
        service.setKafkaListenTopics(getInputTopics());
        service.setKafkaPublishTopics(getOutputTopics());
        services.put(getServiceName(), service);
        pipeline.setService(services);

        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put(getPipelineName(), pipeline);
        pipelineConfigManager.setPipelines(pipelines);
        log.info("Pipeline Config Manager configured with pipeline: {}", getPipelineName());

        // 5. Add specific pipeline config properties to Micronaut Environment for potential bean use
        // Note: This might be redundant if beans read directly from PipelineConfigManager
        Map<String, Object> dynamicProps = new HashMap<>();
        String listenTopicsKey = "pipeline.configs." + getPipelineName() + ".service." + getServiceName() + ".kafka-listen-topics";
        String publishTopicsKey = "pipeline.configs." + getPipelineName() + ".service." + getServiceName() + ".kafka-publish-topics";
        dynamicProps.put(listenTopicsKey, String.join(",", getInputTopics()));
        dynamicProps.put(publishTopicsKey, String.join(",", getOutputTopics()));

        // Use a unique name for the property source per setup run
        String propertySourceName = "pipeline-setup-" + System.currentTimeMillis();
        environment.addPropertySource(PropertySource.of(propertySourceName, dynamicProps));
        log.info("Added dynamic properties to environment under source '{}': Keys={}", propertySourceName, dynamicProps.keySet());

        log.info("Test setup complete.");
    }

    // --- Test Methods ---

    @Test
    void testInfrastructureIsRunning() {
        log.info("Checking infrastructure status...");
        // Basic checks again, mainly for logging/confirmation
        assertTrue(consulContainer.isRunning(), "Consul container should be running");
        assertTrue(kafka.isRunning(), "Kafka container (static) should be running");
        // Cannot easily check static schema registry container directly here without exposing it
        // We rely on the static initializer having succeeded.
        log.info("Consul endpoint: {}", consulContainer.getEndpoint());
        log.info("Kafka bootstrap servers: {}", getBootstrapServers());
        log.info("Schema registry type configured: {}", AbstractKafkaTest.chosenRegistryType);
        // Log the endpoint from the static config record if AbstractKafkaTest makes it accessible
        // log.info("Schema registry endpoint: {}", AbstractKafkaTest.getStaticRegistryConfig().endpoint()); // Requires getter/protected field
    }

    @Test
    void testPipelineConfigurationIsLoaded() {
        log.info("Verifying pipeline configuration...");
        Map<String, PipelineConfig> pipelines = pipelineConfigManager.getPipelines();
        assertNotNull(pipelines, "Pipelines map should not be null");
        assertTrue(pipelines.containsKey(getPipelineName()), "Pipelines map should contain " + getPipelineName());

        PipelineConfig pipeline = pipelines.get(getPipelineName());
        assertNotNull(pipeline, "Pipeline should not be null");
        assertEquals(getPipelineName(), pipeline.getName());

        Map<String, ServiceConfiguration> services = pipeline.getService();
        assertNotNull(services, "Services map should not be null");
        assertTrue(services.containsKey(getServiceName()), "Services map should contain " + getServiceName());

        ServiceConfiguration service = services.get(getServiceName());
        assertNotNull(service, "Service should not be null");
        assertEquals(getServiceName(), service.getName());

        // Check topics match (using lists directly)
        assertIterableEquals(getInputTopics(), service.getKafkaListenTopics(), "Listen topics should match");
        assertIterableEquals(getOutputTopics(), service.getKafkaPublishTopics(), "Publish topics should match");
        log.info("Pipeline configuration verified successfully.");
    }

    @Test
    void testProcessorExecution() {
        log.info("Testing processor execution...");
        // Arrange
        PipelineServiceProcessor processor = getProcessor();
        assertNotNull(processor, "Processor under test cannot be null");

        PipeDoc doc = getTestDocument();
        PipeRequest request = PipeRequest.newBuilder().setDoc(doc).build();
        PipeStream inputPipeStream = PipeStream.newBuilder().setRequest(request).build();
        log.debug("Input PipeStream: {}", inputPipeStream);

        PipeStream expected = expectedResult();
        assertNotNull(expected, "Expected result cannot be null for comparison");
        log.debug("Expected PipeStream: {}", expected);

        // Act
        PipeResponse response = processor.process(inputPipeStream);
        log.debug("Processor Response: {}", response);

        // Assert Response Success
        assertTrue(response.getSuccess(), "Processor response should indicate success");
        assertNull(response.getErrorDate(), "ErrorData should be null on success"); // Check hasErrorDate() if that's the proto way

        // Assert Resulting PipeStream state (using helper)
        assertResult(inputPipeStream, expected); // Pass the modified input stream

        log.info("Processor execution test completed successfully.");
    }

    /**
     * Assert that the actual result matches the expected result.
     * This method can be overridden by subclasses for more specific assertions.
     * By default, it compares document IDs and potentially other core fields.
     *
     * @param actual the PipeStream object *after* it has been processed (state is modified in place)
     * @param expected the expected state defined by {@link #expectedResult()}
     */
    protected void assertResult(@NonNull PipeStream actual, @NonNull PipeStream expected) {
        assertNotNull(actual, "Actual result stream cannot be null");
        assertNotNull(actual.getRequest(), "Actual request cannot be null");
        assertNotNull(actual.getRequest().getDoc(), "Actual document cannot be null");

        assertNotNull(expected.getRequest(), "Expected request cannot be null");
        assertNotNull(expected.getRequest().getDoc(), "Expected document cannot be null");

        // Example assertion: Compare IDs
        assertEquals(
                expected.getRequest().getDoc().getId(),
                actual.getRequest().getDoc().getId(),
                "Document IDs should match after processing"
        );

        // Example assertion: Compare Titles (add more as needed)
        assertEquals(
                expected.getRequest().getDoc().getTitle(),
                actual.getRequest().getDoc().getTitle(),
                "Document Titles should match after processing"
        );

        // Log the actual and expected results for debugging if assertions fail
        if (!actual.equals(expected)) { // Basic equals check might fail on complex protos
            log.debug("Detailed Comparison - Actual result: {}", actual);
            log.debug("Detailed Comparison - Expected result: {}", expected);
        }
    }
}