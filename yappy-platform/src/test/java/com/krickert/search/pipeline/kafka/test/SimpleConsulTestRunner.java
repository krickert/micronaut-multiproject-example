package com.krickert.search.pipeline.kafka.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class SimpleConsulTestRunner {

    private static final Logger log = LoggerFactory.getLogger(SimpleConsulTestRunner.class);
    // Use a specific, recent Consul version
    private static final DockerImageName CONSUL_IMAGE = DockerImageName.parse("hashicorp/consul:1.20");
    private static final int CONSUL_HTTP_PORT = 8500;

    public static void main(String[] args) throws InterruptedException {
        log.info("Starting Consul containers using Testcontainers...");

        // Option 1: Using GenericContainer
        // We need to explicitly expose the port


        // Option 2: Using Specific ConsulContainer
        // This container knows about port 8500 by default
        try (ConsulContainer specificConsul = new ConsulContainer(CONSUL_IMAGE)
             // Optional: Give it a friendly name for docker ps
            .withLabel("runner", "specific")) {

            specificConsul.start();
            // Use the dedicated method if available, otherwise fallback
            Integer specificMappedPort = specificConsul.getMappedPort(8500); // More specific method
            // Integer specificMappedPort = specificConsul.getMappedPort(CONSUL_HTTP_PORT); // Alternative
            String specificHost = specificConsul.getHost();

            log.info(">>> Specific ConsulContainer Started <<<");
            log.info("    Image: {}", CONSUL_IMAGE);
            log.info("    Host: {}", specificHost);
            log.info("    Consul HTTP Port 8500 Mapped To: {}", specificMappedPort);
            log.info("    Consul UI URL: http://{}:{}", specificHost, specificMappedPort);


            log.info("Containers are running. Inspect ports (e.g., using 'docker ps'). Press Ctrl+C to stop.");
            // Keep the main thread alive indefinitely
            Thread.sleep(Long.MAX_VALUE);

        } // specificConsul stops here due to try-with-resources
    }
}