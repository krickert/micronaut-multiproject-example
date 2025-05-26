import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import PipelineList from './PipelineList'; // Adjust path as necessary
import * as pipelineService from '../../services/pipelineService'; // To mock its functions
import '@testing-library/jest-dom';
import { vi } from 'vitest';

// Mock the pipelineService
vi.mock('../../services/pipelineService');

const mockPipelines = [
  { id: 'pipeline-uuid-1', name: 'Event Processing Pipeline', description: 'Processes raw events...' },
  { id: 'pipeline-uuid-2', name: 'Customer Data Sync', description: 'Synchronizes customer data...' },
];

describe('PipelineList Component', () => {
  beforeEach(() => {
    // Reset mocks before each test
    pipelineService.getAllPipelines.mockReset();
  });

  test('renders loading state initially', () => {
    pipelineService.getAllPipelines.mockReturnValue(new Promise(() => {})); // Keep promise pending
    render(<PipelineList onPipelineSelect={() => {}} />);
    expect(screen.getByText(/Loading pipelines.../i)).toBeInTheDocument();
  });

  test('renders a list of pipelines based on mocked data', async () => {
    pipelineService.getAllPipelines.mockResolvedValue(mockPipelines);
    render(<PipelineList onPipelineSelect={() => {}} />);

    // Wait for the component to update after fetching data
    await waitFor(() => {
      expect(screen.getByText('Event Processing Pipeline')).toBeInTheDocument();
      expect(screen.getByText('Customer Data Sync')).toBeInTheDocument();
    });
  });

  test('calls onPipelineSelect with the correct pipeline ID when an item is clicked', async () => {
    pipelineService.getAllPipelines.mockResolvedValue(mockPipelines);
    const mockOnPipelineSelect = vi.fn();
    render(<PipelineList onPipelineSelect={mockOnPipelineSelect} />);

    await waitFor(() => {
      expect(screen.getByText('Event Processing Pipeline')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Event Processing Pipeline'));
    expect(mockOnPipelineSelect).toHaveBeenCalledTimes(1);
    expect(mockOnPipelineSelect).toHaveBeenCalledWith('pipeline-uuid-1');

    fireEvent.click(screen.getByText('Customer Data Sync'));
    expect(mockOnPipelineSelect).toHaveBeenCalledTimes(2);
    expect(mockOnPipelineSelect).toHaveBeenCalledWith('pipeline-uuid-2');
  });

  test('renders error message if fetching pipelines fails', async () => {
    pipelineService.getAllPipelines.mockRejectedValue(new Error('Failed to fetch'));
    render(<PipelineList onPipelineSelect={() => {}} />);

    await waitFor(() => {
      expect(screen.getByText(/Failed to load pipelines/i)).toBeInTheDocument();
    });
  });

  test('renders "No pipelines found." message if data is empty', async () => {
    pipelineService.getAllPipelines.mockResolvedValue([]);
    render(<PipelineList onPipelineSelect={() => {}} />);

    await waitFor(() => {
      expect(screen.getByText(/No pipelines found/i)).toBeInTheDocument();
    });
  });
});
