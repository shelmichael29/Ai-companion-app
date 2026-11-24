// Experimental BMW ENET Connection Tester: a separate app (own application ID) so it installs
// alongside Penelope without touching her code, data or permissions.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Build identifier shown in the app: the short commit it was built from, set automatically.
val buildId: String = providers.exec {
    commandLine("git", "rev-parse", "--short=7", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.getOrElse("").ifBlank { "local" }

android {
    namespace = "com.aicarcompanion.enettester"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aicarcompanion.enettester"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0-experimental"
        buildConfigField("String", "BUILD_ID", "\"$buildId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":enet-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}
