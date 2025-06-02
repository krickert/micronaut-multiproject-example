// Package main demonstrates gRPC Health Service implementation for Go modules
// This shows how Go modules should implement health checks using the standard
// grpc.health.v1 service as required by the Yappy platform.
package main

import (
	"context"
	"log"
	"net"
	"sync"

	"google.golang.org/grpc"
	"google.golang.org/grpc/health"
	"google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/reflection"
)

// YappyHealthService wraps the standard gRPC health service with Yappy-specific functionality
type YappyHealthService struct {
	healthServer *health.Server
	mu           sync.RWMutex
}

// NewYappyHealthService creates a new health service instance
func NewYappyHealthService() *YappyHealthService {
	return &YappyHealthService{
		healthServer: health.NewServer(),
	}
}

// SetHealthy marks a service as healthy (SERVING)
func (h *YappyHealthService) SetHealthy(serviceName string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	
	h.healthServer.SetServingStatus(serviceName, grpc_health_v1.HealthCheckResponse_SERVING)
	log.Printf("Set service '%s' as HEALTHY", serviceName)
}

// SetUnhealthy marks a service as unhealthy (NOT_SERVING)
func (h *YappyHealthService) SetUnhealthy(serviceName string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	
	h.healthServer.SetServingStatus(serviceName, grpc_health_v1.HealthCheckResponse_NOT_SERVING)
	log.Printf("Set service '%s' as UNHEALTHY", serviceName)
}

// GetHealthServer returns the underlying health server for registration
func (h *YappyHealthService) GetHealthServer() *health.Server {
	return h.healthServer
}

// RegisterWithServer registers the health service with a gRPC server
func (h *YappyHealthService) RegisterWithServer(server *grpc.Server) {
	grpc_health_v1.RegisterHealthServer(server, h.healthServer)
	log.Println("gRPC Health Service registered with server")
}

// Example implementation showing how to use the health service in a Go module
func main() {
	// Create gRPC server
	server := grpc.NewServer()
	
	// Create and register health service
	healthService := NewYappyHealthService()
	healthService.RegisterWithServer(server)
	
	// Register your actual module service here
	// pb.RegisterYourServiceServer(server, &YourServiceImpl{})
	
	// Enable reflection for debugging (optional)
	reflection.Register(server)
	
	// Set initial health status
	healthService.SetHealthy("") // Empty string for default service
	
	// Start server
	lis, err := net.Listen("tcp", ":50051")
	if err != nil {
		log.Fatalf("Failed to listen: %v", err)
	}
	
	log.Println("Server starting on :50051")
	log.Println("Health check available at /grpc.health.v1.Health")
	
	// Graceful shutdown handling
	go func() {
		if err := server.Serve(lis); err != nil {
			log.Fatalf("Failed to serve: %v", err)
		}
	}()
	
	// Wait for interrupt signal for graceful shutdown
	// In a real implementation, you would handle SIGINT/SIGTERM here
	select {}
}

// Example of a custom service that manages its own health status
type CustomYappyModule struct {
	healthService *YappyHealthService
	// Add your module-specific fields here
}

// NewCustomYappyModule creates a new module instance with health monitoring
func NewCustomYappyModule() *CustomYappyModule {
	return &CustomYappyModule{
		healthService: NewYappyHealthService(),
	}
}

// StartHealthMonitoring starts background health monitoring for this module
func (m *CustomYappyModule) StartHealthMonitoring(ctx context.Context) {
	go func() {
		for {
			select {
			case <-ctx.Done():
				m.healthService.SetUnhealthy("")
				return
			default:
				// Perform your health checks here
				if m.isModuleHealthy() {
					m.healthService.SetHealthy("")
				} else {
					m.healthService.SetUnhealthy("")
				}
				
				// Check health every 30 seconds
				select {
				case <-ctx.Done():
					return
				case <-time.After(30 * time.Second):
					continue
				}
			}
		}
	}()
}

// isModuleHealthy performs module-specific health checks
func (m *CustomYappyModule) isModuleHealthy() bool {
	// Implement your module's health check logic here
	// Examples:
	// - Check database connectivity
	// - Verify external service availability
	// - Check resource usage (memory, disk, etc.)
	// - Validate configuration
	
	return true // Placeholder
}

// RegisterWithServer registers both the module service and health service
func (m *CustomYappyModule) RegisterWithServer(server *grpc.Server) {
	// Register health service
	m.healthService.RegisterWithServer(server)
	
	// Register your module's actual service
	// pb.RegisterYourServiceServer(server, m)
}