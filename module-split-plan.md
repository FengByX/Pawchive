# Pawchive 模块拆分规划（ARCH-002）

> 状态：阶段 1（:core）、阶段 2（:data）、阶段 3（:feature-common + :feature-* + AppNavigator）、阶段 4（:app 瘦身 + lint + README）全部完成
> 目标：拆分为 `core`（网络/错误/存储/模型）、`data`（API 业务/Repository/Worker）、`feature-*`（页面）、`app`（装配）
> 验收：各 `feature-*` 模块间无直接互相依赖（仅依赖 core/data）；`app` 模块仅负责装配

---

## 1. 依赖方向规则（硬约束）

```
:app (装配)
  ├── :feature-*  (页面：home/search/post/downloads/settings/account)
  ├── :data       (业务 Repository/Manager/Worker)
  └── :core       (网络/错误/存储/模型/数据库)

:feature-* ──→ :data ──→ :core
:feature-* ──→ :core
:feature-* 之间：禁止直接依赖（导航走 :app 提供的 Navigator 接口）
:core：禁止依赖任何项目内模块
```

- 模块间只能通过公开 API 交互，禁止跨模块引用内部包
- 资源（strings/drawable/layout）：公共资源放 `:core` 或 `:feature-common`，模块私有资源放各自模块

---

## 2. 模块划分总览

| 模块 | 职责 | 依赖 |
|---|---|---|
| `:core` | 网络客户端、错误体系、模型、Room 数据库、安全/配置存储、通用工具 | 仅第三方库 |
| `:data` | 业务 Repository/Manager、更新检查、下载 Worker | `:core` |
| `:feature-common` | 跨页面共享 UI（adapter/widget/VideoPlayerManager/item 布局/通用 drawable） | `:core`、`:data` |
| `:feature-home/search/post/downloads/settings/account` | 各页面 Fragment/ViewModel/专属布局 | `:core`、`:data`、`:feature-common` |
| `:app` | Application、MainActivity、导航装配、Manifest | 全部 feature + data + core |

---

## 3. `:core` 模块边界

**包：`com.pawchive.core.*`**

| 目标包 | 迁移类（现包 → 目标包） | 说明 |
|---|---|---|
| `core/api` | `data.api.ApiClient`、`CloudflareManager`、`ApiCallHandler`、`ApiResult`、`PawchiveApi`；`data.github.GithubApi` | 网络层；ApiClient 依赖 BuildConfig（core 需 `buildFeatures.buildConfig=true`） |
| `core/model` | `data.model.Models.kt`（Post/Creator/Comment/…）、`DownloadRecord`（Room Entity）、`data.github.GithubRelease` | 共享模型；DownloadRecord 是 Room Entity，与 db 同模块 |
| `core/error` | `data.AppError`、`utils.ErrorMessageHelper` | **强耦合对**：AppError 引用 ErrorMessageHelper、后者按 AppError 分类，必须同模块，否则循环依赖 |
| `core/db` | `data.db.PawchiveDatabase`、`DownloadHistoryDao` | Room 数据库（version 2 + MIGRATION_1_2） |
| `core/di` | `data.di.DatabaseModule` | Hilt 提供 DB/DAO |
| `core/store` | `data.SettingsManager`、`repository.SessionManager`、`repository.AppMemoryCache` | 配置/安全存储、通用内存缓存 |
| `core/util` | `utils.SafeHtmlHelper`、`utils.NetworkUtils`、`utils.CrashHandler` | 通用工具（SafeHtmlHelper 依赖 R.string，core 需 strings） |

**依赖（仅第三方）**：Hilt、Retrofit + OkHttp + logging、Gson、DataStore、Room（runtime + ksp compiler）、security-crypto、appcompat、material、coroutines

**资源**：`strings.xml` + `values-en/values-ja`（error_*、unsafe_link_blocked、browser_not_available 等 core 用到的 key，从 app strings 拆出）

**测试**：AppErrorTest、SettingsManagerTest、SessionManagerTest、AppMemoryCacheTest、SafeHtmlHelperTest、DownloadHistoryManagerTest（依赖 core DAO）随代码迁移至 `core/src/test`

---

## 4. `:data` 模块边界

**包：保持 `com.pawchive.data.*`（减少 import 改动）**

| 目标包 | 迁移类 | 说明 |
|---|---|---|
| `data/repository` | `AuthRepository`、`BookmarkManager`、`BlockedCreatorManager`、`CreatorNameCache`、`DownloadCenter`、`DownloadHistoryManager`、`DownloadRepository`、`LocalDataCleaner`、`ReadingProgressManager`、`SearchHistoryManager` | 业务数据层；DownloadHistoryManager 用 core 的 DAO；AuthRepository 依赖 R.string（register_*） |
| `data/github` | `UpdateChecker` | 依赖 R.string + MaterialAlertDialog（需 material 依赖） |
| `data/work` | `DownloadWorker`、`CacheCleanWorker` | 下载/缓存 Worker；依赖 core 的 ApiClient/SettingsManager + data 的 DownloadHistoryManager/DownloadRepository |

**依赖**：`:core` + work-runtime-ktx + appcompat + material + Hilt（@HiltWorker）

**资源**：`strings.xml` + 三语（register_*、update_*、download_*、download_channel_*、no_images_to_save 等）

