plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "com.example.objectdetectionapp"
    compileSdk = 36

    buildFeatures {
        // ensure BuildConfig is generated so BuildConfig.VISION_API_KEY exists
        buildConfig = true
        compose = true
    }

    defaultConfig {
        applicationId = "com.example.objectdetectionapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ✅ Read from gradle.properties (fallback to ENV)
        val visionApiKey: String =
            providers.gradleProperty("VISION_API_KEY").orNull
                ?: System.getenv("VISION_API_KEY")
                ?: ""

        buildConfigField("String", "VISION_API_KEY", "\"$visionApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "2.5.1"
    }

    // Proper packaging for native libs (TFLite / CameraX)
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // Universal APK for all ABIs
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
}

dependencies {
    // --- Core AndroidX ---
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    // --- Compose ---
    implementation(platform("androidx.compose:compose-bom:2023.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.10.0")

    // --- Testing ---
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // --- CameraX ---
    val cameraXVersion = "1.5.0"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // --- TensorFlow Lite ---
    val tfliteVersion = "2.12.0"
    val tfliteSupportVersion = "0.4.4"
    val tfliteTaskVisionVersion = "0.4.4"
    implementation("org.tensorflow:tensorflow-lite:$tfliteVersion")
    implementation("org.tensorflow:tensorflow-lite-support:$tfliteSupportVersion")
    implementation("org.tensorflow:tensorflow-lite-task-vision:$tfliteTaskVisionVersion")
    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:$tfliteVersion")

    // --- ML Kit (optional, local detection) ---
    implementation("com.google.mlkit:object-detection:17.0.1")
    implementation("com.google.mlkit:object-detection-custom:17.0.1")

    // --- ConstraintLayout ---
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // --- AppCompat ---
    implementation("androidx.appcompat:appcompat:1.6.1")

    // --- ✅ Cloud Vision API calls ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}
