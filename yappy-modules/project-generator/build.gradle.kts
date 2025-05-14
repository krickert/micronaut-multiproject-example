import org.apache.tools.ant.filters.ReplaceTokens
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

plugins {
    base // Provides tasks like 'clean'
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

    doLast {
        val templateDir = project.projectDir.resolve("template-files")
        val targetRootDir = project.projectDir.parentFile.resolve("modules")
        val newModuleDir = targetRootDir.resolve(moduleNameLowerHyphen)

        if (newModuleDir.exists()) {
            throw GradleException("Target directory $newModuleDir already exists.")
        }
        targetRootDir.mkdirs()

        // --- 1. Copy and Filter Files ---
        project.copy {
            from(templateDir)
            into(newModuleDir)
            exclude("build", ".gradle")

            // CORRECTED TOKEN REPLACEMENT:
            filter(ReplaceTokens::class) { filter ->
                filter.beginToken = "@@"
                filter.endToken = "@@"
                filter.tokens = mapOf(
                    "MODULE_NAME_PASCAL_CASE" to moduleNamePascalCase,
                    "MODULE_NAME_LOWER_HYPHEN" to moduleNameLowerHyphen,
                    "APPLICATION_NAME" to applicationName,
                    "BASE_PACKAGE" to basePackage,
                    "GROUP_ID" to groupId
                    // Add any other tokens you defined here, WITHOUT the @@
                )
            }
        }

        // --- 2. Rename Package Directories ---
        val originalTemplatePackage = "com/krickert/yappy/modules/echo" // Path format for template
        val newPackagePath = basePackage.replace('.', '/')

        listOf("src/main/java", "src/test/java").forEach { srcDir ->
            val oldPackageDirInNewModule = newModuleDir.resolve(srcDir).resolve(originalTemplatePackage)
            val targetPackageDirInNewModule = newModuleDir.resolve(srcDir).resolve(newPackagePath)

            if (oldPackageDirInNewModule.exists() && oldPackageDirInNewModule.isDirectory) {
                println("Renaming package directory from $oldPackageDirInNewModule to $targetPackageDirInNewModule")
                Files.createDirectories(targetPackageDirInNewModule.parentFile.toPath())
                Files.move(oldPackageDirInNewModule.toPath(), targetPackageDirInNewModule.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                println("Warning: Original template package directory not found at $oldPackageDirInNewModule for $srcDir. This might be okay if your template doesn't have this exact path.")
            }
        }

        // --- 3. Rename Specific Files ---
        val newJavaMainSrcPath = newModuleDir.resolve("src/main/java").resolve(newPackagePath)
        val newJavaTestSrcPath = newModuleDir.resolve("src/test/java").resolve(newPackagePath)

        // Rename Application.java (or EchoApplication.java)
        val templateAppFileNameInTemplate = "Application.java" // The name of the file in your `template-files`
        val originalAppFileInNewPath = newJavaMainSrcPath.resolve(templateAppFileNameInTemplate)
        val targetAppFileInNewPath = newJavaMainSrcPath.resolve("${moduleNamePascalCase}Application.java")

        if (originalAppFileInNewPath.exists()) {
            println("Renaming file $originalAppFileInNewPath to $targetAppFileInNewPath")
            Files.move(originalAppFileInNewPath.toPath(), targetAppFileInNewPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            // Fallback if your template file was named EchoApplication.java
            val altOriginalAppFile = newJavaMainSrcPath.resolve("EchoApplication.java")
            if (altOriginalAppFile.exists()){
                println("Renaming file $altOriginalAppFile to $targetAppFileInNewPath")
                Files.move(altOriginalAppFile.toPath(), targetAppFileInNewPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                println("Warning: Main application file '$templateAppFileNameInTemplate' or 'EchoApplication.java' not found in $newJavaMainSrcPath to rename.")
            }
        }

        // Rename other specific files
        mapOf(
            newJavaMainSrcPath.resolve("EchoService.java") to newJavaMainSrcPath.resolve("${moduleNamePascalCase}Service.java"),
            // Clients.java might not need module-specific renaming, or it might. Adjust as needed.
            // If Clients.java in template refers to EchoService, its content should have been tokenized.
            newJavaMainSrcPath.resolve("Clients.java") to newJavaMainSrcPath.resolve("Clients.java"),

            newJavaTestSrcPath.resolve("EchoApplicationTest.java") to newJavaTestSrcPath.resolve("${moduleNamePascalSpplicationTest.java"),
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