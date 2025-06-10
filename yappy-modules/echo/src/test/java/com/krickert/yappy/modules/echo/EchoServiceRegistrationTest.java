package com.krickert.yappy.modules.echo;

import com.google.protobuf.Empty;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ServiceRegistrationData;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class EchoServiceRegistrationTest {

    @Inject
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub blockingClient;

    @Test
    @DisplayName("Should return valid service registration data")
    void testGetServiceRegistration() {
        // Call the service registration method
        ServiceRegistrationData registration = blockingClient.getServiceRegistration(Empty.getDefaultInstance());
        
        // Verify the response
        assertNotNull(registration, "Registration data should not be null");
        assertEquals("echo", registration.getModuleName(), "Module name should be 'echo'");
        assertTrue(registration.hasJsonConfigSchema(), "Should have a JSON config schema");
        
        // Verify the schema is valid JSON
        String schema = registration.getJsonConfigSchema();
        assertNotNull(schema, "Schema should not be null");
        assertTrue(schema.contains("\"type\": \"object\""), "Schema should define an object type");
        assertTrue(schema.contains("\"log_prefix\""), "Schema should include log_prefix property");
    }
}