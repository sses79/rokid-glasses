plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.tim.gesturelab"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tim.gesturelab"
        // Rokid Glasses run Android 12; their bare-metal guide sets minSdk 31.
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }

    // The model must stay uncompressed so MediaPipe can memory-map it.
    androidResources { noCompress += "task" }
}

dependencies {
    val camerax = "1.5.3"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; replay tests need the real one.
    testImplementation("org.json:json:20240303")
}

// Replay tests print a per-clip report; show it in the build output.
tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true }
}
