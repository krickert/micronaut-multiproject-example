package com.krickert.search.pipeline.engine.exception;

public class GrpcEngineException extends RuntimeException {
    public GrpcEngineException(String s) {
        super(s);
    }
    public GrpcEngineException(String s, Throwable throwable) {
        super(s, throwable);
    }

}
