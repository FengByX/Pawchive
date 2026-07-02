# Pawchive

Pawchive 是一款基于 [Pawchive](https://pawchive.st) 平台的第三方 Android 客户端，提供浏览帖子、搜索创作者、收藏管理与个性化设置等功能。

## 功能

- **首页帖子流** — 浏览最新帖子，支持按关键词筛选
- **搜索** — 按文件哈希搜索帖子，快速定位内容
- **创作者页面** — 查看创作者详情、公告、关联链接及全部帖子
- **帖子详情** — 查看帖子内容、评论、修订记录，支持图片查看与视频播放
- **账号收藏** — 登录后同步收藏的帖子和创作者
- **本地收藏** — 离线收藏创作者（无需登录）
- **设置** — 支持语言切换与深色/浅色外观

## 技术栈

- **语言**：Kotlin
- **UI**：XML + ViewBinding + Fragment
- **网络**：Retrofit 2 + OkHttp
- **图片加载**：Coil
- **视频播放**：AndroidX Media3 (ExoPlayer)

## 环境要求

- Android Studio 2024+
- Android SDK 36
- JDK 17
- Gradle 9.2+

## 构建与运行

```bash
git clone https://github.com/FengByX/Pawchive.git
cd Pawchive
./gradlew assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/`。

## 许可证

本项目仅用于学习与交流。
