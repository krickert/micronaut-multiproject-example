# YAPPY Kafka Listener Architecture and Frontend Design

## 1. Multiple Listener in Kafka Strategy

### Current Architecture Overview

The current Kafka listener architecture in YAPPY consists of several key components:

1. **KafkaListenerManager**: Orchestrates the creation, management, and control of Kafka listeners for pipeline steps.
2. **KafkaListenerPool**: Maintains a pool of DynamicKafkaListener instances, allowing for efficient lookup and management.
3. **ConsumerStateManager**: Tracks the state of Kafka consumers, including whether they are paused and when they were last updated.
4. **DynamicKafkaListener**: Implements the actual Kafka consumer logic, polling for messages and processing them.

This architecture already supports creating multiple listeners, but needs enhancements to better control data flow between pipelines.

### Enhanced Multiple Listener Strategy

To implement a robust multiple listener strategy that controls data flow between pipelines, I propose the following architecture:

```java
/**
 * Manages a pool of Kafka listeners with enhanced flow control capabilities.
 */
public class EnhancedKafkaListenerPool extends KafkaListenerPool {
    private final Map<String, Integer> topicConcurrencyLimits = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> activeListenersByTopic = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<PipeStream>> messageBuffers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flowControlExecutor;

    public EnhancedKafkaListenerPool() {
        super();
        this.flowControlExecutor = Executors.newScheduledThreadPool(1, 
                new ThreadFactoryBuilder()
                        .setNameFormat("kafka-flow-control-%d")
                        .setDaemon(true)
                        .build());

        // Start the flow control monitoring
        startFlowControl();
    }

    /**
     * Sets the concurrency limit for a specific topic.
     * This controls how many listeners can actively process messages from this topic simultaneously.
     * 
     * @param topic The Kafka topic
     * @param limit The maximum number of concurrent listeners
     */
    public void setTopicConcurrencyLimit(String topic, int limit) {
        topicConcurrencyLimits.put(topic, limit);
    }

    /**
     * Creates a listener with flow control capabilities.
     * The listener will respect concurrency limits for its topic.
     */
    @Override
    public DynamicKafkaListener createListener(
            String listenerId,
            String topic, 
            String groupId, 
            Map<String, String> consumerConfig,
            String pipelineName,
            String stepName,
            PipeStreamEngine pipeStreamEngine) {

        // Initialize tracking for this topic if not already done
        activeListenersByTopic.computeIfAbsent(topic, k -> new AtomicInteger(0));
        messageBuffers.computeIfAbsent(topic, k -> new LinkedBlockingQueue<>());

        // Create a flow-controlled listener
        FlowControlledKafkaListener listener = new FlowControlledKafkaListener(
                listenerId, topic, groupId, consumerConfig, 
                pipelineName, stepName, pipeStreamEngine,
                this::acquireProcessingPermit,
                this::releaseProcessingPermit,
                messageBuffers.get(topic));

        // Store in our pool
        super.listeners.put(listenerId, listener);

        return listener;
    }

    /**
     * Attempts to acquire a processing permit for a topic.
     * Returns true if processing is allowed, false if the concurrency limit is reached.
     */
    private boolean acquireProcessingPermit(String topic) {
        Integer limit = topicConcurrencyLimits.getOrDefault(topic, Integer.MAX_VALUE);
        AtomicInteger active = activeListenersByTopic.get(topic);

        // Try to increment if below limit
        while (true) {
            int current = active.get();
            if (current >= limit) {
                return false;
            }
            if (active.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * Releases a processing permit for a topic.
     */
    private void releaseProcessingPermit(String topic) {
        activeListenersByTopic.get(topic).decrementAndGet();
    }

    /**
     * Starts the flow control monitoring.
     * This periodically checks for buffered messages and tries to process them
     * if concurrency permits are available.
     */
    private void startFlowControl() {
        flowControlExecutor.scheduleAtFixedRate(() -> {
            for (Map.Entry<String, BlockingQueue<PipeStream>> entry : messageBuffers.entrySet()) {
                String topic = entry.getKey();
                BlockingQueue<PipeStream> buffer = entry.getValue();

                // Try to process buffered messages if permits are available
                while (!buffer.isEmpty() && acquireProcessingPermit(topic)) {
                    PipeStream message = buffer.poll();
                    if (message != null) {
                        // Find a listener for this topic that can process the message
                        for (DynamicKafkaListener listener : getAllListeners()) {
                            if (listener instanceof FlowControlledKafkaListener && 
                                    listener.getTopic().equals(topic) &&
                                    !((FlowControlledKafkaListener) listener).isProcessing()) {
                                ((FlowControlledKafkaListener) listener).processBufferedMessage(message);
                                break;
                            }
                        }
                    }
                }
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Shuts down the flow control executor.
     */
    @Override
    public void shutdownAllListeners() {
        super.shutdownAllListeners();
        flowControlExecutor.shutdown();
    }
}

/**
 * A Kafka listener with flow control capabilities.
 */
public class FlowControlledKafkaListener extends DynamicKafkaListener {
    private final Function<String, Boolean> acquirePermit;
    private final Consumer<String> releasePermit;
    private final BlockingQueue<PipeStream> messageBuffer;
    private final AtomicBoolean processing = new AtomicBoolean(false);

    public FlowControlledKafkaListener(
            String listenerId,
            String topic,
            String groupId,
            Map<String, String> consumerConfig,
            String pipelineName,
            String stepName,
            PipeStreamEngine pipeStreamEngine,
            Function<String, Boolean> acquirePermit,
            Consumer<String> releasePermit,
            BlockingQueue<PipeStream> messageBuffer) {
        super(listenerId, topic, groupId, consumerConfig, pipelineName, stepName, pipeStreamEngine);
        this.acquirePermit = acquirePermit;
        this.releasePermit = releasePermit;
        this.messageBuffer = messageBuffer;
    }

    /**
     * Processes a record with flow control.
     */
    @Override
    protected void processRecord(ConsumerRecord<UUID, PipeStream> record) {
        PipeStream pipeStream = record.value();

        // Try to acquire a processing permit
        if (acquirePermit.apply(getTopic())) {
            try {
                processing.set(true);
                // Process the record normally
                super.processRecord(record);
            } finally {
                processing.set(false);
                releasePermit.accept(getTopic());
            }
        } else {
            // Buffer the message for later processing
            messageBuffer.offer(pipeStream);
        }
    }

    /**
     * Processes a buffered message.
     */
    public void processBufferedMessage(PipeStream pipeStream) {
        try {
            processing.set(true);

            // Update the PipeStream with the current pipeline and step information
            PipeStream updatedPipeStream = pipeStream.toBuilder()
                    .setCurrentPipelineName(getPipelineName())
                    .setTargetStepName(getStepName())
                    .build();

            // Process the stream
            pipeStreamEngine.processStream(updatedPipeStream);

        } finally {
            processing.set(false);
            releasePermit.accept(getTopic());
        }
    }

    /**
     * Checks if this listener is currently processing a message.
     */
    public boolean isProcessing() {
        return processing.get();
    }
}
```

