package com.krickert.search.pipeline.engine.exception;

public class PipelineExecutionException extends PipelineServerError {
    public PipelineExecutionException(String s) {
        super(s);
    }
    public PipelineExecutionException(String s, Throwable t) {
        super(s, t);
    }
}
