# Project Generator Template Variables

This document describes the template variables used in the project generator. These variables are used to customize the generated project.

## Template Variables

| Variable                     | Description                                                                                    | Example                                                                        |
|------------------------------|------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------|
| @@MODULE_NAME_PASCAL_CASE@@  | The module name in PascalCase format. Used for class names, etc.                               | `Echo` → `NewModuleName`                                                       |
| @@MODULE_NAME_LOWER_HYPHEN@@ | The module name in lower-hyphen-case format. Used for directory names, artifact IDs in Gradle. | `echo` → `new-module-name`                                                     |
| @@APPLICATION_NAME@@         | The full application name, often derived from the module name.                                 | `EchoApplication` → `NewModuleNameApplication`                                 |
| @@BASE_PACKAGE@@             | The base package for the new module.                                                           | `com.krickert.yappy.modules.echo` → `com.krickert.yappy.modules.newmodulename` |
| @@GROUP_ID@@                 | The Gradle group ID.                                                                           | `com.krickert.yappy.modules.echo` → `com.krickert.yappy.modules.newmodulename` |

## Usage

These variables are used throughout the template files to customize the generated project. When generating a new project, these variables
will be replaced with the actual values provided by the user.

## Files Modified

The following files in the template-files directory use these variables:

- `settings.gradle.kts`: Project name
- `build.gradle.kts`: Group ID, main class, annotations
- `micronaut-cli.yml`: Default package
- `src/main/resources/application.yml`: Application name
- `src/main/java/com/krickert/yappy/modules/echo/Application.java`: Package declaration
- `src/main/java/com/krickert/yappy/modules/echo/EchoService.java`: Package declaration, class name, references
- `src/test/java/com/krickert/yappy/modules/echo/Clients.java`: Package declaration
- `src/test/java/com/krickert/yappy/modules/echo/EchoApplicationTest.java`: Package declaration, class name
- `src/test/java/com/krickert/yappy/modules/echo/EchoServiceTest.java`: Package declaration, class name, references
- `README.md`: Module name