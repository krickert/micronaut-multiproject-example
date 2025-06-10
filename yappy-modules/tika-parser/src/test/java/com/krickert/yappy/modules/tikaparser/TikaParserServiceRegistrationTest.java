package com.krickert.yappy.modules.tikaparser;

import com.google.protobuf.Empty;
import com.krickert.search.sdk.ServiceRegistrationData;
import io.grpc.stub.StreamObserver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class TikaParserServiceRegistrationTest {

    @Inject
    TikaParserService tikaParserService;

    @Test
    void testGetServiceRegistration() throws InterruptedException {
        // Given
        Empty request = Empty.getDefaultInstance();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ServiceRegistrationData> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        StreamObserver<ServiceRegistrationData> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(ServiceRegistrationData value) {
                responseRef.set(value);
            }

            @Override
            public void onError(Throwable t) {
                errorRef.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };

        // When
        tikaParserService.getServiceRegistration(request, responseObserver);

        // Then
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Response should be received within 5 seconds");
        assertNull(errorRef.get(), "No error should occur");
        
        ServiceRegistrationData response = responseRef.get();
        assertNotNull(response, "Response should not be null");
        assertEquals("tika-parser", response.getModuleName());
        assertTrue(response.hasJsonConfigSchema(), "Should have a config schema");
        
        // Verify the schema is valid JSON
        String schema = response.getJsonConfigSchema();
        assertNotNull(schema, "Schema should not be null");
        assertTrue(schema.contains("$schema"), "Schema should contain JSON schema reference");
        assertTrue(schema.contains("parsingOptions"), "Schema should define parsingOptions");
        assertTrue(schema.contains("features"), "Schema should define features");
        assertTrue(schema.contains("GEO_TOPIC_PARSER"), "Schema should include GEO_TOPIC_PARSER feature");
    }

    @Test
    void testGetServiceRegistrationModuleName() throws InterruptedException {
        // Given
        Empty request = Empty.getDefaultInstance();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ServiceRegistrationData> responseRef = new AtomicReference<>();

        StreamObserver<ServiceRegistrationData> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(ServiceRegistrationData value) {
                responseRef.set(value);
            }

            @Override
            public void onError(Throwable t) {
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };

        // When
        tikaParserService.getServiceRegistration(request, responseObserver);
        latch.await(5, TimeUnit.SECONDS);

        // Then
        ServiceRegistrationData response = responseRef.get();
        assertEquals("tika-parser", response.getModuleName(), 
                "Module name should be 'tika-parser'");
    }

    @Test
    void testGetServiceRegistrationSchema() throws InterruptedException {
        // Given
        Empty request = Empty.getDefaultInstance();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ServiceRegistrationData> responseRef = new AtomicReference<>();

        StreamObserver<ServiceRegistrationData> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(ServiceRegistrationData value) {
                responseRef.set(value);
            }

            @Override
            public void onError(Throwable t) {
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };

        // When
        tikaParserService.getServiceRegistration(request, responseObserver);
        latch.await(5, TimeUnit.SECONDS);

        // Then
        ServiceRegistrationData response = responseRef.get();
        String schema = response.getJsonConfigSchema();
        
        // Verify it's a valid JSON schema structure
        assertTrue(schema.contains("\"type\": \"object\""), 
                "Schema should define an object type");
        assertTrue(schema.contains("\"properties\""), 
                "Schema should have properties");
        
        // Verify specific properties are documented
        assertTrue(schema.contains("\"log_prefix\""), 
                "Schema should document log_prefix");
        assertTrue(schema.contains("\"parsingOptions\""), 
                "Schema should document parsingOptions");
        assertTrue(schema.contains("\"maxContentLength\""), 
                "Schema should document maxContentLength");
        assertTrue(schema.contains("\"extractMetadata\""), 
                "Schema should document extractMetadata");
        
        // Verify features enum
        assertTrue(schema.contains("\"enum\": [\"GEO_TOPIC_PARSER\", \"OCR\", \"LANGUAGE_DETECTION\"]"), 
                "Schema should define available features");
    }
}