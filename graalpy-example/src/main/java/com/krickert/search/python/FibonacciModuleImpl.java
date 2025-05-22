/*
 * Copyright 2017-2024 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.krickert.search.python;

import jakarta.inject.Singleton;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 * Implementation of the FibonacciModule interface using GraalPy.
 * This class uses embedded Python code to calculate Fibonacci numbers.
 */
@Singleton
public class FibonacciModuleImpl implements FibonacciModule {

    private final Context pythonContext;
    private final Value fibonacciCalculate;
    private final Value fibonacciSequence;

    /**
     * Constructor that initializes the Python context and functions.
     */
    FibonacciModuleImpl() {
        // Create a Python context
        this.pythonContext = Context.newBuilder("python")
                .allowAllAccess(true)
                .build();

        // Define the Python Fibonacci functions
        String pythonCode = """
            def calculate(position):
                if position < 0:
                    raise ValueError("Position must be a non-negative integer")

                if position <= 1:
                    return position

                a, b = 0, 1
                for _ in range(2, position + 1):
                    a, b = b, a + b

                return b

            def sequence(length):
                if length < 0:
                    raise ValueError("Length must be a non-negative integer")

                result = []
                for i in range(length):
                    result.append(calculate(i))

                return result
            """;

        // Evaluate the Python code
        pythonContext.eval(Source.create("python", pythonCode));

        // Get references to the Python functions
        fibonacciCalculate = pythonContext.getBindings("python").getMember("calculate");
        fibonacciSequence = pythonContext.getBindings("python").getMember("sequence");
    }

    /**
     * Calculate the Fibonacci number at a specific position using Python.
     *
     * @param position The position in the Fibonacci sequence (0-based index)
     * @return The Fibonacci number at the specified position
     * @throws IllegalArgumentException if position is negative
     */
    @Override
    public long calculate(int position) {
        try {
            // Log that we're using Python
            System.out.println("Calculating Fibonacci number using Python for position: " + position);

            // Call the Python function
            Value result = fibonacciCalculate.execute(position);
            return result.asLong();
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Position must be a non-negative integer")) {
                throw new IllegalArgumentException("Position must be a non-negative integer");
            }
            throw new RuntimeException("Error calculating Fibonacci number", e);
        }
    }

    /**
     * Generate a sequence of Fibonacci numbers up to a specified length using Python.
     *
     * @param length The length of the sequence to generate
     * @return An array of Fibonacci numbers
     * @throws IllegalArgumentException if length is negative
     */
    @Override
    public long[] sequence(int length) {
        try {
            // Log that we're using Python
            System.out.println("Generating Fibonacci sequence using Python with length: " + length);

            // Call the Python function
            Value result = fibonacciSequence.execute(length);

            // Convert the Python list to a Java array
            long[] sequence = new long[length];
            for (int i = 0; i < length; i++) {
                sequence[i] = result.getArrayElement(i).asLong();
            }

            return sequence;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Length must be a non-negative integer")) {
                throw new IllegalArgumentException("Length must be a non-negative integer");
            }
            throw new RuntimeException("Error generating Fibonacci sequence", e);
        }
    }
}
