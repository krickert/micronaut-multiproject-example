package com.krickert.yappy.engine.dto.validation;

import java.util.List;
import java.util.ArrayList;

public class PipelineValidationResponseDto {

    private boolean isValid;
    private List<ValidationErrorDto> errors;
    private List<String> messages;

    public PipelineValidationResponseDto() {
        this.errors = new ArrayList<>();
        this.messages = new ArrayList<>();
    }

    public PipelineValidationResponseDto(boolean isValid, List<ValidationErrorDto> errors, List<String> messages) {
        this.isValid = isValid;
        this.errors = errors != null ? errors : new ArrayList<>();
        this.messages = messages != null ? messages : new ArrayList<>();
    }

    public boolean isValid() {
        return isValid;
    }

    public void setValid(boolean valid) {
        isValid = valid;
    }

    public List<ValidationErrorDto> getErrors() {
        return errors;
    }

    public void setErrors(List<ValidationErrorDto> errors) {
        this.errors = errors;
    }

    public void addError(ValidationErrorDto error) {
        if (this.errors == null) {
            this.errors = new ArrayList<>();
        }
        this.errors.add(error);
    }

    public List<String> getMessages() {
        return messages;
    }

    public void setMessages(List<String> messages) {
        this.messages = messages;
    }
    
    public void addMessage(String message) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        this.messages.add(message);
    }
}
