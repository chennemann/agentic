import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "expo.modules.t3terminal"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 36
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake { cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror") }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}

kotlin { jvmToolchain(25) }

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}

// Keep upstream formatting so future patches remain directly comparable.
ktlint {
    android.set(true)
    filter {
        exclude("**/GhosttyBridge.kt", "**/TerminalFrame.kt", "**/TerminalCanvasView.kt", "**/T3TerminalView.kt")
    }
}

val verifyUpstreamArtifacts by tasks.registering {
    val manifest = file("upstream.sha256")
    val entries = manifest.readLines().filter(String::isNotBlank).map { it.split("  ", limit = 2) }
    inputs.file(manifest)
    inputs.files(entries.map { file(it[1]) })
    doLast {
        entries.forEach { (expected, path) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(file(path).readBytes())
                .joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Vendored T3 terminal artifact changed: $path" }
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyUpstreamArtifacts) }