### Key Features of the Enhanced Architecture

1. **Concurrency Control**: The `EnhancedKafkaListenerPool` allows setting concurrency limits per topic, controlling how many listeners can process messages from a topic simultaneously.

2. **Message Buffering**: When concurrency limits are reached, messages are buffered in a queue for later processing.

3. **Flow Control Monitoring**: A background thread periodically checks for buffered messages and processes them when concurrency permits become available.

4. **Dynamic Scaling**: The architecture supports dynamically adjusting concurrency limits based on system load or other metrics.

5. **Pipeline Prioritization**: Can be extended to support prioritizing certain pipelines over others by implementing priority queues for message buffers.

### Integration with Existing Architecture

To integrate this enhanced architecture with the existing system:

1. Extend `KafkaListenerManager` to use `EnhancedKafkaListenerPool` instead of the basic `KafkaListenerPool`.
2. Add methods to `KafkaListenerManager` for setting concurrency limits and other flow control parameters.
3. Update the API to expose these new capabilities to administrators.

## 2. Consumer Pause, Continue, and Offset by Date

The current implementation already supports these features through the following methods in `KafkaListenerManager`:

### Pause Consumer

```java
public CompletableFuture<Void> pauseConsumer(String pipelineName, String stepName) {
    // Implementation details
}
```

This method pauses a consumer for a specific pipeline step, preventing it from processing new messages until resumed.

### Resume Consumer

```java
public CompletableFuture<Void> resumeConsumer(String pipelineName, String stepName) {
    // Implementation details
}
```

This method resumes a paused consumer, allowing it to continue processing messages.

### Reset Offset by Date

```java
public CompletableFuture<Void> resetOffsetToDate(String pipelineName, String stepName, Instant date) {
    // Implementation details
}
```

