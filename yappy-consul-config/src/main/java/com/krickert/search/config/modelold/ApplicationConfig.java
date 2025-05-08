//package com.krickert.search.config.modelold;
//
//import io.micronaut.context.annotation.Value;
//import io.micronaut.runtime.context.scope.Refreshable;
//import io.micronaut.serde.annotation.Serdeable;
//import jakarta.inject.Singleton;
//import lombok.Data;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
///**
// * Application configuration POJO.
// * This class represents the application-specific configuration settings.
// * It is a singleton and is refreshable when configuration changes.
// */
//@Singleton
//@Refreshable
//@Data
//@Serdeable
//public class ApplicationConfig {
//    private static final Logger LOG = LoggerFactory.getLogger(ApplicationConfig.class);
//
//    /**
//     * The name of the application.
//     */
//    @Value("${micronaut.application.name}")
//    private String applicationName;
//
//    /**
//     * Flag indicating whether the configuration has been initialized.
//     */
//    private boolean enabled = false;
//
//    /**
//     * Default constructor.
//     */
//    public ApplicationConfig() {
//        LOG.info("Creating ApplicationConfig singleton");
//    }
//
//    /**
//     * Sets the enabled flag to true.
//     * This method is called after the configuration has been seeded.
//     */
//    public void markAsEnabled() {
//        this.enabled = true;
//        LOG.info("ApplicationConfig marked as enabled");
//    }
//
//    public void reset() {
//        this.enabled = false;
//    }
//}