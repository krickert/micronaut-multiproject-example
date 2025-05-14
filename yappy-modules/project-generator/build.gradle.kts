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

    // Define properties for user input
    // These can be set via -P on the command line
    // e.g., ./gradlew generateNewModule -PmoduleNamePascalCase=CoolService -PmoduleNameLowerHyphen=cool-service ...

    val moduleNamePascalCase = project.findProperty("moduleNamePascalCase") ?: "MyModule"
    val moduleNameLowerHyphen = project.findProperty("moduleNameLowerHyphen") ?: "my-module"
    val applicationName = project.findProperty("applicationName") ?: "${moduleNamePascalCase}Application"
    // Construct the base package from a root and the lower hyphen name (common pattern)
    val basePackageRoot = project.findProperty("basePackageRoot") ?: "com.krickert.yappy.modules"
    val basePackage = project.findProperty("basePackage") ?: "$basePackageRoot.${moduleNameLowerHyphen.replace('-', '')}"
    val groupId = project.findProperty("groupId") ?: basePackageRoot // Or simply make it same as basePackage

    doLast {
        val templateDir = project.projectDir.resolve("template-files")
        // Assuming 'modules' directory is a sibling to 'project-generator'
        val targetRootDir = project.projectDir.parentFile.resolve("modules")
        val newModuleDir = targetRootDir.resolve(moduleNameLowerHyphen.toString())

        if (newModuleDir.exists()) {
            throw GradleException("Target directory $newModuleDir already exists.")
        }
        targetRootDir.mkdirs() // Ensure the 'modules' directory exists

        // --- 1. Copy and Filter Files ---
        project.copy {
            from(templateDir)
            into(newModuleDir)
            // Exclude build directories from the template if they were accidentally copied in
            exclude("build", ".gradle")

            // Replace tokens in specified file types
            // Note: This basic filter works on any file it's applied to.
            // You might want to be more specific with include patterns.
            filter(ReplaceTokens::class, "tokens" to mapOf(
                "@@MODULE_NAME_PASCAL_CASE@@" to moduleNamePascalCase,
                "@@MODULE_NAME_LOWER_HYPHEN@@" to moduleNameLowerHyphen,
                "@@APPLICATION_NAME@@" to applicationName,
                "@@BASE_PACKAGE@@" to basePackage,
                "@@GROUP_ID@@" to groupId
                // Add any other tokens you defined
            ))
        }

        // --- 2. Rename Package Directories ---
        // Example: com.krickert.yappy.modules.echo -> @@BASE_PACKAGE@@
        // Original package parts for 'echo' template
        val originalTemplatePackage = "com/krickert/yappy/modules/echo" // Path format
        val newPackagePath = basePackage.toString().replace('.', '/')

        listOf("src/main/java", "src/test/java").forEach { srcDir ->
            val oldPackageDirInNewModule = newModuleDir.resolve(srcDir).resolve(originalTemplatePackage)
            val targetPackageDirInNewModule = newModuleDir.resolve(srcDir).resolve(newPackagePath)

            if (oldPackageDirInNewModule.exists() && oldPackageDirInNewModule.isDirectory) {
                println("Renaming package directory from $oldPackageDirInNewModule to $targetPackageDirInNewModule")
                Files.createDirectories(targetPackageDirInNewModule.parentFile.toPath()) // Ensure parent path exists
                Files.move(oldPackageDirInNewModule.toPath(), targetPackageDirInNewModule.toPath(), StandardCopyOption.REPLACE_EXISTING)

                // Clean up empty parent directories if the package structure significantly changed
                // This part can be tricky and might need a more robust implementation
                // For now, let's assume the depth change isn't drastic enough to leave many empty parents
                // or that the originalTemplatePackage is the full unique part.
            } else {
                println("Warning: Original template package directory not found at $oldPackageDirInNewModule")
            }
        }

        // --- 3. Rename Specific Files ---
        // Example: EchoApplication.java -> @@MODULE_NAME_PASCAL_CASE@@Application.java
        // This needs to be done *after* directory renaming if files are in those dirs.
        // And it needs to find files within the *new* package structure.
        val newJavaSrcPath = newModuleDir.resolve("src/main/java").resolve(newPackagePath)
        val newTestSrcPath = newModuleDir.resolve("src/test/java").resolve(newPackagePath)

        mapOf(
            newJavaSrcPath.resolve("Application.java") to newJavaSrcPath.resolve("${moduleNamePascalCase}Application.java"),
            newJavaSrcPath.resolve("EchoService.java") to newJavaSrcPath.resolve("${moduleNamePascalCase}Service.java"),
            newJavaSrcPath.resolve("Clients.java") to newJavaSrcPath.resolve("Clients.java"), // Assuming Clients.java doesn't need rename based on module
            newTestSrcPath.resolve("EchoApplicationTest.java") to newTestSrcPath.resolve("${moduleNamePascalCase}ApplicationTest.java"),
            newTestSrcPath.resolve("EchoServiceTest.java") to newTestSrcPath.resolve("${moduleNamePascalCase}ServiceTest.java")
        ).forEach { (oldFile, newFile) ->
            if (oldFile.exists()) {
                println("Renaming file $oldFile to $newFile")
                Files.move(oldFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                // If initial file was e.g. EchoApplication.java instead of Application.java
                val altOldFile = oldFile.parentFile.resolve("EchoApplication.java") // Or similar logic
                if (altOldFile.exists() && oldFile.name.contains(moduleNamePascalCase.toString())) {
                    println("Renaming file $altOldFile to $newFile")
                    Files.move(altOldFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } else {
                    println("Warning: File to rename not found: $oldFile (and alternative Echo...java not applicable or not found)")
                }
            }
        }


        println("New module '$moduleNameLowerHyphen' generated in $newModuleDir")
        println("IMPORTANT: Review the generated project, especially package structures and filenames.")
        println("You might need to manually adjust the main Application class name if it wasn't automatically renamed from a generic 'Application.java'.")
    }
}