<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Pawchive" width="120" />
</p>

<h1 align="center">Pawchive</h1>

<p align="center">
  <a href="README.en.md">English</a> | <a href="README.ja.md">日本語</a>
</p>

<p align="center">
  一款精致、流畅的第三方 Android 客户端，为你带来 <a href="https://pawchive.pw">Pawchive</a> 平台的完整体验。<br/>
  聚合 Patreon、Fanbox、Discord 等平台的创作者内容，支持浏览、搜索、收藏与沉浸式媒体播放。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/API-30%2B-34A853?style=for-the-badge&logo=android&logoColor=white" alt="Min API" />
  <img src="https://img.shields.io/badge/Target_API-36-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Target API" />
  <img src="https://img.shields.io/badge/Release-v1.6.6-blue?style=for-the-badge&logo=android" alt="Release" />
  <img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge" alt="License" />
</p>

<p align="center">
  <a href="https://github.com/FengByX/Pawchive/releases">
    <img src="https://img.shields.io/badge/下载-Releases-181717?style=for-the-badge&logo=github&logoColor=white" alt="Download" />
  </a>
  <a href="https://t.me/PawchiveX">
    <img src="https://img.shields.io/badge/Telegram-PawchiveX-229ED9?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram Channel" />
  </a>
</p>

---

## 功能亮点

### 内容浏览
- **首页帖子流**：分页加载最新内容，支持关键词筛选与多种排序方式
- **创作者主页**：查看创作者的帖子、公告、粉丝卡及关联账号
- **帖子详情**：完整正文、评论树、修订历史、文件下载
- **多平台支持**：聚合 Patreon、Fanbox、Discord 等平台内容，平台标签使用品牌色

### 精准搜索
- **关键词搜索**：同时检索帖子与创作者，Tab 切换查看结果
- **文件哈希反查**：通过文件哈希追踪素材出处，支持 Discord 帖子结果
- **搜索历史**：本地持久化搜索记录，支持单条删除与一键清空

### 沉浸式媒体
- **高清图片查看**：手势缩放、双击放大、自由拖拽
- **视频播放**：基于 Media3 ExoPlayer，Bilibili 风格控制器，支持倍速播放、全屏切换、断点续播
- **多域名回退**：缩略图 / 原图 / 下载域名三级自动降级

### 收藏与账号
- 多账号切换，独立收藏/历史/下载数据隔离
- 收藏帖离线归档（Room FTS4）：断网可检索与阅读收藏内容
- **账号云端收藏**：登录后同步收藏的帖子与创作者
- **本地离线收藏**：无需登录也可本地管理收藏

### 下载中心
- **okdownload 断点续传引擎**：网络中断后自动从断点恢复
- **进度通知**：通知栏实时展示下载进度，完成/失败状态反馈
- **后台持续下载**：应用切后台不中断下载
- **下载规则**：按创作者 / 服务 / 文件类型设置自动下载规则
- **历史管理**：支持取消、重试、清除下载记录

### 个性化设置
- **多语言**：中文 / English / 日本語，实时切换
- **外观模式**：日间 / 夜间 / 跟随系统，Material Design 3 主题
- **下载管理**：自定义下载目录（SAF），缓存查看与清理
- **内容更新订阅**：订阅创作者后周期检测新帖
- **应用内更新**：自动检查 GitHub Release，语义化版本比较

---

## 技术架构

| 类别 | 技术选型 | 说明 |
|------|---------|------|
| **语言** | Kotlin 2.2.10 | 现代化、空安全的 JVM 语言 |
| **最低 SDK** | API 30 (Android 11) | 覆盖 95%+ 活跃设备 |
| **目标 SDK** | API 36 | 最新 Android 版本 |
| **UI 框架** | XML + ViewBinding | 声明式布局，类型安全访问 |
| **设计语言** | Material Design 3 | 卡片分组、分段按钮、品牌色标签 |
| **模块化** | 多 Gradle 模块 | `app` / `feature-*` / `data` / `core` |
| **依赖注入** | Hilt + KSP | `@HiltAndroidApp` / `@AndroidEntryPoint` |
| **本地存储** | Room + DataStore | 下载历史 / 归档 Room，设置 DataStore |
| **下载引擎** | okdownload 1.0.7 | 断点续传、进度回调、OkHttp 集成 |
| **网络层** | Retrofit + OkHttp | 类型安全 HTTP 客户端 |
| **图片加载** | Coil 2.6 | Kotlin 优先、协程原生支持 |
| **视频播放** | AndroidX Media3 | ExoPlayer + OkHttp 数据源 |
| **构建工具** | Gradle 9.4.1 + AGP 9.2.1 | 版本目录管理依赖 |

