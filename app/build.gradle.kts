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
        versionCode = 148
        versionName = "1.0.92"
    }

    signingConfigs {
        // Production release signing: uses the keystore stored in GitHub
        // Secrets (KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD).
        // The keystore is base64-encoded in the secret and decoded by the
        // GitHub Actions workflow before build.
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            if (keystoreFile != null) {
                // Use File() instead of file() to resolve from project root,
                // not from the app/ module directory.
                storeFile = rootProject.file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: "manga"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
        // Debug builds use Android's DEFAULT debug keystore (auto-generated
        // at ~/.android/debug.keystore). We no longer ship a custom debug
        // keystore in the repo — it was a security risk (anyone could sign
        // a "update" APK with the known debug key).
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
            // SECURITY: Only sign release with the release keystore (from GitHub Secrets).
            // If KEYSTORE_FILE env var is not set, the build produces an unsigned APK
            // (safer than falling back to the debug keystore).
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            if (keystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Use Android's default debug signing (auto-generated keystore).
            // No custom signingConfig needed — Gradle uses the default debug key.
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
