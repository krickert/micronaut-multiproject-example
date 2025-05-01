# Consul Config Service API Priority

This document outlines the prioritized order of API endpoints for implementation in the `consul-config-service`, along with detailed specifications for each endpoint.

## Prioritized Endpoint Implementation Order:

1.  `GET /api/pipelines`
2.  `POST /api/pipelines`
3.  `GET /api/pipelines/{pipelineName}`
4.  `DELETE /api/pipelines/{pipelineName}`
5.  `PUT /api/pipelines/{pipelineName}`
6.  `GET /api/pipelines/{pipelineName}/services`
7.  `POST /api/pipelines/{pipelineName}/services`
8.  `GET /api/pipelines/{pipelineName}/services/{serviceName}`
9.  `PUT /api/pipelines/{pipelineName}/services/{serviceName}`
10. `DELETE /api/pipelines/{pipelineName}/services/{serviceName}`
11. `GET /api/meta/allowed-topics`
12. `GET /api/meta/available-services`
13. `POST /api/pipelines/validate`
14. `GET /api/pipelines/{pipelineName}/graph`

*(Real-time/Control endpoints are deferred until after `pipeline-core` integration)*

---

## Detailed Endpoint Specifications:

*(All error responses should include a consistent JSON body like `{"timestamp": "...", "status": 4xx/5xx, "error": "...", "message": "...", "path": "..."}`)*

### 1. List Pipelines

*   **Endpoint:** `GET /api/pipelines`
*   **Description:** Retrieves a list of names for all configured pipelines.
*   **Request Body:** None
*   **Success Response:**
    *   Code: `200 OK`
    *   Body: `["pipeline1", "pipeline-alpha", "another_pipeline"]` (JSON Array of strings)
*   **Error Responses:**
    *   `500 Internal Server Error`: If there's an error communicating with Consul or processing the request.
*   **Edge Cases:**
    *   No pipelines configured: Returns an empty JSON array `[]`.

### 2. Create Pipeline

*   **Endpoint:** `POST /api/pipelines`
*   **Description:** Creates a new pipeline configuration with the given name. Initializes version to 1 and sets the creation/update timestamp. Services map will be empty initially.
*   **Request Body:**
    *   Content-Type: `application/json`
    *   Body: `{ "name": "new-pipeline-name" }`
*   **Success Response:**
    *   Code: `201 Created`
    *   Body: The full `PipelineConfigDto` of the newly created pipeline.
        ```json
        {
          "name": "new-pipeline-name",
          "services": {},
          "pipelineVersion": 1,
          "pipelineLastUpdated": "2023-10-27T10:00:00Z" 
        }
        ```
*   **Error Responses:**
    *   `400 Bad Request`: If the request body is missing the `name` field, is malformed JSON, or the name is invalid (e.g., empty, contains illegal characters).
    *   `409 Conflict`: If a pipeline with the same name already exists.
    *   `500 Internal Server Error`: If there's an error communicating with Consul or saving the data.

### 3. Get Pipeline Configuration

*   **Endpoint:** `GET /api/pipelines/{pipelineName}`
*   **Description:** Retrieves the complete configuration (`PipelineConfigDto`) for a specific pipeline.
*   **Path Parameter:** `pipelineName` - The name of the pipeline to retrieve.
*   **Request Body:** None
*   **Success Response:**
    *   Code: `200 OK`
    *   Body: The `PipelineConfigDto` JSON object for the requested pipeline.
*   **Error Responses:**
    *   `404 Not Found`: If no pipeline with the given `pipelineName` exists.
    *   `500 Internal Server Error`: If there's an error communicating with Consul or processing the request.

### 4. Delete Pipeline

*   **Endpoint:** `DELETE /api/pipelines/{pipelineName}`
*   **Description:** Deletes the entire configuration for the specified pipeline from Consul.
*   **Path Parameter:** `pipelineName` - The name of the pipeline to delete.
*   **Request Body:** None
*   **Success Response:**
    *   Code: `204 No Content`
