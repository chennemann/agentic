plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("org.openapi.generator") version "7.20.0"
}

kotlin {
    jvmToolchain(11)
    sourceSets {
        main {
            kotlin.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/kotlin"))
        }
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

openApiGenerate {
    inputSpec.set("$rootDir/third_party/opencode/packages/sdk/openapi.json")
    configFile.set("$rootDir/openapi/openapi-config.json")
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.path)
    globalProperties.set(
        mapOf(
            "modelDocs" to "false",
            "apiDocs" to "false",
            "modelTests" to "false",
            "apiTests" to "false"
        )
    )
}

val openApiFix by tasks.registering {
    dependsOn(tasks.named("openApiGenerate"))
    doLast {
        val dir = layout.buildDirectory.dir("generated/openapi/src/main/kotlin").get().asFile
        if (!dir.exists()) return@doLast
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        files.forEach { file ->
            var text = file.readText()
            text = text.replace(
                Regex("\\) : kotlin\\.collections\\.HashMap<([^>]+)>\\(\\)\\(\\)"),
                ") : kotlin.collections.HashMap<$1>()",
            )
            text = text.replace(
                "@SerialName(value = \"true\") `true`(\"true\")",
                "@SerialName(value = \"true\") `true`(true)",
            )
            text = text.replace(
                "@SerialName(value = \"false\") `false`(\"false\")",
                "@SerialName(value = \"false\") `false`(false)",
            )
            text = text.replace("java.io.File", "FileStatus")
            val mapReplacements = listOf(
                "kotlin.collections.Map<kotlin.String, kotlin.Any>" to
                    "kotlin.collections.Map<kotlin.String, kotlinx.serialization.json.JsonElement>",
                "kotlin.collections.HashMap<String, kotlin.Any>" to
                    "kotlin.collections.HashMap<String, kotlinx.serialization.json.JsonElement>",
            )
            mapReplacements.forEach { (from, to) ->
                text = text.replace(from, to)
            }
            if (
                file.extension == "kt" &&
                text.contains("kotlinx.serialization.json.JsonElement") &&
                !text.contains("import kotlinx.serialization.json.JsonElement")
            ) {
                text = text.replace(
                    Regex("package ([^\\n]+)\\n"),
                    "package $1\n\nimport kotlinx.serialization.json.JsonElement\n",
                )
            }
            if (
                file.extension == "kt" &&
                text.contains("FileStatus") &&
                !text.contains("import de.chennemann.opencode.mobile.api.models.FileStatus") &&
                file.parentFile.name == "apis"
            ) {
                text = text.replace(
                    Regex("package ([^\\n]+)\\n"),
                    "package $1\n\nimport de.chennemann.opencode.mobile.api.models.FileStatus\n",
                )
            }
            if (file.name == "java.io.File.kt") {
                val target = file.parentFile.resolve("FileStatus.kt")
                target.writeText(text)
                file.delete()
                return@forEach
            }
            file.writeText(text)
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(openApiFix)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
