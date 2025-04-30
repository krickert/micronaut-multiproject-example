package com.krickert.search.config.consul.service;

import com.ecwid.consul.v1.ConsulClient;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Value;

public class ConsulClientFactory {

    @Bean(preDestroy = "close")
    ConsulClient consulClient(@Value("${consul.host}") String host, @Value("${consul.port}") int port) {
        return new ConsulClient(host, port);
    }
}
