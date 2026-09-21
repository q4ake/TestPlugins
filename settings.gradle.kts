rootProject.name = "CloudstreamExtensions"

// Auto-include all subdirectories that have a build.gradle.kts
File(rootDir.absolutePath).listFiles()?.forEach { subDir ->
    if (subDir.isDirectory && File(subDir, "build.gradle.kts").exists()) {
        include(subDir.name)
    }
}
