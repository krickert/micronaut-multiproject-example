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

import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

// Import the generated gRPC classes
import com.krickert.search.python.FibonacciServiceGrpc;
import com.krickert.search.python.FibonacciRequest;
import com.krickert.search.python.FibonacciResponse;
import com.krickert.search.python.FibonacciSequenceRequest;
import com.krickert.search.python.FibonacciSequenceResponse;

/**
 * Implementation of the Fibonacci gRPC service.
 * This service uses the FibonacciModule to calculate Fibonacci numbers.
 */
@Singleton
@GrpcService
public class FibonacciServiceImpl extends FibonacciServiceGrpc.FibonacciServiceImplBase {

    private final FibonacciModule fibonacciModule;

    /**
     * Constructor with dependency injection.
     *
     * @param fibonacciModule The GraalPy module for Fibonacci calculations
     */
    @Inject
    public FibonacciServiceImpl(FibonacciModule fibonacciModule) {
        this.fibonacciModule = fibonacciModule;
    }

    /**
     * Calculate a single Fibonacci number at the requested position.
     *
     * @param request The request containing the position
     * @param responseObserver The observer for the response
     */
    @Override
    public void calculateFibonacci(FibonacciRequest request, StreamObserver<FibonacciResponse> responseObserver) {
        try {
            int position = request.getPosition();
            long result = fibonacciModule.calculate(position);

            FibonacciResponse response = FibonacciResponse.newBuilder()
                    .setResult(result)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }

    /**
     * Calculate a sequence of Fibonacci numbers up to the requested length.
     *
     * @param request The request containing the sequence length
     * @param responseObserver The observer for the response
     */
    @Override
    public void calculateFibonacciSequence(FibonacciSequenceRequest request, StreamObserver<FibonacciSequenceResponse> responseObserver) {
        try {
            int length = request.getLength();
            long[] sequence = fibonacciModule.sequence(length);

            FibonacciSequenceResponse.Builder responseBuilder = FibonacciSequenceResponse.newBuilder();
            for (long num : sequence) {
                responseBuilder.addSequence(num);
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }
}
