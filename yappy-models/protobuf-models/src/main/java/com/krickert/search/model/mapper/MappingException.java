// src/main/java/com/krickert/search/model/MappingException.java
// No changes from the previous correct version or the original file's intent.
package com.krickert.search.model.mapper;

/**
 * Custom exceptions for errors during the mapping process.
 */
public class MappingException extends Exception {
    private final String failedRule;

    public MappingException(String message, Throwable cause, String failedRule) {
        super(message, cause);
        this.failedRule = failedRule;
    }

    public MappingException(String message, String failedRule) {
        super(message);
        this.failedRule = failedRule;
    }

    public MappingException(String message, Throwable cause) {
        super(message, cause);
        this.failedRule = null;
    }

    public MappingException(String s) {
        super(s);
        this.failedRule = null;
    }


    public String getFailedRule() {
        return failedRule;
    }

    @Override
    public String getMessage() {
        String baseMessage = super.getMessage();
        if (failedRule != null && !failedRule.isEmpty()) {
            return baseMessage + " (Rule: '" + failedRule + "')";
        }
        return baseMessage;
    }
}