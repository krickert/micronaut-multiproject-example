package com.krickert.yappy.engine;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

@Controller("/test")
public class TestController {

    @Get("/hello")
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "Hello from TestController";
    }
    
    @Get("/json")
    @Produces(MediaType.APPLICATION_JSON)
    public TestResponse json() {
        return new TestResponse("test", "value");
    }
    
    public static class TestResponse {
        private String key;
        private String value;
        
        public TestResponse() {}
        
        public TestResponse(String key, String value) {
            this.key = key;
            this.value = value;
        }
        
        public String getKey() {
            return key;
        }
        
        public void setKey(String key) {
            this.key = key;
        }
        
        public String getValue() {
            return value;
        }
        
        public void setValue(String value) {
            this.value = value;
        }
    }
}