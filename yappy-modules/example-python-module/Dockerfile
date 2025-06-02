FROM python:3.11-slim

WORKDIR /app

# Copy requirements and install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# For now, we'll copy pre-compiled proto files
# In production, you'd compile these as part of CI/CD
COPY *.py ./

# Expose gRPC port
EXPOSE 50051

# Environment variables
ENV GRPC_PORT=50051
ENV CONSUL_ENABLED=false
ENV CONSUL_HOST=consul
ENV CONSUL_PORT=8500

# Health check
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD python -c "import grpc; channel = grpc.insecure_channel('localhost:50051'); channel.close()" || exit 1

# Run the module
CMD ["python", "text_analyzer_module.py"]