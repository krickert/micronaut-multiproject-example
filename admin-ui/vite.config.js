/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/setupTests.js', // or .ts if using TypeScript
    // You might want to add other configurations here, like:
    // css: true, // if you want to process CSS files during tests
  },
});