This method resets a consumer's offset to a specific date, allowing it to reprocess messages from that point in time.

### Additional Offset Reset Methods

```java
public CompletableFuture<Void> resetOffsetToEarliest(String pipelineName, String stepName) {
    // Implementation details
}

public CompletableFuture<Void> resetOffsetToLatest(String pipelineName, String stepName) {
    // Implementation details
}
```

These methods reset a consumer's offset to the earliest or latest available offset, respectively.

### Implementation Details

The implementation follows a consistent pattern:

1. Find the listener for the specified pipeline step
2. Pause the consumer if necessary
3. Reset the offset using the KafkaAdminService
4. Resume the consumer if it was previously running
5. Update the consumer state in the ConsumerStateManager

This approach ensures that offset resets are performed safely without losing messages.

## 3. Front-end Architecture for Custom Graph Drawing App

### Overview

The front-end architecture for creating dynamic pipelines should be a web-based application that allows users to visually design, configure, and monitor pipelines. The application will communicate with the YAPPY backend through REST APIs.

### Key Components

1. **Pipeline Designer**: A canvas-based interface for creating and connecting pipeline steps.
2. **Step Configuration Panel**: A form-based interface for configuring individual pipeline steps.
3. **Pipeline Management**: Tools for saving, loading, and deploying pipelines.
4. **Pipeline Monitoring**: Real-time visualization of pipeline execution and performance.

### Technology Stack

- **React.js**: For building the user interface
- **Redux**: For state management
- **React Flow**: For the graph-based pipeline designer
- **Material-UI**: For UI components
- **Axios**: For API communication
- **Socket.IO**: For real-time updates

### Architecture Diagram

```
+-----------------------------------+
|           Web Browser             |
+-----------------------------------+
                |
                v
+-----------------------------------+
|         React Application         |
|-----------------------------------|
|  +-------------+  +-------------+ |
|  |   Pipeline  |  |    Step     | |
|  |  Designer   |  | Configuration| |
|  +-------------+  +-------------+ |
|                                   |
|  +-------------+  +-------------+ |
|  |   Pipeline  |  |   Pipeline  | |
|  | Management  |  |  Monitoring | |
|  +-------------+  +-------------+ |
+-----------------------------------+
                |
                v
+-----------------------------------+
|           REST APIs               |
|-----------------------------------|
|  - Pipeline CRUD                  |
|  - Step Configuration             |
|  - Kafka Listener Management      |
|  - Pipeline Execution             |
+-----------------------------------+
                |
                v
+-----------------------------------+
|         YAPPY Backend             |
+-----------------------------------+
```

### Pipeline Designer Component

The Pipeline Designer will be built using React Flow, which provides a powerful framework for creating node-based editors. Key features include:

1. **Drag-and-Drop Interface**: Users can drag pipeline step types from a palette onto the canvas.
2. **Connection Management**: Users can connect steps by drawing edges between them.
3. **Step Visualization**: Each step is represented as a node with visual indicators for its type and status.
4. **Validation**: Real-time validation of pipeline structure to ensure it's valid.

```jsx
import React, { useState, useCallback } from 'react';
import ReactFlow, {
  addEdge,
  MiniMap,
  Controls,
  Background,
  useNodesState,
  useEdgesState,
} from 'reactflow';
import 'reactflow/dist/style.css';

const initialNodes = [
  {
    id: '1',
    type: 'input',
    data: { label: 'Input' },
    position: { x: 250, y: 25 },
  },
];

const initialEdges = [];

function PipelineDesigner() {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);
  const [selectedNode, setSelectedNode] = useState(null);

  const onConnect = useCallback(
    (params) => setEdges((eds) => addEdge(params, eds)),
    [setEdges],
  );

  const onNodeClick = (event, node) => {
    setSelectedNode(node);
  };

  return (
    <div style={{ height: '100vh', width: '100%' }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onNodeClick={onNodeClick}
        fitView
      >
        <Controls />
        <MiniMap />
        <Background variant="dots" gap={12} size={1} />
      </ReactFlow>
      {selectedNode && (
        <div className="node-configuration-panel">
          <h3>Configure {selectedNode.data.label}</h3>
          {/* Configuration form for the selected node */}
        </div>
      )}
    </div>
  );
}

export default PipelineDesigner;
```

### Step Configuration Component

The Step Configuration component will provide a form-based interface for configuring individual pipeline steps. It will dynamically load the appropriate configuration schema for each step type.

