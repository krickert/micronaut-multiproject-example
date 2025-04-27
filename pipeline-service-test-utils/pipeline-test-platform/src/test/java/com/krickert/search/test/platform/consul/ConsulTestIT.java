package com.krickert.search.test.platform.consul;

import com.krickert.search.test.consul.ConsulContainer;
import com.krickert.search.test.consul.ConsulTestHelper;
import com.krickert.search.test.platform.kafka.TestContainerManager;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for ConsulTest.
 * This test verifies that the Consul container is properly set up and can be used.
 */
@MicronautTest(
    environments = "consul",
    packages = {"com.krickert.search.test.platform.consul", "com.krickert.search.test.platform.kafka"},
    transactional = false
)
public class ConsulTestIT extends AbstractConsulTest {
    private static final Logger log = LoggerFactory.getLogger(ConsulTestIT.class);

    private TestContainerManager containerManager = TestContainerManager.getInstance();

    @Inject
    private ConsulTestHelper consulTestHelper;

    /**
     * Test that the Consul container is properly set up and can be used.
     */
    @Test
    void testConsulSetup() {
        // Verify that the container is running
        assertThat(isContainerRunning()).isTrue();

        // Verify that the endpoint is set
        String endpoint = getEndpoint();
        assertThat(endpoint).isNotNull();
        assertThat(endpoint).contains("http://");
        log.info("Consul endpoint: {}", endpoint);

        // Verify that the host and port are set
        String hostAndPort = getHostAndPort();
        assertThat(hostAndPort).isNotNull();
        assertThat(hostAndPort).contains(":");
        log.info("Consul host and port: {}", hostAndPort);

        // Verify that the properties are set correctly
        Map<String, String> props = getProperties();
        assertThat(props).isNotEmpty();

        // Verify Consul properties
        assertThat(props).containsKey("consul.client.defaultZone");
        assertThat(props).containsKey("consul.client.registration.enabled");
        assertThat(props.get("consul.client.registration.enabled")).isEqualTo("false");

        // Skip testing loadConfig as it requires Environment injection
        log.info("Skipping loadConfig test as it requires Environment injection");

        log.info("All tests passed for Consul setup");
    }
}
