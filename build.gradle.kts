// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    // 代码覆盖率（ARCH-014 质量门禁）：根模块作为 merging module 聚合全仓覆盖率
    alias(libs.plugins.kover)
}

// 覆盖率合并（ARCH-014）：只聚合核心业务层 :core 与 :data。
// 注意：不要把 feature/app 模块加进来——Kover 0.9.9 的 :koverVerify 聚合合并
// 不能对全部附加模块稳定应用根级 includes 过滤器，feature/app 的类（零测试）
// 会按"未覆盖"泄漏进 verify 口径，导致门禁永远无法达到按报告口径校准的阈值
// （2026-09 实测：泄漏 1476 行 → 35.53%；剔除后核心层 46.5% ≥ 45 通过）。
// feature/app 模块仍各自应用 kover 插件，可独立生成报告查看。
dependencies {
    kover(project(":core"))
    kover(project(":data"))
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
                // 核心业务层（core + data）line 覆盖率下限——"只增不减"的棘轮：
                // 基准 = CI 全新检出实测 46.5%（合并报告 2224/4783 行）。
                // 注意：本地跑同一门禁可能报出偏低值（实测 44.1%~44.6%）：本地合并产物会残留
                // includes 过滤器本应剔除的包（如 com.pawchive.work，262 行，clean 后仍在），
                // 而 CI 全新检出不会。判定以 CI 为准，本地数字仅看趋势。
                // 阈值定在 45，任何使核心层覆盖率跌破该线的改动都会在 CI 被拦下。
                // 补充测试后可上调，禁止下调。修改此值须同步更新 CHANGELOG。
                minBound(45)
            }
        }
    }
}
