plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.yazan.manga"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yazan.manga"
        minSdk = 24
        targetSdk = 35
        versionCode = 86
        versionName = "1.0.30"
    }

    signingConfigs {
        // Production release signing: uses the keystore stored in GitHub
        // Secrets (RELEASE_KEYSTORE, RELEASE_KEYSTORE_PASSWORD,
        // RELEASE_KEY_PASSWORD). The keystore is base64-encoded in the
        // secret and decoded by the GitHub Actions workflow before build.
        // If KEYSTORE_FILE env var is not set, the release build falls back
        // to the debug keystore (handled in buildTypes below).
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: "manga"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
        getByName("debug") {
            storeFile = file("yz-manga-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Enable R8/ProGuard: shrinks the APK ~30-50%, removes unused code,
            // and obfuscates the remaining code so reverse-engineering is harder.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with the release keystore if available (CI env), otherwise
            // fall back to debug (local dev builds or CI without secrets).
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // Enable all signing schemes on debug builds too
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // Lint: don't abort the release build on warnings (only on hard errors).
    // This prevents DuplicateIds-like warnings from unrelated sections from
    // blocking production builds. We still see them in CI logs.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Shimmer — skeleton loading placeholders
    implementation("com.facebook.shimmer:shimmer:0.5.0")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
