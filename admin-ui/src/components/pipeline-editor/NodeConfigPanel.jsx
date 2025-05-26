import React, { useState, useEffect } from 'react';
import Form from '@rjsf/mui'; // Using MUI theme
import validator from '@rjsf/validator-ajv8';
import { getSchemaById } from '../../services/pipelineService';
import Button from '@mui/material/Button'; // Import MUI Button
import './NodeConfigPanel.css'; 

const NodeConfigPanel = ({ selectedNode, onUpdateNodeConfig }) => { // Added onUpdateNodeConfig prop
  const [schema, setSchema] = useState(null);
  const [isLoadingSchema, setIsLoadingSchema] = useState(false);
  const [schemaError, setSchemaError] = useState(null);
  const [formData, setFormData] = useState({});
  const [formKey, setFormKey] = useState(0); // Key to reset/re-render form

  // Effect to fetch schema when selectedNode changes
  useEffect(() => {
    if (selectedNode && selectedNode.type) { 
      setIsLoadingSchema(true);
      setSchemaError(null);
      setSchema(null); 
      setFormKey(prevKey => prevKey + 1); // Change key to force re-render of Form

      let schemaId = selectedNode.type; 
      // Basic capitalization mapping - this should ideally be more robust or handled by backend schema naming
      if (schemaId === 'tikaParser' || schemaId === 'openSearchSink' || 
          schemaId === 'kafkaSource' || schemaId === 'grpcConnector' ||
          schemaId === 'customProcessor' || schemaId === 'kafkaSink' ||
          schemaId === 'fileSource' || schemaId === 'logSink' || 
          schemaId === 'kafkaTopic') {
          const firstChar = schemaId.charAt(0);
          if (firstChar >= 'a' && firstChar <= 'z') { // Only capitalize if first char is lowercase
            schemaId = firstChar.toUpperCase() + schemaId.slice(1);
          }
      }
      
      console.log(`Fetching schema for node type: ${selectedNode.type}, attempting schema ID: ${schemaId}`);

      getSchemaById(schemaId)
        .then(fetchedSchemaData => {
          console.log('Fetched schema data:', fetchedSchemaData);
          if (fetchedSchemaData && fetchedSchemaData.schema) {
            setSchema(fetchedSchemaData.schema);
          } else if (fetchedSchemaData && Object.keys(fetchedSchemaData).length > 0 && fetchedSchemaData.constructor === Object) {
            // Check if fetchedSchemaData itself is a valid schema object (non-empty and an object)
            console.warn(`Schema data for ${schemaId} does not have a nested 'schema' property. Using the root object. Ensure this is the correct JSON schema.`);
            setSchema(fetchedSchemaData);
          } else {
            console.error(`Schema data for ${schemaId} is undefined, empty, or not a valid object.`);
            setSchemaError(`Schema not found or invalid for type: ${schemaId}`);
            setSchema(null);
          }
          setIsLoadingSchema(false);
        })
        .catch(err => {
          console.error(`Error fetching schema for ${schemaId}:`, err);
          setSchemaError(`Failed to load schema for ${schemaId}. ${err.message}`);
          setIsLoadingSchema(false);
          setSchema(null);
        });
    } else {
      setSchema(null);
      setFormData({});
      setIsLoadingSchema(false);
      setSchemaError(null);
    }
  }, [selectedNode]);

  // Effect to update formData when selectedNode.data.config changes or when selectedNode itself changes
  useEffect(() => {
    if (selectedNode && selectedNode.data && selectedNode.data.config) {
      setFormData(selectedNode.data.config);
      setFormKey(prevKey => prevKey + 1); // Also update key here to ensure form pre-fills correctly
    } else {
      setFormData({});
    }
  }, [selectedNode]); // Rerun when selectedNode changes, which includes its config


  const handleFormSubmit = ({ formData: submittedFormData }) => {
    if (selectedNode && selectedNode.id && onUpdateNodeConfig) {
      console.log(`Applying changes for node ${selectedNode.id}:`, submittedFormData);
      onUpdateNodeConfig(selectedNode.id, submittedFormData);
      // Optionally, provide feedback e.g., a temporary message or a "saved" state
      // For now, console log serves as feedback.
    } else {
      console.error("Cannot apply changes: No selected node or update handler missing.");
    }
  };


  if (!selectedNode) {
    return (
      <div>
        <h3 className="sidebar-heading">Node Configuration</h3>
        <p className="config-panel-placeholder">Select a node to see its details.</p>
      </div>
    );
  }

  const { id, type, data = {} } = selectedNode;
  const { label, status, type: nodeCategory } = data; 

  return (
    <div>
      <h3 className="sidebar-heading">Node: {label || id}</h3>
      <div className="config-panel-content">
        <div className="config-section">
          <p><strong>ID:</strong> {id}</p>
          <p><strong>Registered Type:</strong> {type}</p>
          {nodeCategory && <p><strong>Category:</strong> {nodeCategory}</p>}
          {status && <p><strong>Status:</strong> <span style={getStatusStyle(status)}>{status.charAt(0).toUpperCase() + status.slice(1)}</span></p>}
        </div>
        
        <div className="config-section">
          <strong>Configuration:</strong>
          {isLoadingSchema && <p className="config-message">Loading schema...</p>}
          {schemaError && <p className="config-message error-message">{schemaError}</p>}
          
          {!isLoadingSchema && !schemaError && schema && (
            <Form
              key={formKey} // Add key to force re-render when schema or initial formData changes
              schema={schema}
              formData={formData}
              validator={validator}
              onChange={(e) => setFormData(e.formData)}
              onSubmit={handleFormSubmit} 
              // onError={(errors) => console.log("Form errors:", errors)} 
            >
              <Button 
                type="submit" 
                variant="contained" 
                color="primary" 
                style={{ marginTop: '15px' }}
              >
                Apply Changes
              </Button>
            </Form>
          )}
          {!isLoadingSchema && !schemaError && !schema && selectedNode && (
            <p className="config-message">No schema available for this node type. Raw config (if any):</p>
          )}
           {(!isLoadingSchema && !schemaError && !schema && selectedNode && selectedNode.data && selectedNode.data.config && Object.keys(selectedNode.data.config).length > 0) && (
             <pre className="config-pre">{JSON.stringify(selectedNode.data.config, null, 2)}</pre>
           )}
           {(!isLoadingSchema && !schemaError && !schema && selectedNode && (!selectedNode.data || !selectedNode.data.config || Object.keys(selectedNode.data.config).length === 0)) && (
             <p className="config-message">No configuration data for this node.</p>
           )}
        </div>
      </div>
    </div>
  );
};

const getStatusStyle = (status) => {
  let style = { fontWeight: 'bold', padding: '2px 6px', borderRadius: '4px', color: 'white' };
  if (status === 'running') style.backgroundColor = '#28a745'; 
  else if (status === 'stopped') style.backgroundColor = '#ffc107'; 
  else if (status === 'error') style.backgroundColor = '#dc3545'; 
  else {
    style.backgroundColor = '#6c757d'; 
    style.color = 'white';
  }
  return style;
};

export default NodeConfigPanel;
