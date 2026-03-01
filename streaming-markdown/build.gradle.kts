plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    id("maven-publish")
}

group = findProperty("streamingMarkdownGroup") as String? ?: "de.chennemann.agentic"
version = findProperty("streamingMarkdownVersion") as String? ?: "0.1.0-SNAPSHOT"

android {
    namespace = "de.chennemann.agentic.streamingmarkdown"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        compose = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

ktlint {
    android.set(true)
    outputToConsole.set(true)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "streaming-markdown"
            version = project.version.toString()
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name.set("Streaming Markdown")
                description.set("Compose-specific streaming markdown rendering")
            }
        }
    }
}
