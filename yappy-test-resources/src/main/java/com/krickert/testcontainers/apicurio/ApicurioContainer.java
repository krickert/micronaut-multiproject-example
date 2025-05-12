package com.krickert.testcontainers.apicurio; // Assuming same package as your provider

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;

/**
 * Testcontainers implementation for Apicurio Registry.
 * <p>
 * Supported image: {@code apicurio/apicurio-registry} (and its versions)
 * <p>
 * Exposed port:
 * <ul>
 *     <li>HTTP: 8080</li>
 * </ul>
 */
public class ApicurioContainer extends GenericContainer<ApicurioContainer> {

    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("apicurio/apicurio-registry:latest");
    private static final int APICURIO_HTTP_PORT = 8080;
    // Apicurio can sometimes be slow to start, especially the first time an image is pulled.
    // Providing a longer default wait time for the strategy.
    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(120);


    /**
     * Constructs an ApicurioContainer with the default Docker image name.
     */
    public ApicurioContainer() {
        this(DEFAULT_IMAGE_NAME);
    }

    /**
     * Constructs an ApicurioContainer with a specific Docker image name as a string.
     *
     * @param dockerImageName The Docker image name (e.g., "apicurio/apicurio-registry:2.5.0.Final")
     */
    public ApicurioContainer(String dockerImageName) {
        this(DockerImageName.parse(dockerImageName));
    }

    /**
     * Constructs an ApicurioContainer with a specific DockerImageName.
     *
     * @param dockerImageName The DockerImageName object.
     */
    public ApicurioContainer(final DockerImageName dockerImageName) {
        super(dockerImageName);
        // Ensure the provided image is compatible with the known default,
        // though this is a soft check as users might use custom or versioned images.
        dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);

        // Apicurio Registry (built with Quarkus) exposes a health check endpoint.
        // We wait for the /q/health/ready endpoint to be available and return HTTP 200.
        setWaitStrategy(
            Wait.forHttp("/q/health/ready")
                .forPort(APICURIO_HTTP_PORT)
                .forStatusCode(200)
                .withStartupTimeout(DEFAULT_WAIT_TIMEOUT)
        );

        // As seen in your ApicurioTestResourceProvider, setting QUARKUS_PROFILE to "prod"
        // is a common configuration for the Apicurio Docker image.
        withEnv("QUARKUS_PROFILE", "prod");

        // Expose the default Apicurio HTTP port.
        withExposedPorts(APICURIO_HTTP_PORT);
    }

    /**
     * Gets the publicly accessible base URL for the Apicurio Registry API (v3).
     *
     * @return The registry API URL string (e.g., "http://localhost:RANDOM_PORT/apis/registry/v3")
     */
    public String getRegistryApiV3Url() {
        return String.format("http://%s:%d/apis/registry/v3",
                             getHost(),
                             getMappedPort(APICURIO_HTTP_PORT));
    }

    /**
     * Gets the publicly accessible base URL for the Apicurio Registry UI.
     *
     * @return The registry UI URL string (e.g., "http://localhost:RANDOM_PORT/ui")
     */
    public String getRegistryUiUrl() {
        return String.format("http://%s:%d/ui",
                             getHost(),
                             getMappedPort(APICURIO_HTTP_PORT));
    }
}