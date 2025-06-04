# SSL Certificate Setup Guide for YAPPY

## Certificate Requirements

### For Services That Need Certificates

1. **Kafka** - YES, needs Java keystore (JKS format)
2. **Consul** - Can use PEM files directly
3. **OpenSearch** - Can use PEM files directly
4. **Apicurio** - Can use PEM files directly
5. **Micronaut (Engines)** - Prefers PKCS12 or JKS format

### What You Need From Your Signed Certificate

When you have a signed certificate, you typically receive:
- **Certificate file** (.crt, .cer, or .pem) - Your server certificate
- **Private key file** (.key or .pem) - Your private key
- **CA bundle/chain** (.crt, .pem, or .ca-bundle) - Certificate authority chain

### File Naming Convention

Please copy your certificate files to `./certs/` with these names:

```bash
mkdir -p ./certs
cd ./certs

# Copy your files with standard names:
cp /path/to/your-certificate.crt server.crt
cp /path/to/your-private-key.key server.key
cp /path/to/your-ca-bundle.crt ca.crt

# If you have intermediate certificates, combine them:
cat intermediate.crt root-ca.crt > ca.crt
```

## Do You Need Java Keystores?

**YES** - For Kafka and optimal Java application performance, you need Java keystores.

Even with signed certificates, Java applications (like Kafka) require certificates in Java Keystore (JKS) or PKCS12 format.

### Creating Java Keystores from Your Signed Certificate

```bash
# 1. Create PKCS12 keystore (modern format)
openssl pkcs12 -export \
    -in server.crt \
    -inkey server.key \
    -certfile ca.crt \
    -out server.p12 \
    -name server \
    -password pass:changeit

# 2. Create JKS keystore for Kafka (if needed)
keytool -importkeystore \
    -srckeystore server.p12 \
    -srcstoretype PKCS12 \
    -srcstorepass changeit \
    -destkeystore kafka.keystore.jks \
    -deststoretype JKS \
    -deststorepass changeit

# 3. Create truststore with CA certificates
keytool -import \
    -file ca.crt \
    -alias ca \
    -keystore truststore.jks \
    -storepass changeit \
    -noprompt
```

## Quick Setup Steps

1. **Identify your certificate files**:
   ```bash
   ls -la /path/to/your/certs/
   ```

2. **Copy them with standard names**:
   ```bash
   mkdir -p ./certs
   cp /path/to/cert.crt ./certs/server.crt
   cp /path/to/key.key ./certs/server.key
   cp /path/to/ca.crt ./certs/ca.crt
   ```

3. **Run the setup script**:
   ```bash
   ./setup-ssl.sh
   ```
   The script will detect your certificates and create the necessary Java keystores.

4. **Start services with SSL**:
   ```bash
   ./yappy-compose-ssl.sh --ssl up
   ```

## Certificate Formats Explained

- **PEM** (.pem, .crt, .cer) - Base64 encoded, starts with `-----BEGIN CERTIFICATE-----`
- **DER** (.der, .cer) - Binary format
- **PKCS12** (.p12, .pfx) - Binary format containing certificate and private key
- **JKS** (.jks) - Java-specific keystore format

## Verifying Your Certificate

```bash
# Check certificate details
openssl x509 -in server.crt -text -noout

# Verify certificate chain
openssl verify -CAfile ca.crt server.crt

# Check that private key matches certificate
openssl x509 -noout -modulus -in server.crt | openssl md5
openssl rsa -noout -modulus -in server.key | openssl md5
# These should match!
```

## Common Issues

1. **Certificate chain order**: Ensure your server.crt has the server certificate first, then any intermediates
2. **Private key format**: Should be unencrypted for Docker containers
3. **File permissions**: Keep private keys secure (chmod 600)
4. **Hostname verification**: Certificate CN or SAN should match service hostnames