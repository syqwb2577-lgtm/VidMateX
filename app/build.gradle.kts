plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "app.vidmatex"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.vidmatex"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // Required by androidx.test (Compose ui-test-junit4, HiltAndroidRule,
        // WorkManagerTestInitHelper). Test-only config; no effect on release APK.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // ffmpeg-kit-full-gpl:8.1.9 AAR ships only 64-bit ABIs (arm64-v8a, x86_64);
        // restricting here prevents install/runtime failures on 32-bit devices.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = false
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":source:adapters"))
    implementation(project(":media:processor"))
    implementation(project(":feature:home"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:subtitles"))
    implementation(project(":feature:history"))
    implementation(project(":feature:settings"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.splashscreen)

    implementation(libs.coil.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    // Material Components provides Theme.Material3.DayNight.NoActionBar used as the
    // post-splash platform theme (Compose draws the real UI, this is a brief fallback).
    implementation("com.google.android.material:material:1.12.0")
    kapt(libs.hilt.compiler)

    // JVM-pure unit tests for player speed/delay/position/error/track policy helpers
    // (no android.* imports; see app/src/test/kotlin/app/vidmatex/player/).
    testImplementation(libs.junit)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    // Instrumented test infrastructure (Hilt DI init, WorkManager init, navigation).
    // Test-scoped only; no production-build impact. Versions pinned to match
    // libs.versions.toml (hilt 2.51.1, work 2.9.0, navigation 2.7.7, coroutines 1.8.1)
    // and core/database androidTest (androidx.test:core 1.6.1, runner 1.6.2).
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.51.1")
    kaptAndroidTest("com.google.dagger:hilt-android-compiler:2.51.1")
    androidTestImplementation("androidx.work:work-testing:2.9.0")
    androidTestImplementation("androidx.navigation:navigation-testing:2.7.7")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
