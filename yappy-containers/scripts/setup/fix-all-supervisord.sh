#!/bin/bash
set -e

echo "Fixing supervisord configuration for all containers..."

# List of containers to fix
CONTAINERS=(
    "engine-chunker"
    "engine-embedder"
    "engine-opensearch-sink"
    "engine-test-connector"
)

# Create the start.sh template
create_start_script() {
    local container=$1
    local module_name=$2
    local module_port=$3
    local engine_name=$4
    
    cat > "$container/src/main/resources/start.sh" << 'EOF'
#!/bin/sh

# Generate supervisord.conf with environment variables
cat > /etc/supervisor/conf.d/supervisord.conf <<EOC
[supervisord]
nodaemon=true
user=root
logfile=/var/log/supervisor/supervisord.log
pidfile=/var/run/supervisord.pid

[program:engine]
command=java -Dmicronaut.config.files=/app/engine/application.yml -jar /app/engine/engine.jar
directory=/app/engine
autostart=true
autorestart=true
startretries=3
startretrydelay=5
user=appuser
environment=YAPPY_ENGINE_NAME="${YAPPY_ENGINE_NAME:-ENGINE_NAME}",YAPPY_CLUSTER_NAME="${YAPPY_CLUSTER_NAME:-default-cluster}",CONSUL_HOST="${CONSUL_HOST:-consul}",CONSUL_PORT="${CONSUL_PORT:-8500}",KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}",APICURIO_REGISTRY_URL="${APICURIO_REGISTRY_URL:-http://apicurio:8080}"
stdout_logfile=/var/log/supervisor/engine.log
stderr_logfile=/var/log/supervisor/engine.err.log
priority=10

[program:MODULE_NAME]
command=java -Dmicronaut.config.files=/app/modules/module-application.yml -jar /app/modules/MODULE_JAR.jar
directory=/app/modules
autostart=true
autorestart=true
startretries=3
startretrydelay=5
user=appuser
environment=MICRONAUT_APPLICATION_NAME="MODULE_NAME",MICRONAUT_SERVER_PORT="MODULE_PORT",GRPC_SERVER_PORT="MODULE_PORT"EXTRA_ENV
stdout_logfile=/var/log/supervisor/MODULE_NAME.log
stderr_logfile=/var/log/supervisor/MODULE_NAME.err.log
priority=20

[group:yappy]
programs=engine,MODULE_NAME

[unix_http_server]
file=/var/run/supervisor.sock

[supervisorctl]
serverurl=unix:///var/run/supervisor.sock

[rpcinterface:supervisor]
supervisor.rpcinterface_factory = supervisor.rpcinterface:make_main_rpcinterface
EOC

# Start supervisord
exec /usr/bin/supervisord -c /etc/supervisor/conf.d/supervisord.conf
EOF
    
    # Replace placeholders
    sed -i.bak "s/ENGINE_NAME/$engine_name/g" "$container/src/main/resources/start.sh"
    sed -i.bak "s/MODULE_NAME/$module_name/g" "$container/src/main/resources/start.sh"
    sed -i.bak "s/MODULE_JAR/$module_name/g" "$container/src/main/resources/start.sh"
    sed -i.bak "s/MODULE_PORT/$module_port/g" "$container/src/main/resources/start.sh"
    
    # Add extra environment for opensearch-sink
    if [ "$module_name" == "opensearch-sink" ]; then
        sed -i.bak 's/EXTRA_ENV/,OPENSEARCH_HOSTS="${OPENSEARCH_HOSTS:-opensearch:9200}"/g' "$container/src/main/resources/start.sh"
    else
        sed -i.bak 's/EXTRA_ENV//g' "$container/src/main/resources/start.sh"
    fi
    
    # Clean up backup files
    rm -f "$container/src/main/resources/start.sh.bak"
    
    chmod +x "$container/src/main/resources/start.sh"
}

# Update Dockerfile template
update_dockerfile() {
    local container=$1
    local dockerfile="$container/Dockerfile"
    
    # Check if start.sh is already in Dockerfile
    if ! grep -q "start.sh" "$dockerfile"; then
        # Add start.sh copy and update CMD
        sed -i.bak '/COPY.*module-application.yml/a\
COPY yappy-containers/'"$container"'/src/main/resources/start.sh /app/start.sh\
\
# Make start script executable\
RUN chmod +x /app/start.sh' "$dockerfile"
        
        # Update CMD to use start.sh
        sed -i.bak 's|CMD \["/usr/bin/supervisord".*\]|CMD ["/app/start.sh"]|' "$dockerfile"
        
        # Clean up backup
        rm -f "${dockerfile}.bak"
    fi
}

# Fix engine-chunker
echo "Fixing engine-chunker..."
create_start_script "engine-chunker" "chunker" "50053" "yappy-engine-chunker"
update_dockerfile "engine-chunker"

# Fix engine-embedder
echo "Fixing engine-embedder..."
create_start_script "engine-embedder" "embedder" "50054" "yappy-engine-embedder"
update_dockerfile "engine-embedder"

# Fix engine-opensearch-sink
echo "Fixing engine-opensearch-sink..."
create_start_script "engine-opensearch-sink" "opensearch-sink" "50055" "yappy-engine-opensearch-sink"
update_dockerfile "engine-opensearch-sink"

# Fix engine-test-connector
echo "Fixing engine-test-connector..."
create_start_script "engine-test-connector" "test-connector" "50059" "yappy-engine-test-connector"
update_dockerfile "engine-test-connector"

echo ""
echo "All supervisord configurations fixed!"
echo ""
echo "Now rebuild all containers with:"
echo "  ./build-all-containers.sh"