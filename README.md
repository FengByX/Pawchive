<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Pawchive" width="120" />
</p>

<h1 align="center">Pawchive</h1>

<p align="center">
  一款精致、流畅的第三方 Android 客户端，为你带来 <a href="https://pawchive.pw">Pawchive</a> 平台的完整体验。<br/>
  聚合 Patreon、Fanbox、Discord 等平台的创作者内容，支持浏览、搜索、收藏与沉浸式媒体播放。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-1.9.22-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/API-30%2B-34A853?style=for-the-badge&logo=android&logoColor=white" alt="Min API" />
  <img src="https://img.shields.io/badge/Target_API-36-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Target API" />
  <img src="https://img.shields.io/badge/AGP-9.2.1-00878F?style=for-the-badge&logo=androidstudio&logoColor=white" alt="AGP" />
  <img src="https://img.shields.io/badge/Release-v1.3.1-blue?style=for-the-badge&logo=android" alt="Release" />
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

## ✨ 功能亮点

### 📚 内容浏览
- **首页帖子流**：分页加载最新内容，支持关键词筛选与多种排序方式
- **创作者主页**：查看创作者的帖子、公告（Announcements）、粉丝卡（Fan Cards）及关联账号
- **帖子详情**：完整正文、评论树、修订历史（Revisions）、文件下载
- **多平台支持**：聚合 Patreon、Fanbox、Discord 等平台内容，平台标签使用品牌色

### 🔍 精准搜索
- **关键词搜索**：同时检索帖子与创作者，Tab 切换查看结果
- **文件哈希反查**：通过文件哈希追踪素材出处，支持 Discord 帖子结果
- **搜索历史**：本地持久化搜索记录，支持单条删除与一键清空（上限 30 条，自动置顶）

### 🎬 沉浸式媒体
- **高清图片查看**：手势缩放、双击放大、自由拖拽
- **视频播放**：基于 Media3 ExoPlayer，Bilibili 风格控制器，支持倍速播放、全屏切换、断点续播
- **多域名回退**：缩略图 / 原图 / 下载域名三级自动降级（`img.pawchive.pw` → `file.pawchive.pw`）

### ⭐ 收藏与账号
- **账号云端收藏**：登录后同步收藏的帖子与创作者
- **本地离线收藏**：无需登录也可本地管理收藏
- **账号管理**：登录、登出，注册通过 Chrome Custom Tabs 跳转官方页面

### ⚙️ 个性化设置
- **多语言**：中文 / English / 日本語，实时切换
- **外观模式**：日间 / 夜间 / 跟随系统，Material Design 3 主题
- **下载管理**：自定义下载目录（SAF），缓存查看与清理
- **应用内更新**：自动检查 GitHub Release，语义化版本比较，Markdown 更新日志渲染

---

## 🛠 技术架构

| 类别 | 技术选型 | 说明 |
|------|---------|------|
| **语言** | Kotlin 1.9.22 | 现代化、空安全的 JVM 语言 |
| **最低 SDK** | API 30 (Android 11) | 覆盖 95%+ 活跃设备 |
| **目标 SDK** | API 36 | 最新 Android 版本 |
| **UI 框架** | XML + ViewBinding | 声明式布局，类型安全访问 |
| **设计语言** | Material Design 3 | 卡片分组、分段按钮、品牌色标签 |
| **网络层** | Retrofit 2.9 + OkHttp 4.12 | 类型安全 HTTP 客户端，拦截器链架构 |
| **图片加载** | Coil 2.6 | Kotlin 优先、协程原生支持 |
| **视频播放** | AndroidX Media3 1.4.1 | ExoPlayer + OkHttp 数据源 |
| **安全存储** | EncryptedSharedPreferences | AES256 加密存储敏感凭据 |
| **浏览器** | Chrome Custom Tabs | 外部页面跳转，保留应用上下文 |
| **图标库** | Lucide | 统一、精致的矢量图标 |
| **构建工具** | Gradle 9.2 + AGP 9.2.1 | 版本目录（libs.versions.toml）管理依赖 |

---

## 🌟 核心技术亮点

### 1. Cloudflare 托管挑战自动过盾
目标站点启用 Cloudflare 防护，纯 OkHttp 请求会被拦截返回 403。`CloudflareManager` 通过隐藏 WebView 执行 JS 挑战，提取 `cf_clearance` Cookie 并绑定 User-Agent，注入到后续所有 OkHttp 请求中。凭据通过 `EncryptedSharedPreferences` 加密持久化，设置 20 分钟 TTL，冷启动无需重新过盾。

### 2. 智能拦截器链
- **主域注入**：仅对 `pawchive.pw` 主域注入 `cf_clearance` / `Referer`；图片 CDN 子域不注入，避免触发防盗链
- **透明重试**：响应 403 时强制刷新凭据并重试一次，带重试限制防止死循环；过盾失败返回 403 响应而非抛异常，保证 Coil/Retrofit 正常处理为加载失败
- **双 OkHttpClient**：`sharedOkHttpClient`（带 CF 拦截器，用于 API/图片/视频）与 `imageOkHttpClient`（轻量级，用于无需过盾的场景）

### 3. 双 API 端点架构
- **公开 API**：`pawchive.pw/api/v1/` —— 内容浏览、搜索、创作者信息
- **登录 API**：`pawchive.pw/` —— Flask Web 端点，处理登录注册，解析 302 重定向与 Set-Cookie 提取 session

