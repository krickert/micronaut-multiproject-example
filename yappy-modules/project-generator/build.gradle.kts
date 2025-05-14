import org.apache.tools.ant.filters.ReplaceTokens
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    base
}

tasks.register("generateNewModule") {
    description = "Generates a new module from the template."
    group = "generation"

    val moduleNamePascalCase = (project.findProperty("moduleNamePascalCase") ?: "MyModule").toString()
    val moduleNameLowerHyphen = (project.findProperty("moduleNameLowerHyphen") ?: "my-module").toString()
    val applicationName = (project.findProperty("applicationName") ?: "${moduleNamePascalCase}Application").toString()
    val basePackageRoot = (project.findProperty("basePackageRoot") ?: "com.krickert.yappy.modules").toString()
    val basePackage = project.findProperty("basePackage")?.toString() ?: "$basePackageRoot.${moduleNameLowerHyphen.replace("-", "")}"
    val groupId = (project.findProperty("groupId") ?: basePackageRoot).toString()
    val micronautVersion = (project.findProperty("micronautVersionProp") ?: "4.8.2").toString()

    doLast {
        val templateDir = project.projectDir.resolve("template-files")
        val targetRootDir = project.projectDir.parentFile
        val newModuleDir = targetRootDir.resolve(moduleNameLowerHyphen) // Use the String version

        if (newModuleDir.exists()) {
            throw GradleException("Target directory $newModuleDir already exists.")
        }
        targetRootDir.mkdirs()

        println("Generating new module in: $newModuleDir") // Added for clarity

        project.copy {
            from(templateDir)
            into(newModuleDir)
            exclude("build", ".gradle")

            // CORRECTED TOKEN REPLACEMENT SYNTAX (using filter(Map, Class) overload):
            filter(
                mapOf(
                    "beginToken" to "@@",
                    "endToken" to "@@",
                    "tokens" to mapOf(
                        "MODULE_NAME_PASCAL_CASE" to moduleNamePascalCase,
                        "MODULE_NAME_LOWER_HYPHEN" to moduleNameLowerHyphen,
                        "APPLICATION_NAME" to applicationName,
                        "BASE_PACKAGE" to basePackage,
                        "MICRONAUT_VERSION" to micronautVersion,
                        "GROUP_ID" to groupId
                    )
                ),
                ReplaceTokens::class.java
            )
        }

        val originalTemplatePackage = "com/krickert/yappy/modules/echo"
        val newPackagePath = basePackage.replace('.', '/')

        listOf("src/main/java", "src/test/java").forEach { srcDir ->
            val oldPackageDirInNewModule = newModuleDir.resolve(srcDir).resolve(originalTemplatePackage)
            val targetPackageDirInNewModule = newModuleDir.resolve(srcDir).resolve(newPackagePath)

            if (oldPackageDirInNewModule.exists() && oldPackageDirInNewModule.isDirectory) {
                println("Renaming package directory from $oldPackageDirInNewModule to $targetPackageDirInNewModule")
                Files.createDirectories(targetPackageDirInNewModule.parentFile.toPath())
                Files.move(oldPackageDirInNewModule.toPath(), targetPackageDirInNewModule.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                println("Warning: Original template package directory not found at $oldPackageDirInNewModule for $srcDir.")
            }
        }

        val newJavaMainSrcPath = newModuleDir.resolve("src/main/java").resolve(newPackagePath)
        val newJavaTestSrcPath = newModuleDir.resolve("src/test/java").resolve(newPackagePath)

        val templateAppFileNameInTemplate = "Application.java"
        val originalAppFileInNewPath = newJavaMainSrcPath.resolve(templateAppFileNameInTemplate)
        val targetAppFileInNewPath = newJavaMainSrcPath.resolve("${moduleNamePascalCase}Application.java")

        if (originalAppFileInNewPath.exists()) {
            println("Renaming file $originalAppFileInNewPath to $targetAppFileInNewPath")
            Files.move(originalAppFileInNewPath.toPath(), targetAppFileInNewPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            val altOriginalAppFile = newJavaMainSrcPath.resolve("EchoApplication.java")
            if (altOriginalAppFile.exists()){
                println("Renaming file $altOriginalAppFile to $targetAppFileInNewPath")
                Files.move(altOriginalAppFile.toPath(), targetAppFileInNewPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                println("Warning: Main application file '$templateAppFileNameInTemplate' or 'EchoApplication.java' not found in $newJavaMainSrcPath to rename.")
            }
        }

        mapOf(
            newJavaMainSrcPath.resolve("EchoService.java") to newJavaMainSrcPath.resolve("${moduleNamePascalCase}Service.java"),
            newJavaMainSrcPath.resolve("Clients.java") to newJavaMainSrcPath.resolve("Clients.java"),
            newJavaTestSrcPath.resolve("EchoApplicationTest.java") to newJavaTestSrcPath.resolve("${moduleNamePascalCase}ApplicationTest.java"),
            newJavaTestSrcPath.resolve("EchoServiceTest.java") to newJavaTestSrcPath.resolve("${moduleNamePascalCase}ServiceTest.java")
        ).forEach { (sourceFileInNewPath, targetFileInNewPath) ->
            if (sourceFileInNewPath.exists()) {
                Files.createDirectories(targetFileInNewPath.parentFile.toPath())
                println("Renaming file $sourceFileInNewPath to $targetFileInNewPath")
                Files.move(sourceFileInNewPath.toPath(), targetFileInNewPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                println("Warning: File to rename not found: $sourceFileInNewPath")
            }
        }

        println("New module '$moduleNameLowerHyphen' generated in $newModuleDir")
        println("IMPORTANT: Review the generated project, especially file contents for token replacement.")
    }
}