import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "expo.modules.t3reviewdiff"
    compileSdk = 37
    defaultConfig { minSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}

kotlin { jvmToolchain(25) }

ktlint {
    android.set(true)
    filter { exclude("**/T3ReviewDiffView.kt", "**/ReviewDiffCanvasDrawing.kt", "**/ReviewDiffCodeLayout.kt") }
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
            check(actual == expected) { "Vendored T3 diff artifact changed: $path" }
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyUpstreamArtifacts) }
