plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.sqldelight)
}

val appVersionName = (findProperty("appVersionName") as String?) ?: "0.0.0"
val appVersionCode = ((findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1).coerceAtLeast(1)
val updateRepoOwner = (findProperty("updateRepoOwner") as String?) ?: "chennemann"
val updateRepoName = (findProperty("updateRepoName") as String?) ?: "agentic"
val signingKeystorePath = System.getenv("ANDROID_SIGNING_KEYSTORE_PATH")
val signingKeystorePassword = System.getenv("ANDROID_SIGNING_KEYSTORE_PASSWORD")
val signingKeyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    signingKeystorePath,
    signingKeystorePassword,
    signingKeyAlias,
    signingKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "de.chennemann.opencode.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.chennemann.opencode.mobile"
        minSdk = 36
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_REPO_OWNER", "\"$updateRepoOwner\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"$updateRepoName\"")
    }

    testBuildType = "uitest"

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(checkNotNull(signingKeystorePath))
                storePassword = checkNotNull(signingKeystorePassword)
                keyAlias = checkNotNull(signingKeyAlias)
                keyPassword = checkNotNull(signingKeyPassword)
            }
        }
    }

    buildTypes {
        create("uitest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".uitest"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(project(":api"))
    implementation(project(":streaming-markdown"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kmp.app.updater.core)
    implementation(libs.kmp.app.updater.compose.ui)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.coroutines.extensions)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.sqldelight.sqlite.driver)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

ktlint {
    android.set(true)
    outputToConsole.set(true)
}

sqldelight {
    databases {
        create("AgenticDb") {
            packageName.set("de.chennemann.opencode.mobile.db")
            verifyMigrations = false
            generateAsync.set(true)
            deriveSchemaFromMigrations.set(true)
        }
    }
}

tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    enabled = false
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.register("testDebugUnitTest") {
    dependsOn("testUitestUnitTest")
}

tasks.register("connectedDebugAndroidTest") {
    dependsOn("connectedUitestAndroidTest")
}
