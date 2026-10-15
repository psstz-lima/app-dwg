import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.appdwg.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.gromatic"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        // Native debug symbols travel inside the AAB (Play Console "native code without debug symbols").
        ndk {
            debugSymbolLevel = "FULL"
        }
    }

    buildTypes {
        release {
            // R8 shrinks, optimizes and obfuscates. Signing stays with the upload key (not in the repository).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.appdwg:core")

    // Newest Compose BOM that still builds with AGP 8.9 and compileSdk 36.
    // 2026.08+ (Compose 1.12) requires AGP 9.1 and compileSdk 37.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // 1.13 is the newest activity-compose that still compiles against SDK 36.
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    // Play Console flags older androidx.fragment pulled in transitively. 1.9.1 is the current stable
    // and still builds with this AGP (it asks only for compileSdk 34).
    implementation("androidx.fragment:fragment:1.9.1")
    constraints {
        implementation("androidx.fragment:fragment:1.9.1") {
            because("Play Console reports older androidx.fragment:fragment as an outdated SDK")
        }
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.android.billingclient:billing:9.1.0")
}
