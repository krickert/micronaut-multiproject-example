import React, { useState, useRef, useCallback, useEffect } from 'react';
import { ReactFlowProvider, useNodesState, useEdgesState, addEdge, useReactFlow, Controls, Background } from 'reactflow';
import Button from '@mui/material/Button'; 
import Box from '@mui/material/Box'; 
import TextField from '@mui/material/TextField'; 
import Typography from '@mui/material/Typography'; 
import { useSnackbar } from 'notistack'; 
import 'reactflow/dist/style.css';

import NodePalette from './components/pipeline-editor/NodePalette';
import PipelineList from './components/pipeline-management/PipelineList';
import NodeConfigPanel from './components/pipeline-editor/NodeConfigPanel';
import ValidationResultsPanel from './components/pipeline-editor/ValidationResultsPanel'; 
import { getPipelineById, updatePipeline, createPipeline, deletePipeline, validatePipeline } from './services/pipelineService';

import AppNode from './components/pipeline-editor/custom-nodes/AppNode';
import KafkaTopicNode from './components/pipeline-editor/custom-nodes/KafkaTopicNode';

import './App.css'; 

const initialNodes = [];
const initialEdges = [];

let newIdCounter = 0;
const getNewNodeId = () => `newnode_${newIdCounter++}`;

const nodeTypes = {
  kafkaTopic: KafkaTopicNode,
  kafkaSource: AppNode,
  tikaParser: AppNode,
  openSearchSink: AppNode,
  grpcConnector: AppNode,
  customProcessor: AppNode,
  kafkaSink: AppNode,
  fileSource: AppNode,
  logSink: AppNode,
};

