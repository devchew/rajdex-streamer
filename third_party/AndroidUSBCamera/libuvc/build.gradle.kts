plugins { id("com.android.library") }

android {
    namespace = "com.jiangdg.uvccamera"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly(files("libs/libuvccommon-3.3.3-classes.jar", "../libausbc/libs/libutils-3.3.3-classes.jar"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.elvishew:xlog:1.11.0")
}
