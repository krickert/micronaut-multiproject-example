import React from 'react'
import ReactDOM from 'react-dom/client'
import AppWrapper from './App' // AppWrapper already includes ReactFlowProvider
import './index.css'
import { SnackbarProvider } from 'notistack';
import { ThemeProvider, createTheme } from '@mui/material/styles'; // For MUI consistency (optional)

// Optional: Define a basic MUI theme if not already done elsewhere,
// so notistack's MUI components (if used via @rjsf/mui or directly) look consistent.
const theme = createTheme({
  // You can define palette, typography, etc. here
});

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ThemeProvider theme={theme}> {/* Optional: ThemeProvider for MUI */}
      <SnackbarProvider 
        maxSnack={3} 
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        autoHideDuration={3000} // Auto hide after 3 seconds
      >
        <AppWrapper /> 
      </SnackbarProvider>
    </ThemeProvider>
  </React.StrictMode>,
)
