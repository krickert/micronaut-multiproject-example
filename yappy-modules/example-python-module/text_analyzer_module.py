#!/usr/bin/env python3
"""
Example Yappy Module: Text Analyzer
This module demonstrates how to create a Yappy pipeline module in Python.
It analyzes text and adds metadata like word count, sentence count, etc.
"""

import grpc
from concurrent import futures
import logging
import json
import re
import os
import sys
import time
from typing import Dict, Any

# These will be generated from the proto files
import yappy_core_types_pb2 as core_types
import pipe_step_processor_service_pb2 as service_pb2
import pipe_step_processor_service_pb2_grpc as service_grpc
from google.protobuf import empty_pb2, struct_pb2

# Set up logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

class TextAnalyzerModule(service_grpc.PipeStepProcessorServicer):
    """
    Example module that analyzes text and adds metadata.
    This demonstrates:
    - Basic gRPC service implementation
    - Configuration handling
    - Test mode support
    - Proper error handling
    """
    
    def __init__(self):
        self.module_name = "text-analyzer"
        self.version = "1.0.0"
        logger.info(f"Initializing {self.module_name} v{self.version}")
    
    def GetServiceRegistration(self, request, context):
        """
        Returns metadata about this module.
        This is called by the engine to understand what this module provides.
        """
        logger.info("GetServiceRegistration called")
        
        # Define the configuration schema for this module
        schema = {
            "$schema": "http://json-schema.org/draft-07/schema#",
            "type": "object",
            "properties": {
                "analyze_sentences": {
                    "type": "boolean",
                    "description": "Whether to count sentences",
                    "default": True
                },
                "analyze_words": {
                    "type": "boolean",
                    "description": "Whether to count words",
                    "default": True
                },
                "extract_keywords": {
                    "type": "boolean",
                    "description": "Whether to extract keywords",
                    "default": False
                },
                "min_keyword_length": {
                    "type": "integer",
                    "description": "Minimum length for keyword extraction",
                    "default": 4,
                    "minimum": 1
                },
                "add_reading_time": {
                    "type": "boolean",
                    "description": "Whether to calculate estimated reading time",
                    "default": True
                },
                "words_per_minute": {
                    "type": "integer",
                    "description": "Average reading speed for time calculation",
                    "default": 200,
                    "minimum": 100,
                    "maximum": 500
                }
            }
        }
        
        # Build the response
        metadata = service_pb2.ServiceMetadata(
            pipe_step_name=self.module_name,
            context_params={
                "module_version": self.version,
                "module_language": "python",
                "description": "Analyzes text and adds metadata like word count, sentences, reading time",
                "author": "Example Developer",
                "json_config_schema": json.dumps(schema, indent=2)
            }
        )
        
        return metadata
    
    def ProcessData(self, request, context):
        """
        Process a document according to configuration.
        This is the main processing method called for each document.
        """
        logger.info(f"ProcessData called for document: {request.document.id}")
        
        try:
            # Check if this is a test request
            is_test = (request.metadata.context_params.get("_test_mode") == "true" or
                      request.document.id.startswith("test-doc-"))
            
            if is_test:
                logger.info("Running in test mode")
                return self._handle_test_request(request)
            
            # Extract configuration
            config = self._extract_config(request)
            
            # Process the document
            processed_doc = self._process_document(request.document, config)
            
            # Build response
            response = service_pb2.ProcessResponse(
                success=True,
                output_doc=processed_doc,
                processor_logs=[
                    f"Successfully analyzed document {request.document.id}",
                    f"Added {len(processed_doc.custom_data.fields)} metadata fields"
                ]
            )
            
            return response
            
        except Exception as e:
            logger.error(f"Error processing document {request.document.id}: {str(e)}")
            
            # Return error response
            error_details = struct_pb2.Struct()
            error_details.update({
                "error_type": type(e).__name__,
                "error_message": str(e)
            })
            
            return service_pb2.ProcessResponse(
                success=False,
                processor_logs=[
                    f"Failed to process document: {str(e)}"
                ],
                error_details=error_details
            )
    
    def _handle_test_request(self, request):
        """Handle test mode requests"""
        # Minimal processing for test mode
        response = service_pb2.ProcessResponse(
            success=True,
            processor_logs=[
                "[TEST] Module validation successful",
                "[TEST] Configuration parsed successfully" if request.config.custom_json_config else "[TEST] No configuration provided"
            ]
        )
        return response
    
    def _extract_config(self, request) -> Dict[str, Any]:
        """Extract and validate configuration from request"""
        config = {
            "analyze_sentences": True,
            "analyze_words": True,
            "extract_keywords": False,
            "min_keyword_length": 4,
            "add_reading_time": True,
            "words_per_minute": 200
        }
        
        if request.config and request.config.custom_json_config:
            # Merge with provided configuration
            custom_config = struct_pb2_to_dict(request.config.custom_json_config)
            config.update(custom_config)
            logger.info(f"Using configuration: {config}")
        
        return config
    
    def _process_document(self, doc, config):
        """Process the document and add analysis metadata"""
        # Create a copy of the document
        output_doc = core_types.PipeDoc()
        output_doc.CopyFrom(doc)
        
        # Get the text to analyze
        text = doc.body if doc.body else ""
        if doc.title:
            text = doc.title + " " + text
        
        # Initialize metadata
        metadata = {}
        
        # Word count analysis
        if config["analyze_words"]:
            words = text.split()
            metadata["word_count"] = len(words)
            metadata["character_count"] = len(text)
            metadata["character_count_no_spaces"] = len(text.replace(" ", ""))
        
        # Sentence count analysis
        if config["analyze_sentences"]:
            # Simple sentence splitting (could be improved with NLP)
            sentences = re.split(r'[.!?]+', text)
            sentences = [s.strip() for s in sentences if s.strip()]
            metadata["sentence_count"] = len(sentences)
            
            if len(sentences) > 0 and "word_count" in metadata:
                metadata["avg_words_per_sentence"] = metadata["word_count"] / len(sentences)
        
        # Extract keywords
        if config["extract_keywords"]:
            keywords = self._extract_keywords(text, config["min_keyword_length"])
            metadata["extracted_keywords"] = keywords
            
            # Add keywords to the document
            for keyword in keywords[:10]:  # Top 10 keywords
                output_doc.keywords.append(keyword)
        
        # Reading time estimation
        if config["add_reading_time"] and "word_count" in metadata:
            reading_time_minutes = metadata["word_count"] / config["words_per_minute"]
            metadata["estimated_reading_time_minutes"] = round(reading_time_minutes, 1)
            
            # Human-readable format
            if reading_time_minutes < 1:
                metadata["reading_time_display"] = "Less than 1 minute"
            elif reading_time_minutes < 2:
                metadata["reading_time_display"] = "1 minute"
            else:
                metadata["reading_time_display"] = f"{int(reading_time_minutes)} minutes"
        
        # Add metadata to document
        if not output_doc.custom_data:
            output_doc.custom_data.CopyFrom(struct_pb2.Struct())
        
        # Add our analysis under a nested structure
        analysis_data = struct_pb2.Struct()
        analysis_data.update({"text_analysis": metadata})
        output_doc.custom_data.update(dict_to_struct_pb2({"text_analysis": metadata}))
        
        # Add processing timestamp
        output_doc.processed_date.GetCurrentTime()
        
        logger.info(f"Document {doc.id} analyzed: {metadata}")
        return output_doc
    
    def _extract_keywords(self, text, min_length):
        """Simple keyword extraction (could be enhanced with NLP)"""
        # Remove punctuation and convert to lowercase
        words = re.findall(r'\b\w+\b', text.lower())
        
        # Filter by length
        words = [w for w in words if len(w) >= min_length]
        
        # Count frequency
        word_freq = {}
        for word in words:
            word_freq[word] = word_freq.get(word, 0) + 1
        
        # Sort by frequency
        sorted_words = sorted(word_freq.items(), key=lambda x: x[1], reverse=True)
        
        # Return top keywords
        return [word for word, freq in sorted_words[:20]]

