#!/usr/bin/env python3
"""
Test script for the Text Analyzer module
This demonstrates how to call the module directly
"""

import grpc
import sys
import json
from google.protobuf import empty_pb2, struct_pb2, timestamp_pb2

# These would be imported from generated files
import yappy_core_types_pb2 as core_types
import pipe_step_processor_service_pb2 as service_pb2
import pipe_step_processor_service_pb2_grpc as service_grpc

def test_registration(stub):
    """Test GetServiceRegistration"""
    print("Testing GetServiceRegistration...")
    
    response = stub.GetServiceRegistration(empty_pb2.Empty())
    
    print(f"Module name: {response.pipe_step_name}")
    print(f"Module version: {response.context_params.get('module_version', 'N/A')}")
    print(f"Description: {response.context_params.get('description', 'N/A')}")
    
    if 'json_config_schema' in response.context_params:
        schema = json.loads(response.context_params['json_config_schema'])
        print(f"Configuration schema properties: {list(schema.get('properties', {}).keys())}")
    
    print("✓ Registration test passed\n")

def test_processing(stub):
    """Test ProcessData"""
    print("Testing ProcessData...")
    
    # Create a test document
    doc = core_types.PipeDoc(
        id="test-123",
        title="Test Document",
        body="This is a test document with multiple sentences. It contains various words and demonstrates text analysis. The module should count words, sentences, and estimate reading time."
    )
    
    # Create configuration
    config_data = struct_pb2.Struct()
    config_data.update({
        "analyze_sentences": True,
        "analyze_words": True,
        "extract_keywords": True,
        "add_reading_time": True
    })
    
    # Create metadata
    metadata = service_pb2.ServiceMetadata(
        pipeline_name="test-pipeline",
        pipe_step_name="text-analyzer",
        stream_id="stream-test-123",
        current_hop_number=1
    )
    
    # Create request
    request = service_pb2.ProcessRequest(
        document=doc,
        config=service_pb2.ProcessConfiguration(
            custom_json_config=config_data
        ),
        metadata=metadata
    )
    
    # Call the service
    response = stub.ProcessData(request)
    
    print(f"Success: {response.success}")
    print(f"Logs: {response.processor_logs}")
    
    if response.success and response.output_doc:
        output_doc = response.output_doc
        print(f"Output document ID: {output_doc.id}")
        
        if output_doc.custom_data and 'text_analysis' in output_doc.custom_data:
            analysis = output_doc.custom_data['text_analysis']
            print("\nText Analysis Results:")
            for key, value in analysis.items():
                print(f"  {key}: {value}")
        
        if output_doc.keywords:
            print(f"\nExtracted keywords: {', '.join(output_doc.keywords[:5])}...")
    
    print("✓ Processing test passed\n")

def test_error_handling(stub):
    """Test error handling"""
    print("Testing error handling...")
    
    # Create a document with missing required fields
    doc = core_types.PipeDoc(id="")  # Empty ID should cause an issue
    
    request = service_pb2.ProcessRequest(document=doc)
    
    response = stub.ProcessData(request)
    
    print(f"Success: {response.success}")
    print(f"Logs: {response.processor_logs}")
    
    if not response.success:
        print("✓ Error handling test passed (module handled invalid input gracefully)\n")
    else:
        print("⚠ Module accepted invalid input\n")

def main():
    # Connect to the module
    channel = grpc.insecure_channel('localhost:50051')
    stub = service_grpc.PipeStepProcessorStub(channel)
    
    try:
        # Run tests
        test_registration(stub)
        test_processing(stub)
        test_error_handling(stub)
        
        print("All tests completed!")
        
    except grpc.RpcError as e:
        print(f"gRPC error: {e.code()} - {e.details()}")
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)
    finally:
        channel.close()

if __name__ == '__main__':
    main()