### 4. 单 Activity + Fragment 架构
- 主界面 Tab Fragment 缓存复用，切换不重建、状态保留
- 二级页面自动隐藏底部导航，避免用户迷失
- `popBackStackImmediate` 同步执行，解决 Fragment 事务竞态导致的叠加问题

### 5. 视频播放器生命周期管理
`VideoPlayerManager` 封装 ExoPlayer，复用 `sharedOkHttpClient` 使视频流透明过盾；支持播放位置保存与恢复，Activity 生命周期切换无缝续播。

---

## 🏗 项目结构

```
Pawchive/
├── app/
│   └── src/main/
│       ├── java/com/pawchive/
│       │   ├── data/
│       │   │   ├── api/              # Retrofit 接口与网络层
│       │   │   │   ├── ApiClient.kt          # OkHttp/Retrofit 配置，拦截器链
│       │   │   │   ├── CloudflareManager.kt  # Cloudflare 过盾管理器
│       │   │   │   ├── PawchiveApi.kt        # API 接口定义
│       │   │   │   └── ApiCallHandler.kt     # 请求封装与结果包装
│       │   │   ├── github/            # GitHub Release 更新检查
│       │   │   ├── model/             # 数据模型（Post, Creator, Comment...）
│       │   │   ├── repository/        # 数据仓库（Auth, Bookmark, Session, SearchHistory）
│       │   │   └── SettingsManager.kt # 应用设置持久化
│       │   ├── ui/
│       │   │   ├── adapter/           # RecyclerView 适配器
│       │   │   ├── home/              # 首页（含 ViewModel）
│       │   │   ├── search/            # 搜索（帖子/创作者/哈希反查）
│       │   │   ├── creator/           # 创作者主页
│       │   │   ├── post/              # 帖子详情/图片查看/视频播放
│       │   │   ├── favorites/         # 账号收藏
│       │   │   ├── account/           # 账号管理
│       │   │   ├── login/             # 登录
│       │   │   ├── settings/          # 设置
│       │   │   ├── widget/            # 自定义控件（ZoomableImageView）
│       │   │   └── MainActivity.kt    # 单 Activity 入口
│       │   ├── utils/                 # 工具类（ErrorMessageHelper）
│       │   └── PawchiveApplication.kt # Application 初始化
│       └── res/
│           ├── drawable/              # Lucide 矢量图标
│           ├── layout/                # 布局文件
│           ├── values/                # 默认（中文）字符串与主题
│           ├── values-en/             # 英文
│           ├── values-ja/             # 日文
│           └── values-night/          # 深色主题
├── gradle/
│   └── libs.versions.toml            # 版本目录
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

---

## 🚀 快速开始

### 环境要求

- **Android Studio** Meerkat (2024.3+) 或更高
- **JDK** 17+
- **Gradle** 9.2+（项目内置 wrapper）

### 克隆 & 构建

```bash
# 克隆仓库
git clone https://github.com/FengByX/Pawchive.git
cd Pawchive

# 构建 Release APK
./gradlew assembleRelease
```

> APK 输出路径：`app/build/outputs/apk/release/Pawchive-v1.3.1.apk`

### 安装

从 [Releases](https://github.com/FengByX/Pawchive/releases) 页面下载最新 APK，直接安装到 Android 11+ 设备即可。

---

## 📱 运行要求

| 权限 | 用途 |
|------|------|
| `INTERNET` | 网络请求访问 Pawchive 平台 |
| `WRITE_EXTERNAL_STORAGE`（maxSdk 28） | 旧版本文件下载兼容 |
| `ACCESS_MEDIA_LOCATION`（maxSdk 32） | 媒体文件位置访问 |

> 最低系统版本：Android 11（API 30）

---

## 🌍 国际化

应用完整支持三种语言，所有 UI 字符串均通过资源文件管理：

| 语言 | 资源目录 |
|------|---------|
| 简体中文（默认） | `values/` |
| English | `values-en/` |
| 日本語 | `values-ja/` |

---

## 📸 截图

| 首页 | 搜索 | 创作者 |
|------|------|--------|
| 帖子流浏览 | 关键词 / 哈希搜索 | 创作者详情 |

| 帖子详情 | 收藏 | 设置 |
|----------|------|------|
| 评论 · 修订 · 播放 | 账号云端同步 | 主题 · 语言 · 下载 |

---

## 🔄 版本更新

应用内置 GitHub Release 更新检查器：
- 启动时静默检查（24 小时间隔，避免频繁打扰）
- 语义化版本号比较（支持预发布后缀 `-beta` / `-rc.1`）
- 发现新版本时弹出 Material 对话框，Markdown 渲染更新日志
- 点击跳转 Chrome Custom Tabs 打开下载页

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request。提交前请确保：
1. 代码风格与现有代码保持一致
2. 新增功能适配三种语言字符串资源
3. 遵循 Material Design 3 设计规范
4. 网络请求通过 `CloudflareManager.withClearance()` 包装

---

## 📄 开源协议

本项目基于 **MIT License** 开源。

---

<p align="center">
  <sub>图标资源来自 <a href="https://lucide.dev">Lucide</a> · 设计灵感来自 Material Design 3</sub>
</p>
