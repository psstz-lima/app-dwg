import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    // Kotlin itself comes from AGP 9 (built-in). The Compose compiler plugin stays explicit.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.appdwg.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.gromatic"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
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
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.appdwg:core")

    // Compose BOM that builds with AGP 9.0 and compileSdk 36.
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
    // Per-app locales on API 26+ (AppCompatDelegate.setApplicationLocales / LocaleManager).
    implementation("androidx.appcompat:appcompat:1.7.1")
    // Map tiles: downsample, memory cache and disk cache. Replaces BitmapFactory on the network path.
    // 3.3.0 is the newest Coil 3 built with Kotlin 2.2. Coil 3.4+ ships Kotlin 2.3/2.4 metadata,
    // which AGP 9's built-in Kotlin 2.2.10 cannot read.
    implementation("io.coil-kt.coil3:coil:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
}
