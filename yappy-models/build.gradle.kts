plugins {
    `java-platform`
    `maven-publish`
}

group = rootProject.group
version = rootProject.version

javaPlatform {
    // allowDependencies()
}

dependencies {
    constraints {
        // Constrain own modules
        api("${rootProject.group}:pipeline-config-models:${rootProject.version}")
        api("${rootProject.group}:schema-registry-models:${rootProject.version}")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJavaPlatform") {
            from(components["javaPlatform"])
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()
            pom {
                name.set("Yappy Models BOM")
                description.set("Bill of Materials for Yappy Models components")
            }
        }
    }
}