*   **Error Responses:**
    *   `404 Not Found`: If no pipeline with the given `pipelineName` exists.
    *   `500 Internal Server Error`: If there's an error communicating with Consul or deleting the data.

### 5. Replace Pipeline Configuration (Optimistic Locking)

*   **Endpoint:** `PUT /api/pipelines/{pipelineName}`
*   **Description:** Replaces the entire configuration for a given pipeline. Uses optimistic locking based on the provided `pipelineVersion`. Increments the version number and updates the timestamp on successful update.
*   **Path Parameter:** `pipelineName` - The name of the pipeline to update.
*   **Request Body:**
    *   Content-Type: `application/json`
    *   Body: The full `PipelineConfigDto` object, **including the `pipelineVersion` field** which *must* match the version currently stored in Consul.
        ```json
        {
          "name": "pipeline1", // Name in body usually ignored or validated against path param
          "services": { ... }, 
          "pipelineVersion": 5, // Must match current version in Consul
          "pipelineLastUpdated": "..." // Usually ignored, set by server
        }
        ```
*   **Success Response:**
    *   Code: `200 OK`
    *   Body: The updated `PipelineConfigDto` with the incremented version and new timestamp.
*   **Error Responses:**
    *   `400 Bad Request`: If the request body is malformed, missing required fields (like `pipelineVersion`), or fails validation (e.g., internal structure checks, loop detection if implemented).
    *   `404 Not Found`: If no pipeline with the given `pipelineName` exists.
    *   `409 Conflict`: If the `pipelineVersion` in the request body does not match the current version in Consul (optimistic lock failure). Throw `PipelineVersionConflictException`.
    *   `500 Internal Server Error`: If there's an error communicating with Consul or saving the data.

### 6. List Services in Pipeline

*   **Endpoint:** `GET /api/pipelines/{pipelineName}/services`
*   **Description:** Retrieves a list of all service configurations (`ServiceConfigurationDto`) within the specified pipeline.
*   **Path Parameter:** `pipelineName` - The name of the pipeline.
*   **Request Body:** None
*   **Success Response:**
    *   Code: `200 OK`
    *   Body: A JSON object where keys are service names and values are `ServiceConfigurationDto` objects, or a JSON array of `ServiceConfigurationDto` objects. (Using an object/map might be slightly more conventional: `{"serviceA": {...}, "serviceB": {...}}`)
*   **Error Responses:**
    *   `404 Not Found`: If the `pipelineName` does not exist.
    *   `500 Internal Server Error`: Consul communication error.
*   **Edge Cases:**
    *   Pipeline exists but has no services: Returns an empty JSON object `{}` or array `[]`.

### 7. Add Service to Pipeline (Optimistic Locking)

*   **Endpoint:** `POST /api/pipelines/{pipelineName}/services`
*   **Description:** Adds a new service configuration to the specified pipeline. Requires the *pipeline's* current `pipelineVersion` for optimistic locking on the parent resource. Increments the pipeline's version and updates its timestamp.
*   **Path Parameter:** `pipelineName` - The name of the pipeline to add the service to.
*   **Request Body:**
    *   Content-Type: `application/json`
    *   Body: Contains the `ServiceConfigurationDto` for the new service **and** the expected `pipelineVersion` of the parent pipeline.
        ```json
        {
          "pipelineVersion": 5, // Expected current version of the pipeline
          "service": {
            "name": "new-service-name",
            "kafkaListenTopics": [...],
            // ... other service fields
          }
        }
        ```
*   **Success Response:**
    *   Code: `201 Created`
    *   Body: The `ServiceConfigurationDto` of the newly added service. (Alternatively, could return the full updated `PipelineConfigDto`).