def struct_pb2_to_dict(struct):
    """Convert protobuf Struct to Python dict"""
    return json.loads(json.dumps(dict(struct.fields)))

def dict_to_struct_pb2(d):
    """Convert Python dict to protobuf Struct"""
    struct = struct_pb2.Struct()
    struct.update(d)
    return struct

def serve():
    """Start the gRPC server"""
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    service_grpc.add_PipeStepProcessorServicer_to_server(
        TextAnalyzerModule(), server
    )
    
    port = os.environ.get('GRPC_PORT', '50051')
    server.add_insecure_port(f'[::]:{port}')
    server.start()
    
    logger.info(f"Text Analyzer Module started on port {port}")
    logger.info("Waiting for requests...")
    
    # Register with Consul if enabled
    if os.environ.get('CONSUL_ENABLED', 'false').lower() == 'true':
        register_with_consul(port)
    
    try:
        server.wait_for_termination()
    except KeyboardInterrupt:
        logger.info("Shutting down...")
        server.stop(0)

def register_with_consul(port):
    """Register this module with Consul for discovery"""
    try:
        import consul
        
        consul_host = os.environ.get('CONSUL_HOST', 'localhost')
        consul_port = int(os.environ.get('CONSUL_PORT', '8500'))
        
        c = consul.Consul(host=consul_host, port=consul_port)
        
        # Register service
        c.agent.service.register(
            name='text-analyzer-module',
            service_id=f'text-analyzer-module-{port}',
            port=int(port),
            tags=['yappy-module', 'text-analysis', 'python'],
            check=consul.Check.tcp('localhost', int(port), interval='10s')
        )
        
        logger.info(f"Registered with Consul at {consul_host}:{consul_port}")
        
    except Exception as e:
        logger.warning(f"Could not register with Consul: {e}")

if __name__ == '__main__':
    serve()