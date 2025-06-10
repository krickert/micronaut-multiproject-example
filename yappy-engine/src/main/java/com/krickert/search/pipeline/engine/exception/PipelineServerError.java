package com.krickert.search.pipeline.engine.exception;

public class PipelineServerError extends RuntimeException {

    public PipelineServerError(String s, Throwable t) {
        super(s, t);
    }

    public PipelineServerError(String s) {
        super(s);
    }
}
