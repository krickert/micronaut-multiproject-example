package com.krickert.search.pipeline.engine.bootstrap;

import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.event.ApplicationStartupEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

@Singleton
public class YappyEngineBootstrapper implements ApplicationEventListener<ApplicationStartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(YappyEngineBootstrapper.class);

    private final Environment environment;

    // ApplicationContext is not strictly needed for this revised, simpler bootstrapper logic.
    // If it were needed for other bootstrap tasks later, it could be re-added.
    public YappyEngineBootstrapper(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationStartupEvent event) {
        LOG.info("YAPPY Engine is starting. Performing Consul configuration check...");

        // Define the essential Consul properties to check
        Optional<String> consulHost = environment.getProperty("consul.client.host", String.class);
        Optional<Integer> consulPort = environment.getProperty("consul.client.port", Integer.class);
        // You might also check for consul.client.config.enabled or consul.client.registration.enabled
        // if these being true without host/port is also an invalid state.

        if (consulHost.isPresent() && !consulHost.get().isBlank() && consulPort.isPresent() && consulPort.get() > 0) {
            LOG.info("Consul configuration found (Host: '{}', Port: {}). Proceeding with normal YAPPY Engine startup.",
                    consulHost.get(), consulPort.get());
            // Normal startup will proceed. Micronaut's Consul client (if enabled for discovery/config)
            // will attempt to initialize and connect based on these properties.
            // If the connection to Consul fails at that stage, Micronaut's default error handling
            // for its Consul features will apply.
        } else {
            // Main application does NOT enter a setup mode. It signals a configuration error.
            LOG.error("--------------------------------------------------------------------------------------");
            LOG.error("CRITICAL: Essential Consul configuration (consul.client.host and/or consul.client.port) is MISSING or INVALID.");
            LOG.error("The YAPPY Engine requires valid Consul connection details to start its core services.");
            LOG.error("Please ensure Consul is configured correctly in your application properties, environment variables,");
            LOG.error("or via the YAPPY Engine bootstrap configuration file (e.g., ~/.yappy/engine-bootstrap.properties).");
            LOG.error("If this is a new installation or Consul details have changed, you may need to run the YAPPY Setup Utility.");
            LOG.error("The application may not function correctly or may fail to start fully.");
            LOG.error("--------------------------------------------------------------------------------------");

            // At this point, you have a few choices for how the application behaves:
            // 1. Do nothing further here: Let Micronaut continue. Beans that require a
            //    properly initialized Consul client (e.g., for service discovery or
            //    distributed config) will likely fail to initialize, and the application
            //    might not become fully healthy or might even fail to start completely.
            //    This is often acceptable as the logs clearly indicate the root cause.
            //
            // 2. Forcefully exit (more assertive):
            //    if (isProductionEnvironment()) { // Or based on some other flag
            //        LOG.error("Exiting application due to missing critical Consul configuration.");
            //        System.exit(1); // Or applicationContext.stop() if you want graceful shutdown of what has started
            //    }
            //    This ensures the application doesn't run in a broken state.
            //
            // For now, just logging the error is a good first step. The subsequent behavior
            // will depend on how critical Micronaut's Consul integrations are to your app's startup.
        }
    }

    // Helper method example if you choose to exit based on environment
    // private boolean isProductionEnvironment() {
    // return environment.getActiveNames().contains(io.micronaut.context.env.Environment.PRODUCTION);
    // }
}