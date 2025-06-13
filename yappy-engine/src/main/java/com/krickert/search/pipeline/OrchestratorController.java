package com.krickert.search.pipeline;

import io.micronaut.http.annotation.*;

@Controller("/orchestrator")
public class OrchestratorController {

    @Get(uri="/", produces="text/plain")
    public String index() {
        return "Example Response";
    }
}