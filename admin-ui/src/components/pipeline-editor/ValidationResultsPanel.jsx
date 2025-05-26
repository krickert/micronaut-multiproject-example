import React from 'react';
import { Alert, AlertTitle, List, ListItem, ListItemText, Typography, Paper, Box } from '@mui/material';

const ValidationResultsPanel = ({ validationOutcome }) => {
  if (!validationOutcome) {
    return (
      <Box mt={2} p={2} component={Paper} elevation={1} sx={{ backgroundColor: '#f9f9f9' }}>
        <Typography variant="body2" color="textSecondary">
          Validation results will appear here after running "Validate Pipeline".
        </Typography>
      </Box>
    );
  }

  const { isValid, errors = [], messages = [] } = validationOutcome;

  return (
    <Box mt={2} p={2} component={Paper} elevation={2} sx={{ maxHeight: '300px', overflowY: 'auto', borderColor: isValid ? 'success.main' : 'error.main', borderWidth: '1px', borderStyle: 'solid' }}>
      {isValid ? (
        <Alert severity="success" sx={{ mb: 2 }}>
          <AlertTitle>Pipeline is Valid</AlertTitle>
          {messages.length > 0 ? (
            <List dense>
              {messages.map((msg, index) => (
                <ListItem key={index} disableGutters>
                  <ListItemText primary={msg} />
                </ListItem>
              ))}
            </List>
          ) : (
            "No specific messages."
          )}
        </Alert>
      ) : (
        <Alert severity="error" sx={{ mb: 2 }}>
          <AlertTitle>Pipeline Validation Failed</AlertTitle>
          {errors.length > 0 ? (
            <List dense>
              {errors.map((error, index) => {
                const isGlobalAdvancedError = error.elementType === 'advanced' && (!error.elementId || error.elementId.trim() === "");
                
                return (
                  <ListItem key={index} disableGutters>
                    <ListItemText
                      primary={error.message}
                      secondary={
                        isGlobalAdvancedError
                          ? null // No secondary info for global advanced errors
                          : `Element: ${error.elementType || 'N/A'}` +
                            `${error.elementId ? ` [${error.elementId}]` : ''}` +
                            `${error.field ? `, Field: ${error.field}` : ''}`
                      }
                      primaryTypographyProps={isGlobalAdvancedError ? { sx: { fontWeight: 'medium' } } : {}}
                    />
                  </ListItem>
                );
              })}
            </List>
          ) : (
            "No specific error details provided."
          )}
           {messages.length > 0 && ( 
            <>
              <Typography variant="subtitle2" sx={{ mt: 2, fontWeight: 'bold' }}>Additional Messages:</Typography>
              <List dense>
                {messages.map((msg, index) => (
                  <ListItem key={index} disableGutters>
                    <ListItemText primary={msg} />
                  </ListItem>
                ))}
              </List>
            </>
          )}
        </Alert>
      )}
    </Box>
  );
};

export default ValidationResultsPanel;
