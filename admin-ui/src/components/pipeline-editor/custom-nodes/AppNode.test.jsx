import React from 'react';
import { render, screen } from '@testing-library/react';
import AppNode from './AppNode'; // Adjust path as necessary
import '@testing-library/jest-dom';

describe('AppNode Component', () => {
  const mockDataRunning = {
    label: 'Test Node Running',
    status: 'running',
    type: 'processor', // Example category
  };

  const mockDataStopped = {
    label: 'Test Node Stopped',
    status: 'stopped',
  };
  
  const mockDataError = {
    label: 'Test Node Error',
    status: 'error',
  };

  test('renders the node label', () => {
    render(<AppNode data={mockDataRunning} type="testType" selected={false} />);
    expect(screen.getByText('Test Node Running')).toBeInTheDocument();
  });

  test('renders the node category if data.type is provided', () => {
    render(<AppNode data={mockDataRunning} type="testType" selected={false} />);
    expect(screen.getByText(/Category: processor/i)).toBeInTheDocument();
  });

  test('does not render category if data.type is not provided', () => {
    render(<AppNode data={mockDataStopped} type="testType" selected={false} />);
    expect(screen.queryByText(/Category:/i)).not.toBeInTheDocument();
  });

  test('applies running status indicator', () => {
    render(<AppNode data={mockDataRunning} type="testType" selected={false} />);
    const nodeElement = screen.getByText('Test Node Running').closest('div'); // Get the main div of the node
    expect(nodeElement).toHaveStyle('border: 2px solid #28a745'); // Green border for running
    expect(screen.getByText('Running')).toBeInTheDocument();
    expect(screen.getByText('Running')).toHaveStyle('color: rgb(40, 167, 69)'); // Green text
  });

  test('applies stopped status indicator', () => {
    render(<AppNode data={mockDataStopped} type="testType" selected={false} />);
    const nodeElement = screen.getByText('Test Node Stopped').closest('div');
    expect(nodeElement).toHaveStyle('border: 2px solid #ffc107'); // Yellow/Orange border for stopped
    expect(screen.getByText('Stopped')).toBeInTheDocument();
    expect(screen.getByText('Stopped')).toHaveStyle('color: rgb(179, 134, 0)'); // Darker yellow text
  });
  
  test('applies error status indicator', () => {
    render(<AppNode data={mockDataError} type="testType" selected={false} />);
    const nodeElement = screen.getByText('Test Node Error').closest('div');
    expect(nodeElement).toHaveStyle('border: 2px solid #dc3545'); // Red border for error
    expect(screen.getByText('Error')).toBeInTheDocument();
    expect(screen.getByText('Error')).toHaveStyle('color: rgb(220, 53, 69)'); // Red text
  });

  test('applies selection indicator when selected prop is true', () => {
    render(<AppNode data={mockDataRunning} type="testType" selected={true} />);
    const nodeElement = screen.getByText('Test Node Running').closest('div');
    // Check for the specific box-shadow applied for selection
    expect(nodeElement).toHaveStyle('box-shadow: 0 0 0 2px #007bff, 0 2px 5px rgba(0,0,0,0.15)');
  });

  test('does not apply selection indicator when selected prop is false', () => {
    render(<AppNode data={mockDataRunning} type="testType" selected={false} />);
    const nodeElement = screen.getByText('Test Node Running').closest('div');
    expect(nodeElement).toHaveStyle('box-shadow: 0 1px 3px rgba(0,0,0,0.1)');
  });

  // Test handle visibility based on type (basic heuristic)
  test('hides source handle if type includes "sink"', () => {
    const { container } = render(<AppNode data={{ label: 'Sink Node' }} type="openSearchSink" selected={false} />);
    // React Flow handles are not easily queryable by role or text.
    // We can check the number of handles, or their presence based on class/style if specific enough.
    // For simplicity, this test might be more robust with more specific selectors or e2e tests.
    // A common way is to count elements with class 'react-flow__handle-source'
    const sourceHandles = container.querySelectorAll('.react-flow__handle-source');
    expect(sourceHandles.length).toBe(0);
    const targetHandles = container.querySelectorAll('.react-flow__handle-target');
    expect(targetHandles.length).toBe(1); // Should still have target handle
  });

  test('hides target handle if type includes "source"', () => {
    const { container } = render(<AppNode data={{ label: 'Source Node' }} type="kafkaSource" selected={false} />);
    const targetHandles = container.querySelectorAll('.react-flow__handle-target');
    expect(targetHandles.length).toBe(0);
    const sourceHandles = container.querySelectorAll('.react-flow__handle-source');
    expect(sourceHandles.length).toBe(1); // Should still have source handle
  });

  test('shows both handles for a processor type', () => {
    const { container } = render(<AppNode data={{ label: 'Processor Node' }} type="customProcessor" selected={false} />);
    const targetHandles = container.querySelectorAll('.react-flow__handle-target');
    expect(targetHandles.length).toBe(1);
    const sourceHandles = container.querySelectorAll('.react-flow__handle-source');
    expect(sourceHandles.length).toBe(1);
  });
});
