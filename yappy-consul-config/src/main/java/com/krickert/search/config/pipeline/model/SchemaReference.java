package com.krickert.search.config.pipeline.model; // Adjusted package

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
// No Micronaut imports

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchemaReference {

    @JsonProperty("subject") // Explicit for clarity, though often not needed if names match
    private String subject;

    @JsonProperty("version")
    private Integer version;
}