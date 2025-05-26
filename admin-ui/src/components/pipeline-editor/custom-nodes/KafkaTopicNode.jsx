import React from 'react';
import { Handle, Position } from 'reactflow';

const KafkaTopicNode = ({ data, selected }) => {
  let statusBorderColor = '#adb5bd'; 
  let statusTextColor = '#495057';
  let statusText = data.status ? data.status.charAt(0).toUpperCase() + data.status.slice(1) : 'Info'; 

  if (data.status === 'error' || data.status === 'critical') {
    statusBorderColor = '#dc3545'; 
    statusTextColor = '#dc3545';
  } else if (data.status === 'warning') {
    statusBorderColor = '#ffc107'; 
    statusTextColor = '#b38600';
  } else if (data.status === 'ready' || data.status === 'nominal' || data.status === 'running') { 
    statusBorderColor = '#28a745'; 
    statusTextColor = '#28a745';
  }

  let borderStyle = `2px solid ${data.hasValidationError ? '#FF0000' : statusBorderColor}`;
  if (data.hasValidationError) {
    borderStyle = `3px dashed #FF0000`; // More prominent error border for Kafka topics
  }


  const nodeStyle = {
    padding: '15px',
    borderRadius: '4px', 
    background: '#e0f7fa', 
    border: borderStyle, // Apply dynamic border style
    width: '160px', 
    height: '160px', 
    textAlign: 'center',
    display: 'flex',
    flexDirection: 'column',
    justifyContent: 'center',
    alignItems: 'center',
    boxShadow: selected 
      ? `0 0 0 2px #007bff, 0 2px 5px rgba(0,0,0,0.15)` 
      : `0 1px 3px rgba(0,0,0,0.1)`, 
    transition: 'box-shadow 0.2s ease-in-out, border-color 0.2s ease-in-out, border-style 0.2s ease-in-out',
    opacity: data.hasValidationError ? 0.9 : 1,
  };

  return (
    <div style={nodeStyle}>
      <Handle type="target" position={Position.Top} style={{ background: '#6c757d', height: '8px', width: '8px' }} />
      <Handle type="source" position={Position.Bottom} style={{ background: '#6c757d', height: '8px', width: '8px' }} />
      <Handle type="target" position={Position.Left} style={{ background: '#6c757d', top: 'auto', height: '8px', width: '8px' }} /> 
      <Handle type="source" position={Position.Right} style={{ background: '#6c757d', top: 'auto', height: '8px', width: '8px' }} />
      
      <div style={{ marginBottom: '8px' }}>
        <strong style={{ fontSize: '1em', color: '#0077b6' }}>{data.label || 'Kafka Topic'}</strong> 
      </div>
      {data.config?.name && (
        <div style={{ fontSize: '0.75em', color: '#005073', marginBottom: '8px', wordBreak: 'break-all' }}>
          ({data.config.name})
        </div>
      )}
       {data.status && (
        <div style={{ fontSize: '0.8em', color: data.hasValidationError ? '#FF0000' : statusTextColor, fontWeight: 'bold' }}>
          {data.hasValidationError ? `Error: ${statusText}` : statusText}
        </div>
      )}
      {!data.status && data.hasValidationError && (
         <div style={{ fontSize: '0.8em', color: '#FF0000', fontWeight: 'bold' }}>
            Validation Error
        </div>
      )}
    </div>
  );
};

export default KafkaTopicNode;
