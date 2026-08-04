# Pawchive 架构与工程质量审查（高层视角）

> 视角：整体架构、工程质量与可演进性，**非逐行修改**。
> 配合之前的逐行细节审查 `code-review-2026-08-04.md` 一起看。
> 所有结论均基于本仓当前源码（已编译通过的 1.5.0 基线）实地核实。

---

## 一、总体结论（TL;DR）

Pawchive 是一个**单模块、以包（package）划分层**的 MVVM 风格 Android 应用，包命名（`data` / `ui` / `utils` / `work`）基本合理、依赖方向单向（ui → data，无反向引用），**没有循环依赖**。但工程化成熟度仍有明显短板：

1. **没有依赖注入、没有模块边界、没有基类脚手架**——54 个 Kotlin 文件全挤在 `:app` 单模块，UI 直接 `new` 数据层类，28 个 UI 文件重复大量样板。
2. **状态管理风格不统一**（LiveData 与 StateFlow 混用）。
3. **零自动化测试**（Robolectric/JUnit 已声明依赖，但没有任何 test 目录）。
4. **Release 构建既用 debug 签名又关闭混淆**，且用了 **alpha 版安全库**。
5. 核心网络/缓存/过盾逻辑复杂，**缺乏回归保护和可观测性**（无崩溃上报、无统一错误模型）。

整体是「能跑、结构清晰但工程化偏早期」的状态，适合做一次「架构与测试基建」的集中补课。

---

## 二、按优先级排序的发现总表

| ID | 优先级 | 类别 | 问题 | 位置 |
|---|---|---|---|---|
| A1 | 高 | 安全/构建 | Release 用 debug 签名 + 关闭混淆，代码与接口完全暴露 | `app/build.gradle.kts:24-30` |
| A2 | 高 | 架构 | 单模块 + 无 DI，UI 直接实例化数据层，耦合重、难测试 | 各 Fragment / ViewModel |
| A3 | 高 | 测试 | 零自动化测试，核心逻辑无回归保护 | 无 `test/` `androidTest/` 目录 |
| A4 | 高 | 安全/依赖 | 使用 alpha 版安全库 `security-crypto:1.1.0-alpha06` | `app/build.gradle.kts:76` |
| A5 | 高 | 安全 | 主客户端日志 `Level.BODY` 泄露 session / cf_clearance | `ApiClient.kt:257-263` |
| A6 | 中 | 架构 | Fragment 间直接 import 互相 `new`，无 Navigation Component，返回栈实例膨胀 | `MainPagerAdapter` / 各 Fragment |
| A7 | 中 | 代码质量 | 状态管理不统一：HomeViewModel 用 LiveData，其余用 StateFlow | `HomeViewModel.kt` vs 其他 VM |
| A8 | 中 | 代码质量 | 无基类脚手架（BaseFragment/BaseViewModel/BaseAdapter 均缺失），样板重复 | 28 个 UI 文件 |
| A9 | 中 | 错误处理 | 无统一错误/加载/空态模型，多处静默吞异常 | 各 VM / `ErrorMessageHelper.kt` |
| A10 | 中 | 可观测性 | 无全局异常兜底 / 崩溃上报，线上故障不可见 | 全仓 |
| A11 | 中 | 性能 | 手动 offset 分页无 Paging3，整列表驻留内存、无占位 | `HomeViewModel.fetchPosts` |
| A12 | 中 | 性能 | 搜索全量拉创作者后在主线程线性过滤 | `SearchFragment` / `SearchViewModel` |
| A13 | 中 | 安全 | 拦截器内 `runBlocking` 驱动 WebView 过盾，阻塞网络线程 | `ApiClient.kt:198` |
| A14 | 中 | 依赖管理 | Kotlin 1.9.22 偏旧（AGP 9.2.1 配套建议 2.0+）；Retrofit 2.9.0 较老 | `gradle/libs.versions.toml` |
| A15 | 低 | 代码质量 | 魔法数字：超时 15/30/60/120s 散落多处 | `ApiClient`/`DownloadWorker`/`GithubApi`/`PhotoViewer` |
| A16 | 低 | 性能 | `FavoritePostAdapter` 用 `notifyDataSetChanged`，未用 DiffUtil（对比 `PostAdapter` 已用） | `FavoritePostAdapter.kt` |
| A17 | 低 | 代码质量 | `ApiClient` 职责过重（OkHttp 构建+缓存+Cloudflare+日志一体） | `ApiClient.kt` |
| A18 | 低 | 安全/配置 | 建议补 `network_security_config`（限制明文、可选证书固定） | `AndroidManifest` / `res/xml` |

