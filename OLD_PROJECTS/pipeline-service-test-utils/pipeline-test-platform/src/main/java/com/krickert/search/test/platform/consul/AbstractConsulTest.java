package com.krickert.search.test.platform.consul;

import com.krickert.search.test.platform.kafka.TestContainerManager;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Abstract base class for Consul tests, integrated with TestContainerManager.
 * Relies on TestContainerManager for Consul container lifecycle and configuration properties.
 */
public abstract class AbstractConsulTest implements ConsulTest {
    protected static final Logger log = LoggerFactory.getLogger(AbstractConsulTest.class);

    // REMOVE: Consul container instance - managed by TestContainerManager
    // protected static final ConsulContainer consulContainer = new ConsulContainer();

    // Get the singleton TestContainerManager instance
    protected static final TestContainerManager containerManager = TestContainerManager.getInstance();

    // Property keys expected to be set by TestContainerManager
    // Ensure these match the keys used in TestContainerManager when it starts Consul
    protected static final String CONSUL_ENDPOINT_PROP = "consul.http.addr"; // e.g., "http://localhost:8500"
    protected static final String CONSUL_HOST_PORT_PROP = "consul.host.and.port"; // e.g., "localhost:8500"
    protected static final String CONSUL_ENABLED_PROP = "consul.client.enabled"; // Often set by manager

    // Optional: Inject ConsulTestHelper if subclasses need it commonly
    @Inject
    protected ConsulTestHelper consulTestHelper;

    /**
     * Set up the test environment before each test.
     * Ensures the TestContainerManager (and thus Consul) is initialized.
     */
    @BeforeEach
    public void setUp() {
        log.debug("Ensuring containers are started via TestContainerManager...");
        // Getting the instance ensures the manager initializes and starts containers if needed.
        TestContainerManager.getInstance();
        log.debug("TestContainerManager instance obtained, Consul container should be running.");
        // Optional: Verify essential properties are present
        // Objects.requireNonNull(getEndpoint(), "Consul endpoint property missing");
        // Objects.requireNonNull(getHostAndPort(), "Consul host/port property missing");
    }

    /**
     * Clean up the test environment after each test.
     */
    @AfterEach
    public void tearDown() {
        // Reset specific Consul state if needed (e.g., clearing keys)
        resetContainer();
        // REMOVE: System.gc() is generally discouraged in tests.
        // System.gc();
    }

    /**
     * Get the endpoint URL for the Consul server from TestContainerManager.
     *
     * @return the endpoint URL as a string
     * @throws IllegalStateException if the property is not found.
     */
    @Override
    public String getEndpoint() {
        String endpoint = containerManager.getProperties().get(CONSUL_ENDPOINT_PROP);
        if (endpoint == null || endpoint.isBlank()) {
            log.warn("Consul endpoint property ('{}') not found in TestContainerManager properties. Forcing manager init.", CONSUL_ENDPOINT_PROP);
            TestContainerManager.getInstance(); // Ensure manager is initialized
            endpoint = containerManager.getProperties().get(CONSUL_ENDPOINT_PROP);
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalStateException("Consul endpoint property ('" + CONSUL_ENDPOINT_PROP + "') not found in TestContainerManager properties after retry.");
            }
        }
        return endpoint;
    }

    /**
     * Get the host and port of the Consul server from TestContainerManager.
     *
     * @return the host and port as a string (e.g., "localhost:8500")
     * @throws IllegalStateException if the property is not found.
     */
    @Override
    public String getHostAndPort() {
        String hostAndPort = containerManager.getProperties().get(CONSUL_HOST_PORT_PROP);
        if (hostAndPort == null || hostAndPort.isBlank()) {
            log.warn("Consul host/port property ('{}') not found in TestContainerManager properties. Forcing manager init.", CONSUL_HOST_PORT_PROP);
            TestContainerManager.getInstance(); // Ensure manager is initialized
            hostAndPort = containerManager.getProperties().get(CONSUL_HOST_PORT_PROP);
            if (hostAndPort == null || hostAndPort.isBlank()) {
                throw new IllegalStateException("Consul host/port property ('" + CONSUL_HOST_PORT_PROP + "') not found in TestContainerManager properties after retry.");
            }
        }
        return hostAndPort;
    }

    /**
     * REMOVE: Starting is handled by TestContainerManager during its initialization.
     * This method is no longer needed in the same way. Kept for interface compatibility if needed,
     * but delegates to ensuring the manager is initialized.
     */
    @Override
    public void startContainer() {
        log.debug("startContainer() called - ensuring TestContainerManager is initialized.");
        TestContainerManager.getInstance(); // Ensures containers are requested if not already up
    }

    /**
     * Check if the Consul container (managed by TestContainerManager) is running.
     *
     * @return true if the container is running, false otherwise
     */
    @Override
    public boolean isContainerRunning() {
        // Delegate check to TestContainerManager, assuming it has a way to check Consul status
        // Adapt the key "consul" if TestContainerManager uses a different identifier.
        boolean running = containerManager.areEssentialContainersRunning("consul");
        log.trace("Checked Consul container status via TestContainerManager: {}", running);
        return running;
    }

    /**
     * Reset Consul state between tests if necessary.
     * This implementation currently only logs. Subclasses or direct test calls
     * should use ConsulTestHelper or a Consul client to clear specific keys if needed.
     * It NO LONGER clears properties from the central TestContainerManager.
     */
    @Override
    public void resetContainer() {
        log.info("Resetting Consul state (if implemented by helper/client)...");
        // Example using ConsulTestHelper if it had a clear method:
        // if (consulTestHelper != null) {
        //     consulTestHelper.clearAllKvPairs("config/"); // Example path
        // } else {
        //     log.warn("ConsulTestHelper not available for reset.");
        // }
        // For now, just logging as the default behavior.
    }

    /**
     * Load configuration into the Consul instance managed by TestContainerManager.
     * Relies on ConsulTestHelper being available (e.g., injected).
     *
     * @param filename the name of the properties file to load
     * @param prefix   the prefix to use for the keys in Consul
     * @return true if the operation was successful, false otherwise
     * @throws IllegalStateException if ConsulTestHelper is not available.
     */
    @Override
    public boolean loadConfig(String filename, String prefix) {
        log.info("Loading configuration from {} with prefix {}", filename, prefix);
        // Ensure helper is available (might be injected or instantiated)
        if (consulTestHelper == null) {
            log.warn("ConsulTestHelper instance is null in loadConfig. Attempting to create a new one.");
            // This might fail if ConsulTestHelper needs injection itself
            try {
                this.consulTestHelper = new ConsulTestHelper(); // Or get it from context if possible
            } catch (Exception e) {
                log.error("Failed to create ConsulTestHelper instance.", e);
                throw new IllegalStateException("ConsulTestHelper not available to load config. Ensure it's injectable or manually instantiate.", e);
            }
        }
        // Ensure endpoint is configured for the helper implicitly or explicitly
        // Helper likely uses Micronaut config which should pick up properties from containerManager
        return consulTestHelper.loadPropertiesFile(filename, prefix);
    }

    /**
     * REMOVE: Property setup is now handled by TestContainerManager.
     */
    // protected void setupConsulProperties() { ... }

    /**
     * Get all properties managed by the central TestContainerManager.
     * This includes Consul properties set by the manager, and potentially others (Kafka, etc.).
     *
     * @return the properties map from TestContainerManager
     */
    @Override
    public Map<String, String> getProperties() {
        // Properties are sourced directly from the central manager
        return containerManager.getProperties();
    }
}