```jsx
import React, { useState, useEffect } from 'react';
import { TextField, Select, MenuItem, Button } from '@material-ui/core';
import { fetchStepSchema } from '../api/schemaApi';

function StepConfiguration({ stepType, initialConfig, onSave }) {
  const [schema, setSchema] = useState(null);
  const [config, setConfig] = useState(initialConfig || {});

  useEffect(() => {
    async function loadSchema() {
      const stepSchema = await fetchStepSchema(stepType);
      setSchema(stepSchema);
    }
    loadSchema();
  }, [stepType]);

  const handleChange = (field, value) => {
    setConfig({
      ...config,
      [field]: value,
    });
  };

  const handleSave = () => {
    onSave(config);
  };

  if (!schema) {
    return <div>Loading schema...</div>;
  }

  return (
    <div className="step-configuration">
      <h3>{schema.title}</h3>
      <p>{schema.description}</p>

      {schema.properties.map((prop) => (
        <div key={prop.name} className="config-field">
          <label>{prop.title}</label>
          {prop.type === 'string' && (
            <TextField
              value={config[prop.name] || ''}
              onChange={(e) => handleChange(prop.name, e.target.value)}
              fullWidth
              helperText={prop.description}
            />
          )}
          {prop.type === 'number' && (
            <TextField
              type="number"
              value={config[prop.name] || 0}
              onChange={(e) => handleChange(prop.name, parseFloat(e.target.value))}
              fullWidth
              helperText={prop.description}
            />
          )}
          {prop.type === 'boolean' && (
            <Select
              value={config[prop.name] || false}
              onChange={(e) => handleChange(prop.name, e.target.value)}
              fullWidth
            >
              <MenuItem value={true}>True</MenuItem>
              <MenuItem value={false}>False</MenuItem>
            </Select>
          )}
          {prop.type === 'enum' && (
            <Select
              value={config[prop.name] || prop.enum[0]}
              onChange={(e) => handleChange(prop.name, e.target.value)}
              fullWidth
            >
              {prop.enum.map((option) => (
                <MenuItem key={option} value={option}>{option}</MenuItem>
              ))}
            </Select>
          )}
        </div>
      ))}

      <Button variant="contained" color="primary" onClick={handleSave}>
        Save Configuration
      </Button>
    </div>
  );
}

export default StepConfiguration;
```

### Pipeline Management Component

The Pipeline Management component will provide tools for saving, loading, and deploying pipelines.

```jsx
import React, { useState, useEffect } from 'react';
import { Button, TextField, Dialog, DialogTitle, DialogContent, DialogActions, List, ListItem } from '@material-ui/core';
import { savePipeline, loadPipeline, deployPipeline, listPipelines } from '../api/pipelineApi';

function PipelineManagement({ currentPipeline, onLoad }) {
  const [pipelineName, setPipelineName] = useState(currentPipeline?.name || '');
  const [saveDialogOpen, setSaveDialogOpen] = useState(false);
  const [loadDialogOpen, setLoadDialogOpen] = useState(false);
  const [pipelines, setPipelines] = useState([]);

  useEffect(() => {
    async function fetchPipelines() {
      const pipelineList = await listPipelines();
      setPipelines(pipelineList);
    }
    if (loadDialogOpen) {
      fetchPipelines();
    }
  }, [loadDialogOpen]);

  const handleSave = async () => {
    await savePipeline({
      ...currentPipeline,
      name: pipelineName,
    });
    setSaveDialogOpen(false);
  };

  const handleLoad = async (pipelineId) => {
    const pipeline = await loadPipeline(pipelineId);
    onLoad(pipeline);
    setLoadDialogOpen(false);
  };

  const handleDeploy = async () => {
    await deployPipeline(currentPipeline.id);
    alert('Pipeline deployed successfully!');
  };

  return (
    <div className="pipeline-management">
      <Button variant="contained" color="primary" onClick={() => setSaveDialogOpen(true)}>
        Save Pipeline
      </Button>
      <Button variant="contained" color="secondary" onClick={() => setLoadDialogOpen(true)}>
        Load Pipeline
      </Button>
      <Button variant="contained" color="default" onClick={handleDeploy}>
        Deploy Pipeline
      </Button>

      <Dialog open={saveDialogOpen} onClose={() => setSaveDialogOpen(false)}>
        <DialogTitle>Save Pipeline</DialogTitle>
        <DialogContent>
          <TextField
            label="Pipeline Name"
            value={pipelineName}
            onChange={(e) => setPipelineName(e.target.value)}
            fullWidth
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSaveDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleSave} color="primary">Save</Button>
        </DialogActions>
      </Dialog>

      <Dialog open={loadDialogOpen} onClose={() => setLoadDialogOpen(false)}>
        <DialogTitle>Load Pipeline</DialogTitle>
        <DialogContent>
          <List>
            {pipelines.map((pipeline) => (
              <ListItem button key={pipeline.id} onClick={() => handleLoad(pipeline.id)}>
                {pipeline.name}
              </ListItem>
            ))}
          </List>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setLoadDialogOpen(false)}>Cancel</Button>
        </DialogActions>
      </Dialog>
    </div>
  );
}

export default PipelineManagement;
```

