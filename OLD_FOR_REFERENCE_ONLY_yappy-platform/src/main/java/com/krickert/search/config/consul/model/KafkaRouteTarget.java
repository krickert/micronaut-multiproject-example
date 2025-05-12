package com.krickert.search.config.consul.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record KafkaRouteTarget(String topic, String targetPipeStepId) {
}
