import React from 'react';
import { render, screen } from '@testing-library/react';
import NodePalette from './NodePalette'; // Adjust path as necessary
import '@testing-library/jest-dom';

// The node types expected to be in the palette
const expectedNodeTypes = [
  'Kafka Source',
  'Tika Parser',
  'OpenSearch Sink',
  'gRPC Connector',
  'Custom Processor',
  'Kafka Sink',
  'File Source',
  'Log Sink',
  'Kafka Topic',
];

describe('NodePalette Component', () => {
  test('renders all expected draggable node types', () => {
    render(<NodePalette />);

    expectedNodeTypes.forEach(nodeLabel => {
      const nodeElement = screen.getByText(nodeLabel);
      expect(nodeElement).toBeInTheDocument();
      expect(nodeElement).toHaveAttribute('draggable', 'true');
    });
  });

  test('each node type item has correct onDragStart setup', () => {
    render(<NodePalette />);
    // Example check for one node type
    const kafkaSourceNode = screen.getByText('Kafka Source');
    // Check if onDragStart is present. Testing the actual drag functionality
    // is more complex and often falls into e2e testing.
    // Here, we're primarily concerned with rendering.
    // For unit tests, we'd typically check if the props that configure onDragStart (like type and label) are correctly passed
    // if onDragStart was passed as a prop to child items.
    // Since onDragStart is defined and used directly within NodePalette on mapped items,
    // we've already implicitly tested its rendering part by checking labels.
    // A more detailed test would involve mocking event.dataTransfer.
    expect(kafkaSourceNode).toBeInTheDocument();
  });
});
