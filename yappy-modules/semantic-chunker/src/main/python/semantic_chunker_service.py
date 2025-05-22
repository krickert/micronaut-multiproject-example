"""
Semantic Chunker gRPC Service.

This module implements the PipeStepProcessor gRPC service for semantic chunking.
"""

import os
import sys
import json
import grpc
import logging
import argparse
import uuid
import socket
from concurrent import futures
from typing import Dict, Any, Optional
import time

# Add the generated directory to the Python path
sys.path.append(os.path.join(os.path.dirname(__file__), "generated"))

# Import generated gRPC code
# These imports will be available after running the generatePythonProto Gradle task
try:
    from generated import pipe_step_processor_service_pb2 as processor_pb2
    from generated import pipe_step_processor_service_pb2_grpc as processor_pb2_grpc
    from generated import yappy_core_types_pb2 as core_pb2
    from generated import yappy_service_registration_pb2 as registration_pb2
    from generated import yappy_service_registration_pb2_grpc as registration_pb2_grpc
except ImportError:
    print("Error: Generated gRPC code not found. Run './gradlew generatePythonProto' first.")
    sys.exit(1)

# Import the semantic chunker implementation
from semantic_chunker.chunker import SemanticChunker

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

class SemanticChunkerServicer(processor_pb2_grpc.PipeStepProcessorServicer):
    """
    Implementation of the PipeStepProcessor gRPC service for semantic chunking.
    """
    
    def __init__(self):
        """Initialize the servicer with a default semantic chunker."""
        self.chunker = SemanticChunker()
        logger.info("Semantic Chunker Service initialized")
    
    def _parse_config(self, config_struct: Any) -> Dict[str, Any]:
        """
        Parse the configuration from the gRPC request.
        
        Args:
            config_struct: The custom_json_config from the request
            
        Returns:
            A dictionary containing the parsed configuration
        """
        if not config_struct:
            return {}
            
        try:
            # Convert the Struct to a Python dictionary
            config = {}
            
            # Extract chunker configuration
            fields = config_struct.fields
            
            if "model_name" in fields:
                config["model_name"] = fields["model_name"].string_value
                
            if "chunk_size" in fields:
                config["chunk_size"] = int(fields["chunk_size"].number_value)
                
            if "chunk_overlap" in fields:
                config["chunk_overlap"] = int(fields["chunk_overlap"].number_value)
                
            if "similarity_threshold" in fields:
                config["similarity_threshold"] = fields["similarity_threshold"].number_value
                
            return config
        except Exception as e:
            logger.error(f"Error parsing configuration: {e}")
            return {}
    
    def _create_semantic_processing_result(self, 
                                          source_field_name: str, 
                                          chunker_result: Dict[str, Any],
                                          config: Dict[str, Any]) -> core_pb2.SemanticProcessingResult:
        """
        Create a SemanticProcessingResult from the chunker result.
        
        Args:
            source_field_name: The name of the field that was processed
            chunker_result: The result from the semantic chunker
            config: The configuration used for processing
            
        Returns:
            A SemanticProcessingResult protobuf message
        """
        result = core_pb2.SemanticProcessingResult()
        result.result_id = str(uuid.uuid4())
        result.source_field_name = source_field_name
        
        # Set configuration IDs
        result.chunk_config_id = f"semantic-chunker-{config.get('chunk_size', 512)}-{config.get('chunk_overlap', 50)}"
        result.embedding_config_id = config.get("model_name", "all-MiniLM-L6-v2")
        
        # Set result set name
        result.result_set_name = f"{source_field_name}_chunks_{result.embedding_config_id.replace('-', '_')}"
        
        # Add chunks
        for i, chunk_data in enumerate(chunker_result["chunks"]):
            chunk = result.chunks.add()
            chunk.chunk_id = chunk_data["chunk_id"]
            chunk.chunk_number = i
            
            # Set embedding info
            chunk.embedding_info.text_content = chunk_data["text_content"]
            
            # Add vector values
            for value in chunk_data["vector"]:
                chunk.embedding_info.vector.append(float(value))
                
            # Set offsets if available
            if "original_char_start_offset" in chunk_data:
                chunk.embedding_info.original_char_start_offset = chunk_data["original_char_start_offset"]
                
            if "original_char_end_offset" in chunk_data:
                chunk.embedding_info.original_char_end_offset = chunk_data["original_char_end_offset"]
                
            # Set group ID if available
            for group in chunker_result.get("chunk_groups", []):
                if i in group.get("chunk_ids", []):
                    chunk.embedding_info.chunk_group_id = group["chunk_group_id"]
                    break
        
        return result
    
    def ProcessData(self, request, context):
        """
        Process a document by applying semantic chunking.
        
        Args:
            request: The ProcessRequest containing the document and configuration
            context: The gRPC context
            
        Returns:
            A ProcessResponse containing the processed document
        """
        logger.info("Received ProcessData request")
        
        try:
            # Parse the configuration
            config = self._parse_config(request.config.custom_json_config)
            logger.info(f"Using configuration: {config}")
            
            # Get the document
            doc = request.document
            
            # Check if we have a body to process
            if not doc.body:
                logger.warning("Document has no body to process")
                return processor_pb2.ProcessResponse(
                    success=False,
                    processor_logs=["Document has no body to process"]
                )
            
            # Process the document body
            chunker_result = self.chunker.process_text(doc.body, config)
            
            # Create a copy of the input document for the output
            output_doc = core_pb2.PipeDoc()
            output_doc.CopyFrom(doc)
            
            # Add the semantic processing result to the document
            semantic_result = self._create_semantic_processing_result("body", chunker_result, config)
            output_doc.semantic_results.append(semantic_result)
            
            # Return the response
            return processor_pb2.ProcessResponse(
                success=True,
                output_doc=output_doc,
                processor_logs=[f"Successfully processed document. Created {len(semantic_result.chunks)} chunks."]
            )
            
        except Exception as e:
            logger.error(f"Error processing document: {e}", exc_info=True)
            return processor_pb2.ProcessResponse(
                success=False,
                processor_logs=[f"Error processing document: {str(e)}"]
            )