### Pipeline Monitoring Component

The Pipeline Monitoring component will provide real-time visualization of pipeline execution and performance.

```jsx
import React, { useState, useEffect } from 'react';
import { Table, TableBody, TableCell, TableHead, TableRow, Paper } from '@material-ui/core';
import { getConsumerStatuses } from '../api/kafkaApi';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend } from 'recharts';

function PipelineMonitoring({ pipelineId }) {
  const [consumerStatuses, setConsumerStatuses] = useState([]);
  const [performanceData, setPerformanceData] = useState([]);

  useEffect(() => {
    // Initial fetch
    fetchConsumerStatuses();
    fetchPerformanceData();

    // Set up polling
    const statusInterval = setInterval(fetchConsumerStatuses, 5000);
    const performanceInterval = setInterval(fetchPerformanceData, 10000);

    return () => {
      clearInterval(statusInterval);
      clearInterval(performanceInterval);
    };
  }, [pipelineId]);

  const fetchConsumerStatuses = async () => {
    const statuses = await getConsumerStatuses(pipelineId);
    setConsumerStatuses(statuses);
  };

  const fetchPerformanceData = async () => {
    // This would be replaced with an actual API call
    const newDataPoint = {
      time: new Date().toISOString(),
      messagesProcessed: Math.floor(Math.random() * 100),
      processingTime: Math.floor(Math.random() * 500),
    };
    setPerformanceData((prevData) => [...prevData.slice(-19), newDataPoint]);
  };

  return (
    <div className="pipeline-monitoring">
      <h2>Consumer Statuses</h2>
      <Paper>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>ID</TableCell>
              <TableCell>Pipeline</TableCell>
              <TableCell>Step</TableCell>
              <TableCell>Topic</TableCell>
              <TableCell>Group ID</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Last Updated</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {consumerStatuses.map((status) => (
              <TableRow key={status.id}>
                <TableCell>{status.id}</TableCell>
                <TableCell>{status.pipelineName}</TableCell>
                <TableCell>{status.stepName}</TableCell>
                <TableCell>{status.topic}</TableCell>
                <TableCell>{status.groupId}</TableCell>
                <TableCell>{status.paused ? 'Paused' : 'Running'}</TableCell>
                <TableCell>{new Date(status.lastUpdated).toLocaleString()}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>

      <h2>Performance Metrics</h2>
      <LineChart width={600} height={300} data={performanceData}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey="time" />
        <YAxis yAxisId="left" />
        <YAxis yAxisId="right" orientation="right" />
        <Tooltip />
        <Legend />
        <Line yAxisId="left" type="monotone" dataKey="messagesProcessed" stroke="#8884d8" name="Messages Processed" />
        <Line yAxisId="right" type="monotone" dataKey="processingTime" stroke="#82ca9d" name="Processing Time (ms)" />
      </LineChart>
    </div>
  );
}

export default PipelineMonitoring;
```

### API Integration

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

### Deployment and Integration

The front-end application can be deployed as a static web application served by a web server or CDN. It will communicate with the YAPPY backend through the REST APIs described above.

To integrate with the existing YAPPY architecture:

1. Implement the REST APIs in the YAPPY backend
2. Configure CORS to allow the front-end to communicate with the backend
3. Deploy the front-end application
4. Configure authentication and authorization for the APIs

## Conclusion

The proposed architecture for multiple Kafka listeners, consumer control, and the front-end graph drawing app provides a comprehensive solution for the requirements. The multiple listener strategy enhances the existing architecture with flow control capabilities, while the consumer control functionality is already well-implemented. The front-end architecture provides a powerful and intuitive interface for creating and managing dynamic pipelines.
