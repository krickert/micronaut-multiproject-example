package com.krickert.search.pipeline.service;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeResponse;
import lombok.Data;

@Data
public class PipeServiceDto {
    PipeResponse response;
    PipeDoc pipeDoc;
}
