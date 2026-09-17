# 第三方组件与许可声明

本文件汇总本项目使用的第三方资源及其许可。**本文件仅为汇总便利，不替代各组件自身的 LICENSE 原文**；如遇不一致，以各组件的官方声明为准。

## 本项目

Pawchive 以 **MIT License** 发布，见 [LICENSE](LICENSE)。Copyright (c) 2024-2026 FengByX。

## 静置资源（图标）

| 资源 | 来源 | 许可 |
|------|------|------|
| UI 图标（`ic_*.xml` 矢量图） | [Lucide](https://lucide.dev) | **ISC License** |

> Lucide 官方 README 声明："Lucide is totally free for commercial use and personal use, this software is licensed under the ISC License."（来源：https://github.com/lucide-icons/lucide）
>
> 本项目将 Lucide 图标以其路径数据复刻为 Android Vector Drawable，未直接分发其 SVG 文件。

## 主要运行时依赖

以下为主要直接依赖。AndroidX / Kotlin 生态组件通常均以 **Apache License 2.0** 发布；
具体以各组件的官方仓库或 Maven POM 声明为准。

| 组件 | 用途 | 许可（依官方声明） |
|------|------|------------------|
| Kotlin Standard Library | 语言运行时 | Apache-2.0 |
| AndroidX（core-ktx、appcompat、activity、fragment、lifecycle、work、room、datastore、viewpager2、browser、security-crypto、media3、swiperefreshlayout） | 基础框架 | Apache-2.0 |
| Material Components for Android | Material Design 3 组件 | Apache-2.0 |
| Retrofit / OkHttp / logging-interceptor / mockwebserver | 网络层 | Apache-2.0 |
| Gson | JSON 序列化 | Apache-2.0 |
| Coil | 图片加载 | Apache-2.0 |
| Hilt / Dagger | 依赖注入 | Apache-2.0 |
| Tink（经 security-crypto 传递引入） | 加密原语 | Apache-2.0 |

> `androidx.security:security-crypto` 全部 API 已在 1.1.0 中弃用，官方不再发布新版本。
> 本项目正在评估迁移至平台 API / Android Keystore 直用。

## 已移除的依赖

| 组件 | 移除版本 | 移除原因 |
|------|---------|---------|
| okdownload (`com.liulishuo.okdownload`) | 随 1.7.0 移除 | 同步线程阻塞无超时导致下载永久卡死；多块下载与文件服务器限流要求冲突 |

## 待跟进

- 尚未引入自动化许可证审计（如 `com.jaredsburrows:gradle-license-plugin`），
  上表依赖许可为依官方声明的手工汇总。后续计划在 CI 中生成完整依赖许可证报告，
  以消除人工汇总可能的遗漏。
- 若你发现本文件存在遗漏或错误，欢迎提交 Issue 或 PR。
