package com.krickert.yappy.modules.testmodule;

import io.micronaut.runtime.Micronaut;
import io.micronaut.core.annotation.Introspected;

@Introspected
public class TestModuleApplication {
    public static void main(String[] args) {
        Micronaut.run(TestModuleApplication.class, args);
    }
}