---

## 三、分维度详述

### 1. 架构与目录结构

**A2（高）· 单模块 + 无 DI，UI 直接持有数据层实现**
- **位置**：`HomeFragment`/`SearchFragment`/`CreatorProfileFragment`/`PostDetailFragment` 内 `BookmarkManager.getInstance(...)`；`SettingsManager`/`BlockedCreatorManager`/`UpdateChecker` 等在 Fragment/ViewModel 中直接 `new` 或取单例。
- **影响**：UI 与具体数据实现紧耦合，无法在测试中替换为假实现；对象生命周期分散在各调用点，易产生「同一概念多实例」类 bug（此前 `BookmarkManager` 多实例串号即此类）。
- **建议方向**：
  - 轻量方案：引入 **Hilt**（Android 官方 DI），把 `ApiClient`、`SessionManager`、各 `*Manager`、ViewModel 的依赖集中声明，UI 只 `@Inject` 接口。
  - 或过渡方案：建一个 `AppContainer`/`object` 显式组装依赖图，ViewModel 通过 `viewModelFactory` 注入。
  - 长期：按 `:core` / `:data` / `:feature:home` 等拆多模块，强制层边界。

**A6（中）· Fragment 互相 import + 无 Navigation Component**
- **位置**：`HomeFragment` import `CreatorProfileFragment`/`PostDetailFragment`；`SearchFragment` import `CreatorProfileFragment`/`PostDetailFragment`；`AccountFavoritesFragment`、`CreatorProfileFragment` 同理。依赖清单中**无 `androidx.navigation`**，主 Tab 用 `ViewPager2`（`MainPagerAdapter`），详情页靠手动 Fragment 事务。
- **影响**：导航硬编码、跨屏传参与返回栈全手动管理，Fragment 实例被反复 `new`（此前已发现返回栈实例膨胀、状态丢失风险）；转场动画/深层链接/deeplink 难以统一。
- **建议方向**：迁移到 **Navigation Component（Fragment 版）** 或 Navigation Compose，用 `nav_graph.xml` + Safe Args 统一导航与参数；详情/创作者页作为 destination，避免互相直接引用。

**A7（中）· 状态管理风格不统一**
- **位置**：`HomeViewModel` 用 `LiveData`/`MutableLiveData`（且拆成 4 个独立字段 `_posts`/`_isLoading`/`_errorMessage`/`_hasMore`）；`DownloadsViewModel`/`SettingsViewModel`/`PostDetailViewModel`/`SearchViewModel` 用 `StateFlow` + 单一 `XxxUiState` data class。
- **影响**：同一代码库两套范式，新人易混乱；`HomeViewModel` 没有聚合的 `UiState`，UI 需分别订阅 4 个 LiveData，状态一致性难保证。
- **建议方向**：统一为 **`StateFlow` + `sealed interface UiState`**（Loading/Success/Empty/Error），`HomeViewModel` 先对齐；`LiveData` 仅在必须兼容旧 XML 观察处保留。

**A8（中）· 无基类脚手架，样板重复**
- **位置**：全仓 `abstract class BaseFragment` / `BaseViewModel` / `BaseAdapter` 均不存在（已 grep 确认）。每个 Fragment 重复 `_binding!!` + `onDestroyView()` 解绑 + ViewModel 取用；每个 Adapter 重复 `onCreateViewHolder`/`onBindViewHolder` 模板；深色模式配色逻辑在多处重复（此前审查已记 4 处）。
- **影响**：复制粘贴导致改一处要改 N 处，易遗漏（如某 Fragment 忘了 `onDestroyView` 解绑 → 内存泄漏）。
- **建议方向**：抽 `BaseFragment<B : ViewBinding>`（封装 binding 生命周期）、`BaseListAdapter`（封装 DiffUtil + 点击回调）；或改用 **ViewBinding 委托属性**减少样板。

