plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.arshanwar.tv_launcher"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.arshanwar.tv_launcher"
        minSdk = 26
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = false; checkReleaseBuilds = false }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")   // QR code for the Drop share link
    implementation("dev.mobile:dadb:1.2.9") {        // MCP: real remote-key presses through the TV's own ADB (works inside Netflix)
        exclude(group = "org.junit.platform"); exclude(group = "org.junit.jupiter"); exclude(group = "junit"); exclude(group = "org.opentest4j")
    }
}
