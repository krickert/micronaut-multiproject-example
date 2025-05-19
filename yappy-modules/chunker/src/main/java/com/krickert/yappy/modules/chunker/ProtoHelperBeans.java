package com.krickert.yappy.modules.chunker;

import com.krickert.search.model.mapper.PathResolver;
import com.krickert.search.model.mapper.ValueHandler;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;

@Factory
public class ProtoHelperBeans {

    @Bean
    PathResolver pathResolver() {
        return new PathResolver();
    }

    @Bean
    ValueHandler valueHandler() {
        return new ValueHandler(pathResolver());
    }
}