### 2. 代码质量

**A15（低）· 魔法数字**
- **位置**：超时 `15/30/60/120s` 散落在 `ApiClient.kt`（15/30/60）、`DownloadWorker.kt`（120）、`GithubApi.kt`（`TIMEOUT_SECONDS`）、`PhotoViewerFragment.kt`（60）。
- **建议方向**：集中到 `object NetworkConfig { const val CONNECT_TIMEOUT = 15_000 ... }` 或 `BuildConfig`/resource，便于调参与统一。

**A17（低）· `ApiClient` 职责过重**
- **位置**：`ApiClient.kt` 同时负责 OkHttp 构建、内存缓存拦截器、Cloudflare 注入/重试、日志、auth/public 双实例。**单一类承担过多关注点**，约 300+ 行。
- **建议方向**：将「内存缓存」「Cloudflare 拦截器」「日志脱敏」拆为独立 `Interceptor` 实现类 + 独立 `MemoryCache` 组件，`ApiClient` 仅做组装；也更利于单测（A3）。

### 3. 错误处理与健壮性

**A9（中）· 无统一错误模型 + 静默吞异常**
- **位置**：`ErrorMessageHelper.kt` 用**子串匹配**把 HTTP 错误归类（此前审查已确认会误匹配，如把包含 "403" 的正文误判为权限错误）；多处 `catch` 后仅 `return` 或打 log，未上抛给用户。
- **影响**：错误对用户不可见或提示不准确；同一类错误在不同页面处理不一致。
- **建议方向**：定义领域 ` sealed class AppError `（Network/Auth/Cloudflare/Unknown），由 Repository 返回 `Result<T>`/`AppError`，ViewModel 映射进 `UiState.Error(messageRes)`；`ErrorMessageHelper` 改为基于**响应码/类型**而非子串。

**A10（中）· 无全局异常兜底 / 崩溃上报**
- **位置**：全仓无 `Thread.setDefaultUncaughtExceptionHandler`，无 Sentry/Crashlytics 依赖。
- **影响**：线上崩溃与过盾失败不可见，难以排障（Cloudflare 过盾尤其依赖真机日志，此前审查一直受限于「无设备无法验证」）。
- **建议方向**：加 `CoroutineExceptionHandler` + 全局 `UncaughtExceptionHandler` 至少把崩溃写本地日志；若接受，引入 Firebase Crashlytics（注意：本类 Pixiv 客户端涉及隐私，需评估合规）。

**输入校验（中）**：搜索词长度/特殊字符、下载目标 URI 权限（`download_tree_uri` SAF）建议集中校验，避免脏输入进入网络/文件系统。

### 4. 性能与资源使用

**A11（中）· 手动 offset 分页，无 Paging3**
- **位置**：`HomeViewModel.fetchPosts(reset)` 用 `currentOffset` 累加，列表整体存 `_posts: List<Post>`。
- **影响**：翻页靠整列表持有，长信息流内存增长；无加载占位（skeleton），下滑到底体验差；无法稳定做增量 diff。
- **建议方向**：接入 **Paging3**（`PagingSource` + `Pager`），与 `RecyclerView`/`Adapter` 的 `LoadState` 联动，自动占位与回收。

**A12（中）· 搜索全量拉取 + 主线程过滤**
- **位置**：`SearchFragment`/`SearchViewModel` 拉取全部创作者后在主线程线性过滤（此前审查已确认）。
- **建议方向**：检索参数下沉到接口（若后端支持）；否则在 `viewModelScope` + `Dispatchers.Default` 做过滤，结果回主线程。

**A16（低）· `FavoritePostAdapter` 未用 DiffUtil**
- **位置**：`FavoritePostAdapter.kt` 使用 `notifyDataSetChanged()`；而 `PostAdapter.kt` 已用 `ListAdapter`/DiffUtil。
- **影响**：收藏列表增删时整表刷新，闪烁 + 浪费。
- **建议方向**：统一改用 `ListAdapter` + `DiffUtil.ItemCallback`。

