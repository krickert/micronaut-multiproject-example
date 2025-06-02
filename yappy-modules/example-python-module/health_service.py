#!/usr/bin/env python3
"""
Example Python gRPC Health Service Implementation for Yappy Modules

This demonstrates how Python modules should implement gRPC health checks
using the standard grpc_health.v1 service as required by the Yappy platform.
"""

import grpc
from grpc_health.v1 import health_pb2_grpc
from grpc_health.v1 import health_pb2
import threading
import time
import logging

logger = logging.getLogger(__name__)


class HealthService(health_pb2_grpc.HealthServicer):
    """
    Standard gRPC Health Service implementation for Python modules.
    
    This service provides health check functionality that can be queried
    by the Yappy engine and service mesh infrastructure.
    """
    
    def __init__(self):
        self._service_status = {}
        self._lock = threading.RLock()
        
        # Set default service as healthy on startup
        self.set_status("", health_pb2.HealthCheckResponse.SERVING)
        
    def Check(self, request, context):
        """
        Synchronous health check.
        
        Args:
            request: HealthCheckRequest containing service name
            context: gRPC context
            
        Returns:
            HealthCheckResponse with serving status
        """
        with self._lock:
            service_name = request.service
            status = self._service_status.get(service_name, health_pb2.HealthCheckResponse.SERVICE_UNKNOWN)
            
            logger.debug(f"Health check for service '{service_name}': {self._status_to_string(status)}")
            
            return health_pb2.HealthCheckResponse(status=status)
    
    def Watch(self, request, context):
        """
        Streaming health check for watching status changes.
        
        Args:
            request: HealthCheckRequest containing service name
            context: gRPC context
            
        Yields:
            HealthCheckResponse when status changes
        """
        service_name = request.service
        
        # Send current status immediately
        with self._lock:
            current_status = self._service_status.get(service_name, health_pb2.HealthCheckResponse.SERVICE_UNKNOWN)
        
        yield health_pb2.HealthCheckResponse(status=current_status)
        
        # Note: For a complete implementation, you would maintain a list of watchers
        # and notify them when status changes. For simplicity, this example
        # just sends the current status once.
        
    def set_status(self, service_name: str, status: health_pb2.HealthCheckResponse.ServingStatus):
        """
        Set the health status for a service.
        
        Args:
            service_name: Name of the service (empty string for default)
            status: Health status (SERVING, NOT_SERVING, etc.)
        """
        with self._lock:
            self._service_status[service_name] = status
            logger.info(f"Set health status for '{service_name}': {self._status_to_string(status)}")
    
    def set_healthy(self, service_name: str = ""):
        """Mark a service as healthy (SERVING)."""
        self.set_status(service_name, health_pb2.HealthCheckResponse.SERVING)
    
    def set_unhealthy(self, service_name: str = ""):
        """Mark a service as unhealthy (NOT_SERVING)."""
        self.set_status(service_name, health_pb2.HealthCheckResponse.NOT_SERVING)
    
    def _status_to_string(self, status):
        """Convert status enum to string for logging."""
        status_map = {
            health_pb2.HealthCheckResponse.UNKNOWN: "UNKNOWN",
            health_pb2.HealthCheckResponse.SERVING: "SERVING", 
            health_pb2.HealthCheckResponse.NOT_SERVING: "NOT_SERVING",
            health_pb2.HealthCheckResponse.SERVICE_UNKNOWN: "SERVICE_UNKNOWN"
        }
        return status_map.get(status, f"UNKNOWN_STATUS({status})")


def add_health_service_to_server(server: grpc.Server) -> HealthService:
    """
    Add the health service to a gRPC server.
    
    Args:
        server: The gRPC server instance
        
    Returns:
        HealthService instance for status management
        
    Example:
        server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
        health_service = add_health_service_to_server(server)
        
        # Add your module's service
        your_service_pb2_grpc.add_YourServiceServicer_to_server(YourService(), server)
        
        # Start server
        listen_addr = '[::]:50051'
        server.add_insecure_port(listen_addr)
        server.start()
        
        # Health service is automatically available at /grpc.health.v1.Health
    """
    health_service = HealthService()
    health_pb2_grpc.add_HealthServicer_to_server(health_service, server)
    
    logger.info("gRPC Health Service added to server")
    return health_service


# Example usage in a complete Python module
if __name__ == "__main__":
    import concurrent.futures
    
    # Configure logging
    logging.basicConfig(level=logging.INFO)
    
    # Create server
    server = grpc.server(concurrent.futures.ThreadPoolExecutor(max_workers=10))
    
    # Add health service
    health_service = add_health_service_to_server(server)
    
    # Add your actual module service here
    # your_service_pb2_grpc.add_YourServiceServicer_to_server(YourService(), server)
    
    # Start server
    listen_addr = '[::]:50051'
    server.add_insecure_port(listen_addr)
    server.start()
    
    logger.info(f"Server started on {listen_addr}")
    logger.info("Health check available at /grpc.health.v1.Health")
    
    try:
        server.wait_for_termination()
    except KeyboardInterrupt:
        health_service.set_unhealthy()  # Mark as unhealthy during shutdown
        server.stop(grace=5)
        logger.info("Server stopped")