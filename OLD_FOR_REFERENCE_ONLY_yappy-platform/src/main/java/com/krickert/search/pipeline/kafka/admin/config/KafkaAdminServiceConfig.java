package com.krickert.search.pipeline.kafka.admin.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("kafka.admin.service")
public class KafkaAdminServiceConfig {

    private Duration requestTimeout = Duration.ofSeconds(60);
    private Duration recreatePollTimeout = Duration.ofSeconds(120);
    private Duration recreatePollInterval = Duration.ofSeconds(5);

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getRecreatePollTimeout() {
        return recreatePollTimeout;
    }

    public void setRecreatePollTimeout(Duration recreatePollTimeout) {
        this.recreatePollTimeout = recreatePollTimeout;
    }

    public Duration getRecreatePollInterval() {
        return recreatePollInterval;
    }

    public void setRecreatePollInterval(Duration recreatePollInterval) {
        this.recreatePollInterval = recreatePollInterval;
    }
}