*   **Error Responses:**
    *   `400 Bad Request`: Malformed request, missing `pipelineVersion` or `service` data, invalid service configuration (e.g., missing name, validation rules fail).
    *   `404 Not Found`: If the `pipelineName` does not exist.
    *   `409 Conflict`:
        *   If a service with the same name already exists within that pipeline.
        *   If the provided `pipelineVersion` does not match the pipeline's current version in Consul (`PipelineVersionConflictException`).
    *   `500 Internal Server Error`: Consul communication or saving error.

### 8. Get Service Configuration

*   **Endpoint:** `GET /api/pipelines/{pipelineName}/services/{serviceName}`
*   **Description:** Retrieves the configuration (`ServiceConfigurationDto`) for a specific service within a specific pipeline.
*   **Path Parameters:**
    *   `pipelineName` - The name of the pipeline.
    *   `serviceName` - The name of the service.
*   **Request Body:** None
*   **Success Response:**
    *   Code: `200 OK`
    *   Body: The `ServiceConfigurationDto` JSON object for the requested service.
*   **Error Responses:**
    *   `404 Not Found`: If the `pipelineName` does not exist, or if the pipeline exists but the `serviceName` does not exist within it.
    *   `500 Internal Server Error`: Consul communication error.

### 9. Update Service in Pipeline (Optimistic Locking)

*   **Endpoint:** `PUT /api/pipelines/{pipelineName}/services/{serviceName}`
*   **Description:** Updates the configuration of an existing service within a pipeline. Requires the *pipeline's* current `pipelineVersion` for optimistic locking. Increments the pipeline's version and updates its timestamp.
*   **Path Parameters:**
    *   `pipelineName` - The name of the pipeline.
    *   `serviceName` - The name of the service to update.
*   **Request Body:**
    *   Content-Type: `application/json`
    *   Body: Contains the updated `ServiceConfigurationDto` **and** the expected `pipelineVersion` of the parent pipeline. The `name` in the service DTO should match `serviceName` path param or be validated against it.
        ```json
        {
          "pipelineVersion": 6, // Expected current version of the pipeline
          "service": {
            "name": "service-to-update", // Should match {serviceName}
            "kafkaListenTopics": [...], // Updated fields
            // ... other service fields
          }
        }
        ```
*   **Success Response:**
    *   Code: `200 OK`
    *   Body: The updated `ServiceConfigurationDto`. (Alternatively, the full updated `PipelineConfigDto`).
*   **Error Responses:**
    *   `400 Bad Request`: Malformed request, missing fields, invalid service configuration.
    *   `404 Not Found`: If the `pipelineName` or `serviceName` within that pipeline does not exist.
    *   `409 Conflict`: If the provided `pipelineVersion` does not match the pipeline's current version (`PipelineVersionConflictException`).
    *   `500 Internal Server Error`: Consul communication or saving error.

### 10. Delete Service from Pipeline (Optimistic Locking)

*   **Endpoint:** `DELETE /api/pipelines/{pipelineName}/services/{serviceName}`
*   **Description:** Deletes a specific service from a pipeline. Requires the *pipeline's* current `pipelineVersion` for optimistic locking. Increments the pipeline's version and updates its timestamp.
*   **Path Parameters:**
    *   `pipelineName` - The name of the pipeline.
    *   `serviceName` - The name of the service to delete.
*   **Query Parameter (Optional):** `removeDependencies=true|false` (Default: `false`) - If `true`, potentially remove other services that depend directly on this one (complex logic, implement later).
*   **Request Header (or Body field):** Need to pass the expected `pipelineVersion` for optimistic locking, e.g., via a required request body: `{ "pipelineVersion": 7 }`
*   **Success Response:**
    *   Code: `204 No Content`
*   **Error Responses:**
    *   `400 Bad Request`: If the required `pipelineVersion` is missing (if using request body).
    *   `404 Not Found`: If the `pipelineName` or `serviceName` within that pipeline does not exist.
    *   `409 Conflict`: If the provided `pipelineVersion` does not match the pipeline's current version (`PipelineVersionConflictException`).
    *   `500 Internal Server Error`: Consul communication or deletion error.

