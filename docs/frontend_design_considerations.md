# Front-end Design Considerations for YAPPY

## Overview

This document outlines the design considerations for a custom front-end graph drawing application for the YAPPY (Yet Another Pipeline Processor) system. The front-end will allow users to visually create, configure, and monitor dynamic pipelines.

## Key Requirements

1. **Graph-based Pipeline Editor**: A visual interface for creating and connecting pipeline steps.
2. **Configuration Management**: Forms for configuring individual pipeline steps based on JSON schema.
3. **Pipeline Monitoring**: Real-time visualization of pipeline execution and performance.
4. **Kafka Control**: Interface for pausing, resuming, and resetting Kafka consumers.

## Architecture

### Technology Stack

- **React.js**: For building the user interface
- **Redux**: For state management
- **React Flow**: For the graph-based pipeline designer
- **Material-UI**: For UI components
- **Axios**: For API communication
- **Socket.IO**: For real-time updates

### Component Structure

```
frontend/
├── src/
│   ├── components/
│   │   ├── common/
│   │   │   ├── Header.jsx
│   │   │   ├── Sidebar.jsx
│   │   │   └── Footer.jsx
│   │   ├── pipeline-editor/
│   │   │   ├── PipelineCanvas.jsx
│   │   │   ├── NodePalette.jsx
│   │   │   ├── NodeConfig.jsx
│   │   │   └── EdgeConfig.jsx
│   │   ├── pipeline-management/
│   │   │   ├── PipelineList.jsx
│   │   │   ├── PipelineSave.jsx
│   │   │   └── PipelineImportExport.jsx
│   │   └── pipeline-monitoring/
│   │       ├── PipelineStatus.jsx
│   │       ├── KafkaControls.jsx
│   │       └── MetricsDisplay.jsx
│   ├── redux/
│   │   ├── actions/
│   │   ├── reducers/
│   │   └── store.js
│   ├── services/
│   │   ├── api.js
│   │   ├── pipelineService.js
│   │   ├── kafkaService.js
│   │   └── schemaService.js
│   ├── utils/
│   │   ├── graphUtils.js
│   │   └── schemaUtils.js
│   ├── App.jsx
│   └── index.jsx
└── public/
    ├── index.html
    └── assets/
```

## Key Features

### 1. Graph-based Pipeline Editor

The pipeline editor will be built using React Flow, which provides a powerful framework for creating node-based editors. Key features include:

- **Drag-and-Drop Interface**: Users can drag pipeline step types from a palette onto the canvas.
- **Connection Management**: Users can connect steps by drawing edges between them.
- **Step Visualization**: Each step is represented as a node with visual indicators for its type and status.
- **Validation**: Real-time validation of pipeline structure to ensure it's valid.

### 2. Configuration Management

The configuration management system will dynamically generate forms based on JSON schema:

- **Schema-driven Forms**: Forms are generated based on the JSON schema for each step type.
- **Validation**: Real-time validation of configuration values against the schema.
- **Default Values**: Sensible defaults for configuration values.
- **Help Text**: Contextual help for configuration options.

### 3. Pipeline Monitoring

The monitoring system will provide real-time visualization of pipeline execution:

- **Status Dashboard**: Overview of all pipelines and their status.
- **Step Status**: Detailed status for each step in a pipeline.
- **Metrics**: Performance metrics for each step and the overall pipeline.
- **Logs**: Access to logs for debugging.

### 4. Kafka Control

The Kafka control interface will allow users to manage Kafka consumers:

- **Pause/Resume**: Pause and resume Kafka consumers for specific pipeline steps.
- **Reset Offset**: Reset consumer offset to a specific date, earliest, or latest.
- **Consumer Status**: View the status of all consumers.

## API Integration

The front-end will communicate with the YAPPY backend through a set of REST APIs:

1. **Pipeline API**:
   - `GET /api/pipelines`: List all pipelines
   - `GET /api/pipelines/{id}`: Get a specific pipeline
   - `POST /api/pipelines`: Create a new pipeline
   - `PUT /api/pipelines/{id}`: Update a pipeline
   - `DELETE /api/pipelines/{id}`: Delete a pipeline
   - `POST /api/pipelines/{id}/deploy`: Deploy a pipeline

2. **Schema API**:
   - `GET /api/schemas`: List all schemas
   - `GET /api/schemas/{id}`: Get a specific schema

3. **Kafka API**:
   - `GET /api/kafka/consumers`: Get all consumer statuses
   - `POST /api/kafka/consumers/{pipelineName}/{stepName}/pause`: Pause a consumer
   - `POST /api/kafka/consumers/{pipelineName}/{stepName}/resume`: Resume a consumer
   - `POST /api/kafka/consumers/{pipelineName}/{stepName}/reset/date`: Reset a consumer to a date
   - `POST /api/kafka/consumers/{pipelineName}/{stepName}/reset/earliest`: Reset a consumer to earliest
   - `POST /api/kafka/consumers/{pipelineName}/{stepName}/reset/latest`: Reset a consumer to latest

## User Experience Considerations

1. **Intuitive Interface**: The interface should be intuitive and easy to use, even for users who are not familiar with pipeline processing.

2. **Responsive Design**: The application should be responsive and work well on different screen sizes.

3. **Error Handling**: Clear error messages and guidance for resolving issues.

4. **Performance**: The application should be performant, even when working with large pipelines.

5. **Accessibility**: The application should be accessible to users with disabilities.

## Implementation Plan

1. **Phase 1**: Implement the basic pipeline editor with drag-and-drop functionality and connection management.

2. **Phase 2**: Implement the configuration management system with schema-driven forms.

3. **Phase 3**: Implement the pipeline monitoring system with real-time updates.

4. **Phase 4**: Implement the Kafka control interface for managing Kafka consumers.

5. **Phase 5**: Implement the API integration and deploy the application.

## Conclusion

The custom front-end graph drawing application for YAPPY will provide a powerful and intuitive interface for creating, configuring, and monitoring dynamic pipelines. By leveraging modern web technologies and following best practices for user experience, the application will enable users to efficiently work with the YAPPY system.