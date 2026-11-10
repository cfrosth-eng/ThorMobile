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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-")) {
            useVersion("2.2.10")
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Google Play Services Location
    implementation("com.google.android.gms:play-services-location:21.4.0")

    // On-device wake word (openWakeWord models run through ONNX Runtime).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")

    // On-device Gemma 4 E2B multimodal runtime (Audio + Text)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.0")

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


// AUTO-SYNC ANDROID CODEBASE TO THOR 3D VIKING CORE ON EVERY BUILD/RUN
tasks.register("syncVikingCoreSnapshot") {
    doLast {
        try {
            Runtime.getRuntime().exec(
                arrayOf("python", "C:\\Users\\craft\\Thor\\sync_viking_core_snapshot.py", "--push")
            )
        } catch (_: Exception) {}
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("syncVikingCoreSnapshot")
}
