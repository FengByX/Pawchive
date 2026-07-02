<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Pawchive" width="120" />
</p>

<h1 align="center">Pawchive</h1>

<p align="center">
  一款精致、流畅的第三方 Android 客户端，为你带来 <a href="https://pawchive.st">Pawchive</a> 平台的完整体验。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-1.9-7F52FF?style=for-the-badge&amp;logo=kotlin&amp;logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/API-30%2B-34A853?style=for-the-badge&amp;logo=android&amp;logoColor=white" alt="Min API" />
  <img src="https://img.shields.io/badge/AGP-9.2-3DDC84?style=for-the-badge&amp;logo=androidstudio&amp;logoColor=white" alt="AGP" />
  <img src="https://img.shields.io/badge/Release-v1.0.0-blue?style=for-the-badge&amp;logo=android" alt="Release" />
  <img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge" alt="License" />
</p>

---

## ✨ 功能亮点

<table>
  <tr>
    <td align="center" width="33%">
      <img src="https://img.icons8.com/fluency/48/home.png" width="40" /><br />
      <b>浏览发现</b><br />
      <sub>首页帖子流，支持关键词筛选</sub>
    </td>
    <td align="center" width="33%">
      <img src="https://img.icons8.com/fluency/48/search.png" width="40" /><br />
      <b>精准搜索</b><br />
      <sub>按文件哈希快速追踪出处</sub>
    </td>
    <td align="center" width="33%">
      <img src="https://img.icons8.com/fluency/48/image.png" width="40" /><br />
      <b>帖子详情</b><br />
      <sub>评论 · 修订记录 · 文件下载</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <img src="https://img.icons8.com/fluency/48/video-playlist.png" width="40" /><br />
      <b>沉浸式媒体</b><br />
      <sub>高清图片查看 · 手势缩放 · 视频播放</sub>
    </td>
    <td align="center" width="33%">
      <img src="https://img.icons8.com/fluency/48/bookmark-ribbon.png" width="40" /><br />
      <b>收藏管理</b><br />
      <sub>账号云端同步 · 本地离线收藏</sub>
    </td>
    <td align="center" width="33%">
      <img src="https://img.icons8.com/fluency/48/color-palette.png" width="40" /><br />
      <b>个性设置</b><br />
      <sub>深色 / 浅色主题 · 多语言切换</sub>
    </td>
  </tr>
</table>

## 🛠 技术架构

| 类别 | 技术选型 | 说明 |
|------|---------|------|
| **语言** | Kotlin 1.9 | 现代化、空安全的 JVM 语言 |
| **最低 SDK** | API 30 (Android 11) | 覆盖 95%+ 活跃设备 |
| **目标 SDK** | API 36 | 最新 Android 版本 |
| **UI 框架** | XML + ViewBinding | 声明式布局，类型安全 |
| **网络层** | Retrofit 2 + OkHttp 4 | 类型安全的 HTTP 客户端 |
| **图片加载** | Coil 2 | Kotlin 优先、协程原生支持 |
| **视频播放** | Media3 ExoPlayer | Google 官方多媒体框架 |
| **构建工具** | Gradle 9.2 + AGP | 现代构建系统 |

## 🏗 项目结构

```
Pawchive/
├── app/
│   └── src/main/
│       ├── java/com/pawchive/
│       │   ├── data/
│       │   │   ├── api/          # Retrofit 接口定义
│       │   │   ├── model/        # 数据模型
│       │   │   └── repository/   # 数据仓库 & 认证
│       │   ├── ui/
│       │   │   ├── adapter/      # RecyclerView 适配器
│       │   │   ├── home/         # 首页
│       │   │   ├── search/       # 搜索
│       │   │   ├── creator/      # 创作者主页
│       │   │   ├── post/         # 帖子详情 & 照片查看
│       │   │   ├── favorites/    # 收藏管理
│       │   │   ├── account/      # 账号管理
│       │   │   ├── login/        # 登录
│       │   │   ├── settings/     # 设置
│       │   │   └── widget/       # 自定义控件
│       │   └── PawchiveApplication.kt
│       └── res/                  # 资源文件 & 多语言
├── gradle/
│   └── libs.versions.toml        # 版本目录
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

## 🚀 快速开始

### 环境要求

- **Android Studio** Meerkat (2024.3+) 或更高
- **JDK** 17+
- **Gradle** 9.2+

### 克隆 & 构建

```bash
# 克隆仓库
git clone https://github.com/FengByX/Pawchive.git
cd Pawchive

# 构建 Release APK
./gradlew assembleRelease
```

> APK 输出路径：`app/build/outputs/apk/release/Pawchive-v1.0.0.apk`

### 安装

从 [Releases](https://github.com/FengByX/Pawchive/releases) 页面下载最新的 APK，直接安装到 Android 设备即可。

## 📸 截图

| 首页 | 搜索 | 创作者 |
|------|------|--------|
| 帖子流浏览 | 哈希精确查找 | 创作者详情 |

| 帖子详情 | 收藏 | 设置 |
|----------|------|------|
| 评论 & 修订 | 账号 & 本地 | 主题 & 语言 |

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request。

---

<p align="center">
  <sub>图标资源来自 <a href="https://www.iconfont.cn/user/detail?spm=a313x.search_index.0.d214f71f6.4b203a819GYzb1&uid=3866068&nid=90kPUssmIYZQ">Iconfont</a> | 创作者主页 <a href="https://github.com/FengByX">FengByX</a></sub>
</p>
