import axios from 'axios';

const API_BASE_URL = 'http://localhost:8080/api'; // Assuming yappy-engine runs on port 8080

const pipelineClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

export const getAllPipelines = async () => {
  try {
    const response = await pipelineClient.get('/pipelines/');
    return response.data;
  } catch (error) {
    console.error('Error fetching all pipelines:', error);
    throw error;
  }
};

export const getPipelineById = async (pipelineId) => {
  if (!pipelineId) {
    throw new Error('pipelineId is required');
  }
  try {
    const response = await pipelineClient.get(`/pipelines/${pipelineId}`);
    return response.data;
  } catch (error) {
    console.error(`Error fetching pipeline with id ${pipelineId}:`, error);
    throw error;
  }
};

// Future functions like create, update, delete can be added here
// export const createPipeline = async (pipelineData) => { ... }
// export const updatePipeline = async (pipelineId, pipelineData) => { ... }
// export const deletePipeline = async (pipelineId) => { ... }

export default {
  getAllPipelines,
  getPipelineById,
  getSchemaById,
  updatePipeline,
  createPipeline,
  deletePipeline,
  validatePipeline, // Add the new function here
};

export const validatePipeline = async (pipelineData) => {
  if (!pipelineData || !pipelineData.structure) {
    throw new Error('Pipeline structure is required for validation');
  }
  try {
    const response = await pipelineClient.post('/pipelines/validate', pipelineData);
    return response.data; // The PipelineValidationResponseDto from the backend
  } catch (error) {
    console.error('Error validating pipeline:', error);
    if (error.response) {
      console.error('Error response data:', error.response.data);
      console.error('Error response status:', error.response.status);
    }
    throw error;
  }
};

export const deletePipeline = async (pipelineId) => {
  if (!pipelineId) {
    throw new Error('pipelineId is required for deletion');
  }
  try {
    // DELETE requests might not return a body, so focus on status or handle no content
    await pipelineClient.delete(`/pipelines/${pipelineId}`);
    return true; // Indicate success
  } catch (error) {
    console.error(`Error deleting pipeline ${pipelineId}:`, error);
    if (error.response) {
      console.error('Error response data:', error.response.data);
      console.error('Error response status:', error.response.status);
    }
    throw error;
  }
};

export const createPipeline = async (newPipelineData) => {
  if (!newPipelineData || !newPipelineData.name) {
    throw new Error('Pipeline name is required for creation');
  }
  try {
    const response = await pipelineClient.post('/pipelines/', newPipelineData);
    return response.data; // The newly created pipeline from the backend
  } catch (error) {
    console.error('Error creating new pipeline:', error);
    if (error.response) {
      console.error('Error response data:', error.response.data);
      console.error('Error response status:', error.response.status);
    }
    throw error;
  }
};

export const updatePipeline = async (pipelineId, pipelineDto) => {
  if (!pipelineId) {
    throw new Error('pipelineId is required for update');
  }
  if (!pipelineDto) {
    throw new Error('pipelineDto is required for update');
  }
  try {
    const response = await pipelineClient.put(`/pipelines/${pipelineId}`, pipelineDto);
    return response.data;
  } catch (error) {
    console.error(`Error updating pipeline ${pipelineId}:`, error);
    if (error.response) {
      console.error('Error response data:', error.response.data);
      console.error('Error response status:', error.response.status);
    }
    throw error;
  }
};

export const getSchemaById = async (schemaId) => {
  if (!schemaId) {
    throw new Error('schemaId is required');
  }
  try {
    // Use the existing pipelineClient which has the baseURL configured
    const response = await pipelineClient.get(`/schemas/${schemaId}`);
    return response.data; // This should be the JSON schema object
  } catch (error) {
    console.error(`Error fetching schema for ID ${schemaId}:`, error);
    // Log additional details if available (e.g., from error.response)
    if (error.response) {
      console.error('Error response data:', error.response.data);
      console.error('Error response status:', error.response.status);
    }
    throw error; // Re-throw to allow caller to handle
  }
};