**A13（中，性能/线程）**：拦截器内 `runBlocking` 驱动 WebView 过盾（见安全节 A13），本质是线程资源问题，并发 403 会耗尽 OkHttp 网络线程。

### 5. 安全性

**A1（高）· Release 用 debug 签名 + 关闭混淆**
- **位置**：`app/build.gradle.kts:24-30`：`release { isMinifyEnabled = false; ... signingConfig = debug }`。
- **影响**：发布包代码、接口路径、密钥逻辑完全可读；且同证书 debug 包可覆盖替换正式包，**等同无发布安全保障**。
- **建议方向**：配置独立 release keystore（`signingConfigs.release`）+ `isMinifyEnabled = true` + `shrinkResources`，并加 `proguard-rules.pro`（保留 `Parcelable`/`Gson` 模型/`WebView` 回调）。

**A4（高）· alpha 版安全库**
- **位置**：`app/build.gradle.kts:76` `androidx.security:security-crypto:1.1.0-alpha06`。
- **影响**：加密相关依赖使用 alpha 版本，API 与行为不稳定，且 `EncryptedSharedPreferences` 是 session 令牌的存储底座（`SessionManager`）。
- **建议方向**：升级到稳定版 `1.1.0`（非 alpha）；并评估 `SessionManager` 的「初始化失败明文回退」——不应静默降级，应失败可见（此前审查已记）。

**A5（高）· 主客户端日志泄露凭证**
- **位置**：`ApiClient.kt:257-263` 主 OkHttp 用 `HttpLoggingInterceptor.Level.BODY`；图片客户端已是 `BASIC`。BODY 会打印 `session`/`cf_clearance` 等 Cookie。
- **建议方向**：主客户端也降为 `BASIC` 或 `NONE`，并对日志做**脱敏**（经 `sanitized` 过滤后再输出，当前脱敏仅覆盖部分路径）。发布构建务必关闭网络日志。

**A13（中）· 拦截器内 `runBlocking` 过盾**
- **位置**：`ApiClient.kt:198`。403 时在 OkHttp 网络线程 `runBlocking` 驱动 WebView，并发会耗尽线程、放大卡顿/超时。
- **建议方向**：将过盾从「拦截器内同步等待」改为「调用层挂起等待 + 单飞」（此前已将 `CloudflareManager` 改为 `CompletableDeferred` 单飞，但拦截器里的 `runBlocking` 外壳未拆掉——这是下一步重构重点）。

**A18（低）· 网络安全配置**
- **建议方向**：补 `res/xml/network_security_config.xml`，默认禁明文、按需对 API 域名做证书固定（pinning），降低中间人风险。

### 6. 测试与可维护性

**A3（高）· 零自动化测试**
- **位置**：`app/src` 下无 `test/` 或 `androidTest/` 目录（已确认）；但 `build.gradle.kts:99-104` 声明了 JUnit/Robolectric/Espresso。
- **影响**：缓存命中/隔离、Cloudflare 单飞、各 `*Manager` 的「内存↔磁盘一致」、排序逻辑等**全靠人工 + 真机回归**，本次多轮修复均无测试兜底——风险最高处恰好零覆盖。
- **建议方向（由易到难）**：
  1. 先补**纯逻辑单测**：`ApiClient` 内存缓存（key 命名空间/过期/LRU）、`SessionManager` 存取、`BookmarkManager`/`SearchHistoryManager` 的写入串行与 `ensureLoaded` 护栏。
  2. `ErrorMessageHelper` 子串误匹配单测（小而易见效）。
  3. Robolectric 跑 Fragment 启动冒烟测试。

**A14（中）· 依赖版本偏旧**
- **位置**：`gradle/libs.versions.toml`：`kotlin = 1.9.22`、`retrofit = 2.9.0`、`okhttp = 4.12.0`、`coil = 2.6.0`；而 AGP 已 `9.2.1`。
- **影响**：Kotlin 1.9 与 AGP 9 组合可用但非推荐（2.0+ 有性能与 K2 编译器红利）；Retrofit 2.9 较老。
- **建议方向**：评估升级 Kotlin 2.0.x、Retrofit 2.11+、OkHttp 4.12+（已有）至最新补丁；升级前务必先有测试（呼应 A3）。

