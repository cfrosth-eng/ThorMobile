plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.thormobile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.thormobile"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // On-device wake word (openWakeWord models run through ONNX Runtime).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")

    // THOR MICRO-AGENT: Room Database for Local Outbox (Migrated to KSP)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation(libs.junit)
    // Desktop ONNX Runtime so WakeWordDetectorTest can run on the JVM against the Python reference.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.30.0")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}