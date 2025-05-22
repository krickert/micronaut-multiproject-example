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

/**
 * Interface for calculating Fibonacci numbers.
 * This interface provides methods to calculate individual Fibonacci numbers
 * and generate Fibonacci sequences.
 */
public interface FibonacciModule {

    /**
     * Calculate the Fibonacci number at a specific position.
     * 
     * @param position The position in the Fibonacci sequence (0-based index)
     * @return The Fibonacci number at the specified position
     */
    long calculate(int position);

    /**
     * Generate a sequence of Fibonacci numbers up to a specified length.
     * 
     * @param length The length of the sequence to generate
     * @return An array of Fibonacci numbers
     */
    long[] sequence(int length);
}
