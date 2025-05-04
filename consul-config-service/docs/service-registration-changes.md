# Service Registration Changes

## Overview

This document describes the changes made to the service registration process in the consul-config-service module. The changes were made to decouple service registration from pipeline configuration, allowing services to run without being registered to a pipeline.

## Background

Previously, the service registration process assumed that a service must be registered with a pipeline to run. This was implemented in the `ServiceRegistrationManager` class, which would register a service with a pipeline when the service started up. If no pipeline was configured, the service registration would be skipped.

However, this approach did not align with the actual architecture of the system, where pipelines are dynamically built and a service does not need to have pipeline configuration to run.

## Changes Made

The following changes were made to decouple service registration from pipeline configuration:

1. Updated the documentation for the `ServiceRegistrationManager` class to reflect the new concept that a service does not need to have pipeline configuration to run.

2. Modified the `onApplicationEvent` method in `ServiceRegistrationManager` to allow services to run without being registered to a pipeline. If no pipeline name is configured, the service will still run with its default configuration instead of skipping registration entirely.

3. Added a new test case to `ServiceRegistrationManagerTest` that verifies a service can run without pipeline configuration.

## Impact

These changes allow services to run without being registered to a pipeline, which aligns with the architecture where pipelines are dynamically built. Services now have their config built into them with default config properties, and they can have custom config added that is validated by a schema.

This decoupling makes the system more flexible and easier to understand, as it more accurately reflects the actual architecture of the system.

## Testing

The changes were tested by adding a new test case to `ServiceRegistrationManagerTest` that verifies a service can run without pipeline configuration. The test sets the pipeline.name property to an empty string and then triggers service registration. It verifies that the service doesn't throw an exception and doesn't get registered to any pipeline.

All tests pass, confirming that the changes work correctly.