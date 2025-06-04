#!/bin/bash
set -e

# SSL Certificate Setup for YAPPY
echo "=== YAPPY SSL Certificate Setup ==="
echo ""

# Configuration
CERT_DIR="./certs"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-changeit}"
TRUSTSTORE_PASSWORD="${TRUSTSTORE_PASSWORD:-changeit}"
VALIDITY_DAYS=3650

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Create certificate directory
mkdir -p "$CERT_DIR"

# Function to check if user has provided certificates
check_user_certs() {
    echo -e "${BLUE}Checking for user-provided certificates...${NC}"
    
    if [ -f "$CERT_DIR/server.crt" ] && [ -f "$CERT_DIR/server.key" ]; then
        echo -e "${GREEN}Found user certificates:${NC}"
        echo "  - Certificate: $CERT_DIR/server.crt"
        echo "  - Private key: $CERT_DIR/server.key"
        
        # Check for CA certificate
        if [ -f "$CERT_DIR/ca.crt" ]; then
            echo "  - CA certificate: $CERT_DIR/ca.crt"
        else
            echo -e "${YELLOW}Warning: No CA certificate found. Using server cert as CA.${NC}"
            cp "$CERT_DIR/server.crt" "$CERT_DIR/ca.crt"
        fi
        
        return 0
    else
        return 1
    fi
}

# Function to generate self-signed certificates
generate_self_signed() {
    echo -e "${YELLOW}Generating self-signed certificates...${NC}"
    
    # Generate private key
    openssl genrsa -out "$CERT_DIR/ca.key" 4096
    
    # Generate CA certificate
    openssl req -new -x509 -days $VALIDITY_DAYS \
        -key "$CERT_DIR/ca.key" \
        -out "$CERT_DIR/ca.crt" \
        -subj "/C=US/O=YAPPY/CN=YAPPY CA"
    
    # Generate server private key
    openssl genrsa -out "$CERT_DIR/server.key" 4096
    
    # Generate certificate request
    openssl req -new \
        -key "$CERT_DIR/server.key" \
        -out "$CERT_DIR/server.csr" \
        -subj "/C=US/O=YAPPY/CN=*.yappy.local"
    
    # Create extensions file for SAN
    cat > "$CERT_DIR/extensions.cnf" <<EOF
subjectAltName = DNS:localhost,DNS:*.yappy.local,DNS:consul,DNS:kafka,DNS:opensearch,DNS:apicurio,IP:127.0.0.1
EOF
    
    # Sign the certificate
    openssl x509 -req -days $VALIDITY_DAYS \
        -in "$CERT_DIR/server.csr" \
        -CA "$CERT_DIR/ca.crt" \
        -CAkey "$CERT_DIR/ca.key" \
        -CAcreateserial \
        -out "$CERT_DIR/server.crt" \
        -extfile "$CERT_DIR/extensions.cnf"
    
    # Clean up
    rm -f "$CERT_DIR/server.csr" "$CERT_DIR/extensions.cnf"
    
    echo -e "${GREEN}Self-signed certificates generated successfully!${NC}"
}

# Function to create Java keystores
create_java_keystores() {
    echo -e "${BLUE}Creating Java keystores...${NC}"
    
    # Create PKCS12 keystore from PEM files
    openssl pkcs12 -export \
        -in "$CERT_DIR/server.crt" \
        -inkey "$CERT_DIR/server.key" \
        -out "$CERT_DIR/server.p12" \
        -name server \
        -password pass:$KEYSTORE_PASSWORD
    
    # Convert PKCS12 to JKS for Kafka
    keytool -importkeystore \
        -srckeystore "$CERT_DIR/server.p12" \
        -srcstoretype PKCS12 \
        -srcstorepass $KEYSTORE_PASSWORD \
        -destkeystore "$CERT_DIR/kafka.keystore.jks" \
        -deststoretype JKS \
        -deststorepass $KEYSTORE_PASSWORD \
        -noprompt 2>/dev/null || true
    
    # Create truststore
    keytool -import \
        -file "$CERT_DIR/ca.crt" \
        -alias ca \
        -keystore "$CERT_DIR/truststore.jks" \
        -storepass $TRUSTSTORE_PASSWORD \
        -noprompt
    
    # Create Kafka truststore
    cp "$CERT_DIR/truststore.jks" "$CERT_DIR/kafka.truststore.jks"
    
    echo -e "${GREEN}Java keystores created successfully!${NC}"
}

