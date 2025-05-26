package com.krickert.yappy.wikicrawler.controller.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// Using @Introspected for automatic serialization/deserialization by Micronaut
@Introspected
@Schema(description = "Request payload for initiating a Wikipedia dump crawl.")
@Serdeable
public class InitiateCrawlRequest {
    @Schema(description = "Full URL of the Wikipedia dump file (e.g., .xml.bz2).", required = true, example = "https://dumps.wikimedia.org/enwiki/latest/enwiki-latest-pages-articles.xml.bz2")
    private String url;
    @Schema(description = "Target local file name for the dump.", required = true, example = "enwiki-latest-pages-articles.xml.bz2")
    private String fileName;
    @Schema(description = "Date of the dump file in YYYYMMDD format.", required = true, example = "20230101")
    private String fileDumpDate; // "YYYYMMDD"
    @Schema(description = "Checksum value for verifying file integrity.", required = true)
    private String errorCheckValue;
    @Schema(description = "Type of checksum algorithm used. Allowed values: MD5, SHA1, SHA256.", required = true, type = "string", example = "MD5")
    private String errorCheckType; // "MD5", "SHA1", "SHA256"
    @Schema(description = "Optional list of expected file names within the dump (for multi-stream dumps).")
    private List<String> expectedFilesInDump;

    // Getters and Setters... (no change to method bodies)
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileDumpDate() { return fileDumpDate; }
    public void setFileDumpDate(String fileDumpDate) { this.fileDumpDate = fileDumpDate; }
    public String getErrorCheckValue() { return errorCheckValue; }
    public void setErrorCheckValue(String errorCheckValue) { this.errorCheckValue = errorCheckValue; }
    public String getErrorCheckType() { return errorCheckType; }
    public void setErrorCheckType(String errorCheckType) { this.errorCheckType = errorCheckType; }
    public List<String> getExpectedFilesInDump() { return expectedFilesInDump; }
    public void setExpectedFilesInDump(List<String> expectedFilesInDump) { this.expectedFilesInDump = expectedFilesInDump; }
}
