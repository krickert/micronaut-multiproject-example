package com.krickert.search.python;

import io.micronaut.graal.graalpy.annotations.GraalPyModule;

/**
 * GraalPy module interface for the Fibonacci Python module.
 * This interface maps to the Python functions in the fib.py script.
 */
@GraalPyModule("fib")
public interface FibonacciPythonModule {

    /**
     * Calculate the Fibonacci number at a specific position.
     * Maps to the calculate() function in fib.py.
     * 
     * @param position The position in the Fibonacci sequence (0-based index)
     * @return The Fibonacci number at the specified position
     */
    long calculate(int position);

    /**
     * Generate a sequence of Fibonacci numbers up to a specified length.
     * Maps to the sequence() function in fib.py.
     * 
     * @param length The length of the sequence to generate
     * @return An array of Fibonacci numbers
     */
    long[] sequence(int length);
}
