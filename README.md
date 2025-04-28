# Taming Dependencies: A Step-by-Step Guide to Building a Custom Micronaut BOM with Gradle Kotlin DSL

This repository generates the documentation to create this project.  The link below is a step-by-step guild to setting up a 
multi-project build for micronaut applications.

To make it more hands-on, this will be an actual open source application we're building: a search engine indexer powered by kafka.

**What We're Building:**

Imagine a system designed for processing data pipelines. This system consists of several parts:

* **Shared Libraries:** A core library containing the main pipeline logic (`pipeline-service-core`), data models defined using Protocol Buffers (`protobuf-models`), and common helper functions (`util`).
* **Testing Utilities:** A dedicated library (`pipeline-service-test-utils`) to assist in testing the pipeline components.
* **Microservices:** Specific implementations of pipelines as runnable Micronaut applications (e.g., `pipeline-instance-A`).

**The Goal:**

Our goal is to manage this system effectively within a single repository (monorepo) using Gradle. We'll focus on:

1.  **Centralized Dependency Management:** Creating a custom Bill of Materials (BOM) and using Gradle's version catalog (`libs.versions.toml`) to ensure all modules use consistent library versions.
2.  **Consistent Build Environment:** Using Gradle Kotlin DSL and configuring for JDK 21.
3.  **Modular Structure:** Defining clear dependencies between the different project modules.
4.  **Efficient CI/CD:** Discussing strategies to build and deploy only the parts of the system that have changed.

Let's begin!

The tutorial can me found [here](docs/tutorial.html)

## Development Environment

A Docker-based development environment is available in the `docker-dev` directory. This environment includes:

- **Kafka** (in Kraft mode) - Message broker
- **Apicurio Registry** - Schema registry
- **Solr** (in cloud mode) - Search platform
- **Kafka UI** - Web UI for Kafka management

To start the development environment:

```bash
cd docker-dev
docker-compose up -d
```

For more details, see the [docker-dev README](docker-dev/README.md).
