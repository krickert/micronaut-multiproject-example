import React from 'react';
import { Handle, Position } from 'reactflow';

const AppNode = ({ data, type, selected }) => { 
  let statusBorderColor = '#adb5bd'; 
  let statusTextColor = '#495057'; 
  let statusText = data.status ? data.status.charAt(0).toUpperCase() + data.status.slice(1) : 'Unknown';

  if (data.status === 'running') {
    statusBorderColor = '#28a745'; 
    statusTextColor = '#28a745';
  } else if (data.status === 'stopped') {
    statusBorderColor = '#ffc107'; 
    statusTextColor = '#b38600'; 
  } else if (data.status === 'error') {
    statusBorderColor = '#dc3545'; 
    statusTextColor = '#dc3545';
  }

  // Error highlighting takes precedence for border, but selection can still add a shadow
  let borderStyle = `2px solid ${data.hasValidationError ? '#FF0000' : statusBorderColor}`;
  if (data.hasValidationError) {
    borderStyle = `3px dashed #FF0000`; // More prominent error border
  }


  const nodeStyle = {
    padding: '12px 18px', 
    borderRadius: '6px',  
    background: '#f8f9fa', 
    border: borderStyle, // Apply dynamic border style
    minWidth: '160px', 
    textAlign: 'center',
    boxShadow: selected 
      ? `0 0 0 2px #007bff, 0 2px 5px rgba(0,0,0,0.15)` 
      : `0 1px 3px rgba(0,0,0,0.1)`, 
    transition: 'box-shadow 0.2s ease-in-out, border-color 0.2s ease-in-out, border-style 0.2s ease-in-out',
    opacity: data.hasValidationError ? 0.9 : 1, // Slightly transparent if error, if desired
  };

  const showSourceHandle = !type?.toLowerCase().includes('sink');
  const showTargetHandle = !type?.toLowerCase().includes('source');

  return (
    <div style={nodeStyle}>
      {showTargetHandle && <Handle type="target" position={Position.Left} style={{ background: '#6c757d' }} />}
      <div style={{ marginBottom: '5px' }}> 
        <strong style={{ fontSize: '1em', color: '#343a40' }}>{data.label || 'Unnamed Node'}</strong>
      </div>
      
      {data.type && ( 
        <div style={{ fontSize: '0.75em', color: '#6c757d', marginBottom: '5px' }}> 
          Category: {data.type}
        </div>
      )}
      
      {data.status && ( 
        <div style={{ fontSize: '0.8em', color: data.hasValidationError ? '#FF0000' : statusTextColor, fontWeight: 'bold' }}>
          {data.hasValidationError ? `Error: ${statusText}` : statusText}
        </div>
      )}
      {!data.status && data.hasValidationError && ( // If no status but has validation error
         <div style={{ fontSize: '0.8em', color: '#FF0000', fontWeight: 'bold' }}>
            Validation Error
        </div>
      )}
      {showSourceHandle && <Handle type="source" position={Position.Right} style={{ background: '#6c757d' }} />}
    </div>
  );
};

export default AppNode;
