import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "9.3.2"
    id("com.android.library") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

android {
    namespace = "com.qiuyuxc.tingyu"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId =
            providers.gradleProperty("applicationId").getOrElse("com.qiuyuxc.tingyu.opensource")
        minSdk = 24
        targetSdk = 35
        versionCode = 26
        versionName = "0.12.9"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("src/main/kotlin"))
            res.setSrcDirs(listOf("res"))
        }
        getByName("test") {
            java.setSrcDirs(listOf("src/test/kotlin", "src/test/java"))
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin.compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging.resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
}

dependencies {
    implementation(project(":vendor:lrcview"))
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.animation:animation:1.12.0")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.12.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")
    debugImplementation("androidx.compose.ui:ui-tooling:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
