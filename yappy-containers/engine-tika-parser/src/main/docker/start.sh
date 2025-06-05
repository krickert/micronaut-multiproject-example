#!/bin/bash
set -e

echo "Starting YAPPY Engine with Tika Parser module..."
echo "================================================"
echo "Environment:"
echo "  YAPPY_CLUSTER_NAME=${YAPPY_CLUSTER_NAME:-default-cluster}"
echo "  CONSUL_HOST=${CONSUL_HOST:-localhost}"
echo "  CONSUL_PORT=${CONSUL_PORT:-8500}"
echo "  KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
echo "  APICURIO_REGISTRY_URL=${APICURIO_REGISTRY_URL:-http://localhost:8080}"
echo "================================================"

# Export environment variables for supervisord
export MICRONAUT_ENVIRONMENTS="docker"
export JAVA_OPTS="${JAVA_OPTS:--Xmx1g}"

# Engine-specific environment
export YAPPY_ENGINE_NAME="${YAPPY_ENGINE_NAME:-yappy-engine-tika-parser}"
export YAPPY_CLUSTER_NAME="${YAPPY_CLUSTER_NAME:-default-cluster}"
export CONSUL_HOST="${CONSUL_HOST:-localhost}"
export CONSUL_PORT="${CONSUL_PORT:-8500}"
export CONSUL_ENABLED="${CONSUL_ENABLED:-true}"
export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
export KAFKA_ENABLED="${KAFKA_ENABLED:-true}"
export APICURIO_REGISTRY_URL="${APICURIO_REGISTRY_URL:-http://localhost:8080}"
export SCHEMA_REGISTRY_TYPE="${SCHEMA_REGISTRY_TYPE:-apicurio}"

# Module-specific environment
export TIKA_MAX_FILE_SIZE="${TIKA_MAX_FILE_SIZE:-52428800}"
export TIKA_TIMEOUT_MS="${TIKA_TIMEOUT_MS:-60000}"
export TIKA_EXTRACT_METADATA="${TIKA_EXTRACT_METADATA:-true}"
export TIKA_EXTRACT_CONTENT="${TIKA_EXTRACT_CONTENT:-true}"
export TIKA_DETECT_LANGUAGE="${TIKA_DETECT_LANGUAGE:-true}"

# Start supervisord
echo "Starting supervisord..."
exec /usr/bin/supervisord -c /etc/supervisor/conf.d/supervisord.conf