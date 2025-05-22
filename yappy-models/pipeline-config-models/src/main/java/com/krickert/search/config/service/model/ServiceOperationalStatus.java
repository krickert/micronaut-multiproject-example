package com.krickert.search.config.service.model;

import java.util.Optional;

/**
 * Defines the primary operational states a service instance or aggregated service can be in.
 * Each status includes a numeric code for potential gRPC/API equivalence and a human-readable description.
 */
public enum ServiceOperationalStatus {
    /**
     * The status of the service is unknown. This is often the initial state before any information is gathered.
     */
    UNKNOWN(0, "The status of the service is unknown or not yet determined."),

    /**
     * The service is defined in the configuration but not yet actively managed or monitored.
     * It might be awaiting initial deployment or discovery.
     */
    DEFINED(1, "Service is defined in configuration but not yet actively managed or monitored."),

    /**
     * The service instance is currently initializing, starting up, or performing initial setup tasks.
     * It is not yet ready to serve traffic or report a definitive health status.
     */
    INITIALIZING(2, "Service instance is currently initializing or starting up."),

    /**
     * The service instance has been started/discovered and is awaiting successful health registration in Consul (or other discovery service).
     * It's not yet considered healthy or part of the active pool.
     */
    AWAITING_HEALTHY_REGISTRATION(3, "Service instance is awaiting successful health registration."),

    /**
     * The service (or all its instances) is registered, healthy, and operating as expected.
     * It is fully capable of handling requests.
     */
    ACTIVE_HEALTHY(10, "Service is active, healthy, and operating as expected."),

    /**
     * The service is active but is currently proxying requests to another instance (local or remote)
     * due to issues with its own module or local dependencies.
     */
    ACTIVE_PROXYING(11, "Service is active but proxying requests to another instance."),

    /**
     * The service is active and operational but experiencing some non-critical issues.
     * It might have reduced capacity, increased latency, or some features might be impaired, but core functionality is available.
     */
    DEGRADED_OPERATIONAL(20, "Service is operational but experiencing non-critical issues (e.g., reduced capacity, increased latency)."),

    /**
     * The service has a configuration error that prevents it from starting or operating correctly.
     * This can include issues like bad schema for its configuration, invalid pipeline definitions, or missing dependencies.
     */
    CONFIGURATION_ERROR(30, "Service has a configuration error (e.g., bad schema, invalid settings)."),

    /**
     * The service (or all its instances) is not reachable or not responding to health checks.
     * It is not capable of handling requests.
     */
    UNAVAILABLE(40, "Service is unavailable, unreachable, or failing health checks."),

    /**
     * The service is currently undergoing an upgrade or maintenance process.
     * It might be temporarily unavailable or operating in a limited capacity.
     */
    UPGRADING(50, "Service is currently undergoing an upgrade or maintenance."),

    /**
     * The service has been intentionally stopped and is not expected to be operational.
     */
    STOPPED(60, "Service has been intentionally stopped.");

    private final int numericCode;
    private final String description;

    ServiceOperationalStatus(int numericCode, String description) {
        this.numericCode = numericCode;
        this.description = description;
    }

    /**
     * Gets the numeric code associated with the operational status.
     * This can be useful for gRPC/API representations or for compact storage.
     *
     * @return The numeric code.
     */
    public int getNumericCode() {
        return numericCode;
    }

    /**
     * Gets the human-readable description of the operational status.
     *
     * @return The description.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Finds a ServiceOperationalStatus by its numeric code.
     *
     * @param code The numeric code to search for.
     * @return An Optional containing the matching ServiceOperationalStatus, or Optional.empty() if not found.
     */
    public static Optional<ServiceOperationalStatus> fromNumericCode(int code) {
        for (ServiceOperationalStatus status : values()) {
            if (status.getNumericCode() == code) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}