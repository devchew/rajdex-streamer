plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.devchew.rajdex_streamer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.devchew.rajdex_streamer"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":ausbc"))
    implementation(files(
        "../third_party/AndroidUSBCamera/libnative-3.3.3.aar",
        "../third_party/AndroidUSBCamera/libutils-3.3.3.aar",
        "../third_party/AndroidUSBCamera/libuvccommon-3.3.3.aar"
    ))
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
