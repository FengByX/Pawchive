plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.pawchive.data"
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
        // WorkManager Worker.setForeground 的已知误报：前台服务类型由
        // WorkManager 内部 SystemForegroundService 声明（androidx.work 官方处理方式）
        disable += "SpecifyForegroundServiceType"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // 模块拆分（ARCH-002）：:data 依赖 :core 基础设施
    implementation(project(":core"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Retrofit - AuthRepository 直接解析 Response/ResponseBody
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging)

    // WorkManager - @HiltWorker 下载/缓存任务
    implementation(libs.androidx.work.runtime.ktx)

    // Chrome Custom Tabs - UpdateChecker 打开下载页
    implementation("androidx.browser:browser:1.8.0")

    // Jetpack DataStore - BookmarkManager/SearchHistoryManager/BlockedCreatorManager 等持久化
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Gson - BookmarkManager/DownloadHistoryManager legacy 数据迁移
    implementation("com.google.code.gson:gson:2.10.1")

    // Coil - AppCacheCleaner 清理图片缓存
    implementation(libs.coil)

    // okdownload - 断点续传下载引擎（替代手动 OkHttp 下载）
    implementation(libs.okdownload.core)
    implementation(libs.okdownload.okhttp)

    // Hilt - 依赖注入（ARCH-003）
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.room.testing)
}

