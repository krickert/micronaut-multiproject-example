#!/bin/bash
# Script to compile the protobuf files for Python

PROTO_DIR="../../yappy-models/protobuf-models/src/main/proto"
OUT_DIR="."

echo "Compiling protobuf files..."

# Compile the proto files
python -m grpc_tools.protoc \
    -I"$PROTO_DIR" \
    --python_out="$OUT_DIR" \
    --grpc_python_out="$OUT_DIR" \
    "$PROTO_DIR/yappy_core_types.proto" \
    "$PROTO_DIR/pipe_step_processor_service.proto"

echo "Proto files compiled successfully!"

# Fix imports in generated files (grpc_tools generates absolute imports)
echo "Fixing imports..."
sed -i '' 's/import yappy_core_types_pb2/from . import yappy_core_types_pb2/g' pipe_step_processor_service_pb2_grpc.py 2>/dev/null || true

echo "Done!"