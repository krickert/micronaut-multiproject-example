package com.krickert.search.config.schema.registry.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchemaVersionData {

    @JsonProperty("globalId")
    private Long globalId;

    @JsonProperty("subject")
    private String subject;

    @JsonProperty("version")
    private Integer version;

    @JsonProperty("schemaContent")
    private String schemaContent;

    @JsonProperty("schemaType")
    private SchemaType schemaType = SchemaType.JSON_SCHEMA;

    @JsonProperty("compatibility")
    private SchemaCompatibility compatibility;

    @JsonProperty("createdAt")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Instant createdAt;

    @JsonProperty("versionDescription")
    private String versionDescription;
}