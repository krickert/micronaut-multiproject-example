import React, { useState, useEffect } from 'react';
import { getAllPipelines } from '../../services/pipelineService';
import Button from '@mui/material/Button'; // For delete button
import IconButton from '@mui/material/IconButton';
import DeleteIcon from '@mui/icons-material/Delete';
import './PipelineList.css'; 

const PipelineList = ({ onPipelineSelect, onAttemptDeletePipeline }) => { // Added onAttemptDeletePipeline
  const [pipelines, setPipelines] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    const fetchPipelines = async () => {
      try {
        setLoading(true);
        const data = await getAllPipelines();
        setPipelines(data || []); 
        setError(null);
      } catch (err) {
        console.error('Failed to fetch pipelines:', err);
        setError('Failed to load pipelines.');
        setPipelines([]); 
      } finally {
        setLoading(false);
      }
    };

    fetchPipelines();
  }, []); // This effect runs when the component mounts (or key changes)

  if (loading) {
    return <p className="status-message">Loading pipelines...</p>;
  }

  if (error) {
    return <p className="status-message error-message">{error}</p>;
  }

  if (pipelines.length === 0) {
    return <p className="status-message">No pipelines found.</p>;
  }

  return (
    <div> 
      <h3 className="sidebar-heading">Pipelines</h3>
      <ul> 
        {pipelines.map((pipeline) => (
          <li
            key={pipeline.id}
            className="pipeline-list-item" 
          >
            <span 
              onClick={() => onPipelineSelect(pipeline.id)} 
              className="pipeline-name"
              title={`Load pipeline: ${pipeline.name || 'Unnamed Pipeline'}`}
            >
              {pipeline.name || 'Unnamed Pipeline'}
            </span>
            <IconButton
              aria-label="delete pipeline"
              onClick={(e) => {
                e.stopPropagation(); // Prevent li's onClick from firing
                onAttemptDeletePipeline(pipeline.id);
              }}
              size="small"
              className="delete-button"
              title={`Delete ${pipeline.name || 'Unnamed Pipeline'}`}
            >
              <DeleteIcon fontSize="inherit" />
            </IconButton>
          </li>
        ))}
      </ul>
    </div>
  );
};

export default PipelineList;
