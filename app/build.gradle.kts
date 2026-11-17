plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover) // test coverage report (informational; not a gate)
    alias(libs.plugins.ksp)
}

/**
 * Firebase is optional at build time: with app/google-services.json present (local file or
 * written by CI from a secret) the app can use Gemini via Firebase AI Logic; without it the
 * app builds and runs with Penelope's built-in answers only. The file is never committed.
 */
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
    // Crash reporting (Firebase Crashlytics) only when the Firebase project config is present.
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "com.aicarcompanion.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aicarcompanion.app"
        // API 26 gives us java.time and a modern Bluetooth stack for future OBD-II work.
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.7.0"
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())
    }

    buildTypes {
        debug {
            // Test builds read e.g. "v0.6.0-beta • Build 6" on the home and About screens.
            versionNameSuffix = "-beta"
            buildConfigField("boolean", "SHOW_BUILD_LABEL", "true")
        }
        release {
            buildConfigField("boolean", "SHOW_BUILD_LABEL", "false")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    lint {
        // Lint errors fail the build. Accessibility (e.g. missing content descriptions, small touch
        // targets), lifecycle, security and correctness checks all stay on at their default levels.
        abortOnError = true
        checkReleaseBuilds = true
        checkDependencies = true
        sarifReport = true
        // Version-update notices are handled by Dependabot pull requests instead of lint warnings.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Robolectric needs the merged resources and manifest for Compose UI tests.
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    // Room schema history is committed so future database migrations can be verified.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Material 3 stopped bundling the standard icons; the app uses a few from the core set.
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Gemini through Firebase AI Logic (no API key in the app), protected by App Check.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    implementation(libs.firebase.config)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug) // debug builds only
    implementation(libs.firebase.crashlytics) // crash reports; inactive without Firebase config

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// AGP 9 compiles Kotlin itself (no separate Kotlin Android plugin).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