const App = () => {
  const reactFlowWrapper = useRef(null);
  // rawNodes/rawEdges store the authoritative state of the graph (user edits, API load)
  const [rawNodes, setRawNodes, onRawNodesChange] = useNodesState(initialNodes);
  const [rawEdges, setRawEdges, onRawEdgesChange] = useEdgesState(initialEdges);

  // displayNodes/displayEdges are derived from rawNodes/rawEdges + validationOutcome for rendering
  const [displayNodes, setDisplayNodes] = useState(initialNodes);
  const [displayEdges, setDisplayEdges] = useState(initialEdges);
  
  const { project, setViewport } = useReactFlow();
  const { enqueueSnackbar } = useSnackbar(); 

  const [currentPipelineDetails, setCurrentPipelineDetails] = useState(null); 
  const [isLoadingPipeline, setIsLoadingPipeline] = useState(false);
  const [errorLoadingPipeline, setErrorLoadingPipeline] = useState(null);
  
  const [selectedNode, setSelectedNode] = useState(null); // This should be based on displayNodes
  const [isSaving, setIsSaving] = useState(false); 
  const [isCreatingPipeline, setIsCreatingPipeline] = useState(false); 
  const [pipelineListRefreshTrigger, setPipelineListRefreshTrigger] = useState(0); 
  const [isDeletingPipeline, setIsDeletingPipeline] = useState(false);
  const [isValidating, setIsValidating] = useState(false); 
  const [validationOutcome, setValidationOutcome] = useState(null); 

  // Effect to update displayNodes/displayEdges when rawNodes/rawEdges or validationOutcome change
  useEffect(() => {
    let newDisplayNodes = [...rawNodes];
    let newDisplayEdges = [...rawEdges];

    if (validationOutcome && !validationOutcome.isValid && validationOutcome.errors) {
      const problematicNodeIds = new Set();
      const problematicEdgeIds = new Set();

      validationOutcome.errors.forEach(error => {
        if (error.elementType === 'node' && error.elementId) {
          problematicNodeIds.add(error.elementId);
        } else if (error.elementType === 'edge' && error.elementId) {
          problematicEdgeIds.add(error.elementId);
        }
        // Handle errors that might point to nodes via edge source/target
        if (error.elementType === 'edge' && (error.field === 'source' || error.field === 'target')) {
            // If an edge error specifies a source/target node that doesn't exist,
            // the error.elementId might be the edge ID.
            // If we want to highlight nodes connected to problematic edges, we'd need more logic here.
            // For now, focusing on direct elementId matches.
        }
      });

      newDisplayNodes = rawNodes.map(node => {
        if (problematicNodeIds.has(node.id)) {
          return { ...node, data: { ...node.data, hasValidationError: true } };
        }
        return { ...node, data: { ...node.data, hasValidationError: false } }; // Ensure reset
      });

      newDisplayEdges = rawEdges.map(edge => {
        if (problematicEdgeIds.has(edge.id)) {
          return { 
            ...edge, 
            style: { ...edge.style, stroke: '#FF0000', strokeWidth: 3 },
            animated: true,
            hasValidationError: true, // Custom flag for edges too
          };
        }
        // Reset style if not problematic
        const { stroke, strokeWidth, ...restStyle } = edge.style || {};
        return { ...edge, style: restStyle, animated: false, hasValidationError: false }; // Ensure reset
      });
    } else {
      // Clear highlights if validation is successful or outcome is null
      newDisplayNodes = rawNodes.map(node => ({ ...node, data: { ...node.data, hasValidationError: false } }));
      newDisplayEdges = rawEdges.map(edge => {
        const { stroke, strokeWidth, ...restStyle } = edge.style || {};
        return { ...edge, style: restStyle, animated: false, hasValidationError: false };
      });
    }
    setDisplayNodes(newDisplayNodes);
    setDisplayEdges(newDisplayEdges);
  }, [rawNodes, rawEdges, validationOutcome]);


  const updateNodeConfig = useCallback((nodeId, newConfig) => {
    setRawNodes((nds) =>
      nds.map((node) => {
        if (node.id === nodeId) {
          return { ...node, data: { ...node.data, config: newConfig } };
        }
        return node;
      })
    );
    setValidationOutcome(null); 
    enqueueSnackbar('Node configuration updated locally.', { variant: 'info', autoHideDuration: 1500 });
  }, [setRawNodes, enqueueSnackbar]); 

  const onConnect = useCallback(
    (params) => {
        setRawEdges((eds) => addEdge(params, eds));
        setValidationOutcome(null); 
    },
    [setRawEdges],
  );

  const onRawNodesChangeCustom = (changes) => {
    onRawNodesChange(changes);
    setValidationOutcome(null); 
  };

  const onRawEdgesChangeCustom = (changes) => {
    onRawEdgesChange(changes);
    setValidationOutcome(null); 
  };


  const onDragOver = useCallback((event) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const onDrop = useCallback(
    (event) => {
      event.preventDefault();
      const reactFlowBounds = reactFlowWrapper.current.getBoundingClientRect();
      const nodeInfoString = event.dataTransfer.getData('application/reactflow');
      if (!nodeInfoString) return;
      const nodeInfo = JSON.parse(nodeInfoString);
      if (typeof nodeInfo === 'undefined' || !nodeInfo || !nodeInfo.type) return;

      const position = project({
        x: event.clientX - reactFlowBounds.left,
        y: event.clientY - reactFlowBounds.top,
      });
      const newNode = {
        id: getNewNodeId(),
        type: nodeInfo.type,
        position,
        data: { label: `${nodeInfo.label || nodeInfo.type}`, config: {}, status: "stopped" },
      };
      setRawNodes((nds) => nds.concat(newNode));
      setValidationOutcome(null); 
    },
    [project, setRawNodes]
  );

  const handlePipelineSelect = useCallback(async (pipelineId) => {
    setSelectedNode(null); 
    setValidationOutcome(null); 
    if (!pipelineId) {
      setCurrentPipelineDetails(null);
      setRawNodes(initialNodes); // Use raw state setters
      setRawEdges(initialEdges); // Use raw state setters
      setErrorLoadingPipeline(null);
      return;
    }
    setIsLoadingPipeline(true);
    setErrorLoadingPipeline(null);
    try {
      const pipelineData = await getPipelineById(pipelineId);
      setCurrentPipelineDetails(pipelineData); 
      enqueueSnackbar(`Pipeline "${pipelineData.name || pipelineData.id}" loaded.`, { variant: 'info', autoHideDuration: 2000 });

      if (pipelineData && pipelineData.structure) {
        const fetchedNodes = (pipelineData.structure.nodes || []).map(node => ({
          ...node,
          data: node.data || { label: `Node ${node.id}`, config: {}, status: 'unknown' },
          position: node.position || { x: Math.random() * 400, y: Math.random() * 400 },
        }));
        const fetchedEdges = (pipelineData.structure.edges || []).map(edge => ({ ...edge }));
        setRawNodes(fetchedNodes); // Use raw state setters
        setRawEdges(fetchedEdges); // Use raw state setters
        setTimeout(() => {
          const firstNode = fetchedNodes[0];
          if (firstNode) {
            setViewport({ x: firstNode.position.x - 50, y: firstNode.position.y - 50, zoom: 0.9 }, { duration: 800 });
          } else {
            setViewport({ x: 0, y: 0, zoom: 1 }, { duration: 800 });
          }
        }, 100);
      } else {
        setRawNodes(initialNodes); // Use raw state setters
        setRawEdges(initialEdges); // Use raw state setters
        setErrorLoadingPipeline('Pipeline data is not in the expected format.');
        enqueueSnackbar('Loaded pipeline data is not in the expected format.', { variant: 'warning' });
      }
    } catch (error) {
      console.error("Error loading pipeline:", error);
      setErrorLoadingPipeline(`Failed to load pipeline: ${error.message}`);
      enqueueSnackbar(`Error loading pipeline: ${error.response?.data?.message || error.message}`, { variant: 'error' });
      setCurrentPipelineDetails(null); 
      setRawNodes(initialNodes); // Use raw state setters
      setRawEdges(initialEdges); // Use raw state setters
    } finally {
      setIsLoadingPipeline(false);
    }
  }, [setRawNodes, setRawEdges, project, setViewport, enqueueSnackbar]);

  useEffect(() => {
    if (!currentPipelineDetails) {
      setRawNodes(initialNodes); // Use raw state setters
      setRawEdges(initialEdges); // Use raw state setters
      setSelectedNode(null); 
      setValidationOutcome(null); 
    }
  }, [currentPipelineDetails, setRawNodes, setRawEdges]);


  const handleSavePipeline = useCallback(async () => {
    if (!currentPipelineDetails || !currentPipelineDetails.id) {
      enqueueSnackbar('No pipeline selected or pipeline ID is missing.', { variant: 'warning' });
      return;
    }
    setIsSaving(true);
    setValidationOutcome(null); 
    const pipelineDtoToSave = {
      id: currentPipelineDetails.id,
      name: currentPipelineDetails.name, 
      description: currentPipelineDetails.description, 
      structure: {
        nodes: rawNodes, // Save rawNodes
        edges: rawEdges, // Save rawEdges
      },
    };
    try {
      await updatePipeline(currentPipelineDetails.id, pipelineDtoToSave);
      enqueueSnackbar(`Pipeline "${pipelineDtoToSave.name}" saved successfully!`, { variant: 'success' });
    } catch (error) {
      console.error('Failed to save pipeline:', error);
      enqueueSnackbar(`Error saving pipeline: ${error.response?.data?.message || error.message}`, { variant: 'error' });
    } finally {
      setIsSaving(false);
    }
  }, [currentPipelineDetails, rawNodes, rawEdges, enqueueSnackbar]);

  const handleCreateNewPipeline = useCallback(async () => {
    const pipelineName = window.prompt("Enter new pipeline name:");
    if (!pipelineName || pipelineName.trim() === "") {
      if (pipelineName !== null) { 
        enqueueSnackbar("Pipeline name cannot be empty.", { variant: 'warning' });
      }
      return;
    }
    setIsCreatingPipeline(true);
    setValidationOutcome(null); 
    const initialStructure = { nodes: [], edges: [] };
    const newPipelineData = {
      name: pipelineName,
      description: "", 
      structure: initialStructure,
    };
    try {
      const createdPipeline = await createPipeline(newPipelineData);
      enqueueSnackbar(`Pipeline "${createdPipeline.name}" created successfully!`, { variant: 'success' });
      setPipelineListRefreshTrigger(prev => prev + 1);
      setCurrentPipelineDetails(createdPipeline);
      setRawNodes(createdPipeline.structure.nodes || []); // Use raw state setters
      setRawEdges(createdPipeline.structure.edges || []); // Use raw state setters
      setSelectedNode(null); 
      setTimeout(() => {
        setViewport({ x: 0, y: 0, zoom: 1 }, { duration: 300 });
      }, 100);
    } catch (error) {
      console.error('Failed to create new pipeline:', error);
      enqueueSnackbar(`Error creating pipeline: ${error.response?.data?.message || error.message}`, { variant: 'error' });
    } finally {
      setIsCreatingPipeline(false);
    }
  }, [setViewport, enqueueSnackbar, setRawNodes, setRawEdges]); 

  const handleAttemptDeletePipeline = useCallback(async (pipelineIdToDelete) => {
    if (!pipelineIdToDelete) return;
    setValidationOutcome(null); 

    // eslint-disable-next-line no-restricted-globals
    if (window.confirm(`Are you sure you want to delete pipeline ID: ${pipelineIdToDelete}? This action cannot be undone.`)) {
      setIsDeletingPipeline(true);
      try {
        await deletePipeline(pipelineIdToDelete);
        enqueueSnackbar(`Pipeline ${pipelineIdToDelete} deleted successfully.`, { variant: 'success' });
        setPipelineListRefreshTrigger(prev => prev + 1); 
        if (currentPipelineDetails && currentPipelineDetails.id === pipelineIdToDelete) {
          setCurrentPipelineDetails(null);
          setRawNodes(initialNodes); // Use raw state setters
          setRawEdges(initialEdges); // Use raw state setters
          setSelectedNode(null);
        }
      } catch (error) {
        console.error(`Failed to delete pipeline ${pipelineIdToDelete}:`, error);
        enqueueSnackbar(`Error deleting pipeline: ${error.response?.data?.message || error.message}`, { variant: 'error' });
      } finally {
        setIsDeletingPipeline(false);
      }
    }
  }, [currentPipelineDetails, enqueueSnackbar, setRawNodes, setRawEdges]); 

  const handleValidatePipeline = useCallback(async () => {
    if (!currentPipelineDetails) {
      enqueueSnackbar('No pipeline selected to validate.', { variant: 'info' });
      return;
    }
    setIsValidating(true);
    setValidationOutcome(null); 
    const pipelineDataToValidate = {
      name: currentPipelineDetails.name, 
      structure: {
        nodes: rawNodes, // Validate rawNodes
        edges: rawEdges, // Validate rawEdges
      },
    };
    try {
      const results = await validatePipeline(pipelineDataToValidate);
      setValidationOutcome(results); 
      if (results.isValid) {
        enqueueSnackbar('Pipeline validation successful: Structure is valid.', { variant: 'success' });
      } else {
        enqueueSnackbar(`Pipeline validation found issues: ${results.errors.length} error(s). See panel for details.`, { variant: 'warning' });
      }
    } catch (error) {
      console.error('Error validating pipeline:', error);
      enqueueSnackbar(`Error validating pipeline: ${error.response?.data?.message || error.message}`, { variant: 'error' });
      setValidationOutcome({ isValid: false, errors: [{ message: `Validation request failed: ${error.message}` }], messages:[] });
    } finally {
      setIsValidating(false);
    }
  }, [currentPipelineDetails, rawNodes, rawEdges, enqueueSnackbar]);

  const handlePipelineNameChange = (event) => {
    if (currentPipelineDetails) {
      setCurrentPipelineDetails(prevDetails => ({
        ...prevDetails,
        name: event.target.value,
      }));
      setValidationOutcome(null); 
    }
  };

  const handlePipelineDescriptionChange = (event) => {
    if (currentPipelineDetails) {
      setCurrentPipelineDetails(prevDetails => ({
        ...prevDetails,
        description: event.target.value,
      }));
      setValidationOutcome(null); 
    }
  };


  const onNodeClick = useCallback((event, node) => {
    // Find the original rawNode to ensure config panel edits the source of truth
    const rawNode = rawNodes.find(n => n.id === node.id);
    setSelectedNode(rawNode || node); // Fallback to node from displayNodes if somehow not found
  }, [rawNodes]);

  const onPaneClick = useCallback(() => {
    setSelectedNode(null);
  }, []);

  const anyOperationInProgress = isLoadingPipeline || isSaving || isCreatingPipeline || isDeletingPipeline || isValidating;

  return (
    <div className="app-container">
      <div className="sidebar pipeline-list-sidebar">
        <Box sx={{ p: 0, borderBottom: '1px solid #dee2e6', mb: '10px', pb: '10px' }}>
          <Typography variant="h6" gutterBottom sx={{ pl:1, pr:1, pt:1, fontSize: '1.1rem', color: '#495057'}}>
            Pipeline Properties
          </Typography>
          <Box sx={{pl:1, pr:1, mb: 1.5}}>
            <TextField
              label="Pipeline Name"
              value={currentPipelineDetails ? currentPipelineDetails.name : ''}
              onChange={handlePipelineNameChange}
              disabled={!currentPipelineDetails || anyOperationInProgress}
              fullWidth
              variant="outlined"
              size="small"
            />
          </Box>
          <Box sx={{pl:1, pr:1}}>
            <TextField
              label="Pipeline Description"
              value={currentPipelineDetails ? currentPipelineDetails.description : ''}
              onChange={handlePipelineDescriptionChange}
              disabled={!currentPipelineDetails || anyOperationInProgress}
              fullWidth
              multiline
              rows={3}
              variant="outlined"
              size="small"
            />
          </Box>
        </Box>

        <Box sx={{ p: 0, borderBottom: '1px solid #dee2e6', mb: '10px' }}> 
          <Box sx={{ display: 'flex', gap: '10px', mb: '10px', pl:1, pr:1, pt:1 }}>
            <Button
              variant="contained"
              color="primary"
              onClick={handleSavePipeline}
              disabled={!currentPipelineDetails || anyOperationInProgress}
              fullWidth
              style={{ textTransform: 'none' }}
            >
              {isSaving ? 'Saving...' : 'Save Pipeline'}
            </Button>
            <Button
              variant="outlined" 
              color="secondary"
              onClick={handleCreateNewPipeline}
              disabled={anyOperationInProgress}
              fullWidth
              style={{ textTransform: 'none' }}
            >
              {isCreatingPipeline ? 'Creating...' : 'New Pipeline'}
            </Button>
          </Box>
          <Box sx={{pl:1, pr:1, mb: '10px'}}>
            <Button
              variant="outlined"
              color="info" 
              onClick={handleValidatePipeline}
              disabled={!currentPipelineDetails || anyOperationInProgress}
              fullWidth
              style={{ textTransform: 'none' }}
            >
              {isValidating ? 'Validating...' : 'Validate Pipeline'}
            </Button>
          </Box>
          <Box sx={{pl:1, pr:1}}> 
            <ValidationResultsPanel validationOutcome={validationOutcome} />
          </Box>
        </Box>
        <PipelineList 
            onPipelineSelect={handlePipelineSelect} 
            onAttemptDeletePipeline={handleAttemptDeletePipeline} 
            key={pipelineListRefreshTrigger} 
        />
      </div>
      <div className="sidebar node-palette-sidebar">
        <NodePalette />
      </div>
      
      <div className="pipeline-editor-container" ref={reactFlowWrapper} onDragOver={onDragOver} onDrop={onDrop}>
        {isLoadingPipeline && <div className="loading-overlay">Loading pipeline...</div>}
        {errorLoadingPipeline && <div className="error-overlay">{errorLoadingPipeline}</div>}
        <ReactFlow
          nodes={displayNodes} // Use displayNodes
          edges={displayEdges} // Use displayEdges
          onNodesChange={onRawNodesChangeCustom} 
          onEdgesChange={onRawEdgesChangeCustom} 
          onConnect={onConnect}
          nodeTypes={nodeTypes}
          onNodeClick={onNodeClick}
          onPaneClick={onPaneClick}
          fitView={!currentPipelineDetails && displayNodes.length === 0} 
        >
          <Controls />
          <Background />
        </ReactFlow>
      </div>

      <div className="sidebar node-config-sidebar">
        <NodeConfigPanel selectedNode={selectedNode} onUpdateNodeConfig={updateNodeConfig} />
      </div>
    </div>
  );
};

const AppWrapper = () => (
  <ReactFlowProvider>
    <App />
  </ReactFlowProvider>
);

export default AppWrapper;
