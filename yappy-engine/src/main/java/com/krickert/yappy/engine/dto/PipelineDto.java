package com.krickert.yappy.engine.dto;

import java.util.Map;
import java.util.Objects;

public class PipelineDto {

    private String id;
    private String name;
    private String description;
    private Map<String, Object> structure;

    public PipelineDto() {
    }

    public PipelineDto(String id, String name, String description, Map<String, Object> structure) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.structure = structure;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getStructure() {
        return structure;
    }

    public void setStructure(Map<String, Object> structure) {
        this.structure = structure;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PipelineDto that = (PipelineDto) o;
        return Objects.equals(id, that.id) && Objects.equals(name, that.name) && Objects.equals(description, that.description) && Objects.equals(structure, that.structure);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, description, structure);
    }

    @Override
    public String toString() {
        return "PipelineDto{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", structure=" + structure +
                '}';
    }
}
