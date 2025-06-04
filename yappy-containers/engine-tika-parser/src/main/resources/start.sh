#!/bin/sh

# Generate supervisord.conf with environment variables
cat > /etc/supervisor/conf.d/supervisord.conf <<EOF
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
environment=YAPPY_ENGINE_NAME="${YAPPY_ENGINE_NAME:-yappy-engine-tika}",YAPPY_CLUSTER_NAME="${YAPPY_CLUSTER_NAME:-default-cluster}",CONSUL_HOST="${CONSUL_HOST:-consul}",CONSUL_PORT="${CONSUL_PORT:-8500}",KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}",APICURIO_REGISTRY_URL="${APICURIO_REGISTRY_URL:-http://apicurio:8080}"
stdout_logfile=/var/log/supervisor/engine.log
stderr_logfile=/var/log/supervisor/engine.err.log
priority=10

[program:tika-parser]
command=java -Dmicronaut.config.files=/app/modules/module-application.yml -jar /app/modules/tika-parser.jar
directory=/app/modules
autostart=true
autorestart=true
startretries=3
startretrydelay=5
user=appuser
environment=MICRONAUT_APPLICATION_NAME="tika-parser",MICRONAUT_SERVER_PORT="50052",GRPC_SERVER_PORT="50052"
stdout_logfile=/var/log/supervisor/tika-parser.log
stderr_logfile=/var/log/supervisor/tika-parser.err.log
priority=20

[group:yappy]
programs=engine,tika-parser

[unix_http_server]
file=/var/run/supervisor.sock

[supervisorctl]
serverurl=unix:///var/run/supervisor.sock

[rpcinterface:supervisor]
supervisor.rpcinterface_factory = supervisor.rpcinterface:make_main_rpcinterface
EOF

# Start supervisord
exec /usr/bin/supervisord -c /etc/supervisor/conf.d/supervisord.conf