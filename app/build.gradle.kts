plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.pawchive"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.pawchive"
        minSdk = 30
        targetSdk = 36
        versionCode = 36
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
