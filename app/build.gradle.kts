plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.elena.thermaltest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.elena.thermaltest"
        minSdk = 29
        targetSdk = 34
        versionCode = 12
        versionName = "3.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
