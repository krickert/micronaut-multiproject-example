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

## Example Usage

To generate a new module, run the following command from the root of the project:

```bash
# Navigate to the project-generator directory
cd yappy-modules/project-generator

# Run the generator with a custom module name
./gradlew generateNewModule -PmoduleNamePascalCase=MyNewModule -PmoduleNameLowerHyphen=my-new-module
```

### Available Parameters

| Parameter              | Description                                                | Default Value                                      |
|------------------------|------------------------------------------------------------|---------------------------------------------------|
| moduleNamePascalCase   | The module name in PascalCase format                       | "MyModule"                                         |
| moduleNameLowerHyphen  | The module name in lower-hyphen-case format                | "my-module"                                        |
| applicationName        | The full application name                                  | "${moduleNamePascalCase}Application"               |
| basePackageRoot        | The base package root                                      | "com.krickert.yappy.modules"                       |
| basePackage            | The full base package                                      | "${basePackageRoot}.${moduleNameLowerHyphen.replace("-", "")}" |
| groupId                | The Gradle group ID                                        | basePackageRoot                                    |
| micronautVersionProp   | The Micronaut version                                      | "4.8.2"                                            |

### Example with All Parameters

```bash
./gradlew generateNewModule \
  -PmoduleNamePascalCase=MyNewModule \
  -PmoduleNameLowerHyphen=my-new-module \
  -PapplicationName=MyNewModuleApplication \
  -PbasePackageRoot=com.krickert.yappy.modules \
  -PbasePackage=com.krickert.yappy.modules.mynewmodule \
  -PgroupId=com.krickert.yappy.modules \
  -PmicronautVersionProp=4.8.2
```

### After Generation

After generating a new module, you'll need to manually add it to the parent project's `settings.gradle.kts` file:

```kotlin
// In the root settings.gradle.kts
include(":yappy-modules:my-new-module")
```

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
