# Test Resources Guidelines

## Common Issues and Solutions

### Consul Test Resource Provider Issues

#### Issue: ConsulTestResourceProviderPropertyInjectionTest Fails to Start

**Symptoms:**
- Tests that depend on Consul test resources fail with errors like:
  ```
  Error resolving property value [consul.client.host]. Property doesn't exist
  ```
- The ConsulTestResourceProvider is properly registered in META-INF/services, but the properties it should provide are not available.

**Cause:**
The issue is related to the `sharedServer` setting in the Micronaut test resources configuration. When `sharedServer` is set to `true`, all tests share a single test resources server. This can cause issues if:

1. The shared server is not properly initialized before tests run
2. There are conflicts between different tests using the same server
3. The server state is not properly reset between tests

**Solution:**
Set `sharedServer` to `false` in the build.gradle.kts file:

```kotlin
testResources {
    enabled.set(true)
    inferClasspath.set(true)
    additionalModules.add(KnownModules.KAFKA)
    clientTimeout.set(60)
    sharedServer.set(false) // Change from true to false
}
```

This ensures that each test uses its own test resources server, which prevents conflicts and ensures proper initialization.

**Prevention:**
- When working with test resources, prefer using `sharedServer.set(false)` unless you have a specific reason to share the server across tests.
- If you do need to share the server, ensure that your tests properly clean up after themselves and don't depend on the state of previous tests.
- Always verify that your test resources are properly registered in META-INF/services.

### General Test Resources Best Practices

1. **Explicit Configuration**: Always explicitly configure test resources in your test configuration files (application-test.yml or similar).
2. **Isolation**: Keep tests isolated by using separate test resources or ensuring proper cleanup between tests.
3. **Logging**: Enable debug logging for test resources to help diagnose issues:
   ```yaml
   logging:
     level:
       io.micronaut.testresources: DEBUG
   ```
4. **Dependency Management**: Ensure that your test resources dependencies are properly declared in your build.gradle.kts file.
5. **Test Resource Providers**: When creating custom test resource providers, ensure they are properly registered in META-INF/services.