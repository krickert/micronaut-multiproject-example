#!/bin/sh

echo "Starting YAPPY Engine with Tika Parser module..."
echo "Environment variables:"
echo "  YAPPY_ENGINE_NAME=${YAPPY_ENGINE_NAME:-yappy-engine-tika-parser}"
echo "  YAPPY_CLUSTER_NAME=${YAPPY_CLUSTER_NAME:-default-cluster}"
echo "  CONSUL_HOST=${CONSUL_HOST:-localhost}"
echo "  CONSUL_PORT=${CONSUL_PORT:-8500}"
echo "  KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
echo "  APICURIO_REGISTRY_URL=${APICURIO_REGISTRY_URL:-http://localhost:8080}"

# Check if JAR files exist
if [ ! -f "/app/modules/tika-parser.jar" ]; then
    echo "ERROR: Tika Parser JAR file not found at /app/modules/tika-parser.jar"
    ls -la /app/modules/
fi

if [ ! -f "/app/engine/engine.jar" ]; then
    echo "ERROR: Engine JAR file not found at /app/engine/engine.jar"
    ls -la /app/engine/
fi

# Create a symbolic link from application.yml to module-application.yml in the modules directory
if [ -f "/app/modules/application.yml" ]; then
    echo "Creating symbolic link from application.yml to module-application.yml"
    ln -sf /app/modules/application.yml /app/modules/module-application.yml
else
    echo "ERROR: application.yml not found in /app/modules/"
    ls -la /app/modules/
fi

# Generate supervisord.conf with environment variables
cat > /etc/supervisor/conf.d/supervisord.conf <<EOC
[supervisord]
nodaemon=true
user=root
logfile=/var/log/supervisor/supervisord.log
pidfile=/var/run/supervisord.pid

[program:tika-parser]
command=bash -c "exec java -jar /app/modules/tika-parser.jar 2>&1"
directory=/app/modules
autostart=true
autorestart=true
startretries=3
startretrydelay=5
user=root
environment=MICRONAUT_APPLICATION_NAME="tika-parser",MICRONAUT_SERVER_PORT="-1",GRPC_SERVER_PORT="50053",MICRONAUT_CONFIG_FILES="/app/modules/application.yml",KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}",APICURIO_REGISTRY_URL="${APICURIO_REGISTRY_URL:-http://localhost:8080}",MICRONAUT_KAFKA_ENABLED="${KAFKA_ENABLED:-true}",KAFKA_SCHEMA_REGISTRY_TYPE="${SCHEMA_REGISTRY_TYPE:-apicurio}",TIKA_MAX_FILE_SIZE="${TIKA_MAX_FILE_SIZE:-52428800}",TIKA_TIMEOUT_MS="${TIKA_TIMEOUT_MS:-60000}",TIKA_EXTRACT_METADATA="${TIKA_EXTRACT_METADATA:-true}",TIKA_EXTRACT_CONTENT="${TIKA_EXTRACT_CONTENT:-true}",TIKA_DETECT_LANGUAGE="${TIKA_DETECT_LANGUAGE:-true}"
stdout_logfile=/var/log/supervisor/tika-parser.log
stdout_logfile_maxbytes=50MB
stdout_logfile_backups=3
stderr_logfile=/var/log/supervisor/tika-parser.err.log
redirect_stderr=true
priority=10

[program:engine]
command=bash -c "exec java -jar /app/engine/engine.jar 2>&1"
directory=/app/engine
autostart=true
autorestart=true
startretries=3
startretrydelay=10
user=root
environment=MICRONAUT_APPLICATION_NAME="tika-parser-engine",YAPPY_ENGINE_NAME="${YAPPY_ENGINE_NAME:-yappy-engine-tika-parser}",YAPPY_CLUSTER_NAME="${YAPPY_CLUSTER_NAME:-default-cluster}",CONSUL_CLIENT_HOST="${CONSUL_HOST:-localhost}",CONSUL_CLIENT_PORT="${CONSUL_PORT:-8500}",CONSUL_CLIENT_DEFAULT_ZONE="${CONSUL_CLIENT_DEFAULT_ZONE:-dc1}",KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}",APICURIO_REGISTRY_URL="${APICURIO_REGISTRY_URL:-http://localhost:8080}",MICRONAUT_CONSUL_ENABLED="${CONSUL_ENABLED:-true}",MICRONAUT_KAFKA_ENABLED="${KAFKA_ENABLED:-true}",KAFKA_SCHEMA_REGISTRY_TYPE="${SCHEMA_REGISTRY_TYPE:-apicurio}",MICRONAUT_SERVER_PORT="${MICRONAUT_SERVER_PORT:-8080}",GRPC_SERVER_PORT="${GRPC_SERVER_PORT:-50051}"
stdout_logfile=/var/log/supervisor/engine.log
stdout_logfile_maxbytes=50MB
stdout_logfile_backups=3
stderr_logfile=/var/log/supervisor/engine.err.log
redirect_stderr=true
priority=20

[group:yappy]
programs=tika-parser,engine

[unix_http_server]
file=/var/run/supervisor.sock

[supervisorctl]
serverurl=unix:///var/run/supervisor.sock

[rpcinterface:supervisor]
supervisor.rpcinterface_factory = supervisor.rpcinterface:make_main_rpcinterface
EOC

echo "Starting supervisord..."
# Start supervisord
exec /usr/bin/supervisord -c /etc/supervisor/conf.d/supervisord.conf