def register_with_yappy(implementation_id: str, host: str, port: int, registration_service_address: str):
    """
    Register this module with the YAPPY platform.
    
    Args:
        implementation_id: The implementation ID for this module
        host: The host where this service is running
        port: The port where this service is running
        registration_service_address: The address of the YAPPY registration service
        
    Returns:
        True if registration was successful, False otherwise
    """
    logger.info(f"Registering with YAPPY at {registration_service_address}")
    
    try:
        # Get the module's configuration
        instance_custom_config = {
            "model_name": "all-MiniLM-L6-v2",
            "chunk_size": 512,
            "chunk_overlap": 50,
            "similarity_threshold": 0.75
        }
        
        # Convert to JSON string with sorted keys
        instance_custom_config_json = json.dumps(instance_custom_config, sort_keys=True)
        
        # Create a gRPC channel to the registration service
        with grpc.insecure_channel(registration_service_address) as channel:
            stub = registration_pb2_grpc.YappyModuleRegistrationServiceStub(channel)
            
            # Create the registration request
            request = registration_pb2.RegisterModuleRequest(
                implementation_id=implementation_id,
                instance_service_name=f"{implementation_id}-instance-{uuid.uuid4().hex[:8]}",
                host=host,
                port=port,
                health_check_type=registration_pb2.HealthCheckType.HTTP,
                health_check_endpoint="/health",
                instance_custom_config_json=instance_custom_config_json,
                module_software_version="1.0.0"
            )
            
            # Send the registration request
            response = stub.RegisterModule(request, timeout=10)
            
            if response.success:
                logger.info(f"Successfully registered with YAPPY. Service ID: {response.registered_service_id}")
                logger.info(f"Config digest: {response.calculated_config_digest}")
                return True
            else:
                logger.error(f"Failed to register with YAPPY: {response.message}")
                return False
                
    except Exception as e:
        logger.error(f"Error registering with YAPPY: {e}", exc_info=True)
        return False

def serve(host: str, port: int, implementation_id: Optional[str] = None, registration_service_address: Optional[str] = None):
    """
    Start the gRPC server.
    
    Args:
        host: The host to bind to
        port: The port to bind to
        implementation_id: The implementation ID for this module (for registration)
        registration_service_address: The address of the YAPPY registration service
    """
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    processor_pb2_grpc.add_PipeStepProcessorServicer_to_server(
        SemanticChunkerServicer(), server
    )
    
    server_address = f"{host}:{port}"
    server.add_insecure_port(server_address)
    server.start()
    
    logger.info(f"Semantic Chunker Service started on {server_address}")
    
    # Register with YAPPY if requested
    if implementation_id and registration_service_address:
        if not register_with_yappy(implementation_id, host, port, registration_service_address):
            logger.warning("Failed to register with YAPPY. Service will continue running.")
    
    try:
        # Keep the server running
        while True:
            time.sleep(86400)  # Sleep for a day
    except KeyboardInterrupt:
        server.stop(0)
        logger.info("Server stopped")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Semantic Chunker gRPC Service")
    parser.add_argument("--host", default=os.environ.get("MODULE_HOST", "0.0.0.0"), 
                        help="Host to bind to")
    parser.add_argument("--port", type=int, default=int(os.environ.get("MODULE_PORT", "50051")), 
                        help="Port to bind to")
    parser.add_argument("--implementation-id", default=os.environ.get("YAPPY_IMPLEMENTATION_ID", "semantic-chunker-v1"), 
                        help="Implementation ID for YAPPY registration")
    parser.add_argument("--registration-service", default=os.environ.get("YAPPY_REGISTRATION_SERVICE_ADDRESS"), 
                        help="Address of the YAPPY registration service")
    
    args = parser.parse_args()
    
    serve(args.host, args.port, args.implementation_id, args.registration_service)