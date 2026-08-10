plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.pawchive.core"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        disable += "UnsafeOptInUsageError"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Retrofit & Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging)

    // Security - EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0")

    // Jetpack DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Room - 下载历史存储（ARCH-004）
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // Hilt - 依赖注入（ARCH-003）
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines（CloudflareManager 使用 Dispatchers.Main）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.room.testing)
}