**必须消除的逆向依赖**：
1. `CacheCleanWorker` 的 `shouldCleanByThreshold()` companion 引用 `PawchiveApplication.getSettingsManager()`（work → app）→ 改为接收 `SettingsManager` 参数，调用方（PawchiveApplication）传入注入实例
2. `DownloadCenter` 依赖 `work.DownloadWorker`（同模块内，无问题）

---

## 5. `:feature-*` 与 `:app`（后续阶段）

**`:feature-common`**：`ui.adapter.*`（7 个 adapter）、`ui.widget.ZoomableImageView`、`ui.post.VideoPlayerManager`、item_* 布局、通用 drawable（ic_*）

**`:feature-*` 划分**（Fragment/ViewModel/专属布局/strings 三语）：
- `feature-home`：HomeFragment/HomeViewModel
- `feature-search`：SearchFragment/SearchViewModel
- `feature-post`：PostDetailFragment/PostDetailViewModel/PhotoViewerFragment/FullscreenVideoDialog
- `feature-downloads`：DownloadsFragment/DownloadsViewModel
- `feature-settings`：SettingsFragment/SettingsViewModel
- `feature-account`：AccountFragment/AccountFavoritesFragment/LoginFragment/CreatorProfileFragment/CreatorProfileViewModel

**导航重构（最大阻塞点）**：
- 现状 23 处 `(activity as? MainActivity)?.loadFragment(...)` 且直接 new 其他 feature 的 Fragment（如 PostDetailFragment → CreatorProfileFragment）
- 方案：`:feature-common` 定义 `AppNavigator` 接口（openPostDetail/openCreatorProfile/openSettings/…）；`:app` 实现并注入；feature 只调用接口，不 new 其他 feature 的 Fragment
- 各 feature 模块加 Hilt（@AndroidEntryPoint Fragment），app 的 `@HiltAndroidApp` 不变

---

## 6. 迁移步骤（每阶段可编译、测试全绿再进入下一阶段）

### 阶段 1：抽出 `:core`（本次执行）
1. `settings.gradle.kts` include `:core`；新建 `core/build.gradle.kts`（library + buildConfig + Hilt/KSP/Room）
2. 按 §3 移动类并改包名为 `com.pawchive.core.*`（机械改 import）
3. 从 app strings 拆出 core 需要的 key → core 三语 strings
4. `app/build.gradle.kts` 依赖 `:core`；`PawchiveApplication`/`MainActivity` 等修复引用（import 调整）
5. 编译 `assembleDebug` + `testDebugUnitTest`（core 相关测试先迁 core 或暂留 app 跑通）
6. 勾选验证项

### 阶段 2：抽出 `:data`
1. `settings.gradle.kts` include `:data`；新建 `data/build.gradle.kts`（依赖 core）
2. 按 §4 移动类（包名不变）；消除 `CacheCleanWorker → PawchiveApplication` 逆向依赖
3. 拆出 data 需要的 strings（register_*/update_*/download_*）
4. app 依赖 `:data` + `:core`；编译 + 测试

### 阶段 3：`:feature-common` + `:feature-*`（已完成）
1. 抽 `:feature-common`（adapter/widget/共享布局/通用 drawable + AppNavigator 接口）✅
2. 按 §5 拆 feature 模块；`MainActivity` 实现 AppNavigator；替换全部 22 处导航调用 ✅
3. 各 feature strings/布局/依赖拆分；编译 + 测试 ✅（assembleDebug + 134 用例全绿）

### 阶段 4：`:app` 瘦身（已完成）
1. app 仅保留 Application/MainActivity/导航装配/Manifest ✅
2. 删除 app 残留的重复布局/strings/colors（19 个布局 + 三语 strings + colors；`ShapeAppearance.Pawchive.Thumbnail` 迁至 feature-common）✅
3. 全量 `lintDebug` 0 error（补模块 Manifest 权限 + UseAppTint 修复 + consumer-rules.pro 补齐）✅
4. `assembleDebug` + `testDebugUnitTest`（134 用例）通过 ✅
5. README 模块化架构图 + 技术栈/权限/版本更新 ✅

---

## 7. 关键风险与应对

| 风险 | 应对 |
|---|---|
| `AppError ↔ ErrorMessageHelper` 循环依赖 | 强制同置 `:core/error` |
| `work → app` 逆向依赖（PawchiveApplication.getSettingsManager） | 阶段 2 先行消除，改为构造参数注入 |
| 跨 feature 导航（23 处直接 new Fragment） | 阶段 3 引入 `AppNavigator` 接口，app 装配 |
| 资源拆分（strings 三语按模块） | 机械迁移；公共 key 归 core/feature-common，模块私有 key 归各自模块 |
| BuildConfig 依赖（ApiClient 用 DEBUG） | core 开启 `buildFeatures.buildConfig=true` |
| Hilt 跨模块 | 各模块加 hilt-android + ksp；app 的 @HiltAndroidApp 不变 |
| viewBinding/R 跨模块 | 布局随所属模块；item_* 布局归 feature-common，避免重复定义 |

---

## 8. 验收标准对照（ARCH-002）

- [x] Gradle 模块拆分完成（core/data/feature-common/feature-*/app）
- [x] 各 `feature-*` 模块间无直接互相依赖（仅依赖 core/data/feature-common）
- [x] `app` 模块仅负责装配（Application/MainActivity/导航/Manifest，重复资源已清理）
- [x] 全部模块编译通过 + `testDebugUnitTest` 全绿（core 69 + data 65 = 134 用例）+ `lintDebug` 0 error
