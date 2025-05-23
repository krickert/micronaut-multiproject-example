import grpc
import concurrent.futures
from concurrent.futures import ThreadPoolExecutor
import logging

# Import the generated protobuf modules
import graalpy_example_pb2
import graalpy_example_pb2_grpc

# Also import some modules from the protobuf-models project to demonstrate integration
import engine_service_pb2
import yappy_core_types_pb2

class FibonacciServiceServicer(graalpy_example_pb2_grpc.FibonacciServiceServicer):
    """Implementation of FibonacciService service."""

    def CalculateFibonacci(self, request, context):
        """Calculate the Fibonacci number for a given position."""
        logging.info(f"Calculating Fibonacci number at position {request.position}")

        # Calculate Fibonacci number
        result = self._fibonacci(request.position)

        # Log that we're also able to access types from the protobuf-models project
        logging.info(f"Successfully imported types from protobuf-models: {engine_service_pb2.DESCRIPTOR.name}, {yappy_core_types_pb2.DESCRIPTOR.name}")

        return graalpy_example_pb2.FibonacciResponse(result=result)

    def CalculateFibonacciSequence(self, request, context):
        """Calculate a sequence of Fibonacci numbers up to a given position."""
        logging.info(f"Calculating Fibonacci sequence of length {request.length}")

        # Calculate Fibonacci sequence
        sequence = [self._fibonacci(i) for i in range(request.length)]

        return graalpy_example_pb2.FibonacciSequenceResponse(sequence=sequence)

    def _fibonacci(self, n):
        """Calculate the nth Fibonacci number."""
        if n <= 0:
            return 0
        elif n == 1:
            return 1
        else:
            a, b = 0, 1
            for _ in range(2, n + 1):
                a, b = b, a + b
            return b

def serve():
    """Start the gRPC server."""
    server = grpc.server(ThreadPoolExecutor(max_workers=10))
    graalpy_example_pb2_grpc.add_FibonacciServiceServicer_to_server(
        FibonacciServiceServicer(), server)
    server.add_insecure_port('[::]:50061')
    server.start()
    logging.info("Server started, listening on port 50061")
    server.wait_for_termination()

if __name__ == '__main__':
    logging.basicConfig(level=logging.INFO)
    serve()
