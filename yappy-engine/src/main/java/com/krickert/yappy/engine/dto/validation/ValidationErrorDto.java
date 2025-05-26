package com.krickert.yappy.engine.dto.validation;

public class ValidationErrorDto {

    private String elementId;
    private String elementType; // e.g., "node", "edge", "global"
    private String field;       // Optional, e.g., "type", "source"
    private String message;

    public ValidationErrorDto() {
    }

    public ValidationErrorDto(String message) {
        this.message = message;
        this.elementType = "global"; // Default to global if not specified
    }

    public ValidationErrorDto(String elementId, String elementType, String message) {
        this.elementId = elementId;
        this.elementType = elementType;
        this.message = message;
    }

    public ValidationErrorDto(String elementId, String elementType, String field, String message) {
        this.elementId = elementId;
        this.elementType = elementType;
        this.field = field;
        this.message = message;
    }

    public String getElementId() {
        return elementId;
    }

    public void setElementId(String elementId) {
        this.elementId = elementId;
    }

    public String getElementType() {
        return elementType;
    }

    public void setElementType(String elementType) {
        this.elementType = elementType;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
