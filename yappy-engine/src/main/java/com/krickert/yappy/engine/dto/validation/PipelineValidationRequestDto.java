package com.krickert.yappy.engine.dto.validation;

import java.util.List;
import java.util.Map;

public class PipelineValidationRequestDto {

    private String name; // Optional, not used in current validation but good for future
    private StructureDto structure;

    public PipelineValidationRequestDto() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public StructureDto getStructure() {
        return structure;
    }

    public void setStructure(StructureDto structure) {
        this.structure = structure;
    }

    // Inner class for structure to match the expected JSON payload
    public static class StructureDto {
        // Nodes and Edges are lists of maps, as sent by the frontend
        // and as implicitly defined in PipelineDto's structure Map<String, Object>
        private List<Map<String, Object>> nodes;
        private List<Map<String, Object>> edges;

        public StructureDto() {
        }

        public List<Map<String, Object>> getNodes() {
            return nodes;
        }

        public void setNodes(List<Map<String, Object>> nodes) {
            this.nodes = nodes;
        }

        public List<Map<String, Object>> getEdges() {
            return edges;
        }

        public void setEdges(List<Map<String, Object>> edges) {
            this.edges = edges;
        }
    }
}
