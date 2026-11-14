plugins {
    id("com.android.library")
}

android {
    namespace = "me.wcy.lrcview"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 14 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
