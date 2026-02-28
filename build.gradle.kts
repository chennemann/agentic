// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.sqldelight) apply false
}

tasks.register("createBaselineTag") {
    group = "release"
    description = "Creates the next manual baseline tag (v<major>) at current HEAD."

    doLast {
        fun runCommand(vararg args: String): String {
            val process = ProcessBuilder(*args)
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException(
                    "Command `${args.joinToString(" ")}` failed with exit code $exitCode:\n$output"
                )
            }
            return output.trim()
        }

        val tagsOutput = runCommand("git", "tag", "--list", "v*")
        val baselineMajors = tagsOutput
            .lineSequence()
            .map(String::trim)
            .filter { it.matches(Regex("""^v\d+$""")) }
            .map { it.removePrefix("v").toInt() }
            .toList()

        val nextMajor = (baselineMajors.maxOrNull() ?: 0) + 1
        val tagName = "v$nextMajor"

        val existingTagOutput = runCommand("git", "tag", "--list", tagName)
        val tagExists = existingTagOutput.lineSequence().map(String::trim).any { it == tagName }
        if (tagExists) {
            throw GradleException("Tag $tagName already exists.")
        }

        runCommand("git", "tag", "-a", tagName, "-m", "Baseline $tagName")
        logger.lifecycle("Created baseline tag $tagName at HEAD.")
        logger.lifecycle("Push it manually with: git push origin $tagName")
    }
}
