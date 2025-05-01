# Consul Unavailability Tests

This package contains tests that verify how the API behaves when Consul is unavailable.

## Implementation Approach

The tests in this package use mock implementations of `ConsulKvService` and `PipelineConfig` that throw exceptions to simulate Consul being unavailable. This approach allows us to test how the API handles errors when Consul is unavailable without actually having to stop a real Consul instance.

### Key Components

1. **PipelineServiceUnavailabilityTest**: The main test class that verifies the API's behavior when Consul is unavailable.
   - Uses `@Property` annotations to disable Consul-related features in Micronaut.
   - Contains inner classes that provide mock implementations of `ConsulKvService` and `PipelineConfig`.

2. **UnavailableConsulKvService**: A mock implementation of `ConsulKvService` that throws exceptions for all methods.
   - Extends `ConsulKvService` and overrides all methods to throw exceptions.
   - Used to simulate Consul being unavailable.

3. **UnavailablePipelineConfig**: A mock implementation of `PipelineConfig` that throws exceptions for all methods.
   - Extends `PipelineConfig` and overrides all methods to throw exceptions.
   - Used to simulate Consul being unavailable.

### Test Cases

The tests verify that the API returns appropriate error responses (HTTP 500) when Consul is unavailable for the following operations:

1. **List Pipelines**: `GET /api/pipelines`
2. **Create Pipeline**: `POST /api/pipelines`
3. **Get Pipeline**: `GET /api/pipelines/{pipelineName}`
4. **Delete Pipeline**: `DELETE /api/pipelines/{pipelineName}`
5. **Update Pipeline**: `PUT /api/pipelines/{pipelineName}`

## Usage

To run the tests:

```bash
./gradlew :consul-config-service:test --tests "com.krickert.search.config.consul.unavailabletests.PipelineServiceUnavailabilityTest"
```

## Notes

- The tests use Micronaut's dependency injection to replace the real `ConsulKvService` and `PipelineConfig` beans with mock implementations.
- The mock implementations are scoped to the test class using inner classes, so they don't affect other tests.
- The `@Property` annotations are used to disable Consul-related features in Micronaut to prevent it from trying to connect to Consul during the tests.