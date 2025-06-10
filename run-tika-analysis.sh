#!/bin/bash
cd /Users/krickert/IdeaProjects/yappy-work
./gradlew :yappy-models:protobuf-models-test-data-resources:jar

# Run the analyzer using the project's classpath
java -cp "yappy-models/protobuf-models-test-data-resources/build/libs/*:yappy-models/protobuf-models/build/libs/*:$(./gradlew -q :yappy-models:protobuf-models-test-data-resources:printClasspath)" \
     com.krickert.search.model.test.DocumentAnalyzer \
     /Users/krickert/IdeaProjects/yappy-work/yappy-models/protobuf-models-test-data-resources/src/main/resources/test-data/tika-pipe-docs-large \
     tika-analysis-results.json