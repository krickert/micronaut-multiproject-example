package com.krickert.search.config.consul.model;

import com.krickert.search.model.*;

import java.util.Collection;


record InternalPipeStepResponse(boolean success,
                                PipeDoc outputDoc,
                                String newLogs,
                                ErrorData errorData,
                                String pipeline,
                                Collection<String> streamLogs,
                                PipeStream originalPipeStreamRequest,
                                ServiceProcessRequest originalServiceRequest) {
}
