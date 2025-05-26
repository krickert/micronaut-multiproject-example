import React from 'react';
import { render, screen } from '@testing-library/react';
import NodeConfigPanel from './NodeConfigPanel'; // Adjust path as necessary
import '@testing-library/jest-dom';

describe('NodeConfigPanel Component', () => {
  const mockSelectedNode = {
    id: 'node-123',
    type: 'customProcessor', // This is the React Flow registered type
    data: {
      label: 'My Custom Processor',
      status: 'running',
      type: 'processor-category-A', // This is the "category" from backend data
      config: {
        param1: 'value1',
        param2: 100,
        nested: {
          deepParam: true,
        },
      },
    },
  };

  test('displays a placeholder message if selectedNode is null', () => {
    render(<NodeConfigPanel selectedNode={null} />);
    expect(screen.getByText(/Select a node to see its details/i)).toBeInTheDocument();
  });

  test('displays node ID, registered type, label, and status when a node is selected', () => {
    render(<NodeConfigPanel selectedNode={mockSelectedNode} />);
    
    expect(screen.getByText(`Node: ${mockSelectedNode.data.label}`)).toBeInTheDocument(); // Heading includes label
    expect(screen.getByText(mockSelectedNode.id)).toBeInTheDocument();
    expect(screen.getByText(mockSelectedNode.type)).toBeInTheDocument(); // Registered type
    expect(screen.getByText(mockSelectedNode.data.type)).toBeInTheDocument(); // Category from data.type
    
    const statusElement = screen.getByText(mockSelectedNode.data.status);
    expect(statusElement).toBeInTheDocument();
    expect(statusElement).toHaveStyle('background-color: rgb(40, 167, 69)'); // Green for running
  });

  test('displays node configuration as a JSON string in a <pre> tag', () => {
    render(<NodeConfigPanel selectedNode={mockSelectedNode} />);
    
    const preElement = screen.getByRole('complementary').querySelector('pre.config-pre'); // Using 'complementary' role for aside, then querySelector
    expect(preElement).toBeInTheDocument();
    
    // Check for parts of the JSON string to confirm content
    // JSON.stringify(mockSelectedNode.data.config, null, 2) would be the full content
    expect(preElement).toHaveTextContent(/"param1": "value1"/);
    expect(preElement).toHaveTextContent(/"param2": 100/);
    expect(preElement).toHaveTextContent(/"deepParam": true/);
  });

  test('displays "No specific configuration for this node." if config is empty or not present', () => {
    const nodeWithoutConfig = {
      ...mockSelectedNode,
      data: {
        ...mockSelectedNode.data,
        config: {}, // Empty config
      },
    };
    render(<NodeConfigPanel selectedNode={nodeWithoutConfig} />);
    expect(screen.getByText(/No specific configuration for this node./i)).toBeInTheDocument();

    const nodeWithNullConfig = {
        ...mockSelectedNode,
        data: {
          ...mockSelectedNode.data,
          config: null, // Null config
        },
      };
    render(<NodeConfigPanel selectedNode={nodeWithNullConfig} />);
    expect(screen.getByText(/No specific configuration for this node./i)).toBeInTheDocument();
  });

  test('displays correct heading when node label is missing', () => {
    const nodeWithoutLabel = {
      ...mockSelectedNode,
      data: {
        ...mockSelectedNode.data,
        label: null,
      },
    };
    render(<NodeConfigPanel selectedNode={nodeWithoutLabel} />);
    expect(screen.getByText(`Node: ${nodeWithoutLabel.id}`)).toBeInTheDocument(); // Falls back to ID in heading
  });
});
