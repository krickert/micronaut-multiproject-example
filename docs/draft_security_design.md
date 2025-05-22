
# YAPPY Security Design

This document outlines the security design for the YAPPY (Yet Another Pipeline Processor) platform, focusing on implementing Micronaut security features to ensure a secure ecosystem for all components.

## Table of Contents
1. [Security Overview](#security-overview)
2. [User Roles and Access Control](#user-roles-and-access-control)
3. [SSL/TLS Configuration](#ssltls-configuration)
4. [OAuth/OIDC Integration](#oauthoidc-integration)
5. [Service-to-Service Authentication](#service-to-service-authentication)
6. [Kafka Security](#kafka-security)
7. [Role Management Workflows](#role-management-workflows)
8. [Additional Security Considerations](#additional-security-considerations)

## Security Overview

YAPPY is a distributed system with multiple components communicating via gRPC and Kafka. Security must be implemented at multiple levels:

1. **Transport Security**: Secure communication between all components using SSL/TLS
2. **Authentication**: Verify the identity of users and services
3. **Authorization**: Control access to resources based on identity
4. **Data Protection**: Protect sensitive data at rest and in transit
5. **Audit Logging**: Track security-relevant events for compliance and troubleshooting

## User Roles and Access Control

### User Levels

The YAPPY platform will support the following user roles:

| Role | Description | Access Level |
|------|-------------|--------------|
| **System Administrator** | Manages the entire YAPPY platform | Full access to all components, configuration, and monitoring |
| **Pipeline Administrator** | Manages pipeline configurations | Create, modify, and delete pipeline configurations; view all pipeline statuses |
| **Pipeline Designer** | Creates and modifies pipelines | Create and modify pipeline designs; limited ability to deploy pipelines |
| **Data Scientist** | Works with data processing pipelines | Create and run data science pipelines; access to specific pipeline outputs |
| **Developer** | Develops pipeline modules | Create and test pipeline modules; limited access to pipeline configurations |
| **Monitoring User** | Monitors pipeline status | View-only access to pipeline status and metrics |
| **API Consumer** | External system accessing YAPPY APIs | Limited access to specific APIs based on assigned permissions |

### Front-End Security Roles

For the front-end applications (Admin UI, Pipeline Editor UI, Pipeline Status UI), the following security roles should be implemented:

1. **admin**: Full access to all UI features
2. **pipeline_admin**: Access to pipeline management features
3. **pipeline_designer**: Access to pipeline design features
4. **pipeline_viewer**: View-only access to pipelines
5. **module_developer**: Access to module development and testing features
6. **monitoring**: Access to monitoring and status features

These roles will be mapped to OAuth scopes when implementing authentication.

## SSL/TLS Configuration

### Micronaut Netty Server SSL Setup

To configure SSL for the Micronaut Netty server, follow these steps:

1. **Generate or Obtain SSL Certificates**:
    - For production: Obtain certificates from a trusted Certificate Authority (CA)
    - For development: Generate self-signed certificates using tools like keytool

2. **Configure SSL in application.yml**:

```yaml
micronaut:
  server:
    ssl:
      enabled: true
      buildSelfSigned: false  # Set to true for development only
      port: 8443  # HTTPS port
      key-store:
        path: classpath:keystore.p12
        password: your-keystore-password
        type: PKCS12
```

3. **Configure SSL for gRPC Server**:

```yaml
grpc:
  server:
    port: 9443
    keep-alive-time: 5m
    max-inbound-message-size: 1MB
    ssl:
      enabled: true
      key-store:
        path: classpath:keystore.p12
        password: your-keystore-password
        type: PKCS12
```

For detailed instructions, refer to the [Micronaut Security - Securing the Server](https://micronaut-projects.github.io/micronaut-security/latest/guide/#server) documentation.

### SSL for Service-to-Service Communication

For gRPC client-server communication between services:

```yaml
grpc:
  client:
    plaintext: false
    max-retry-attempts: 3
    channels:
      service-name:
        address: 'https://service-hostname:9443'
        plaintext: false
        max-retry-attempts: 3
        negotiation-type: TLS
        ssl:
          trust-store:
            path: classpath:truststore.p12
            password: your-truststore-password
            type: PKCS12
```

## OAuth/OIDC Integration

### Okta Integration

To integrate Okta as the OAuth/OIDC provider:

1. **Create an Okta Application**:
    - Sign up for an Okta Developer account if you don't have one
    - Create a new Web Application in the Okta Developer Console
    - Configure the redirect URIs for your application
    - Note the Client ID and Client Secret

2. **Configure Micronaut Security with Okta**:

```yaml
micronaut:
  security:
    enabled: true
    token:
      jwt:
        enabled: true
        signatures:
          jwks:
            okta:
              url: https://your-okta-domain/oauth2/default/v1/keys
    oauth2:
      clients:
        okta:
          client-id: your-client-id
          client-secret: your-client-secret
          openid:
            issuer: https://your-okta-domain/oauth2/default
    endpoints:
      logout:
        get-allowed: true
    authentication: idtoken
```

For detailed instructions, refer to the [Micronaut Security - OAuth 2.0](https://micronaut-projects.github.io/micronaut-security/latest/guide/#oauth) documentation.

### OAuth Roles for Front-End

Define the following OAuth scopes in your Okta application:

1. **yappy:admin**: Full administrative access
2. **yappy:pipeline_admin**: Pipeline administration
3. **yappy:pipeline_designer**: Pipeline design
4. **yappy:pipeline_viewer**: Pipeline viewing
5. **yappy:module_developer**: Module development
6. **yappy:monitoring**: Monitoring access

Map these scopes to Micronaut roles:

```yaml
micronaut:
  security:
    token:
      jwt:
        claims-validators:
          scope:
            patterns:
              - yappy:admin
              - yappy:pipeline_admin
              - yappy:pipeline_designer
              - yappy:pipeline_viewer
              - yappy:module_developer
              - yappy:monitoring
    roles:
      mapping:
        admin:
          - yappy:admin
        pipeline_admin:
          - yappy:pipeline_admin
          - yappy:admin
        pipeline_designer:
          - yappy:pipeline_designer
          - yappy:pipeline_admin
          - yappy:admin
        pipeline_viewer:
          - yappy:pipeline_viewer
          - yappy:pipeline_designer
          - yappy:pipeline_admin
          - yappy:admin
        module_developer:
          - yappy:module_developer
          - yappy:admin
        monitoring:
          - yappy:monitoring
          - yappy:admin
```

## Service-to-Service Authentication

For service-to-service communication, implement the following:

### Application Roles for Service-to-Service Communication

Define the following service roles:

1. **yappy-engine**: Core engine services
2. **yappy-module**: Pipeline module services
3. **yappy-admin**: Administrative services
4. **yappy-connector**: Connector services
5. **yappy-schema-registry**: Schema registry services

### Client Credentials Flow

Use OAuth 2.0 Client Credentials flow for service-to-service authentication:

```yaml
micronaut:
  security:
    token:
      jwt:
        enabled: true
        signatures:
          jwks:
            okta:
              url: https://your-okta-domain/oauth2/default/v1/keys
    oauth2:
      clients:
        service-client:
          client-id: ${SERVICE_CLIENT_ID}
          client-secret: ${SERVICE_CLIENT_SECRET}
          token-url: https://your-okta-domain/oauth2/default/v1/token
          scopes:
            - yappy:service
```

### Service Authentication Implementation

1. **Create a @Client for Service Communication**:

```java
@Client("service-name")
@Requires(property = "services.service-name.enabled", value = "true")
public interface ServiceClient {
    @Get("/api/resource")
    HttpResponse<ResourceResponse> getResource();
}
```

2. **Configure the Client with OAuth**:

```yaml
micronaut:
  http:
    services:
      service-name:
        url: https://service-hostname:8443
        path: /
        read-timeout: 5s
        oauth2:
          client-name: service-client
```

## Kafka Security

### Kafka Security Configuration

Configure Kafka security with the following features:

1. **SSL/TLS Encryption**:

```yaml
kafka:
  bootstrap:
    servers: kafka-broker:9093
  security:
    protocol: SSL
  ssl:
    key-store-location: classpath:kafka-keystore.p12
    key-store-password: ${KAFKA_KEYSTORE_PASSWORD}
    trust-store-location: classpath:kafka-truststore.p12
    trust-store-password: ${KAFKA_TRUSTSTORE_PASSWORD}
    key-password: ${KAFKA_KEY_PASSWORD}
```

2. **SASL Authentication**:

```yaml
kafka:
  bootstrap:
    servers: kafka-broker:9093
  security:
    protocol: SASL_SSL
  sasl:
    mechanism: PLAIN
    jaas:
      config: org.apache.kafka.common.security.plain.PlainLoginModule required username="${KAFKA_USERNAME}" password="${KAFKA_PASSWORD}";
  ssl:
    # SSL configuration as above
```

### Kafka Topic Access Control

For applications to read Kafka topics, implement the following:

1. **Define Topic-Specific Roles**:
    - **yappy-topic-producer-[topic-name]**: Role for services that produce to a specific topic
    - **yappy-topic-consumer-[topic-name]**: Role for services that consume from a specific topic

2. **Configure ACLs in Kafka**:

```bash
# Example ACL commands
kafka-acls.sh --bootstrap-server kafka-broker:9093 \
  --command-config admin.properties \
  --add \
  --allow-principal User:service-name \
  --operation Read \
  --group yappy-consumer-group \
  --topic yappy-topic-name

kafka-acls.sh --bootstrap-server kafka-broker:9093 \
  --command-config admin.properties \
  --add \
  --allow-principal User:service-name \
  --operation Write \
  --topic yappy-topic-name
```

3. **Map Service Identities to Kafka Principals**:
    - Use the service's OAuth client ID as the Kafka principal
    - Configure Kafka to validate JWT tokens for authentication

## Role Management Workflows

### Adding New Roles

1. **Administrator Creates Role**:
    - Admin defines new role in Okta/OAuth provider
    - Admin assigns appropriate scopes to the role
    - Admin updates Micronaut security configuration if needed

2. **Assigning Roles to Users**:
    - Admin assigns role to user in Okta/OAuth provider
    - User logs out and logs back in to get new token with updated roles
    - System recognizes new roles and grants appropriate access

### Removing Roles

1. **Administrator Removes Role**:
    - Admin removes role from user in Okta/OAuth provider
    - Admin can optionally force token invalidation for immediate effect
    - On next authentication, user will not have the removed role

### Creating Service Accounts

1. **Administrator Creates Service Account**:
    - Admin creates new client credentials in Okta/OAuth provider
    - Admin assigns appropriate service roles
    - Admin configures the service with the new credentials
    - Admin adds necessary Kafka ACLs if the service needs Kafka access

### Role Audit and Review

1. **Regular Role Review**:
    - Scheduled review of all roles and permissions
    - Verification that principle of least privilege is maintained
    - Removal of unused or unnecessary roles

2. **Role Audit Logging**:
    - All role changes are logged with timestamp, actor, and action
    - Regular audit reports are generated for compliance

## Additional Security Considerations

### Secrets Management

1. **Use Environment Variables or Vault**:
    - Store sensitive configuration in environment variables
    - Consider using HashiCorp Vault for secrets management
    - Integrate with Micronaut's secrets management:

```yaml
micronaut:
  application:
    name: yappy-service
  config-client:
    enabled: true
  http:
    services:
      vault:
        url: https://vault-server:8200
        path: /v1
  security:
    enabled: true
```

### Data Protection

1. **Sensitive Data Handling**:
    - Encrypt sensitive data at rest
    - Use secure random generators for tokens and IDs
    - Implement data masking for logs and error messages

2. **PII Handling**:
    - Identify and classify PII data
    - Implement appropriate controls based on classification
    - Consider pseudonymization where appropriate

### Security Monitoring

1. **Implement Comprehensive Logging**:
    - Log all authentication and authorization events
    - Include correlation IDs for request tracing
    - Use structured logging for easier analysis

2. **Security Metrics**:
    - Track authentication failures
    - Monitor unusual access patterns
    - Set up alerts for potential security incidents

### Container Security

1. **Secure Container Images**:
    - Use minimal base images
    - Scan images for vulnerabilities
    - Implement least privilege in container execution

2. **Runtime Protection**:
    - Implement network policies
    - Use seccomp profiles
    - Consider runtime application self-protection (RASP)

## References

- [Micronaut Security Documentation](https://micronaut-projects.github.io/micronaut-security/latest/guide/)
- [Okta Developer Documentation](https://developer.okta.com/docs/guides/)
- [Kafka Security Documentation](https://kafka.apache.org/documentation/#security)
- [OWASP Top Ten](https://owasp.org/www-project-top-ten/)
- [Micronaut SSL Configuration](https://docs.micronaut.io/latest/guide/#https)