**文档（低）**：`README.md` 存在但无架构/模块/构建说明；建议补 `ARCHITECTURE.md`（层边界、导航、关键数据流），降低接手成本。

---

## 四、新增功能建议（3–5 个，结合现有定位）

> Pawchive 定位：Pixiv 风格的「创作者作品浏览 + 收藏 + 下载」客户端，强依赖第三方 API 与 Cloudflare 过盾。

### F1 · 收藏分组 / 标签（收藏夹）
- **用途**：用户给收藏的帖子/创作者打标签（如「参考」「待临摹」「已购」），按分组筛选查看。
- **适用场景**：重度收藏用户管理数百条收藏，避免信息流淹没。
- **落地依赖**：扩展 `BookmarkManager` 的 DataStore 结构（增加 `tags: Set<String>`），UI 加分组抽屉/多选 chip。
- **难度**：**中**（数据模型 + UI 双改，但复用现有 DataStore 与单例架构）。

### F2 · 创作者关注 + 新作提醒
- **用途**：关注创作者，定期（WorkManager 周期任务）检查是否有新帖子，有则在通知栏提醒。
- **适用场景**：追更、不想错过喜欢的画师更新。
- **落地依赖**：扩展 `BlockedCreatorManager` 同款的关注列表存储；新增 `UpdateWorker`（WorkManager 已引入，见 `DownloadWorker`）；通知渠道复用现有 `ensureChannel` 逻辑。
- **难度**：**中高**（需轮询/增量判断「新作」、省电策略、通知点击深链到 `PostDetailFragment`）。

### F3 · 高级搜索（标签 / 来源筛选 / 排序来源）
- **适用场景**：当前搜索仅「全部/帖子/创作者 + 简单排序」；进阶用户想按标签、R-18 过滤、时间区间检索。
- **落地依赖**：扩展 `SearchViewModel` 的查询模型 + `sheet_sort_options.xml` 同款 BottomSheet 做筛选；依赖后端是否支持对应参数（不支持则本地 `Dispatchers.Default` 过滤，呼应 A12）。
- **难度**：**中**。

### F4 · 图片/视频查看器增强
- **用途**：PhotoViewer 增加双指缩放、滑动切换、背景播放、手势返回；视频增加倍速/循环/画中画。
- **适用场景**：看图/看稿体验，对标专业图库 App。
- **落地依赖**：基于现有 `PhotoViewerFragment` + Media3 `ExoPlayer`（已引入）；缩放可用 `PhotoView` 类库或手势检测器自研。
- **难度**：**中**。

### F5 · 多账号切换与资料隔离
- **用途**：在同一 App 内切换多个 Pixiv 账号，收藏/会话/设置按账号隔离。
- **适用场景**：用户有主号/小号，避免反复登出登录。
- **落地依赖**：当前 `SessionManager` 是单 session 模型，需改为 `Map<accountId, Session>` + 当前账号指针；`ApiClient` 认证实例按账号重建（已有 `clearMemoryCache`/`authApi` 重建机制可复用）；UI 加账号切换菜单。
- **难度**：**高**（触及会话、缓存命名空间、各 Manager 的账号维度，与 A2 DI 改造强相关）。

---

## 五、建议落地路线（按优先级分批）

1. **先止血（安全）**：A1 发布签名+混淆 → A4 安全库稳定版 → A5 日志脱敏/降级。
2. **补地基（可测试 + DI）**：A3 核心逻辑单测 → A2 引入 Hilt/AppContainer → A9/A10 统一错误模型 + 崩溃兜底。
3. **架构收敛**：A6 Navigation Component → A7 状态管理统一为 StateFlow+UiState → A8 基类脚手架。
4. **性能**：A11 Paging3 → A12/A16 搜索与列表优化。
5. **功能迭代**：按 F1→F3→F2→F4→F5 顺序，F5 依赖第 2、3 步的账号/DI 基础。

> 说明：本报告为只读审查，未改动任何代码。逐行细节问题见 `code-review-2026-08-04.md`；本环境无模拟器/真机，运行时表现仍需真机回归确认。
