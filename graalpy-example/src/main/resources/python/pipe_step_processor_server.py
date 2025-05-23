import grpc
import concurrent.futures
from concurrent.futures import ThreadPoolExecutor
import logging
import json

# Import the generated protobuf modules
import pipe_step_processor_service_pb2
import pipe_step_processor_service_pb2_grpc
import yappy_core_types_pb2

class PipeStepProcessorServicer(pipe_step_processor_service_pb2_grpc.PipeStepProcessorServicer):
    """Implementation of PipeStepProcessor service."""

    def ProcessData(self, request, context):
        """Process a document according to the step's configuration and logic."""
        logging.info(f"Processing document with ID: {request.document.id}")
        logging.info(f"Pipeline: {request.metadata.pipeline_name}, Step: {request.metadata.pipe_step_name}")

        # Create a copy of the input document for the output
        output_doc = yappy_core_types_pb2.PipeDoc()
        output_doc.CopyFrom(request.document)

        # Add a dummy processing step - in a real implementation, this would do actual work
        # For example, we'll just add a prefix to the document title if it exists
        if output_doc.title:
            output_doc.title = f"Processed: {output_doc.title}"
        else:
            output_doc.title = "Processed Document"

        # If the document has a body, we'll add a note to it
        if output_doc.body:
            output_doc.body = f"{output_doc.body}\n\nProcessed by Python PipeStepProcessor"
        else:
            output_doc.body = "This document was processed by the Python PipeStepProcessor"

        # Add some keywords
        output_doc.keywords.append("python")
        output_doc.keywords.append("graalpy")
        output_doc.keywords.append("processor")

        # Log the configuration
        if request.config.custom_json_config:
            logging.info("Custom JSON config received")
            # In a real implementation, we would use this configuration

        if request.config.config_params:
            logging.info(f"Config params: {request.config.config_params}")

        # Create processor logs
        processor_logs = [
            f"Processed document {output_doc.id}",
            f"Added keywords: python, graalpy, processor",
            f"Modified title and body"
        ]

        # Create and return the response
        return pipe_step_processor_service_pb2.ProcessResponse(
            success=True,
            output_doc=output_doc,
            processor_logs=processor_logs
        )

def serve():
    """Start the gRPC server."""
    server = grpc.server(ThreadPoolExecutor(max_workers=10))
    pipe_step_processor_service_pb2_grpc.add_PipeStepProcessorServicer_to_server(
        PipeStepProcessorServicer(), server)
    server.add_insecure_port('[::]:50062')
    server.start()
    logging.info("PipeStepProcessor server started, listening on port 50062")
    server.wait_for_termination()

if __name__ == '__main__':
    logging.basicConfig(level=logging.INFO)
    serve()
