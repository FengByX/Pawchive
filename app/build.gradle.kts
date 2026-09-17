plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
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
        // 版本号单一来源：gradle.properties（VERSION_NAME / VERSION_CODE）。
        // P2-1：不再提供兜底默认值——属性缺失或非法时直接构建失败，
        // 避免静默产出低版本号（旧兜底为 55 / "1.5.5"）的构建产物。
        versionCode = requireNotNull(project.findProperty("VERSION_CODE")?.toString()?.toIntOrNull()) {
            "VERSION_CODE 缺失或非整数：版本号唯一来源为 gradle.properties"
        }
        versionName = requireNotNull(
            project.findProperty("VERSION_NAME")?.toString()?.takeIf { it.isNotBlank() }
        ) {
            "VERSION_NAME 缺失或为空：版本号唯一来源为 gradle.properties"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // PERF-009：启用 R8 代码缩减与资源压缩，减小 APK 体积、提升运行时性能
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
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
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("Pawchive-v${android.defaultConfig.versionName}.apk")
        }
    }
}

dependencies {
    // 模块拆分（ARCH-002）：:core 提供网络/错误/模型/存储/数据库基础设施
    implementation(project(":core"))
    // 模块拆分（ARCH-002 阶段 2）：:data 提供业务 Repository/Manager/Worker
    implementation(project(":data"))
    // 模块拆分（ARCH-002 阶段 3）：:feature-common 提供共享 UI + AppNavigator 接口
    implementation(project(":feature-common"))
    implementation(project(":feature-home"))
    implementation(project(":feature-search"))
    implementation(project(":feature-post"))
    implementation(project(":feature-downloads"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-account"))

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

    // Security - EncryptedSharedPreferences（1.1.0：最终稳定版，库已整体弃用，见 libs.versions.toml 说明）
    implementation(libs.androidx.security.crypto)

    // Jetpack DataStore - 高性能键值存储（替代 SharedPreferences）
    implementation(libs.androidx.datastore.preferences)

    // ViewPager2 - 主页面跟手滑动切换
    implementation(libs.androidx.viewpager2)

    // WorkManager - 视频下载前台任务+通知栏进度（P2 FRONTEND-006）
    implementation(libs.androidx.work.runtime.ktx)

    // Chrome Custom Tabs
    implementation(libs.androidx.browser)

    // Coil Image Loading
    implementation(libs.coil)

    // AndroidX Media3 (ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.cast)
    implementation(libs.androidx.media3.datasource.okhttp)

    // Hilt - 依赖注入（ARCH-003）
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Hilt + WorkManager 集成（@HiltWorker）
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Room - 下载历史存储迁移（ARCH-004）
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
