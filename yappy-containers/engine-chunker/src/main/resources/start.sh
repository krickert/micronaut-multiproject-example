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
environment=YAPPY_yappy-engine-chunker="${YAPPY_yappy-engine-chunker:-yappy-engine-chunker}",YAPPY_CLUSTER_NAME="${YAPPY_CLUSTER_NAME:-default-cluster}",CONSUL_HOST="${CONSUL_HOST:-consul}",CONSUL_PORT="${CONSUL_PORT:-8500}",KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}",APICURIO_REGISTRY_URL="${APICURIO_REGISTRY_URL:-http://apicurio:8080}"
stdout_logfile=/var/log/supervisor/engine.log
stderr_logfile=/var/log/supervisor/engine.err.log
priority=10

[program:chunker]
command=java -Dmicronaut.config.files=/app/modules/module-application.yml -jar /app/modules/chunker.jar
directory=/app/modules
autostart=true
autorestart=true
startretries=3
startretrydelay=5
user=appuser
environment=MICRONAUT_APPLICATION_NAME="chunker",MICRONAUT_SERVER_PORT="50053",GRPC_SERVER_PORT="50053"
stdout_logfile=/var/log/supervisor/chunker.log
stderr_logfile=/var/log/supervisor/chunker.err.log
priority=20

[group:yappy]
programs=engine,chunker

[unix_http_server]
file=/var/run/supervisor.sock

[supervisorctl]
serverurl=unix:///var/run/supervisor.sock

[rpcinterface:supervisor]
supervisor.rpcinterface_factory = supervisor.rpcinterface:make_main_rpcinterface
EOC

# Start supervisord
exec /usr/bin/supervisord -c /etc/supervisor/conf.d/supervisord.conf
