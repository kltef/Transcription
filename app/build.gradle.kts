plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kltef.voicekeyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kltef.voicekeyboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.1"

        // Native engines (whisper.cpp via CMake) only ship for these ABIs.
        // arm64-v8a covers essentially all modern phones; armeabi-v7a is for older 32-bit devices.
        // Pass -PslimAbi to build an arm64-v8a-only APK (~26 MB smaller, no 32-bit libs).
        ndk {
            if (project.hasProperty("slimAbi")) {
                abiFilters += "arm64-v8a"
            } else {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }

        externalNativeBuild {
            cmake {
                // Build whisper.cpp + our JNI shim as a shared lib.
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The sherpa-onnx prebuilt .so files live in jniLibs.
    // We don't want Gradle to strip them or complain about missing build ids.
    packaging {
        jniLibs {
            useLegacyPackaging = false
            // keepDebugSymbols += "**/*.so"
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
        viewBinding = true
    }
}

dependencies {
    // sherpa-onnx streaming ASR: prebuilt AAR (JNI .so for all ABIs + Kotlin API).
    // Fetched by scripts/fetch-native.sh; not committed to git.
    implementation(files("libs/sherpa-onnx-1.13.3.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
