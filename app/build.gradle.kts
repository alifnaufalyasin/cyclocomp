plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aliven.cyclocomp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aliven.cyclocomp"
        minSdk = 26
        targetSdk = 34
        // Release builds pass the version from the git tag (see RELEASE.md); other builds are "dev".
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.0.0-dev"
    }

    // Updates only install over the existing app if they are signed with the same key, so the release
    // key comes from the environment (GitHub Actions secrets) and is never in the repository.
    val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
    val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
        !System.getenv("RELEASE_STORE_PASSWORD").isNullOrBlank() &&
        !System.getenv("RELEASE_KEY_ALIAS").isNullOrBlank() &&
        !System.getenv("RELEASE_KEY_PASSWORD").isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Code shrinking is off for now: it has never been tried against osmdroid / ML Kit / Compose
            // here, and a broken shrink would only show up as a crash on the phone.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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
    }

    composeOptions {
        // Compose compiler 1.5.14 pairs with Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // OpenStreetMap map view, no API key; the same library the ESP32 companion app uses.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // On-device text recognition with the model bundled in the APK: works offline, no images leave the phone.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM ("not mocked"); the sync tests need the real one.
    testImplementation("org.json:json:20231013")
}