### 11. Get Allowed Topics

*   **Endpoint:** `GET /api/meta/allowed-topics`
*   **Description:** Returns a list of allowed Kafka topic names that can be used in pipeline service configurations.
*   **Request Body:** None
*   **Success Response:**
    *   Code: `200 OK`
    *   Body: A JSON array of strings representing allowed topic names.
        ```json
        ["topic1", "topic2", "input-topic", "output-topic"]
        ```
*   **Error Responses:**
    *   `500 Internal Server Error`: If there's an error retrieving the topic list from the configured source.
*   **Edge Cases:**
    *   No topics defined: Returns an empty JSON array `[]`.

### 12. Get Available Services

*   **Endpoint:** `GET /api/meta/available-services`
*   **Description:** Returns information about available service types that can be used in pipeline configurations, including their required/optional configuration parameters.
*   **Request Body:** None
*   **Success Response:**
    *   Code: `200 OK`
    *   Body: A JSON object mapping service type identifiers to their metadata.
        ```json
        {
          "Chunker": {
            "name": "Chunker",
            "description": "Breaks input data into chunks",
            "requiredParams": ["chunkSize", "overlapSize"],
            "optionalParams": ["encoding"]
          },
          "Router": {
            "name": "Router",
            "description": "Routes messages based on content",
            "requiredParams": ["routingField"],
            "optionalParams": ["defaultRoute"]
          }
        }
        ```
*   **Error Responses:**
    *   `500 Internal Server Error`: If there's an error retrieving the service registry information.
*   **Edge Cases:**
    *   No services available: Returns an empty JSON object `{}`.

### 13. Validate Pipeline

*   **Endpoint:** `POST /api/pipelines/validate`
*   **Description:** Validates a pipeline configuration without saving it to Consul. Performs checks like loop detection, service implementation availability, and parameter validation.
*   **Request Body:**
    *   Content-Type: `application/json`
    *   Body: The full `PipelineConfigDto` to validate.
*   **Success Response:**
    *   Code: `200 OK`
    *   Body: Validation results JSON.
        ```json
        {
          "valid": true|false,
          "errors": [
            {
              "type": "LOOP_DETECTED|MISSING_PARAM|INVALID_SERVICE|etc.",
              "message": "Detailed error message",
              "location": "services.serviceA.grpcForwardTo[0]" // Path to problematic element
            }
          ],
          "warnings": [
            {
              "type": "UNUSED_SERVICE|etc.",
              "message": "Warning message",
              "location": "services.serviceB"
            }
          ]
        }
        ```
*   **Error Responses:**
    *   `400 Bad Request`: If the request body is malformed or missing required fields.
    *   `500 Internal Server Error`: If there's an error during validation processing.

### 14. Get Pipeline Graph

*   **Endpoint:** `GET /api/pipelines/{pipelineName}/graph`
*   **Description:** Generates a structural representation of the pipeline for visualization purposes.
*   **Path Parameter:** `pipelineName` - The name of the pipeline to visualize.
*   **Query Parameter:** `format=mermaid|json|dot` (Default: `json`) - The output format for the graph.
*   **Request Body:** None
*   **Success Response:**
    *   Code: `200 OK`
    *   Body: Graph in the requested format:
        *   For `format=json`:
            ```json
            {
              "nodes": [
                {"id": "serviceA", "type": "Chunker"},
                {"id": "serviceB", "type": "Router"}
              ],
              "edges": [
                {"from": "serviceA", "to": "serviceB", "type": "grpc"}
              ]
            }
            ```
        *   For `format=mermaid`:
            ```
            graph TD;
              serviceA("Chunker: serviceA");
              serviceB("Router: serviceB");
              serviceA-->serviceB;
            ```
*   **Error Responses:**
    *   `400 Bad Request`: If an unsupported format is requested.
    *   `404 Not Found`: If the `pipelineName` does not exist.
    *   `500 Internal Server Error`: If there's an error generating the graph.
