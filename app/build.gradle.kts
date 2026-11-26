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

// Identifies the exact source revision installed on a tester's device.
// The human-facing release version stays independent of individual commits.
val sourceRevision = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
    }.standardOutput.asText.get().trim().ifEmpty { "local" }
}.getOrDefault("local")

android {
    namespace = "com.aicarcompanion.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aicarcompanion.app"
        // API 26 gives us java.time and a modern Bluetooth stack for future OBD-II work.
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.11.0"
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())
        buildConfigField("String", "SOURCE_REVISION", "\"$sourceRevision\"")
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

// Robolectric reads each bundled ~41 MB knowledge-pack asset into one heap array, and that array stays
// reachable through a finalizable Asset until the finalizer thread and a later GC release it. Measured:
// about 94 MB baseline plus one ~41 MB array per install that lingers, so the ~17 installs in
// OfflineKnowledgeTest need up to roughly 750 MB when the finalizer lags on a busy runner. Gradle's
// default 512 MB test heap left ~90 MB of headroom and failed 3 of 25 CI runs with OutOfMemoryError
// inside the install. OfflineKnowledgeTest asserts this setting so removing it fails loudly.
tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
}

ksp {
    // Room schema history is committed so future database migrations can be verified.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    constraints {
        implementation("com.google.guava:guava:33.7.2-android") {
            because("Earlier versions are affected by GHSA-xxph-c9ww-hj94")
        }
    }

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

    // VIN scanner: camera preview and frames, plus on-device text recognition (bundled model:
    // works offline and without Google Play services; nothing is sent anywhere).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.text.recognition)

    implementation(libs.androidx.room.runtime)
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
