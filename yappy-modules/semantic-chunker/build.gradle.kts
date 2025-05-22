plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.22" // For Kotlin DSL support
    id("com.github.johnrengelman.shadow") version "8.1.1" // For creating fat JARs
}

version = "1.0.0-SNAPSHOT"
group = "com.krickert.yappy.modules.semanticchunker"

repositories {
    mavenCentral()
}

// Custom task to generate Python gRPC code from protobuf files
tasks.register<Exec>("generatePythonProto") {
    workingDir = projectDir
    commandLine("bash", "-c", """
        # Create directories if they don't exist
        mkdir -p src/main/python/generated
        
        # Get the protobuf files from the protobuf-models project
        PROTO_DIR=${rootProject.projectDir}/yappy-models/protobuf-models/src/main/proto
        
        # Generate Python code from proto files
        python -m grpc_tools.protoc \
            -I$PROTO_DIR \
            --python_out=src/main/python/generated \
            --grpc_python_out=src/main/python/generated \
            $PROTO_DIR/yappy_core_types.proto \
            $PROTO_DIR/pipe_step_processor_service.proto \
            $PROTO_DIR/yappy_service_registration.proto
            
        # Create __init__.py files to make the generated code importable
        touch src/main/python/generated/__init__.py
        find src/main/python/generated -type d -exec touch {}/__init__.py \;
    """)
}

// Custom task to install Python dependencies
tasks.register<Exec>("installPythonDependencies") {
    workingDir = projectDir
    commandLine("bash", "-c", """
        # Create and activate virtual environment if it doesn't exist
        if [ ! -d "venv" ]; then
            python -m venv venv
        fi
        
        # Install dependencies
        ./venv/bin/pip install -r requirements.txt
    """)
}

// Custom task to run Python tests
tasks.register<Exec>("pythonTest") {
    workingDir = projectDir
    commandLine("bash", "-c", """
        # Activate virtual environment and run tests
        source venv/bin/activate
        python -m pytest src/test/python
    """)
    dependsOn("installPythonDependencies", "generatePythonProto")
}

// Custom task to build Python package
tasks.register<Exec>("buildPythonPackage") {
    workingDir = projectDir
    commandLine("bash", "-c", """
        # Activate virtual environment and build package
        source venv/bin/activate
        python setup.py sdist bdist_wheel
    """)
    dependsOn("installPythonDependencies", "generatePythonProto")
}

// Custom task to run the Python service
tasks.register<Exec>("runPythonService") {
    workingDir = projectDir
    commandLine("bash", "-c", """
        # Activate virtual environment and run service
        source venv/bin/activate
        python src/main/python/semantic_chunker_service.py
    """)
    dependsOn("installPythonDependencies", "generatePythonProto")
}

// Make the build task depend on the Python build
tasks.named("build") {
    dependsOn("buildPythonPackage")
}

// Make the test task depend on the Python tests
tasks.named("test") {
    dependsOn("pythonTest")
}