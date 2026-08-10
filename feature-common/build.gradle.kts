plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.pawchive.common"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
        // 跨模块版本号：与 app 共享（ARCH-002 阶段 3）
        buildConfigField(
            "String",
            "VERSION_NAME",
            "\"${project.findProperty("VERSION_NAME") ?: "1.5.0"}\""
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    lint {
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    // 模块拆分（ARCH-002）：feature-common 依赖 core + data，提供共享 UI 组件
    implementation(project(":core"))
    implementation(project(":data"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation(libs.androidx.swiperefreshlayout)

    // Coil - 图片加载
    implementation(libs.coil)

    // Media3 - VideoPlayerManager
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Hilt - 依赖注入（feature 模块 @AndroidEntryPoint Fragment）
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
}
