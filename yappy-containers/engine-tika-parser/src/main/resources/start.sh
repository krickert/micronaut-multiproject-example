#!/bin/sh

echo "Starting YAPPY Engine with Tika Parser module..."
echo "Environment variables:"
echo "  YAPPY_ENGINE_NAME=${YAPPY_ENGINE_NAME}"
echo "  YAPPY_CLUSTER_NAME=${YAPPY_CLUSTER_NAME}"
echo "  CONSUL_HOST=${CONSUL_HOST}"
echo "  CONSUL_PORT=${CONSUL_PORT}"
echo "  KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}"
echo "  APICURIO_REGISTRY_URL=${APICURIO_REGISTRY_URL}"

# Generate supervisord.conf with environment variables
cat > /etc/supervisor/conf.d/supervisord.conf <<EOC
[supervisord]
nodaemon=true
user=root
logfile=/var/log/supervisor/supervisord.log
pidfile=/var/run/supervisord.pid

[program:tika-parser]
command=java -Dmicronaut.config.files=/app/modules/module-application.yml -jar /app/modules/tika-parser.jar
directory=/app/modules
autostart=true
autorestart=true
startretries=3
startretrydelay=5
user=root
environment=MICRONAUT_APPLICATION_NAME="tika-parser",MICRONAUT_SERVER_PORT="-1",GRPC_SERVER_PORT="50053",MICRONAUT_CONFIG_FILES="/app/modules/module-application.yml",CONSUL_CLIENT_HOST="${CONSUL_HOST}",CONSUL_CLIENT_PORT="${CONSUL_PORT}",CONSUL_CLIENT_DEFAULT_ZONE="${CONSUL_CLIENT_DEFAULT_ZONE}",KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}",APICURIO_REGISTRY_URL="${APICURIO_REGISTRY_URL}",MICRONAUT_CONSUL_ENABLED="${CONSUL_ENABLED}",MICRONAUT_KAFKA_ENABLED="${KAFKA_ENABLED}",KAFKA_SCHEMA_REGISTRY_TYPE="${SCHEMA_REGISTRY_TYPE}"
stdout_logfile=/var/log/supervisor/tika-parser.log
stdout_logfile_maxbytes=50MB
stdout_logfile_backups=3
stderr_logfile=/var/log/supervisor/tika-parser.err.log
stderr_redirect=true
priority=10

[program:engine]
command=java -Dmicronaut.config.files=/app/engine/application.yml -jar /home/app/application.jar
directory=/home/app
autostart=true
autorestart=true
startretries=3
startretrydelay=10
user=root
environment=YAPPY_ENGINE_NAME="${YAPPY_ENGINE_NAME}",YAPPY_CLUSTER_NAME="${YAPPY_CLUSTER_NAME}",MICRONAUT_CONFIG_FILES="/app/engine/application.yml",CONSUL_CLIENT_HOST="${CONSUL_HOST}",CONSUL_CLIENT_PORT="${CONSUL_PORT}",CONSUL_CLIENT_DEFAULT_ZONE="${CONSUL_CLIENT_DEFAULT_ZONE}",KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}",APICURIO_REGISTRY_URL="${APICURIO_REGISTRY_URL}",MICRONAUT_CONSUL_ENABLED="${CONSUL_ENABLED}",MICRONAUT_KAFKA_ENABLED="${KAFKA_ENABLED}",KAFKA_SCHEMA_REGISTRY_TYPE="${SCHEMA_REGISTRY_TYPE}",MICRONAUT_SERVER_PORT="${MICRONAUT_SERVER_PORT}",GRPC_SERVER_PORT="${GRPC_SERVER_PORT}"
stdout_logfile=/var/log/supervisor/engine.log
stdout_logfile_maxbytes=50MB
stdout_logfile_backups=3
stderr_logfile=/var/log/supervisor/engine.err.log
stderr_redirect=true
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