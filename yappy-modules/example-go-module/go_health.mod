// go.mod dependencies for gRPC Health Service support
// Add these dependencies to your existing go.mod file

module your-yappy-module

go 1.21

require (
    // Core gRPC
    google.golang.org/grpc v1.60.0
    
    // gRPC Health Service (REQUIRED for Yappy modules)
    google.golang.org/grpc/health v1.2.0
    
    // gRPC reflection (optional but recommended for debugging)
    google.golang.org/grpc/reflection v1.2.0
    
    // Protocol Buffers
    google.golang.org/protobuf v1.31.0
    
    // Your module-specific dependencies here
)

// Usage example:
// go mod init your-yappy-module
// go get google.golang.org/grpc@v1.60.0
// go get google.golang.org/grpc/health@v1.2.0