---

## 核心技术亮点

### 1. okdownload 断点续传下载引擎
集成 [lingochamp/okdownload](https://github.com/lingochamp/okdownload) 作为下载核心：
- **断点续传**：网络中断后自动从断点恢复
- **协程驱动**：直接在 CoroutineScope 中执行下载
- **Cloudflare 凭据注入**：复用 `sharedOkHttpClient`，自动携带 cf_clearance / User-Agent

### 2. Cloudflare 托管挑战自动过盾
目标站点启用 Cloudflare 防护，纯 OkHttp 请求会被拦截返回 403。`CloudflareManager` 通过隐藏 WebView 执行 JS 挑战，提取 `cf_clearance` Cookie 并绑定 User-Agent，注入到后续所有 OkHttp 请求中。

### 3. 智能拦截器链
- **主域注入**：仅对 `pawchive.pw` 主域注入凭据，图片 CDN 子域不注入
- **非阻塞 403 兜底**：403 时自动强制刷新重试一次
- **双 OkHttpClient**：带 CF 拦截器的客户端与轻量级客户端分离

### 4. 单 Activity + 模块化导航
- 主界面 Tab Fragment 缓存复用，切换不重建
- **AppNavigator 接口**：各 feature 模块通过接口导航，模块间零直接依赖

### 5. Skeleton 骨架屏加载
自定义 `SkeletonHelper` 实现 shimmer 脉冲加载动画，内容加载完成后 200ms 淡入淡出过渡。

---

## 项目结构

```
Pawchive/
├── app/                          # 装配层：Application / MainActivity
├── feature-common/               # 共享 UI：SkeletonHelper / adapter
├── feature-home/                 # 首页
├── feature-search/               # 搜索
├── feature-post/                 # 帖子详情 / 图片查看 / 全屏视频
├── feature-downloads/            # 下载中心
├── feature-settings/             # 设置
├── feature-account/              # 账号 / 登录 / 收藏
├── data/                         # 业务层：Repository / Manager
├── core/                         # 基础设施：网络 / 模型 / Room
└── gradle/libs.versions.toml     # 版本目录
```

**依赖方向**：`:app` → `:feature-*` → `:data` → `:core`

---

## 快速开始

### 环境要求
- **Android Studio** Meerkat (2024.3+) 或更高
- **JDK** 17+
- **Gradle** 9.2+（项目内置 wrapper）

### 克隆 & 构建

```bash
git clone https://github.com/FengByX/Pawchive.git
cd Pawchive
./gradlew assembleRelease
```

> APK 输出路径：`app/build/outputs/apk/release/Pawchive-v1.6.6.apk`

### 安装

从 [Releases](https://github.com/FengByX/Pawchive/releases) 页面下载最新 APK，安装到 Android 11+ 设备即可。

---

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` | 网络请求 |
| `ACCESS_NETWORK_STATE` | 网络状态检测 |
| `POST_NOTIFICATIONS` | 下载进度通知（Android 13+） |
| `FOREGROUND_SERVICE` | 下载前台服务 |

---

## 贡献

欢迎提交 Issue 和 Pull Request。提交前请确保：
1. 代码风格与现有代码保持一致
2. 新增功能适配三种语言字符串资源
3. 遵循 Material Design 3 设计规范

---

## 开源协议

本项目基于 **MIT License** 开源。

---

<p align="center">
  <sub>图标资源来自 <a href="https://lucide.dev">Lucide</a> · 设计灵感来自 Material Design 3</sub>
</p>
