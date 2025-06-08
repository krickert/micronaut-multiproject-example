package com.krickert.search.pipeline;

import io.micronaut.http.annotation.*;

@Controller("/exampleApplication")
public class ExampleApplicationController {

    @Get(uri="/", produces="text/plain")
    public String index() {
        return "Example Response";
    }
}