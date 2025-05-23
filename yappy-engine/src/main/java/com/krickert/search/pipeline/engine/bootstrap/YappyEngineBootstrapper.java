package com.krickert.search.pipeline.engine.bootstrap;// In YappyEngineBootstrapper.java

import io.micronaut.context.env.Environment; // Make sure this import is present
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

    public YappyEngineBootstrapper(Environment environment) {
        this.environment = environment;
        // VERY LOUD DEBUGGING:
        System.out.println("<<<<< YappyEngineBootstrapper CONSTRUCTOR CALLED >>>>>");
        LOG.info("YappyEngineBootstrapper CONSTRUCTOR CALLED. Classloader: {}", getClass().getClassLoader());
    }

    @Override
    public void onApplicationEvent(ApplicationStartupEvent event) {
        // VERY LOUD DEBUGGING:
        System.out.println("<<<<< YappyEngineBootstrapper.onApplicationEvent ENTERED >>>>>");
        LOG.info("YappyEngineBootstrapper.onApplicationEvent ENTERED");
        LOG.info("YAPPY Engine is starting. Performing initial Consul configuration check...");

        Optional<String> consulHost = environment.getProperty("consul.client.host", String.class);
        Optional<Integer> consulPort = environment.getProperty("consul.client.port", Integer.class);

        if (consulHost.isPresent() && !consulHost.get().isBlank() && consulPort.isPresent() && consulPort.get() > 0) {
            System.out.println("<<<<< YappyEngineBootstrapper: Consul Config FOUND >>>>>");
            LOG.info("Consul configuration found (Host: '{}', Port: {}). Proceeding with normal YAPPY Engine startup.",
                    consulHost.get(), consulPort.get());
        } else {
            System.out.println("<<<<< YappyEngineBootstrapper: Consul Config MISSING >>>>>");
            LOG.error("--------------------------------------------------------------------------------------");
            LOG.error("CRITICAL: Essential Consul configuration (consul.client.host and/or consul.client.port) is MISSING or INVALID.");
            // ... rest of your error logging
            LOG.error("--------------------------------------------------------------------------------------");
        }
    }
}