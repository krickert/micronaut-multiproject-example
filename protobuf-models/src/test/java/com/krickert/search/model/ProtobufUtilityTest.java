package com.krickert.search.model;

import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ProtobufUtilityTest {

    /**
     * Tests the {@link ProtobufUtils#now} method.
     * The method should return a {@link Timestamp} object representing the current time
     * as an Instant object, converted to seconds and nanoseconds.
     */
    @Test
    void testNowReturnsCurrentTimestamp() {
        // Act
        Timestamp timestamp = ProtobufUtils.now();

        // Assert
        Instant currentInstant = Instant.now();
        assertNotNull(timestamp);
        assertTrue(timestamp.getSeconds() <= currentInstant.getEpochSecond());
        assertTrue(timestamp.getNanos() < 1_000_000_000);
        assertTrue(timestamp.getSeconds() >= (currentInstant.getEpochSecond() - 1)); // buffer for slight delays
    }

    @Test
    void nowIsNowNotThen() throws InterruptedException {
        Timestamp now = ProtobufUtils.now();
        Assertions.assertInstanceOf(Timestamp.class, now);
        Thread.sleep(1000);//sleep 1 second so next now() is a second later.
        Assertions.assertTrue(ProtobufUtils.now().getSeconds() > now.getSeconds());
    }

    @Test
    void stamp() {
        long time = System.currentTimeMillis() / 1000;
        Timestamp stamp = ProtobufUtils.stamp(time);
        assertEquals(time, stamp.getSeconds());
        assertEquals(0, stamp.getNanos());
    }

}