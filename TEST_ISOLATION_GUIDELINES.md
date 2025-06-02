# Test Isolation Guidelines

## Critical Issue: Persistent Configuration File Pollution

### Problem
Tests were writing to the real user configuration file `~/.yappy/engine-bootstrap.properties` instead of using temporary test files. This causes:

1. Test configuration to persist between test runs
2. "testhost.local" UnknownHostException errors in unrelated tests  
3. Test isolation failures affecting multiple test classes
4. Tests depending on external state instead of being self-contained

### Root Cause
- BootstrapConfigServiceImpl and related services write to a persistent configuration file
- Tests don't use temporary/UUID-based filenames
- System properties from one test leak to other tests
- No comprehensive cleanup in test setup/teardown

### Solution Applied
1. **Test isolation in AdminSetupControllerIT, AdminKafkaControllerIT, AdminStatusControllerIT:**
   - Clear ALL consul-related system properties in @BeforeEach and @AfterEach
   - Properties cleared: consul.client.host, consul.client.port, consul.client.acl-token, consul.host, consul.port, yappy.bootstrap.consul.host, yappy.bootstrap.consul.port, yappy.bootstrap.consul.acl_token, yappy.bootstrap.cluster.selected_name

2. **Test isolation in BootstrapConfigServiceImplTest:**
   - Same system property clearing as above
   - Uses temporary files via @TempDir

3. **Moved persistent config file:**
   - Backed up ~/.yappy/engine-bootstrap.properties to prevent pollution

### Prevention Guidelines

#### For New Tests:
1. **Use temporary files:** All config file tests should use UUID-based or test-specific filenames
2. **Clean up thoroughly:** Clear system properties in both @BeforeEach and @AfterEach
3. **Self-contained:** Tests should not depend on external state or files
4. **Prefer Integration over Mocking:** AVOID heavy mockito usage - we have test resources for:
   - Apicurio (schema registry)
   - Kafka 
   - OpenSearch
   - Consul
   - Moto (AWS services)
   
   Use real test containers instead of mocks when possible. Only mock for simple unit tests or when external dependencies are unavoidable.

#### System Properties to Always Clear:
```java
System.clearProperty("consul.client.host");
System.clearProperty("consul.client.port");
System.clearProperty("consul.client.acl-token");
System.clearProperty("consul.host");
System.clearProperty("consul.port");
System.clearProperty("yappy.bootstrap.consul.host");
System.clearProperty("yappy.bootstrap.consul.port");
System.clearProperty("yappy.bootstrap.consul.acl_token");
System.clearProperty("yappy.bootstrap.cluster.selected_name");
System.clearProperty("yappy.consul.configured");
```

#### Recommended Test File Pattern:
```java
// Use UUID or test-specific paths
Path testConfigFile = tempDir.resolve("test-" + UUID.randomUUID() + "-bootstrap.properties");
// OR
Path testConfigFile = tempDir.resolve(getClass().getSimpleName() + "-bootstrap.properties");
```

### **IMPORTANT: This Issue WILL Creep Back**
- New tests that use consul configuration
- Changes to BootstrapConfigServiceImpl
- New admin controller tests
- Integration tests that touch consul

### Regression Detection
Watch for these symptoms:
- UnknownHostException: testhost.local
- Test initialization errors in Admin*IT classes  
- Tests that pass individually but fail when run together
- Consul connection errors in test output

## Retesting Instructions

### Quick Validation (After Any Config-Related Changes):
```bash
# 1. Clean any persistent config
mv ~/.yappy/engine-bootstrap.properties ~/.yappy/engine-bootstrap.properties.backup 2>/dev/null || true

# 2. Run admin controller tests specifically
./gradlew :yappy-engine:test --tests "*Admin*IT"

# 3. Run full test suite
./gradlew :yappy-engine:test

# 4. Check for "testhost.local" in output
grep -i "testhost.local" yappy-engine/build/reports/tests/test/index.html || echo "No testhost.local found - Good!"
```

### Full Test Regression Check:
```bash
# 1. Clean environment
mv ~/.yappy/engine-bootstrap.properties ~/.yappy/engine-bootstrap.properties.backup 2>/dev/null || true
./gradlew clean

# 2. Run tests multiple times to check for state leakage
./gradlew :yappy-engine:test
./gradlew :yappy-engine:test  # Second run should also pass

# 3. Check specific problem tests
./gradlew :yappy-engine:test --tests "BootstrapConfigServiceImplTest"
./gradlew :yappy-engine:test --tests "*Admin*IT"

# 4. Verify no persistent state created
ls ~/.yappy/ | grep -v ".backup" | grep "bootstrap" && echo "WARNING: Test created persistent config!" || echo "Good - no persistent config"
```

### Files Modified in This Fix:
- yappy-engine/src/test/java/com/krickert/yappy/engine/controller/admin/AdminSetupControllerIT.java
- yappy-engine/src/test/java/com/krickert/yappy/engine/controller/admin/AdminKafkaControllerIT.java  
- yappy-engine/src/test/java/com/krickert/yappy/engine/controller/admin/AdminStatusControllerIT.java
- yappy-engine/src/test/java/com/krickert/yappy/bootstrap/service/BootstrapConfigServiceImplTest.java

### Next Steps for Permanent Fix:
1. Modify BootstrapConfigServiceImpl to support test mode with temp files
2. Create a test utility class for consul test isolation
3. Add test validation in CI to detect persistent file creation
4. Consider using TestContainers more extensively for consul isolation