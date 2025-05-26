import React from 'react';
// Import a shared CSS module or use specific classes from App.css if applicable
import './NodePalette.css'; // Create this file for specific styles

const nodeTypeDefinitions = [
  { type: 'kafkaSource', label: 'Kafka Source' },
  { type: 'tikaParser', label: 'Tika Parser' },
  { type: 'openSearchSink', label: 'OpenSearch Sink' },
  { type: 'grpcConnector', label: 'gRPC Connector' },
  { type: 'customProcessor', label: 'Custom Processor' },
  { type: 'kafkaSink', label: 'Kafka Sink' },
  { type: 'fileSource', label: 'File Source' },
  { type: 'logSink', label: 'Log Sink' },
  { type: 'kafkaTopic', label: 'Kafka Topic' },
];

const NodePalette = () => {
  const onDragStart = (event, nodeType, label) => {
    const nodeInfo = { type: nodeType, label: label };
    event.dataTransfer.setData('application/reactflow', JSON.stringify(nodeInfo));
    event.dataTransfer.effectAllowed = 'move';
  };

  return (
    // The parent div in App.jsx now controls width and scrolling via 'sidebar' and 'node-palette-sidebar' classes
    <div>
      <h3 className="sidebar-heading">Node Palette</h3>
      {nodeTypeDefinitions.map((nodeDef) => (
        <div
          key={nodeDef.type}
          className="node-palette-item" // Add class for styling
          onDragStart={(event) => onDragStart(event, nodeDef.type, nodeDef.label)}
          draggable
          title={`Drag to add ${nodeDef.label}`} // Tooltip for better UX
        >
          {nodeDef.label}
        </div>
      ))}
    </div>
  );
};

export default NodePalette;
