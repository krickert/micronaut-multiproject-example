FROM python:3.10-slim

WORKDIR /app

# Copy requirements and install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy the Python module code
COPY src/main/python /app/src/main/python
COPY setup.py .

# Create the generated directory structure
RUN mkdir -p /app/src/main/python/generated
RUN touch /app/src/main/python/generated/__init__.py

# Install the module
RUN pip install -e .

# Set environment variables
ENV MODULE_HOST=0.0.0.0
ENV MODULE_PORT=50051
ENV PYTHONPATH=/app

# Download NLTK data
RUN python -c "import nltk; nltk.download('punkt')"

# Expose the port
EXPOSE 50051

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:50051/health || exit 1

# Run the service
CMD ["python", "src/main/python/semantic_chunker_service.py"]