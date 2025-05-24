package com.krickert.yappy.modules.opensearchsink;

/**
 * Simple data class for indexing in OpenSearch.
 */
public class IndexData {
    private String field;
    private String value;

    /**
     * Default constructor for Jackson deserialization.
     */
    public IndexData() {
    }

    /**
     * Constructor with field and value.
     *
     * @param field the field name
     * @param value the field value
     */
    public IndexData(String field, String value) {
        this.field = field;
        this.value = value;
    }

    /**
     * Get the field name.
     *
     * @return the field name
     */
    public String getField() {
        return field;
    }

    /**
     * Set the field name.
     *
     * @param field the field name
     */
    public void setField(String field) {
        this.field = field;
    }

    /**
     * Get the field value.
     *
     * @return the field value
     */
    public String getValue() {
        return value;
    }

    /**
     * Set the field value.
     *
     * @param value the field value
     */
    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "IndexData{" +
                "field='" + field + '\'' +
                ", value='" + value + '\'' +
                '}';
    }
}