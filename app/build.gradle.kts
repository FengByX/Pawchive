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
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    lint {
        disable += "UnsafeOptInUsageError"
    }
}

androidComponents {
    beforeVariants { builder ->
        if (builder.buildType == "debug") {
            builder.enable = false
        }
    }
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("Pawchive-v${android.defaultConfig.versionName}.apk")
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    
    // KTX & Lifecycle ViewModel
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    // Retrofit & Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging)

    // Security - EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coil Image Loading
    implementation(libs.coil)

    // AndroidX Media3 (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-cast:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}