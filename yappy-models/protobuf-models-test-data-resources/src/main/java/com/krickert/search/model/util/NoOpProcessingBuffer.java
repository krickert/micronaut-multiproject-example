package com.krickert.search.model.util;

import com.google.protobuf.Message;

import java.nio.file.Path;

/**
 * A no-op implementation of the ProcessingBuffer interface.
 * This implementation doesn't actually store or save any messages.
 * It's used in production environments where we don't want to incur the overhead
 * of storing and saving protobuf messages.
 *
 * @param <T> the type of protobuf message
 */
public class NoOpProcessingBuffer<T extends Message> implements ProcessingBuffer<T> {

    /**
     * Does nothing.
     *
     * @param message the message to add (ignored)
     */
    @Override
    public void add(T message) {
        // No-op
    }

    /**
     * Does nothing.
     */
    @Override
    public void saveToDisk() {
        // No-op
    }

    /**
     * Does nothing.
     *
     * @param location the directory to save the files to (ignored)
     * @param fileNamePrefix the prefix to use for the file names (ignored)
     * @param numberPrecision the number of digits to use for the file name suffix (ignored)
     */
    @Override
    public void saveToDisk(Path location, String fileNamePrefix, int numberPrecision) {
        // No-op
    }

    /**
     * Always returns 0.
     *
     * @return 0
     */
    @Override
    public int size() {
        return 0;
    }

    /**
     * Does nothing.
     */
    @Override
    public void clear() {
        // No-op
    }
}