# TestContainers Configuration

This directory contains TestResourceProvider implementations for various containers used in tests.

## Available Containers

1. **Kafka** - Apache Kafka message broker
    - Configuration property: `testcontainers.kafka`
    - Default image: `apache/kafka:latest`
    - This is really designed to use "vanilla" kafka - confluent kafka should just use the default micronaut setup.
    ```kotlin
        implementation(project(":yappy-test-resources:apache-kafak-test-resource"))
        testResourcesImplementation(mn.testcontainers.kafka)
        testResourcesImplementation(project(":yappy-test-resources:consul-test-resource"))
    ```


2. **Consul** - Hashicorp Consul service discovery and configuration
    - Configuration property: `testcontainers.consul`
    - Default image: `hashicorp/consul`
    - Include in build.gradle.kts (adjust to groovy if ya need)
    ```kotlin
        implementation(project(":yappy-test-resources:consul-test-resource"))
        testResourcesImplementation(mn.testcontainers.consul)
        testResourcesImplementation(project(":yappy-test-resources:consul-test-resource"))
    ```

3. **Moto** - AWS service mocking
    - Configuration property: `testcontainers.moto`
    - Default image: `motoserver/moto:latest`
    - Provides AWS Glue Schema Registry emulation
    - Include in build.gradle.kts (adjust to groovy if ya need)
    ```kotlin
        implementation(project(":yappy-test-resources:moto-test-resource"))
        testResourcesImplementation(project(":yappy-test-resources:moto-test-resource"))
    ```

4. **Apicurio** - Apicurio Schema Registry
    - Configuration property: `testcontainers.apicurio`
    - Default image: `apicurio/apicurio-registry:latest`
    - Include in build.gradle.kts if your project
   ```kotlin
        implementation(project(":yappy-test-resources:moto-test-resource"))
        testResourcesImplementation(project(":yappy-test-resources:moto-test-resource"))
    ```
  
