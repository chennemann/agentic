import java.io.ByteArrayOutputStream

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
        val tagsOutput = ByteArrayOutputStream()
        project.exec {
            commandLine("git", "tag", "--list", "v*")
            standardOutput = tagsOutput
        }

        val baselineMajors = tagsOutput
            .toString()
            .lineSequence()
            .map(String::trim)
            .filter { it.matches(Regex("""^v\d+$""")) }
            .map { it.removePrefix("v").toInt() }
            .toList()

        val nextMajor = (baselineMajors.maxOrNull() ?: 0) + 1
        val tagName = "v$nextMajor"

        val existingTag = ByteArrayOutputStream()
        project.exec {
            commandLine("git", "tag", "--list", tagName)
            standardOutput = existingTag
        }

        if (existingTag.toString().trim() == tagName) {
            throw GradleException("Tag $tagName already exists.")
        }

        project.exec {
            commandLine("git", "tag", "-a", tagName, "-m", "Baseline $tagName")
        }

        logger.lifecycle("Created baseline tag $tagName at HEAD.")
        logger.lifecycle("Push it manually with: git push origin $tagName")
    }
}
