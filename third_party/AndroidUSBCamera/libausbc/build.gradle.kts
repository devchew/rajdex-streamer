plugins { id("com.android.library") }

android {
    namespace = "com.jiangdg.ausbc"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":ausbc-uvc"))
    compileOnly(files("libs/libnative-3.3.3-classes.jar", "libs/libutils-3.3.3-classes.jar"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.10.0")
    implementation("com.elvishew:xlog:1.11.0")
}
