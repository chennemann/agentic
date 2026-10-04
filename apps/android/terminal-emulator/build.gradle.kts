import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// Kotlin sources carry our lifecycle fix. JNI binaries are unmodified, pinned upstream artifacts.
val upstreamNative by configurations.creating { isTransitive = false }
dependencies {
    upstreamNative(libs.connectbot.terminal)
    implementation(libs.androidx.core.ktx)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
}

val extractNative by tasks.registering(Sync::class) {
    inputs.files(upstreamNative)
    into(layout.buildDirectory.dir("upstream-native"))
    from({ zipTree(upstreamNative.singleFile) }) { include("jni/**") }
    doFirst {
        val digest = MessageDigest.getInstance("SHA-256").digest(upstreamNative.singleFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == "d795aa4ffe244e259c3f034820f6931ed4214637775f0ff465ab48a6367f116e") {
            "ConnectBot 0.3.10 native artifact checksum mismatch"
        }
    }
}

android {
    namespace = "org.connectbot.terminal"
    compileSdk = 37
    defaultConfig {
        minSdk = 36
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("upstream-native/jni").get().asFile)
}
kotlin { jvmToolchain(25) }
tasks.named("preBuild") { dependsOn(extractNative) }
