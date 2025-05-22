package com.krickert.search.python;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A class that runs a Python gRPC server using GraalPy.
 * This server uses the generated Python protobuf stubs from both the local project
 * and the protobuf-models project.
 */
@Singleton
@Context // Eager initialization
public class PythonServerRunner implements ApplicationEventListener<ServerStartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(PythonServerRunner.class);
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private org.graalvm.polyglot.Context pythonContext;

    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        LOG.info("Starting Python gRPC server...");
        startPythonServer();
    }

    /**
     * Starts the Python gRPC server in a separate thread.
     */
    private void startPythonServer() {
        executorService.submit(() -> {
            try {
                // Create a Python context with all access allowed
                pythonContext = org.graalvm.polyglot.Context.newBuilder("python")
                        .allowAllAccess(true)
                        .build();

                // Load the Python server script
                String serverScript = loadPythonScript("/python/fibonacci_server.py");

                LOG.info("Loaded Python server script");

                // Execute the Python script
                pythonContext.eval(Source.create("python", serverScript));

                // Get the serve function and call it
                Value serveFunction = pythonContext.getBindings("python").getMember("serve");
                if (serveFunction != null && serveFunction.canExecute()) {
                    LOG.info("Starting Python gRPC server on port 50051");
                    serveFunction.execute();
                } else {
                    LOG.error("Failed to find or execute the 'serve' function in the Python script");
                }
            } catch (Exception e) {
                LOG.error("Error starting Python gRPC server", e);
            }
        });
    }

    /**
     * Loads a Python script from the classpath.
     *
     * @param resourcePath The path to the Python script
     * @return The content of the Python script
     * @throws IOException If the script cannot be loaded
     */
    private String loadPythonScript(String resourcePath) throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Stops the Python context and executor service when the application shuts down.
     */
    public void close() {
        if (pythonContext != null) {
            pythonContext.close();
        }
        executorService.shutdown();
    }
}
