// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    // 代码覆盖率（ARCH-014 质量门禁）：根模块作为 merging module 聚合全仓覆盖率
    alias(libs.plugins.kover)
}

// 覆盖率合并（ARCH-014）：收集所有模块的类与测试数据，根项目生成聚合报告
dependencies {
    kover(project(":core"))
    kover(project(":data"))
    kover(project(":feature-common"))
    kover(project(":feature-home"))
    kover(project(":feature-search"))
    kover(project(":feature-post"))
    kover(project(":feature-downloads"))
    kover(project(":feature-settings"))
    kover(project(":feature-account"))
    kover(project(":app"))
}

kover {
    reports {
        filters {
            includes {
                // 聚合报告/门禁聚焦核心业务层（core + data）：
                // UI 层（feature/app）测试价值低且覆盖率低，由各模块独立报告查看。
                packages("com.pawchive.core", "com.pawchive.data")
            }
            excludes {
                // 排除 DI/编译期生成代码，只统计手写业务代码
                classes(
                    "**/*_Factory*",
                    "**/*_HiltModules*",
                    "**/*_AssistedFactory*",
                    "**/*_GeneratedInjector*",
                    "**/Hilt_*",
                    "**/*_Impl*",
                    "**/BuildConfig*",
                    "**/R\$*",
                )
            }
        }
        verify {
            rule {
                // 核心业务层 line 覆盖率下限（当前实测 ≈18.5%，随测试补充逐步提高）
                minBound(18)
            }
        }
    }
}
