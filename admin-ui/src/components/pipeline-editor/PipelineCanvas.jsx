import React from 'react';
import ReactFlow, {
  Controls,
  Background,
} from 'reactflow';
import 'reactflow/dist/style.css';

// This component now expects nodes, edges, and handlers as props
// It also expects onDragOver and onDrop to be passed from the parent (App.jsx)
// which controls the drag-and-drop logic for adding new nodes.
const PipelineCanvas = ({
  nodes,
  edges,
  onNodesChange,
  onEdgesChange,
  onConnect,
  onDragOver, // For allowing drop
  onDrop,     // For handling drop
  children,   // For any additional elements like Controls, Background
}) => {
  return (
    // The main div for React Flow should handle onDragOver and onDrop
    // The height and width are crucial for React Flow to render correctly.
    <div style={{ height: '100%', width: '100%' }} onDragOver={onDragOver} onDrop={onDrop}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        fitView // This is good for initial render
        // proOptions={{ hideAttribution: true }} // Example of pro option if you have a subscription
      >
        <Controls />
        <Background />
        {children}
      </ReactFlow>
    </div>
  );
};

export default PipelineCanvas;
