#!/bin/bash
set -e

# Automated setup for rokkon.com wildcard certificate
echo "=== Setting up *.rokkon.com SSL Certificate for YAPPY ==="
echo ""

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

# Configuration
CERT_DIR="./certs"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-changeit}"

# Certificate paths
CERT_PATH="./certs/comodo_certs/CER-CRT-Files/STAR_rokkon_com.crt"
KEY_PATH="./certs/generated_certs/server.key"
CA_BUNDLE="./certs/comodo_certs/CER-CRT-Files/My_CA_Bundle.ca-bundle"

# Verify files exist
echo -e "${BLUE}Verifying certificate files...${NC}"

if [ ! -f "$CERT_PATH" ]; then
    echo -e "${RED}Error: Server certificate not found at: $CERT_PATH${NC}"
    exit 1
fi

if [ ! -f "$KEY_PATH" ]; then
    echo -e "${RED}Error: Private key not found at: $KEY_PATH${NC}"
    exit 1
fi

if [ ! -f "$CA_BUNDLE" ]; then
    echo -e "${RED}Error: CA bundle not found at: $CA_BUNDLE${NC}"
    exit 1
fi

echo -e "${GREEN}✓ All certificate files found${NC}"

# Create certificate directory if needed
mkdir -p "$CERT_DIR"

# Copy files with standard names
echo ""
echo -e "${BLUE}Setting up certificate files...${NC}"
cp "$CERT_PATH" "$CERT_DIR/server.crt"
cp "$KEY_PATH" "$CERT_DIR/server.key"
cp "$CA_BUNDLE" "$CERT_DIR/ca.crt"

# Verify certificate details
echo ""
echo -e "${BLUE}Certificate Information:${NC}"
echo "Domain: *.rokkon.com"
openssl x509 -in "$CERT_DIR/server.crt" -noout -subject -dates

# Verify key matches certificate
echo ""
echo -n "Verifying private key matches certificate... "
cert_modulus=$(openssl x509 -noout -modulus -in "$CERT_DIR/server.crt" 2>/dev/null | openssl md5)
key_modulus=$(openssl rsa -noout -modulus -in "$CERT_DIR/server.key" 2>/dev/null | openssl md5)

if [ "$cert_modulus" = "$key_modulus" ]; then
    echo -e "${GREEN}✓ Match confirmed${NC}"
else
    echo -e "${RED}✗ Key does not match certificate!${NC}"
    echo "Certificate modulus: $cert_modulus"
    echo "Key modulus: $key_modulus"
    exit 1
fi

# Create Java keystores
echo ""
echo -e "${BLUE}Creating Java keystores...${NC}"

# Create PKCS12 keystore
echo "Creating PKCS12 keystore..."
openssl pkcs12 -export \
    -in "$CERT_DIR/server.crt" \
    -inkey "$CERT_DIR/server.key" \
    -certfile "$CERT_DIR/ca.crt" \
    -out "$CERT_DIR/server.p12" \
    -name "*.rokkon.com" \
    -password pass:$KEYSTORE_PASSWORD

# Create JKS keystore for Kafka
echo "Creating JKS keystore for Kafka..."
keytool -importkeystore \
    -srckeystore "$CERT_DIR/server.p12" \
    -srcstoretype PKCS12 \
    -srcstorepass $KEYSTORE_PASSWORD \
    -destkeystore "$CERT_DIR/kafka.keystore.jks" \
    -deststoretype JKS \
    -deststorepass $KEYSTORE_PASSWORD \
    -noprompt 2>/dev/null || {
    echo -e "${YELLOW}Using PKCS12 format for Kafka${NC}"
    cp "$CERT_DIR/server.p12" "$CERT_DIR/kafka.keystore.p12"
}

# Create truststore with CA bundle
echo "Creating truststore..."
# Import each certificate from the bundle
csplit -z -f "$CERT_DIR/ca-" -b "%02d.crt" "$CERT_DIR/ca.crt" '/-----BEGIN CERTIFICATE-----/' '{*}' >/dev/null 2>&1

# Create new truststore
rm -f "$CERT_DIR/truststore.jks"
i=0
for cert in "$CERT_DIR"/ca-*.crt; do
    if [ -f "$cert" ]; then
        keytool -import \
            -file "$cert" \
            -alias "ca-$i" \
            -keystore "$CERT_DIR/truststore.jks" \
            -storepass $KEYSTORE_PASSWORD \
            -noprompt >/dev/null 2>&1
        ((i++))
    fi
done

# Clean up temporary files
rm -f "$CERT_DIR"/ca-*.crt

# Copy for Kafka
cp "$CERT_DIR/truststore.jks" "$CERT_DIR/kafka.truststore.jks"

echo -e "${GREEN}✓ Keystores created successfully!${NC}"

# Create Consul SSL configuration for rokkon.com
echo ""
echo -e "${BLUE}Creating service configurations...${NC}"
cat > "$CERT_DIR/consul-ssl.json" <<EOF
{
  "verify_incoming": false,
  "verify_outgoing": true,
  "verify_server_hostname": true,
  "ca_file": "/consul/config/certs/ca.crt",
  "cert_file": "/consul/config/certs/server.crt",
  "key_file": "/consul/config/certs/server.key",
  "ports": {
    "https": 8501,
    "http": -1
  }
}
EOF

# Update environment for rokkon.com domain
cat > "$CERT_DIR/rokkon.env" <<EOF
# Rokkon.com SSL Configuration
export YAPPY_DOMAIN=rokkon.com
export YAPPY_USE_SSL=true
export KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD
export TRUSTSTORE_PASSWORD=$KEYSTORE_PASSWORD

# Service hostnames (update docker-compose to use these)
export CONSUL_HOSTNAME=consul.rokkon.com
export KAFKA_HOSTNAME=kafka.rokkon.com
export OPENSEARCH_HOSTNAME=opensearch.rokkon.com
export APICURIO_HOSTNAME=apicurio.rokkon.com
EOF

# Set permissions
chmod 600 "$CERT_DIR"/*.key "$CERT_DIR"/*.jks "$CERT_DIR"/*.p12 2>/dev/null || true
chmod 644 "$CERT_DIR"/*.crt "$CERT_DIR"/*.json "$CERT_DIR"/*.env 2>/dev/null || true

echo ""
echo -e "${GREEN}SSL setup complete for *.rokkon.com!${NC}"
echo ""
echo "Certificate configuration:"
echo "  - Domain:         *.rokkon.com"
echo "  - Certificate:    $CERT_DIR/server.crt"
echo "  - Private key:    $CERT_DIR/server.key"
echo "  - CA bundle:      $CERT_DIR/ca.crt"
echo "  - Java keystore:  $CERT_DIR/kafka.keystore.jks"
echo "  - Truststore:     $CERT_DIR/truststore.jks"
echo ""
echo "To use SSL with your domain:"
echo "  source $CERT_DIR/rokkon.env"
echo "  ./yappy-compose-ssl.sh --ssl up"
echo ""
echo -e "${GREEN}Your rokkon.com wildcard certificate is now configured!${NC}"
echo -e "${YELLOW}Note: Update your DNS to point *.rokkon.com to this server${NC}"