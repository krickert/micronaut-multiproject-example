package com.krickert.testcontainers.moto;

import io.micronaut.testresources.testcontainers.TestContainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

/**
 * Testcontainers implementation for Moto Server.
 * <p>
 * Supported image: {@code motoserver/moto} (and its versions)
 * <p>
 * Exposed port:
 * <ul>
 *     <li>HTTP: 5000</li>
 * </ul>
 */
public class MotoContainer extends GenericContainer<MotoContainer> {

    public static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("motoserver/moto:latest");
    public static final int MOTO_HTTP_PORT = 5000;
    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(60); // Moto can take a moment to start

    /**
     * Constructs a MotoContainer with the default Docker image name.
     */
    public MotoContainer() {
        this(DEFAULT_IMAGE_NAME);
    }

    /**
     * Constructs a MotoContainer with a specific Docker image name as a string.
     *
     * @param dockerImageName The Docker image name (e.g., "motoserver/moto:4.0.0")
     */
    public MotoContainer(String dockerImageName) {
        this(DockerImageName.parse(dockerImageName));
    }

    /**
     * Constructs a MotoContainer with a specific DockerImageName.
     *
     * @param dockerImageName The DockerImageName object.
     */
    public MotoContainer(final DockerImageName dockerImageName) {
        super(dockerImageName);
        dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);

        // Moto server's root path typically returns a 200 OK with a list of services.
        // We can use this as a basic health check.
        setWaitStrategy(
                Wait.forHttp("/")
                        .forPort(MOTO_HTTP_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(DEFAULT_WAIT_TIMEOUT)
        );

        withExposedPorts(MOTO_HTTP_PORT);
        withNetwork(TestContainers.network("test-network"));
        withNetworkAliases("localstack"); // Using localstack alias for compatibility
        // Configurations from your MotoTestResourceProvider
        withAccessToHost(true); // Allows container to access host network if needed
        withCommand("-H0.0.0.0"); // Listen on all interfaces within the container
        withEnv(Map.of(
                "MOTO_SERVICE", "glue", // Example: configure to run only glue, or comma-separated list
                "TEST_SERVER_MODE", "true" // Moto specific environment variable
        ));
        // withReuse(false) is typically managed by the TestResources framework or test setup,
        // not usually a direct property of the container class itself.
    }

    /**
     * Gets the publicly accessible HTTP endpoint URL for the Moto server.
     *
     * @return The Moto server endpoint URL string (e.g., "http://localhost:RANDOM_PORT")
     */
    public String getEndpointUrl() {
        return String.format("http://%s:%d",
                getHost(),
                getMappedPort(MOTO_HTTP_PORT));
    }
}