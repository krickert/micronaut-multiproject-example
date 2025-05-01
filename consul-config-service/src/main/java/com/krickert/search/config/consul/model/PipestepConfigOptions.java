package com.krickert.search.config.consul.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.HashMap;

@Introspected
@Serdeable
public class PipestepConfigOptions extends HashMap<String, String> {
}
