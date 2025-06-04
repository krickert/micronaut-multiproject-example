#!/bin/bash
set -e

# Setup script for signed certificates
echo "=== YAPPY Signed Certificate Setup ==="
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

# Create certificate directory
mkdir -p "$CERT_DIR"

# Function to identify certificate files
identify_cert_files() {
    echo -e "${BLUE}Looking for certificate files...${NC}"
    echo ""
    echo "Please provide the paths to your certificate files:"
    echo ""
    
    # Server certificate
    read -p "Path to server certificate (.crt, .pem, .cer): " cert_path
    if [ ! -f "$cert_path" ]; then
        echo -e "${RED}Error: Certificate file not found: $cert_path${NC}"
        exit 1
    fi
    
    # Private key
    read -p "Path to private key (.key, .pem): " key_path
    if [ ! -f "$key_path" ]; then
        echo -e "${RED}Error: Private key file not found: $key_path${NC}"
        exit 1
    fi
    
    # CA certificate
    read -p "Path to CA certificate/bundle (.crt, .pem, .ca-bundle) [optional]: " ca_path
    
    # Copy files with standard names
    echo ""
    echo -e "${BLUE}Copying certificate files...${NC}"
    cp "$cert_path" "$CERT_DIR/server.crt"
    cp "$key_path" "$CERT_DIR/server.key"
    
    if [ -n "$ca_path" ] && [ -f "$ca_path" ]; then
        cp "$ca_path" "$CERT_DIR/ca.crt"
    else
        echo -e "${YELLOW}No CA certificate provided. Using server cert as CA.${NC}"
        cp "$CERT_DIR/server.crt" "$CERT_DIR/ca.crt"
    fi
    
    echo -e "${GREEN}Certificate files copied successfully!${NC}"
}

# Function to verify certificate
verify_certificate() {
    echo ""
    echo -e "${BLUE}Verifying certificate...${NC}"
    
    # Check certificate details
    echo "Certificate subject:"
    openssl x509 -in "$CERT_DIR/server.crt" -noout -subject
    
    echo ""
    echo "Certificate validity:"
    openssl x509 -in "$CERT_DIR/server.crt" -noout -dates
    
    echo ""
    echo "Certificate SANs:"
    openssl x509 -in "$CERT_DIR/server.crt" -noout -text | grep -A1 "Subject Alternative Name" || echo "No SANs found"
    
    # Verify key matches certificate
    echo ""
    echo -n "Verifying private key matches certificate... "
    cert_modulus=$(openssl x509 -noout -modulus -in "$CERT_DIR/server.crt" | openssl md5)
    key_modulus=$(openssl rsa -noout -modulus -in "$CERT_DIR/server.key" 2>/dev/null | openssl md5)
    
    if [ "$cert_modulus" = "$key_modulus" ]; then
        echo -e "${GREEN}✓ Match confirmed${NC}"
    else
        echo -e "${RED}✗ Key does not match certificate!${NC}"
        exit 1
    fi
}

# Function to create Java keystores
create_keystores() {
    echo ""
    echo -e "${BLUE}Creating Java keystores...${NC}"
    
    # Remove any existing keystores
    rm -f "$CERT_DIR"/*.jks "$CERT_DIR"/*.p12 2>/dev/null || true
    
    # Create PKCS12 keystore
    echo "Creating PKCS12 keystore..."
    openssl pkcs12 -export \
        -in "$CERT_DIR/server.crt" \
        -inkey "$CERT_DIR/server.key" \
        -certfile "$CERT_DIR/ca.crt" \
        -out "$CERT_DIR/server.p12" \
        -name server \
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
        echo -e "${YELLOW}Note: Using PKCS12 format for Kafka (JKS creation failed)${NC}"
        cp "$CERT_DIR/server.p12" "$CERT_DIR/kafka.keystore.jks"
    }
    
    # Create truststore
    echo "Creating truststore..."
    keytool -import \
        -file "$CERT_DIR/ca.crt" \
        -alias ca \
        -keystore "$CERT_DIR/truststore.jks" \
        -storepass $KEYSTORE_PASSWORD \
        -noprompt
    
    # Copy for Kafka
    cp "$CERT_DIR/truststore.jks" "$CERT_DIR/kafka.truststore.jks"
    
    echo -e "${GREEN}Keystores created successfully!${NC}"
}

# Function to update docker-compose for your domain
update_compose_config() {
    echo ""
    echo -e "${BLUE}Updating configuration for your domain...${NC}"
    
    # Extract domain from certificate
    domain=$(openssl x509 -in "$CERT_DIR/server.crt" -noout -subject | sed -n 's/.*CN=\([^,]*\).*/\1/p' | sed 's/\*\.//')
    
    if [ -n "$domain" ]; then
        echo "Detected domain: $domain"
        read -p "Use this domain for service hostnames? (y/N) " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            echo "export YAPPY_DOMAIN=$domain" >> "$CERT_DIR/domain.env"
            echo -e "${GREEN}Domain configuration saved${NC}"
        fi
    fi
}

# Main execution
echo "This script will help you set up your signed SSL certificate for YAPPY."
echo ""

# Step 1: Identify and copy certificate files
identify_cert_files

# Step 2: Verify certificate
verify_certificate

# Step 3: Create Java keystores
create_keystores

# Step 4: Update configuration
update_compose_config

# Set permissions
chmod 600 "$CERT_DIR"/*.key "$CERT_DIR"/*.jks "$CERT_DIR"/*.p12 2>/dev/null || true
chmod 644 "$CERT_DIR"/*.crt 2>/dev/null || true

echo ""
echo -e "${GREEN}SSL setup complete!${NC}"
echo ""
echo "Certificate files created:"
echo "  - Server certificate: $CERT_DIR/server.crt"
echo "  - Private key:        $CERT_DIR/server.key"
echo "  - CA certificate:     $CERT_DIR/ca.crt"
echo "  - PKCS12 keystore:    $CERT_DIR/server.p12"
echo "  - Java keystore:      $CERT_DIR/kafka.keystore.jks"
echo "  - Java truststore:    $CERT_DIR/truststore.jks"
echo ""
echo "To start services with SSL:"
echo "  ./yappy-compose-ssl.sh --ssl up"
echo ""
echo -e "${YELLOW}Note: Since you have a signed certificate, browsers will trust your services!${NC}"