# Function to create Consul SSL configuration
create_consul_config() {
    echo -e "${BLUE}Creating Consul SSL configuration...${NC}"
    
    cat > "$CERT_DIR/consul-ssl.json" <<EOF
{
  "verify_incoming": false,
  "verify_outgoing": false,
  "verify_server_hostname": false,
  "ca_file": "/consul/config/certs/ca.crt",
  "cert_file": "/consul/config/certs/server.crt",
  "key_file": "/consul/config/certs/server.key",
  "ports": {
    "https": 8501,
    "http": -1
  }
}
EOF
    
    echo -e "${GREEN}Consul SSL configuration created!${NC}"
}

# Function to create OpenSearch security configuration
create_opensearch_config() {
    echo -e "${BLUE}Creating OpenSearch security configuration...${NC}"
    
    cat > "./opensearch-security.yml" <<EOF
# OpenSearch security configuration
_meta:
  type: "config"
  config_version: 2

config:
  dynamic:
    authc:
      basic_internal_auth_domain:
        http_enabled: true
        transport_enabled: true
        order: 0
        http_authenticator:
          type: basic
          challenge: true
        authentication_backend:
          type: internal
    authz:
      roles_from_myldap:
        http_enabled: true
        transport_enabled: true
        authorization_backend:
          type: internal
EOF
    
    echo -e "${GREEN}OpenSearch security configuration created!${NC}"
}

# Main execution
echo "SSL Setup for YAPPY Containers"
echo "==============================="
echo ""

# Check if user has provided certificates
if check_user_certs; then
    echo -e "${GREEN}Using user-provided certificates${NC}"
    CERT_TYPE="signed"
else
    echo -e "${YELLOW}No user certificates found in $CERT_DIR${NC}"
    echo ""
    echo "Options:"
    echo "1. Generate self-signed certificates (for development/testing)"
    echo "2. Use your own certificates"
    echo ""
    echo "To use your own certificates, place them in $CERT_DIR:"
    echo "  - server.crt (certificate)"
    echo "  - server.key (private key)"
    echo "  - ca.crt (CA certificate, optional)"
    echo ""
    read -p "Generate self-signed certificates? (Y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Nn]$ ]]; then
        generate_self_signed
        CERT_TYPE="self-signed"
    else
        echo "Please provide certificates and run again."
        echo "Or run: ./setup-signed-cert.sh for guided setup"
        exit 1
    fi
fi

# Create Java keystores
create_java_keystores

# Create service configurations
create_consul_config
create_opensearch_config

# Set permissions
chmod 600 "$CERT_DIR"/*.key "$CERT_DIR"/*.jks "$CERT_DIR"/*.p12 2>/dev/null || true
chmod 644 "$CERT_DIR"/*.crt "$CERT_DIR"/*.json 2>/dev/null || true

echo ""
echo -e "${GREEN}SSL setup complete!${NC}"
echo ""
echo "Certificate locations:"
echo "  - CA Certificate:      $CERT_DIR/ca.crt"
echo "  - Server Certificate:  $CERT_DIR/server.crt"
echo "  - Server Private Key:  $CERT_DIR/server.key"
echo "  - Java Keystore:       $CERT_DIR/kafka.keystore.jks"
echo "  - Java Truststore:     $CERT_DIR/truststore.jks"
echo ""
echo "To start services with SSL:"
echo "  docker-compose -f docker-compose-ssl.yml up -d"
echo ""
echo "Service URLs (HTTPS):"
echo "  - Consul:      https://localhost:8501"
echo "  - Apicurio:    https://localhost:8443"
echo "  - OpenSearch:  https://localhost:9200 (admin:admin)"
echo "  - Dashboards:  https://localhost:5601"