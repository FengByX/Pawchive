plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.pawchive.feature.post"
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
}

dependencies {
    // 模块拆分（ARCH-002 阶段 3）：feature 依赖 core + data + feature-common
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":feature-common"))

    // Gson：解析 API 返回的 free-form embed 对象（在核心类 Post.kt 中用 Gson @SerializedName，
    // 这里显式依赖以避免 core 的 implementation 传递失效时本模块类型可见性问题）。
    implementation(libs.gson)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Coil - 图片加载
    implementation(libs.coil)

    // Media3 - 视频播放（PlayerView/ExoPlayer/UnstableApi）
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    // SwipeRefreshLayout - 详情页下拉刷新
    implementation(libs.androidx.swiperefreshlayout)

    // Hilt - 依赖注入
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
}
