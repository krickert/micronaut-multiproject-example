// build.gradle.kts (Root project)
plugins {
    // Apply base plugin for common tasks like 'clean' available in all projects
    base
}

// Define project-wide properties if needed, e.g., group or version
// Often, version is managed in gradle.properties or by a release plugin
group = "com.yourcompany.pipeline"
version = "1.0.0-SNAPSHOT"

// Common configurations can be placed here, but often better handled
// via convention plugins or within specific subprojects.