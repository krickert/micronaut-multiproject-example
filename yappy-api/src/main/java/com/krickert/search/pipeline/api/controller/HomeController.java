package com.krickert.search.pipeline.api.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.swagger.v3.oas.annotations.Hidden;

import java.net.URI;

/**
 * Home controller that redirects to OpenAPI Explorer.
 */
@Controller("/")
@Hidden // Don't include this in OpenAPI spec
public class HomeController {

    @Get
    public HttpResponse<?> index() {
        return HttpResponse.redirect(URI.create("/openapi-explorer/index.html"));
    }
}