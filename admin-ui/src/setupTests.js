// jest-dom adds custom jest matchers for asserting on DOM nodes.
// allows you to do things like:
// expect(element).toHaveTextContent(/react/i)
// learn more: https://github.com/testing-library/jest-dom
import '@testing-library/jest-dom/vitest'; // Use this import for Vitest

// Optional: If you need to globally mock something or set up other test environment aspects
// For example, you might want to silence console.error for specific expected errors during tests:
/*
import { vi } from 'vitest';

const originalError = console.error;
beforeAll(() => {
  console.error = (...args) => {
    if (/Warning: ReactDOM.render is no longer supported in React 18./.test(args[0])) {
      return;
    }
    if (/Warning: Unknown prop `.+` on <.+> tag. Remove this prop from the element./.test(args[0])) {
        // Suppress specific Material-UI or ReactFlow prop warnings if they are noisy and accepted
        return;
    }
    originalError.call(console, ...args);
  };
});

afterAll(() => {
  console.error = originalError;
});
*/
