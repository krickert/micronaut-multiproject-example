// <llm-snippet-file>pipeline-service-test-utils/pipeline-test-platform/src/main/java/com/krickert/search/test/platform/kafka/KafkaApicurioTest.java</llm-snippet-file>
package com.krickert.search.test.platform.kafka;

// Keep necessary imports for Kafka/Serde configuration keys if needed for verification or defaults
import io.apicurio.registry.serde.config.SerdeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of KafkaTest using Apicurio Registry, managed by TestContainerManager.
 */
public class KafkaApicurioTest extends AbstractKafkaTest {
    private static final Logger log = LoggerFactory.getLogger(KafkaApicurioTest.class);

    private static final String REGISTRY_TYPE = "apicurio";
    // Default Protobuf message class for deserialization if not specified
    private static final String DEFAULT_RETURN_CLASS = "com.krickert.search.model.PipeStream"; // Adjust if your model package/name is different
    // Property key expected from TestContainerManager for the Apicurio registry URL
    private static final String APICURIO_REGISTRY_URL_PROP = "apicurio.registry.url"; // Ensure this matches the key set in TestContainerManager

    private final String returnClass;

    /**
     * Constructor specifying the Protobuf class for the default consumer deserializer.
     * Ensures the TestContainerManager knows Apicurio is needed.
     * @param returnClass The fully qualified name of the Protobuf class to deserialize into.
     */
    public KafkaApicurioTest(String returnClass) {
        this.returnClass = returnClass;
        // Ensure TestContainerManager is initialized and aware Apicurio is needed
        log.debug("Initializing KafkaApicurioTest, ensuring TestContainerManager knows type is '{}'", REGISTRY_TYPE);
        TestContainerManager.getInstance().setProperty(TestContainerManager.KAFKA_REGISTRY_TYPE_PROP, REGISTRY_TYPE);
        // Pass the specific return class for the deserializer config
        TestContainerManager.getInstance().setProperty(consumerDeserializerReturnClassPropKey(), returnClass);
    }

    /**
     * Default constructor using the default Protobuf return class.
     */
    public KafkaApicurioTest() {
        this(DEFAULT_RETURN_CLASS);
    }

    // Helper to get the specific property key for the deserializer return class
    private String consumerDeserializerReturnClassPropKey() {
        return "kafka.consumers.default." + SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS;
    }

    @Override
    public String getRegistryType() {
        return REGISTRY_TYPE;
    }

    @Override
    public String getRegistryEndpoint() {
        // Retrieve the endpoint registered by TestContainerManager
        String endpoint = containerManager.getProperties().get(APICURIO_REGISTRY_URL_PROP);
        if (endpoint == null || endpoint.isBlank()) {
            // If called before TestContainerManager is fully initialized, force init and retry.
            log.warn("Apicurio registry endpoint ('{}') not found in TestContainerManager properties. Forcing manager init.", APICURIO_REGISTRY_URL_PROP);
            TestContainerManager.getInstance(); // Ensure init
            endpoint = containerManager.getProperties().get(APICURIO_REGISTRY_URL_PROP);
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalStateException("Apicurio Registry endpoint property ('" + APICURIO_REGISTRY_URL_PROP + "') not found in TestContainerManager properties after retry.");
            }
        }
        log.trace("Retrieved Apicurio endpoint: {}", endpoint);
        return endpoint;
    }

    /**
     * Ensures that the TestContainerManager singleton is initialized,
     * which handles starting all necessary containers (Kafka, Apicurio, Consul).
     */
    @Override
    public void startContainers() {
        log.debug("Requesting container start via TestContainerManager.getInstance()...");
        // Getting the instance triggers the initialization logic within TestContainerManager
        // if it hasn't run already. This includes starting containers.
        TestContainerManager.getInstance();
        log.debug("TestContainerManager instance obtained, containers should be starting/running.");
        // We might want to add a wait condition here if tests immediately fail due to timing.
        // However, TestContainerManager initialization should ideally block until ready.
    }

    /**
     * Checks if the essential containers managed by TestContainerManager are running.
     * Assumes TestContainerManager provides a way to check status.
     * Modify based on TestContainerManager's actual API.
     */
    @Override
    public boolean areContainersRunning() {
        // Delegate check to TestContainerManager
        // Assuming a method like this exists or checking individual container statuses
        boolean running = containerManager.areEssentialContainersRunning("kafka", REGISTRY_TYPE, "consul");
        log.trace("Checked container status via TestContainerManager: {}", running);
        return running;
        // Alternatively, check individual statuses if exposed:
        // return containerManager.isKafkaRunning() && containerManager.isApicurioRunning() && containerManager.isConsulRunning();
    }

    /**
     * Resets container state if necessary.
     * For Apicurio's in-memory registry, reset typically isn't needed unless
     * specific test data needs clearing (which would require registry API calls).
     * Stopping containers is handled globally by TestContainerManager's shutdown hook.
     */
    @Override
    public void resetContainers() {
        log.debug("Resetting containers for KafkaApicurioTest (typically no-op for Apicurio in-memory).");
        // If TestContainerManager provides a specific reset mechanism, call it here:
        // containerManager.resetRegistryState(); // Example
        // Otherwise, this can be empty if no explicit reset action is needed between tests.
    }

    // REMOVE: registerApicurioProperties method - this is now handled by TestContainerManager
}