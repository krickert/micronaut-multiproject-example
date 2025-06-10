package com.krickert.search.pipeline.engine.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class YappyEngineBootstrapperMissingConfigTest {

    private ListAppender<ILoggingEvent> listAppender;
    private Logger bootstrapperLogger;

    @BeforeEach
    void setUpLogCapturing() {
        bootstrapperLogger = (Logger) LoggerFactory.getLogger(YappyEngineBootstrapper.class);
        listAppender = new ListAppender<>();

        ch.qos.logback.classic.LoggerContext loggerContext = (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
        listAppender.setContext(loggerContext);

        listAppender.start();

        // Ensure the logger will process messages at the level you're logging (e.g., ERROR)
        // Setting it explicitly can help during debugging.
        // bootstrapperLogger.setLevel(Level.DEBUG); // Or Level.INFO, Level.ERROR as needed

        bootstrapperLogger.addAppender(listAppender);

        System.out.println(">>>> Logback setup complete for logger: " + bootstrapperLogger.getName());
        System.out.println(">>>> Appenders attached to " + bootstrapperLogger.getName() + ":");
        bootstrapperLogger.iteratorForAppenders().forEachRemaining(app -> System.out.println(">>>>   - " + app.getName() + " (" + app.getClass().getSimpleName() + ")"));
    }


    @AfterEach
    void tearDownLogCapturing() {
        if (bootstrapperLogger != null && listAppender != null) {
            bootstrapperLogger.detachAppender(listAppender);
            listAppender.stop();
        }
    }

    //@Test
    void testStartupWithMissingConsulConfig() {
        // --- Debugging ClassLoader and Class Location ---
        ClassLoader testClassLoader = getClass().getClassLoader();
        System.out.println(">>>> Test ClassLoader: " + testClassLoader);
        try {
            Class<?> bootstrapperClass = testClassLoader.loadClass("com.krickert.search.pipeline.engine.bootstrap.YappyEngineBootstrapper");
            System.out.println(">>>> Successfully loaded YappyEngineBootstrapper class using test ClassLoader.");
            URL location = bootstrapperClass.getProtectionDomain().getCodeSource().getLocation();
            System.out.println(">>>> YappyEngineBootstrapper class location: " + location);
        } catch (ClassNotFoundException e) {
            System.err.println(">>>> ERROR: YappyEngineBootstrapper class NOT found by test ClassLoader!");
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println(">>>> ERROR checking YappyEngineBootstrapper class location: " + e.getMessage());
            e.printStackTrace();
        }
        // --- End Debugging ---


        ApplicationContextBuilder builder = ApplicationContext.builder()
                .packages("com.krickert.search.pipeline.engine.bootstrap") // Verify this package again
                .classLoader(testClassLoader) // <--- EXPLICITLY SET CLASSLOADER
                .deduceEnvironment(false)
                .banner(false);

        // --- Removed problematic debugging line: System.out.println(">>>> Builder's ClassLoader: " + builder.getClassLoader()); ---


        ApplicationContext context = null;
        try {
            context = builder.build(); // Build the context

            // --- Add Bean Presence Check ---
            boolean bootstrapperExists = context.containsBean(YappyEngineBootstrapper.class);
            System.out.println(">>>> Does YappyEngineBootstrapper bean exist in context AFTER build? " + bootstrapperExists);
            // --- End Check ---

            context.start(); // Start the context (this is when events fire)

            // ... rest of your test logic
        } catch (Exception e) {
            System.err.println("Application startup failed as potentially expected: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for startup failures
        } finally {
            if (context != null && context.isRunning()) {
                context.stop();
                context.close();
            } else if (context != null) {
                // If context was built but not started or failed to start
                context.close();
            }
        }

        // Now check the captured logs from YappyEngineBootstrapper
        List<String> capturedLogs = listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // Print captured logs for debugging if the test fails
        if (capturedLogs.stream().noneMatch(log -> log.contains("CRITICAL: Essential Consul configuration"))) {
            System.out.println("Captured logs from YappyEngineBootstrapper:");
            capturedLogs.forEach(System.out::println);
        }

        assertTrue(capturedLogs.stream().anyMatch(log -> log.contains("CRITICAL: Essential Consul configuration")),
                "Should log critical error about missing Consul config");
        assertTrue(capturedLogs.stream().anyMatch(log -> log.contains("Please ensure Consul is configured correctly")),
                "Should provide guidance on fixing Consul config");

        // Verify that the "Consul configuration found" message is NOT present
        assertFalse(capturedLogs.stream().anyMatch(log -> log.contains("Consul configuration found")),
                "Should NOT log that Consul configuration was found");
    }
}