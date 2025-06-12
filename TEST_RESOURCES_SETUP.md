# Test Resources Setup Guide

This document provides a standardized approach for setting up Micronaut Test Resources in any module to prevent recurring test container issues.

## Problem Statement

Test failures due to missing `consul.client.host` and other test resource properties are caused by incomplete test resources configuration. This happens when modules are missing required dependencies or configuration files.

## Required Setup for Test Resources

### 1. build.gradle.kts Dependencies

Every module using test resources MUST have these dependencies:

```kotlin
plugins {
    id("io.micronaut.library")
    id("io.micronaut.test-resources")  // REQUIRED
}

dependencies {
    // Test resources implementation dependencies - CRITICAL
    testResourcesImplementation("org.testcontainers:kafka:1.21.1")
    testResourcesImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:consul-test-resource"))
    
    // Test implementation dependencies (for test code access)
    testImplementation(project(":yappy-test-resources:consul-test-resource"))
    testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testImplementation(project(":yappy-test-resources:apicurio-test-resource"))
}

micronaut {
    testResources {
        enabled = true
        sharedServer = true
        clientTimeout = 60
    }
}
```

### 2. test-resources.properties File

Create `src/test/resources/test-resources.properties`:

```properties
# Enable test containers
testcontainers.enabled=true
testcontainers.consul=true

# DO NOT set consul properties here - they will prevent test resources from resolving them dynamically!
# Test resources will provide:
# - consul.client.host
# - consul.client.port
# - kafka.bootstrap.servers
# - apicurio.registry.url
```

### 3. Test Resources Initialization Test

Create an initialization test to verify containers start properly:

```java
@MicronautTest
@Property(name = "kafka.slot-manager.enabled", value = "false") // Disable complex components
class TestResourcesInitializationTest {
    
    @Property(name = "consul.client.host")
    String consulHost;
    
    @Test
    void testResourcesInitialization() {
        assertNotNull(consulHost, "consul.client.host should be provided by test resources");
        // More assertions...
    }
}
```

## Checklist for New Modules

Before adding tests to any module, verify:

- [ ] `io.micronaut.test-resources` plugin is enabled
- [ ] All `testResourcesImplementation` dependencies are added
- [ ] `micronaut.testResources` configuration is present
- [ ] `test-resources.properties` file exists
- [ ] Initialization test passes
- [ ] Test resources are not being overridden in application-test.yml

## Common Mistakes

1. **Only adding `testImplementation` dependencies** - Must also add `testResourcesImplementation`
2. **Missing plugin** - Must have `id("io.micronaut.test-resources")`
3. **Setting properties in application-test.yml** - This prevents test resources from providing them
4. **Missing test-resources.properties** - Containers won't start without this file

## Working Examples

- ✅ `yappy-orchestrator/kafka-service` - Complete setup
- ✅ `yappy-orchestrator/engine-integration-test` - Now fixed

## Debugging Test Resources Issues

If tests fail with "missing property" errors:

1. Check if test containers are starting in logs
2. Verify `testResourcesImplementation` dependencies
3. Ensure `test-resources.properties` exists
4. Look for property overrides in configuration files
5. Run the initialization test first

## Prevention Strategy

1. **Template Module** - Use kafka-service as the template for test resources setup
2. **Copy-Paste Approach** - Copy the entire test resources setup when creating new modules
3. **Code Review** - Always check test resources setup in PRs adding tests
4. **Documentation** - This guide should be consulted for any test resource issues