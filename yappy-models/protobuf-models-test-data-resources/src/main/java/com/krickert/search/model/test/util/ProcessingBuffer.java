package com.krickert.search.model.test.util;

import com.google.protobuf.Message;

import java.nio.file.Path;

/**
 * Interface for a buffer that stores protobuf messages and can save them to disk.
 * This is used for testing purposes to capture and save protobuf messages during test runs.
 *
 * @param <T> the type of protobuf message to store
 */
public interface ProcessingBuffer<T extends Message> {

    /**
     * Adds a message to the buffer.
     * If the buffer is full, the oldest message will be removed.
     *
     * @param message the message to add
     */
    void add(T message);

    /**
     * Saves all messages in the buffer to disk using default parameters.
     * Default location is the current directory, default prefix is "protobuf",
     * and default precision is 3 digits.
     */
    void saveToDisk();

    /**
     * Saves all messages in the buffer to disk.
     *
     * @param location the directory to save the files to
     * @param fileNamePrefix the prefix to use for the file names
     * @param numberPrecision the number of digits to use for the file name suffix
     */
    void saveToDisk(Path location, String fileNamePrefix, int numberPrecision);

    /**
     * Returns the number of messages in the buffer.
     *
     * @return the number of messages
     */
    int size();

    /**
     * Clears all messages from the buffer.
     */
    void clear();
}