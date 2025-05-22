"""
Tests for the semantic chunker gRPC service.
"""

import unittest
import pytest
import grpc
import os
import sys
from unittest.mock import MagicMock, patch

# Add the generated directory to the Python path
sys.path.append(os.path.join(os.path.dirname(__file__), "../../../main/python/generated"))

# Import generated gRPC code
try:
    from generated import pipe_step_processor_service_pb2 as processor_pb2
    from generated import pipe_step_processor_service_pb2_grpc as processor_pb2_grpc
    from generated import yappy_core_types_pb2 as core_pb2
except ImportError:
    print("Error: Generated gRPC code not found. Run './gradlew generatePythonProto' first.")
    sys.exit(1)

# Import the service implementation
sys.path.append(os.path.join(os.path.dirname(__file__), "../../../main/python"))
from semantic_chunker_service import SemanticChunkerServicer

class TestSemanticChunkerService(unittest.TestCase):
    """Test cases for the SemanticChunkerServicer class."""
    
    def setUp(self):
        """Set up a service instance for testing."""
        # Mock the SemanticChunker to avoid loading the model
        self.chunker_patcher = patch('semantic_chunker_service.SemanticChunker')
        self.mock_chunker_class = self.chunker_patcher.start()
        
        # Create a mock chunker instance
        self.mock_chunker = MagicMock()
        self.mock_chunker_class.return_value = self.mock_chunker
        
        # Set up the mock chunker to return a valid result
        self.mock_chunker.process_text.return_value = {
            "chunks": [
                {
                    "chunk_id": "chunk_0",
                    "text_content": "This is a test chunk.",
                    "vector": [0.1, 0.2, 0.3],
                    "original_char_start_offset": 0,
                    "original_char_end_offset": 20
                }
            ],
            "chunk_groups": [
                {
                    "chunk_ids": [0],
                    "text_content": "This is a test chunk.",
                    "vector": [0.1, 0.2, 0.3],
                    "chunk_group_id": "group_0"
                }
            ]
        }
        
        # Create the service
        self.service = SemanticChunkerServicer()
    
    def tearDown(self):
        """Clean up after tests."""
        self.chunker_patcher.stop()
    
    def test_process_data_success(self):
        """Test successful document processing."""
        # Create a request with a document
        request = processor_pb2.ProcessRequest()
        request.document.id = "test_doc_1"
        request.document.body = "This is a test document for semantic chunking."
        
        # Create a mock gRPC context
        context = MagicMock()
        
        # Process the document
        response = self.service.ProcessData(request, context)
        
        # Check that the response indicates success
        self.assertTrue(response.success)
        
        # Check that the output document exists
        self.assertIsNotNone(response.output_doc)
        
        # Check that the output document has semantic results
        self.assertEqual(len(response.output_doc.semantic_results), 1)
        
        # Check that the semantic result has chunks
        semantic_result = response.output_doc.semantic_results[0]
        self.assertEqual(len(semantic_result.chunks), 1)
        
        # Check that the chunk has the expected data
        chunk = semantic_result.chunks[0]
        self.assertEqual(chunk.chunk_id, "chunk_0")
        self.assertEqual(chunk.embedding_info.text_content, "This is a test chunk.")
        
        # Check that the vector was copied correctly
        self.assertEqual(len(chunk.embedding_info.vector), 3)
        self.assertEqual(chunk.embedding_info.vector[0], 0.1)
        self.assertEqual(chunk.embedding_info.vector[1], 0.2)
        self.assertEqual(chunk.embedding_info.vector[2], 0.3)
        
        # Check that the offsets were copied correctly
        self.assertEqual(chunk.embedding_info.original_char_start_offset, 0)
        self.assertEqual(chunk.embedding_info.original_char_end_offset, 20)
    
    def test_process_data_no_body(self):
        """Test processing a document with no body."""
        # Create a request with a document that has no body
        request = processor_pb2.ProcessRequest()
        request.document.id = "test_doc_2"
        
        # Create a mock gRPC context
        context = MagicMock()
        
        # Process the document
        response = self.service.ProcessData(request, context)
        
        # Check that the response indicates failure
        self.assertFalse(response.success)
        
        # Check that the processor logs contain an error message
        self.assertEqual(len(response.processor_logs), 1)
        self.assertIn("no body", response.processor_logs[0])
    
    def test_parse_config(self):
        """Test parsing configuration from a gRPC request."""
        # Create a config struct
        from google.protobuf.struct_pb2 import Struct, Value
        config_struct = Struct()
        config_struct.fields["model_name"].string_value = "test-model"
        config_struct.fields["chunk_size"].number_value = 200
        config_struct.fields["chunk_overlap"].number_value = 30
        config_struct.fields["similarity_threshold"].number_value = 0.8
        
        # Parse the config
        config = self.service._parse_config(config_struct)
        
        # Check that the config was parsed correctly
        self.assertEqual(config["model_name"], "test-model")
        self.assertEqual(config["chunk_size"], 200)
        self.assertEqual(config["chunk_overlap"], 30)
        self.assertEqual(config["similarity_threshold"], 0.8)

if __name__ == "__main__":
    unittest.main()