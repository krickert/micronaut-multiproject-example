package com.krickert.search.pipeline;

import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;

import jakarta.inject.Inject;

@MicronautTest
@io.micronaut.context.annotation.Property(name = "consul.client.enabled", value = "false")
@Disabled("Skipping due to Consul initialization complexity - will test in integration test instead")
class OrchestratorTest {

    @Inject
    EmbeddedApplication<?> application;

    @Test
    void testItWorks() {
        Assertions.assertTrue(application.isRunning());
    }

}
