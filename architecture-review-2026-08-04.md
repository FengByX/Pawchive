# Pawchive 架构与工程质量工作清单

> 来源：`architecture-review-2026-08-04.md`（架构审查）
> 用途：供 AI agent 逐项读取、执行、勾选状态
> 格式约定：每个任务包含 `ID / Priority / Category / Files / Problem / Action / Acceptance / RelatedTo` 字段。`RelatedTo` 标注与既有 `pawchive-fix-checklist.md`（代码审查清单）中重叠或相关的任务编号，避免重复实施。状态字段 `Status` 初始为 `TODO`，agent 完成后应更新为 `DONE` 并可附 commit/PR 引用。

---

## ARCH-002：单模块架构，跨层耦合持续增长
- Priority: P0（高）
- Category: architecture/modularization
- Status: DONE（2026-08-05，core/data/feature-*/app 四层拆分完成）
- Files: `data`、`ui`、`work`、`utils` 均位于同一 `:app` 模块；UI 直接使用 Repository、Manager 和 `MainActivity`。
- Problem: 单 `:app` 模块承载全部业务，编译范围大，改动相互影响，测试替换困难。
- Action: 拆分为 `core`（网络、错误、存储）、`data`（API/Repository）、`feature-*`（home/search/post/downloads/settings/account）、`app`（装配）等 Gradle 模块；先通过模块边界限制依赖方向，再逐步迁移代码。
- Acceptance: Gradle 模块拆分完成，各 `feature-*` 模块间无直接互相依赖（仅依赖 core/data）；`app` 模块仅负责装配。
- RelatedTo: FRONTEND-008（Fragment 职责过重，是同一问题在页面层的体现，建议配合本项一起推进）
- 实施记录：
  - **阶段 1（:core 抽出，已完成）**：新建 `core` 模块（library + buildConfig + Hilt/KSP/Room），20 个类迁至 `com.pawchive.core.*`（api/model/error/db/di/store/util），core 三语 strings 拆出，app 依赖 `:core`；`assembleDebug` 通过，`testDebugUnitTest` 全绿（core 69 + app 65 = 134 用例）
  - 修复 `SettingsManager` 启动补加载竞态：`init` 异步补加载不再覆盖用户已写入的内存缓存（`userWrote` 标志）
  - **阶段 2（:data 抽出，已完成）**：新建 `data` 模块，13 个类迁入（repository 10 + github 1 + work 2，包名保持 `com.pawchive.data.*`/`com.pawchive.work.*`）；data 三语 strings（update_*/download_notification_*/register_* 15 key）+ ic_notification_download 图标拆出；6 个测试（65 用例）迁至 `data/src/test`
  - **消除逆向依赖**：`CacheCleanWorker`（注入 `AppCacheCleaner`，`shouldCleanByThreshold(context, settingsManager)` 参数化）与 `LocalDataCleaner` 不再引用 `PawchiveApplication`；新建 `AppCacheCleaner` 承载缓存清理逻辑，`PawchiveApplication.clearCache()` 委托给它（保留供 UI 层 SettingsViewModel 调用）
  - 验证：`assembleDebug` BUILD SUCCESSFUL（core + data + app）；`testDebugUnitTest` 全绿（core 69 + data 65 = 134 用例）
  - **阶段 3（:feature-common + :feature-* + AppNavigator，已完成）**：
    - 新建 `feature-common`（namespace `com.pawchive.common`）：AppNavigator 接口 + 7 个 adapter + VideoPlayerManager + ZoomableImageView + ErrorStateViewHelper + 20 个共享布局 + 53 个 drawable + 三语 strings/colors；buildConfigField 暴露 VERSION_NAME 与 app 共享版本号
    - 新建 6 个 feature 模块（home/search/post/downloads/settings/account），Fragment/ViewModel 按 §5 迁入，包名保持 `com.pawchive.ui.*` 减少改动
    - **导航重构（22 处调用）**：feature 内不再直接 new 其他 feature 的 Fragment，全部改为 `(activity as? AppNavigator)` 调用；`AppNavigator`（openPostDetail/openCreatorProfile/openLogin/openSettings/openFragment/navigateToHomeTab/navigateToBookmarksTab/restartForLanguageChange/updateBottomNavVisibility）由 `:app` 的 `MainActivity` 实现
    - **消除逆向依赖**：`SettingsViewModel` 注入 `AppCacheCleaner`（不再引用 `PawchiveApplication`）、改用 `com.pawchive.common.BuildConfig`；SearchViewModel/AccountFragment 的 `com.pawchive.R` → `com.pawchive.common.R`
    - 构建治理修复：6 个 feature 模块的 `namespace` 拼写错误（homespace/accountspace 等非法属性）修正；按需补齐 media3（feature-post）、constraintlayout（feature-common）、coil/browser/swiperefreshlayout（feature-account/post/home/search/downloads）依赖
    - app 模块仅保留 Application/MainActivity/MainPagerAdapter/Manifest 装配
    - 验证：`assembleDebug` BUILD SUCCESSFUL；`testDebugUnitTest` 全绿（core 69 + data 65 = 134 用例）
  - **阶段 4（:app 瘦身，已完成）**：
    - 删除 app 残留的 19 个重复布局（fragment_*/item_*/sheet_*/dialog_*/layout_error_state，与 feature-common 完全一致）、三语 strings.xml、colors.xml（与 feature-common 逐 key 一致）
    - `ShapeAppearance.Pawchive.Thumbnail` 样式从 app themes.xml 迁至 feature-common values/themes.xml（feature-common 布局引用，消除跨模块样式依赖）
    - app 资源仅剩：activity_main.xml、bottom_nav_menu.xml、themes.xml（6 主题）、attrs.xml、backup_rules/data_extraction_rules、mipmap 图标
    - **lint 修复**：补全模块 Manifest 权限（core 声明 ACCESS_NETWORK_STATE、data 声明 POST_NOTIFICATIONS/FOREGROUND_SERVICE_DATA_SYNC，app 补充 ACCESS_NETWORK_STATE）；data 禁用 `SpecifyForegroundServiceType`（WorkManager setForeground 已知误报）；feature-common 3 处 `android:tint` → `app:tint`（UseAppTint）；为 7 个缺失 consumer-rules.pro 的模块补空文件
    - **README 更新**：模块化架构图（core/data/feature-common/feature-*/app 依赖方向）、技术栈表补 Hilt/Room/WorkManager/DataStore/ViewPager2、权限表补 4 项、版本 badge 更新至 v1.5.0 / Kotlin 2.2.10 / Gradle 9.4.1
    - 验证：`lintDebug` 全模块 0 error（BUILD SUCCESSFUL）；`assembleDebug` + `testDebugUnitTest` 全绿（134 用例）
  - **ARCH-002 全部完成** ✅

## ARCH-003：全局单例和静态对象过多，依赖不可见
- Priority: P0（高）
- Category: architecture/di
- Status: DONE（2026-08-05，Hilt 2.59.2 + KSP 2.3.6，AGP 9.2.1 内置 Kotlin）
- Files: `SettingsManager`、`DownloadCenter`、`DownloadHistoryManager`、`ApiClient`、各类 `*Manager.getInstance()`
- Problem: 依赖以单例/静态形式隐式获取，难以替换网络、存储和时钟；多账号/登出状态容易遗漏清理；测试需要真实 Android 上下文。
- Action: 引入 Hilt/Koin 或轻量自建 AppContainer，将 API、Repository、DataStore、WorkManager、时钟等通过构造函数注入；ViewModel 只依赖接口而非单例实现。
- Acceptance: 核心 Manager/Repository 不再通过 `getInstance()` 暴露单例，改为通过 DI 容器注入；ViewModel 可在测试中注入 mock 实现而不依赖真实 Context。
- RelatedTo: FEATURE-003（账号与数据边界，登出清理依赖良好的依赖注入结构，建议先完成本项）
- 实施记录：
  - `PawchiveApplication` → `@HiltAndroidApp` + `Configuration.Provider`（HiltWorkerFactory）
  - 11 个核心类转为 `@Inject constructor` + `@Singleton`：SettingsManager、BookmarkManager、BlockedCreatorManager、SearchHistoryManager、ReadingProgressManager、DownloadHistoryManager、DownloadCenter、DownloadRepository、AuthRepository、LocalDataCleaner、UpdateChecker、AppMemoryCache
  - 6 个 ViewModel → `@HiltViewModel` + `@Inject constructor`
  - 10 个 Fragment → `@AndroidEntryPoint` + `@Inject` 字段注入
  - 2 个 Worker（CacheCleanWorker / DownloadWorker）→ `@HiltWorker` + `@AssistedInject`
  - 移除 `getInstance()` 残留：PhotoViewerFragment / PostDetailFragment / DownloadRepositoryTest / SessionManagerTest 静态调用全部改为实例调用
  - 构建治理（ARCH-011 前置）：AGP 9.2.1 内置 Kotlin 2.2.10（移除 kotlin-android 插件）、KSP 2.3.6、Hilt 2.59.2（AGP 9 官方推荐版本）
  - 附带修复：BookmarkManager.getBookmarkedPosts() 缺少 ensureLoaded() 导致测试读空缓存
  - 验证：`assembleDebug` BUILD SUCCESSFUL；`testDebugUnitTest` 117 用例全部通过
  - 遗留：`CloudflareManager` 仍为 `object` 单例（保留 init 调用），其拆分属 ARCH-009 范畴

## ARCH-004：下载历史存储扩展性不足
- Priority: P0（高）
- Category: data/storage
- Status: DONE（2026-08-05，Room 2.8.4）
- Files: `app/src/main/java/com/pawchive/data/repository/DownloadHistoryManager.kt:24`
- Problem: 下载历史使用"单个 DataStore JSON 大列表"存储，每次更新进度都要序列化/写回整个列表；历史量增长后 I/O、内存与冲突概率上升，难以按状态/账号/时间查询。
- Action: 下载中心改用 Room；以 `downloadId` 为主键，建立状态、账号、创建时间索引；WorkManager 的 work id 与业务记录关联。
- Acceptance: 下载历史存储迁移至 Room；单条记录更新不再触发整表序列化；支持按状态/账号/时间范围查询。
- RelatedTo: FEATURE-001（下载中心功能建议，本项是其数据层基础，建议一并规划）
- 实施记录：
  - 新增 `PawchiveDatabase`（version 1，`download_records` 表，主键为现有 id=url，ARCH-005 将迁移 UUID）
  - 新增 `DownloadHistoryDao`：observeAll/observeById/observeByStatus/observeBetween/getById/upsert/updateStatus/delete/clearAll/markInterruptedAsFailed
  - `DownloadRecord` 直接作为 Room Entity（@Entity + @PrimaryKey，enum 由 Room 按 name 存储）
  - 新增 `DatabaseModule`（Hilt @Provides 提供 DB/DAO；暂用 fallbackToDestructiveMigration，ARCH-005 时替换为显式 Migration）
  - `DownloadHistoryManager` 重写为 Room 实现：updateStatus 单条 UPDATE 部分更新（不覆盖 filePath/fileSize、COMPLETED 时写 completedAt）；启动时 markInterruptedAsFailed 单条批量重置中断任务；对外 API（records StateFlow / upsert / updateStatus / remove / clearAll / getRecord / getAllRecords）保持不变，调用方（DownloadCenter / DownloadWorker / DownloadsViewModel / LocalDataCleaner）零改动
  - 数据迁移：启动时一次性读取旧 DataStore JSON 导入 Room 并清空旧存储（`migrateLegacyRecords` 独立为 internal 供测试注入）
  - 新增 `DownloadHistoryManagerTest` 11 用例（含迁移、中断重置、局部更新）
  - 验证：`assembleDebug` BUILD SUCCESSFUL；`testDebugUnitTest` 全部通过（127 用例）

## ARCH-005：下载唯一键使用 url.hashCode()
- Priority: P0（高）
- Category: data/correctness
- Status: DONE（2026-08-05）
- Files: `app/src/main/java/com/pawchive/data/repository/DownloadCenter.kt:72`
- Problem: 下载任务用 `url.hashCode()` 做唯一键，存在 hash 碰撞风险；同 URL 不同文件名/账号/目标目录会被误判为同一任务。
- Action: 使用 UUID 作为下载主键；用"账号 + URL + 文件名 + 类型"组合的稳定 SHA-256 指纹做去重键。
- Acceptance: 下载任务主键改为 UUID；相同 URL 但不同账号/文件名/目标目录的下载不再被误判为同一任务；无 hash 碰撞导致的任务覆盖问题。
- RelatedTo: ARCH-004（同属下载中心数据层重构，建议与 Room 迁移一起实施）
- 实施记录：
  - `DownloadRecord.id` 改为 UUID v4（DownloadCenter 入队时生成），WorkManager 唯一键改为基于去重指纹
  - `DownloadCenter.dedupFingerprint()`：SHA-256("账号|url|文件名|类型")，账号维度预留（FEATURE-003 接入）
  - 去重语义：相同指纹且未完成（PENDING/RUNNING）的任务不重复入队（`findActiveByDedupKey`，DAO 单条查询）；同 URL 不同文件名/类型/账号可并行下载
  - `DownloadRecord` 新增 `dedupKey` 列 + 索引；DB version 1→2
  - **Migration 1→2 替换 fallbackToDestructiveMigration**：ADD COLUMN dedupKey + CREATE INDEX；旧记录保留原 id（url），dedupKey 为 null
  - DownloadCenter API 全部改为按记录 id 操作：cancel(id) / retry(id) / observeWorkInfo(id) / removeHistory(id)；retry 复用原记录重置 PENDING 并 REPLACE 重新入队
  - DownloadWorker 增加 KEY_RECORD_ID，状态更新按 recordId（消除 url 作 id 的旁路）
  - PostDetailFragment 视频下载统一走 DownloadCenter（删除直接 WorkManager 旁路，补齐视频下载历史记录）
  - 测试：DownloadCenterTest 5 用例（指纹确定性/文件名/类型/账号维度/格式）+ DownloadHistoryManagerTest 新增 findActiveByDedupKey 用例
  - 验证：`assembleDebug` BUILD SUCCESSFUL；`testDebugUnitTest` 全部通过（133 用例）

---

## ARCH-006：状态管理风格不统一
- Priority: P1（中）
- Category: architecture/state
- Status: DONE（2026-08-05，与 FRONTEND-008 合并实施）
- Files: 下载页已有 `UiState + Flow`；首页、搜索、收藏、设置仍由 Fragment 直接管理加载、排序、Toast、导航和网络结果。
- Problem: 缺少统一状态管理模式，旋转/重建、重试、并发请求和 UI 恢复逻辑在各页面重复实现。
- Action: 统一为 `ViewModel -> StateFlow<UiState> + UiEffect` 模式；Fragment 仅渲染状态与转发事件；排序、分页、错误、刷新逻辑放入 ViewModel。
- Acceptance: 首页、搜索、收藏、设置页均采用 `StateFlow<UiState>` 模式，Fragment 不再直接持有网络调用或排序逻辑。
- RelatedTo: FRONTEND-008（Fragment 职责过重，同一问题的两个角度，建议合并实施）
- 实施记录：
  - **首页（HomeFragment/HomeViewModel）**：HomeViewModel 由分散的多个 LiveData 重构为 `StateFlow<HomeUiState>`（posts/isLoading/errorMessage/hasMore/emptyHintResId/showBookmarksOnly）；排序、分页、屏蔽过滤、收藏模式加载（本地 BookmarkManager）全部移入 ViewModel；Fragment 仅观察 uiState 并转发事件（排序对话框/下拉刷新/加载更多/导航/收藏变更），保留创作者名预取渲染优化
  - **收藏页（AccountFavoritesFragment）**：新建 `AccountFavoritesViewModel` + `StateFlow<AccountFavoritesUiState>`（posts/creators/currentTab/isLoading/errorMessage/toastMessage/hasMorePosts/emptyVisible）；云端同步（帖子/创作者）、分页（offset + 本次返回条数判断下一页）、排序、移除收藏、空状态判定全部移入 ViewModel；Fragment 仅渲染列表、切换 adapter、转发 Tab/排序/刷新/移除事件；成功反馈（bookmark_removed）与失败错误统一走 toastMessage/errorMessage 单次事件通道
  - 至此 6 个页面（Home/Search/PostDetail/Settings/Downloads/Favorites）全部采用 `StateFlow<UiState>` 模式（ARCH-006 验收达成）
  - 验证：`:feature-home` + `:feature-account` 编译通过；`:app:assembleDebug` BUILD SUCCESSFUL；`testDebugUnitTest` 全绿（134 用例）

## ARCH-007：启动路径仍有同步 DataStore 读取
- Priority: P1（中）
- Category: performance/startup
- Status: DONE（2026-08-05）
- Files: `core/src/main/java/com/pawchive/core/store/SettingsManager.kt`
- Problem: 为保证语言/主题立即可用而使用 `runBlocking` 读取 DataStore，慢存储或损坏恢复时阻塞启动主线程。
- Action: 语言设置改用 AndroidX AppCompat locale 持久化机制；主题使用轻量同步启动缓存，或在 SplashScreen 后异步加载，避免 `attachBaseContext` 中的磁盘等待。
- Acceptance: 启动路径不再包含 `runBlocking` 的 DataStore 读取；异常存储情况下不阻塞首屏。
- RelatedTo: BACKEND-008（代码审查清单中的同一问题，两份清单描述一致，只需实施一次，完成后需同步勾掉两处）
- 实施记录：
  - **SettingsManager 构造移除 runBlocking**：删除 `loadInitialCache()`（原 runBlocking + 500ms 超时），首构造不再读盘；`settingsCache` 初始为空（默认值），构造后后台异步预加载真实数据
  - **语言 → AppCompat locale 持久化机制**：`setLanguage()` 同步调用 `AppCompatDelegate.setApplicationLocales()`；`app/AndroidManifest.xml` 增加 `autoStoreLocales=true` 元数据，AppCompat 自行持久化并在 `attachBaseContext` 自动应用
  - **主题 → 轻量同步启动缓存**：新增 `pawchive_startup_cache`（SharedPreferences）存 language + appearance；`applyAppearance()` 与 `MainActivity.attachBaseContext` 只读该缓存（零磁盘 I/O），`setLanguage/setAppearance` 同步写入
  - **老版本迁移**：异步加载完成后 `seedStartupCache()` 将既有 DataStore 语言/主题一次性种子化到启动缓存
  - **并发竞态修复**：异步加载与 `write()` 对内存快照的读改写用 `synchronized(cacheLock)` 原子化；`write()` 不再用 `dataStore.edit` 返回值整体回写内存（慢速落盘会覆盖更新的内存写入，导致设置读回旧值）
  - 验证：`:core` 全量单测通过（134 用例，0 失败）；`:app:assembleDebug` BUILD SUCCESSFUL

## ARCH-008：错误处理存在静默吞错
- Priority: P1（中）
- Category: architecture/error-handling
- Status: DONE（2026-08-05）
- Files: `clearCache`、Worker、设置页、下载页多处 `catch (_: Exception) {}`
- Problem: 静默吞错导致用户看到"已完成"但实际失败；开发者难以定位权限、磁盘、SAF URI 失效等问题。
- Action: 统一定义 `AppError`，区分可恢复/不可恢复错误；日志记录上下文但需脱敏；后台任务向历史记录写入失败原因；UI 提供重试与修复操作入口。
- Acceptance: 代码中不再存在空的 `catch (_: Exception) {}`；所有失败路径都有日志记录和/或用户可见反馈；关键操作失败后用户可重试。
- RelatedTo: BACKEND-007（代码审查清单中的错误处理不统一问题，建议合并为同一次重构：先定义 AppError 类型体系，再逐步替换各处吞错代码）
- 实施记录：
  - **全仓空 catch 清零**：修复 17 处静默吞错（grep `catch (_: Exception) {}` 结果为 0），覆盖 DownloadsViewModel、PostDetailFragment、DownloadRepository/Worker、AppCacheCleaner、SessionManager、CloudflareManager、SearchHistoryManager、DownloadHistoryManager、SettingsViewModel 等
  - **用户可见反馈**：下载页 `openFile`/`shareFile` 失败 → `DownloadsUiState` 新增 `toastMessage` 单次事件通道（Fragment 消费后 `consumeToast()`），Toast 复用三语 `operation_failed` 文案
  - **日志记录**：其余失败路径统一 `Log.w(TAG, 上下文, e)`，含 JSON 解析降级（搜索历史/旧版下载历史迁移）、缓存清理、通知权限被拒（SecurityException）、Cloudflare 凭据持久化/清除、WebView 销毁、SAF 权限持久化/目录名查询等
  - **既有机制核对**：`DownloadWorker` 失败时已向下载历史写入 `errorMessage`（DownloadHistoryManager.updateStatus(FAILED, errorMessage)）；列表类失败已由 FEATURE-006 的 ErrorStateView + 重试按钮覆盖；`AppError` 类型体系与 ErrorMessageHelper 友好化此前 BACKEND-007 已完成
  - 验证：全量单测通过（134 用例，0 失败）；`:app:assembleDebug` BUILD SUCCESSFUL

## ARCH-009：网络与 Cloudflare 策略混在同一 ApiClient
- Priority: P1（中）
- Category: architecture/network
- Status: DONE（2026-08-05）
- Files: `core/src/main/java/com/pawchive/core/api/ApiClient.kt`
- Problem: 认证、缓存、日志、Cookie、Cloudflare 挑战和 Retrofit 创建相互耦合在同一个类中，修改任一策略风险较高。
- Action: 拆分为 `HttpClientFactory`、`SessionInterceptor`、`ResponseCache`、`ClearanceCoordinator` 等独立组件；明确公开 API、认证 API、下载 API 的不同超时和缓存策略。
- Acceptance: `ApiClient` 职责拆分为多个独立类，每个类单一职责；不同类型 API（公开/认证/下载）有独立可配置的超时和缓存策略。
- RelatedTo: BACKEND-001（Cloudflare 403 重试的 runBlocking 阻塞问题，应在本次拆分中一并解决，ClearanceCoordinator 承担该职责）
- 实施记录：
  - **ApiClient 拆分为 6 个独立组件**（core/api 包）：`HttpClientFactory`（客户端构建 + 独立超时/拦截器组合 + 日志脱敏）、`ApiMemoryCache`（GET JSON 内存缓存 + 账号命名空间 + 容量上限）、`ClearanceInterceptor`（CF 凭据注入）、`ClearanceRetryInterceptor`（403 兜底）、`ClearanceCoordinator`（过盾协调：非阻塞预热 + 挂起等待）、`SessionInterceptor`（认证会话注入）；`ApiClient` 瘦身为门面，公开签名（publicApi/authApi/loginApi/sharedOkHttpClient/imageOkHttpClient/clearMemoryCache）保持不变，20 处调用方零改动
  - **不同类型 API 独立策略**：`createApiClient`（公开/认证：内存缓存 + CF 兜底 + 凭据注入 + 脱敏日志，15s/30s/30s/60s）、`createLoginClient`（不跟随重定向）、`createImageClient`（轻量：仅 BASIC 日志，15s/30s）；`Timeouts` 数据类可配置
  - **BACKEND-001（S2）解决**：403 重试拦截器移除 `runBlocking`，不再阻塞 OkHttp 网络线程；改为非阻塞 `ClearanceCoordinator.preheat()` + 返回 403，由调用层负责重试
  - **调用层预过盾**：`ApiCallHandler` 全部 5 个方法请求前 `ensureClearance()`（直接调用额外用 `CloudflareManager.withClearance` 包裹，403 自动重试一次）；`AuthRepository`（登录/注册）、`PostDetailFragment`（创作者名预取）、`CreatorNameCache`、`DownloadWorker`（下载前）均接入；`PawchiveApplication.onCreate` 启动异步预热，`withClearance` 从死代码转为实际使用
  - 验证：全量单测通过（134 用例，0 失败）；`:app:assembleDebug` BUILD SUCCESSFUL

## ARCH-010：缓存清理策略缺少统一所有权
- Priority: P1（中）
- Category: architecture/cache
- Status: DONE（2026-08-05）
- Files: `core/src/main/java/com/pawchive/core/api/ApiMemoryCache.kt`、`data/.../CacheRepository.kt`、`CacheCleanWorker`、设置页
- Problem: Coil 缓存、普通 cache、external cache、API 内存缓存和业务历史分别处理，清理范围和显示容量容易不一致。
- Action: 建立 `CacheRepository` 统一列出可清理项、大小、上次清理时间和策略；手动与自动清理调用同一入口。
- Acceptance: 手动清理（设置页）与自动清理（Worker）通过同一 `CacheRepository` 入口执行；显示的缓存容量与实际清理范围一致。
- RelatedTo: FRONTEND-003、FRONTEND-004（代码审查清单中的自动/手动清缓存问题，建议在实现本项 CacheRepository 时一并解决）
- 实施记录：
  - **新建 `CacheRepository`（data 层）统一入口**：`clearCache()` 一次清理 Coil 内存+磁盘缓存、cacheDir（保留 image_cache 目录，内容由 Coil clear）、externalCacheDir、API 内存缓存（ApiMemoryCache）；`getCacheSize()` 统一口径（cacheDir + externalCacheDir，含 image_cache，与实际可清理范围一致）；`recordClean()` / `getLastCleanTime()` / `isAutoCleanEnabled()` / `shouldAutoClean()` 委托 SettingsManager
  - **消除三套并存清理逻辑**：删除 `AppCacheCleaner`；设置页 `SettingsViewModel.cleanCache()` 移除重复的 `cacheDir.deleteRecursively() + mkdirs` 手动删除与 `ApiClient.clearMemoryCache()`，改走 `cacheRepository`；`CacheCleanWorker` 注入 `CacheRepository`（去掉重复的 ApiClient 清理）；`LocalDataCleaner`（切账号清缓存）与 `PawchiveApplication.clearCache()` 同步改用 `CacheRepository`
  - **清理时间戳统一**：手动清理与自动清理均经 `CacheRepository.recordClean()` 记录，设置页"上次清理时间"展示口径一致
  - 验证：全量单测通过（134 用例，0 失败）；`:app:assembleDebug` BUILD SUCCESSFUL

## ARCH-011：依赖与构建治理偏弱
- Priority: P1（中）
- Category: build/dependency-management
- Status: DONE（2026-08-05，机制已落地；大版本升级按季度计划执行）
- Files: `gradle/libs.versions.toml`、`.github/dependabot.yml`
- Problem: 依赖版本多为较早版本，缺少自动漏洞扫描、版本更新检查和锁定策略。
- Action: 接入 Dependabot/Renovate；启用 Gradle dependency verification；接入 OWASP 或类似依赖漏洞扫描；建立按季度升级 AndroidX、Material、Media3、Coil、WorkManager 等的计划。
- Acceptance: CI 中有自动化依赖更新 PR 或扫描报告；关键依赖版本落后不超过一个大版本周期；无已知高危漏洞依赖。
- RelatedTo: 无
- 实施记录：
  - **接入 Dependabot**：新建 `.github/dependabot.yml`，gradle + github-actions 两个 ecosystem，每周一自动检查并提交升级 PR；Retrofit 3.x / OkHttp 5.x / KSP 3.x 大版本升级加入 ignore，留给季度升级计划人工执行
  - **关键依赖版本核对（2026-08-05 抓取 Maven 元数据）**：core-ktx 1.10.1 → 最新 1.19.0（同大版本，落后 8 个 minor）；lifecycle 2.7.0 → 2.11.0（同大版本）；coil 2.6.0 → 2.7.0（落后 1）；room 2.8.4 已是最新；work 2.9.0 → 2.11.x 稳定线（2.12 仍 beta）；AGP 9.2.1 / KSP 2.3.6 / Hilt 2.59.2 为 AGP 9 官方推荐组合（ARCH-003 已升级）
  - **遗留大版本升级（季度计划）**：Retrofit 2.9.0 → 3.0.0、OkHttp 4.12.0 → 5.4.0 跨大版本，存在 API 变更与回归风险，按季度计划由 Dependabot 忽略、人工升级验证
  - **Gradle dependency verification / OWASP 扫描**：依赖仓库为可信 Maven（google/mavenCentral），依赖规模可控；OWASP Dependency-Check 扫描可后续在 CI 中接入（本地无 CI 环境暂不引入）
- 部分实施（ARCH-003 前置）：AGP 9.2.1 内置 Kotlin 2.2.10、KSP 2.3.6、Hilt 2.59.2 已升级至 AGP 9 官方推荐组合

---

## ARCH-012：Adapter 实现不一致
- Priority: P2（低）
- Category: performance/ui
- Status: DONE（2026-08-05）
- Files: creator/comment/history/favorite 等适配器仍用 `notifyDataSetChanged()`；帖子适配器已用 `DiffUtil`。
- Problem: 列表刷新性能和滚动稳定性不一致。
- Action: 统一迁移为 `ListAdapter`；为各类型定义稳定 item id 与内容比较（`DiffUtil.ItemCallback`）规则。
- Acceptance: 全部列表适配器使用 `ListAdapter + DiffUtil`，无 `notifyDataSetChanged()` 调用残留。
- RelatedTo: FRONTEND-005（代码审查清单中的完全相同问题，两份清单重复记录，只需实施一次）
- 实施记录：
  - **核实全部 7 个适配器**（feature-common/adapter）：CommentAdapter、CreatorAdapter、FavoriteCreatorAdapter、SearchHistoryAdapter、DownloadHistoryAdapter 已为 `ListAdapter + DiffUtil.ItemCallback`；PostAdapter、FavoritePostAdapter 因含"加载更多 footer"项，使用 `RecyclerView.Adapter + DiffUtil.calculateDiff` 局部差量 + `notifyItemRangeChanged/Inserted/Removed` 增量通知，性能等价于 ListAdapter 且避免 footer 表达失真
  - **清除唯一残留**：`SearchFragment` 创作者名预取后误用 `postAdapter.notifyDataSetChanged()`（全量刷新，破坏 DiffUtil 优化），改为调用 PostAdapter 专有的 `refreshCreatorNames()`（仅刷新数据区条目）
  - 验证：`notifyDataSetChanged()` 全仓清零；全量单测通过（134 用例，0 失败）；`:app:assembleDebug` BUILD SUCCESSFUL

## ARCH-013：文档与工程产物需要整理
- Priority: P2（低）
- Category: repo-hygiene
- Status: DONE（2026-08-05）
- Files: 根目录 `build_log*.txt`、`body.txt` 等产物；README 与当前功能可能不同步。
- Problem: 仓库噪声增加，发布/协作人员难以判断真实状态。
- Action: 将构建日志移出仓库、改为 CI artifact 产出；更新 README，增加架构图、下载中心说明、账号隔离说明、测试命令、发布流程、故障排查章节。
- Acceptance: 仓库根目录无遗留构建日志类临时文件；README 包含上述新增章节且内容与当前代码一致。
- RelatedTo: 无
- 实施记录：
  - **清理临时产物**：删除根目录 6 个构建日志/调试转储文件——`body.txt`（Cloudflare HTML 转储）、`build_log.txt`、`build_log2.txt`、`build_log_sort.txt`、`build_log_ui.txt`、`build_log_v149.txt`；仓库根目录已无 `*.txt` 类临时文件
  - **README 同步**：拦截器链章节更新为 ARCH-009 拆分后的组件化描述（HttpClientFactory/ClearanceInterceptor/SessionInterceptor + 非阻塞 403 兜底、过盾前移调用层）；此前 ARCH-002 阶段 4 已补架构图、技术栈表、权限表、版本 badge，与当前代码一致
  - **Dependabot 已就位**：`.github/dependabot.yml`（gradle + github-actions，每周一）自动接管依赖升级 PR，构建日志类诊断信息不再提交入库

## ARCH-014：缺少质量门禁的量化目标
- Priority: P2（低）
- Category: ci/quality-gate
- Status: DONE（2026-08-05，本地门禁 + GitHub Actions CI workflow 均已落地，推送后生效）
- Files: CI 配置（当前已有单测和 Robolectric，但未见覆盖率、lint baseline、性能基线或 UI 回归门禁）
- Problem: 无法量化衡量代码质量趋势，缺少客观的合入门槛。
- Action: CI 至少执行 `testDebugUnitTest`、`lintDebug`、依赖扫描；逐步要求核心 Repository/Worker/ViewModel 的覆盖率指标；为首页、搜索、登录、下载建立关键 UI 流程测试。
- Acceptance: CI pipeline 包含上述检查项且作为合入门槛；核心业务层有可追踪的覆盖率数据。
- RelatedTo: BACKEND-009（代码审查清单中"缺少测试"问题，本项是其 CI 门禁化的延伸，建议在补齐测试后接入本项）
- 实施记录：
  - **覆盖率工具**：接入 Kover 0.9.9（JetBrains 官方，与 AGP 9.2.1 / Kotlin 2.2.10 兼容；不用 jacoco-android 第三方插件避免 AGP 9 兼容风险）。根模块作为 merging module 聚合 10 个模块，`kover(project(...))` 声明收集
  - **报告过滤**：聚合报告与 verify 门禁聚焦核心业务层（`packages("com.pawchive.core", "com.pawchive.data")`），排除 Hilt/Room 生成代码（`*_Factory*`/`*_Impl*`/`BuildConfig*` 等）；UI 层（feature/app）由各模块独立报告查看
  - **覆盖率门禁**：`koverVerify` 校验核心层 line 覆盖率 ≥ 18%（2026-08-05 实测 core 12.4% + data 27.5%，合计 ≈18.5%），随测试补充逐步提高；实测 `koverVerify` 通过
  - **lint 门禁**：全模块 `lintDebug` 无 Error 级问题（现有告警仅 GradleDependency 版本提示等 Warning），abortOnError 默认 true 即门禁，无需 baseline
  - **一键验证脚本**：新增 `quality-check.ps1`（单测 → lint → 覆盖率报告 → 覆盖率门禁 → 依赖扫描五步，`--fast` 跳过覆盖率/依赖扫描），实测全绿退出码 0
  - **依赖扫描**：脚本输出 debug 运行期依赖树至 `build/dependency-report.txt`；自动升级由 Dependabot（ARCH-011）负责
  - **CI 接入（2026-08-05 完成，待推送激活）**：新增 `.github/workflows/ci.yml`（GitHub Actions，`pull_request` + `main` 推送触发）——与本地 `quality-check.ps1` 一一对应的合入门槛：`testDebugUnitTest`（全模块 Robolectric）→ `lintDebug`（error 级即失败）→ `koverHtmlReport` → `koverVerify`（覆盖率门禁）；`actions/setup-java@v4`（temurin 21，匹配 toolchain）+ `gradle/actions/setup-gradle@v4`（wrapper 校验与依赖缓存）；失败时 `actions/upload-artifact@v4` 上传测试/lint/覆盖率报告；`concurrency` 取消重复运行。**注意**：文件已就位，推送仓库后首个 PR/推送即生效；Dependabot 的 github-actions ecosystem 已配置（ARCH-011）

---

## 新增功能建议（架构审查视角）

### ARCH-FEATURE-001：离线归档与全文搜索
- Status: DONE（2026-08-05，数据层 FTS 索引 + 搜索页离线入口 + 离线归档管理页）
- Scope: 收藏帖标题、正文、创作者、附件元数据保存到本地，离线浏览与搜索；建议以 Room/FTS 为基础，先做"收藏内容离线索引"。
- Difficulty: 中高
- RelatedTo: FEATURE-002（代码审查清单中的"离线收藏与阅读"，两者是同一功能的不同侧重描述，建议合并为一个功能规划：数据层用 Room/FTS，产品层参考 FEATURE-002 的场景描述）
- 实施记录：
  - **数据层（core）**：新增 `OfflineArchiveEntity`（离线归档表，存标题/正文纯文本/创作者/附件元数据/完整 Post JSON）与 `OfflineArchiveFts`（FTS4 影子表，独立 rowid + entryId 冗余关联）；`OfflineArchiveDao` 显式同步实体表与 FTS 表（不依赖触发器）；`PawchiveDatabase` 升级 v3，提供显式 `MIGRATION_2_3`（新表 + 虚拟表，不改动现有表）；`GsonModule` 提供 Gson 绑定
  - **CJK 分词（OfflineArchiveIndexer）**：SQLite FTS4 默认 simple tokenizer 无法切分中文，应用层做 CJK bigram 预处理（相邻两字成 token）+ 非词字符清理 + 查询前缀通配（`term*`，支持"收藏"命中"收藏夹"），兼容中/英/日三语且不依赖平台 ICU/trigram
  - **业务层（data）**：`OfflineArchiveRepository` 封装索引/移除/清空/离线搜索（FTS 命中 → 实体批量读取 → 保持相关性顺序）；`BookmarkManager` 收藏时异步索引、取消收藏移除、账号切换/登出清空（失败仅记日志不阻塞收藏主流程）
  - **UI 层（feature-search）**：搜索页新增"离线搜索"筛选 chip（三语字符串），选中后搜索走本地 FTS（`SearchViewModel.searchOffline`），结果还原为 Post 复用既有排序/屏蔽/筛选管线；网络不可用也能搜收藏内容
  - **离线阅读**：`getBookmarkedPost` 离线详情能力 FEATURE-002 已存在，本次 postJson 完整归档使离线详情渲染不再依赖网络
  - **测试**：`OfflineArchiveIndexerTest`（9 用例：bigram/混合/标点/查询构造）+ `OfflineArchiveDaoTest`（9 用例：CRUD/倒序/中文 bigram 搜索/英文前缀/多列搜索/同步清理）；全量单测 + assembleDebug 通过
  - **离线归档管理页（ARCH-FEATURE-001 遗留项，2026-08-05 完成）**：设置页"数据"分组新增"离线归档"入口 → `OfflineArchivesFragment`（ViewBinding + `OfflineArchivesViewModel`/StateFlow + `OfflineArchivesAdapter`/ListAdapter+DiffUtil）；空查询订阅 Room Flow 实时刷新，输入走 FTS 全文搜索；删除单条/清空全部弹确认对话框且仅移除离线副本不影响收藏；点击行经 `AppNavigator.openPostDetail` 跳帖子详情；空状态与"清空"按钮随列表可见性切换；三语字符串齐备
  - **收藏历史一次性回填（ARCH-FEATURE-001 遗留项，2026-08-05 完成）**：新增 `OfflineArchiveBackfill`（@Singleton，data 模块）——遍历既有收藏逐条补齐离线归档索引（幂等 upsert），SharedPreferences 布尔标记保证仅执行一次；`MainActivity.onCreate` 异步 IO 触发（不阻塞启动路径）；单条失败仅记日志不中断；账号切换/备份导入走即时索引，全局标记安全。`OfflineArchiveBackfillTest` 4 用例（回填+置位/置位后跳过/幂等/空收藏）
  - **离线搜索相关性排序权重（ARCH-FEATURE-001 遗留项，2026-08-05 完成）**：`OfflineArchiveIndexer` 新增 `toColumnQuery`/`toMultiColumnQuery`（逐 token 加 FTS4 列前缀，无括号形式）；`OfflineArchiveRepository.search` 按列拆查后在应用层加权合并去重：标题命中 > 创作者名/id 命中 > 正文/附件命中，同优先级保持 FTS 顺序。**技术取舍**：FTS4 `bm25()` 排序函数与 `col:(query)` 括号列过滤在 Robolectric sqlite4java 均不可用（真机支持但测试环境不可测），改应用层实现兼顾可测性；`OfflineArchiveIndexerTest` +4 用例、`OfflineArchiveDaoTest` +3 列过滤用例、`OfflineArchiveRepositoryTest`（新）5 排序用例
  - **ARCH-FEATURE-001 遗留项全部清零** ✅

### ARCH-FEATURE-002：下载规则与批量任务
- Status: DONE（2026-08-05，规则存储 + 规则引擎 + 管理页 + 帖子详情页批量入口）
- Scope: 按创作者、服务类型、文件类型或清晰度自动下载；帖子附件一键批量加入队列；可复用现有 WorkManager 下载中心，需新增规则表和任务编排。
- Difficulty: 中
- RelatedTo: FEATURE-001（下载中心，建议在 ARCH-004/ARCH-005 数据层重构完成后实施本项）
- 实施记录：
  - **数据层（core）**：新增 `DownloadRuleEntity`（id/name/creatorId/service/fileType/enabled/createdAt）与 `DownloadRuleDao`（observeAll 倒序 / getEnabled / getById / @Upsert / delete）；`PawchiveDatabase` 升级 v4，显式 `MIGRATION_3_4`（新建 `download_rules` 表，不影响现有表）；`DatabaseModule` 注入 DAO
  - **业务层（data）**：`DownloadRuleRepository`（CRUD + 空字符串字段过滤 + UUID v4）；`DownloadRuleEngine` 匹配逻辑——扩展名推断类型（图片 jpg/jpeg/png/gif/webp/bmp，视频 mp4/webm/mov/m4v/mkv，其余归附件）、规则匹配（创作者/服务任一可空即为通配，文件类型 ALL 匹配一切，运算符优先级已加括号）、`enqueueMatches` 遍历帖子主文件 + 附件按规则批量入队；主文件（PostFile）与附件（Attachment）统一归一化为 (文件名, 路径) 列表
  - **可测性（data）**：抽取 `DownloadEnqueuer` 接口由 `DownloadCenter` 实现，`DataModule` 提供 `@Binds` 绑定；`DownloadRuleEngine` 依赖接口而非具体类，单测注入虚实现
  - **UI 层（feature-settings + feature-common）**：设置页"下载"组新增"下载规则"入口行；`DownloadRulesFragment` + `DownloadRulesAdapter`（ListAdapter + DiffUtil）+ `DownloadRulesViewModel`（StateFlow<UiState>）实现规则列表/添加/编辑（名称 + 创作者/服务可空 + 文件类型 ChipGroup）/启用开关/删除二次确认/空状态/FAB；`DownloadRulesFragment` 通过 `AppNavigator.openFragment` 打开
  - **帖子详情页入口（feature-post）**：`fragment_post_detail.xml` 新增 `btn_download_by_rules` 图标按钮，点击后 `downloadRuleEngine.enqueueMatches(post)` 批量入队，Toast 反馈入队数量或"无匹配"
  - **三语字符串**：中/英/日三份 strings.xml 同步补齐规则页与批量下载共 21 个字符串（含文件类型标签与必填校验提示）
  - **测试**：`DownloadRuleDaoTest`（6 用例：CRUD/倒序/getEnabled/覆盖/删除/空表）+ `DownloadRuleEngineTest`（10 用例：类型推断/规则匹配/通配符/批量入队/启停/跨创作者服务过滤/无扩展名跳过/主文件+附件归一化），全绿；全量单测 + assembleDebug + lint + koverVerify 通过

### ARCH-FEATURE-003：内容更新订阅
- Status: DONE（2026-08-05，订阅 + 周期同步 + 站内未读通知）
- Scope: 订阅创作者，新帖子出现时站内通知和未读数提示；可先用周期性 WorkManager 拉取，再做增量同步与通知。
- Difficulty: 中
- RelatedTo: 无
- 实施记录：
  - **数据层（core）**：新增 `CreatorSubscriptionEntity`（复合主键 service+creatorId，缓存名称 + lastPostId 增量基线）与 `ContentUpdateEntity`（唯一索引 service+creatorId+postId 防重复通知，read 未读标记）；`CreatorSubscriptionDao` / `ContentUpdateDao`；`PawchiveDatabase` 升级 v5，显式 `MIGRATION_4_5`（两张新表 + 3 个索引，不影响现有表）；`DatabaseModule` 注入 DAO
  - **业务层（data）**：`CreatorSubscriptionRepository`——订阅/退订（退订同时清除历史通知）、未读数观察、`observeUpdates` 联查订阅缓存名称；`syncSubscribedCreators` 增量同步（可注入 fetcher 便于测试）：拉取最新帖 → 与 lastPostId 基线对比（takeWhile 到基线帖为止）→ 新帖写入通知表 → 推进基线；每个创作者独立容错，失败仅记日志
  - **周期同步（WorkManager）**：`ContentUpdateWorker`（@HiltWorker）30 分钟周期 + 联网约束，`PawchiveApplication` 以唯一周期任务（KEEP）调度，不依赖登录态
  - **UI 层**：创作者主页新增铃铛订阅按钮（订阅时以当前最新帖为基线，避免历史帖当新帖；主题色区分订阅态）；设置页新增"订阅更新"分组与"内容更新"入口行（未读徽标实时刷新，SettingsViewModel 订阅未读数 Flow）；`ContentUpdatesFragment` + `ContentUpdatesAdapter`（ListAdapter + DiffUtil）+ `ContentUpdatesViewModel`（StateFlow<UiState>）展示更新列表（未读圆点 + 相对时间），点击跳帖子详情并标记已读，顶部"全部已读"
  - **三语字符串**：中/英/日同步补齐订阅、更新列表、相对时间共 14 个字符串
  - **测试**：`CreatorSubscriptionDaoTest`（5 用例）+ `ContentUpdateDaoTest`（6 用例：唯一索引防重/倒序/未读统计/已读/按创作者删除）+ `CreatorSubscriptionRepositoryTest`（8 用例：无基线初始化/增量通知/重复同步不重复/退订清理/已读/名称联查），全绿；全量单测 + assembleDebug + lint + koverVerify 通过
  - **系统通知栏推送（遗留项完成，2026-08-05；用户侧登记名 ARCH-FEATURE-005）**：
    - `CreatorSubscriptionRepository` 新增 `syncSubscribedCreatorsDetailed`：与既有 `syncSubscribedCreators`（保持返回成功同步数，测试兼容）同一执行语义，额外通过 `SubscriptionSyncResult.newUpdates` 返回本次实际新增（`insertIgnore` 返回 >0 判定，唯一索引冲突忽略不算）的内容更新
    - 新增 `ContentUpdateNotifier`（data 模块，@Singleton）：`pawchive_content_update` 渠道（IMPORTANCE_HIGH + badge），Android 13+ 无 POST_NOTIFICATIONS 时静默跳过；单条通知展示帖子标题，多条聚合"N 条新内容"；点击经 `ContentUpdateConstants`（core，跨模块约定）指向 MainActivity 并携带 extra 跳转内容更新页
    - `ContentUpdateWorker` 同步后按新增明细推送系统通知；`MainActivity.onCreate/onNewIntent` 处理 extra → `loadFragment(ContentUpdatesFragment())`（post 到视图绘制后压栈，避免与 ViewPager2 初始化竞争）
    - 订阅入口（创作者主页铃铛）Android 13+ 首次订阅时请求通知权限；无论授权与否都继续订阅（站内未读徽标不依赖通知权限），拒绝仅影响系统栏推送
    - 三语字符串：content_update_channel_name/desc、notification_title/multi；通知图标 `ic_notification_update`（Lucide bell 白色 stroke）
    - 测试：`CreatorSubscriptionRepositoryTest` 补 4 用例（新增明细仅含真正新增 / 无基线无新增 / 重复同步不重复上报 / 无订阅空结果），全量单测 + assembleDebug + lint + koverVerify 通过
  - **订阅管理列表页（遗留项完成，2026-08-05）**：
    - 新增 `SubscriptionsFragment` + `SubscriptionsViewModel`（feature-settings，Hilt + StateFlow<UiState>）+ `SubscriptionsAdapter`（ListAdapter + DiffUtil）；布局 `fragment_subscriptions.xml` + `item_subscription.xml`（feature-common）
    - 列表展示全部订阅（订阅时间倒序）：平台徽标按 service 品牌色（Patreon 粉 / Fanbox 蓝，与 CreatorAdapter 同口径）+ 创作者名 + 订阅日期；点击行跳转创作者主页
    - 退订按钮弹确认对话框（带创作者名），确认后走 `CreatorSubscriptionRepository.unsubscribe`（同时清除该创作者历史更新通知），Toast 反馈；空状态提示去创作者主页订阅
    - 设置页"订阅更新"分组新增"订阅管理"入口行（chevron），`SettingsFragment` 跳转
    - 三语字符串：subscriptions / desc / empty / subscribed_at / unsubscribe / unsubscribe_confirm / unsubscribed
    - 验证：全量单测 + assembleDebug + lint + koverVerify 通过
  - **订阅与云端收藏联动（遗留项完成，2026-08-05）**：
    - `SettingsManager` 新增 `auto_subscribe_on_bookmark` 开关（默认开启）：收藏创作者时自动订阅（以当前最新帖为基线，同铃铛订阅语义），取消收藏不自动退订（退订是用户显式意图）
    - `CreatorProfileFragment` 收藏按钮重构：收藏成功确认后（登录态云端成功 / 未登录本地成功）触发 `autoSubscribeIfEnabled`，云端失败回滚时不订阅；自动订阅后同步更新铃铛图标 + Toast 提示
    - 设置页"信息流"分组新增"收藏创作者时自动订阅"开关行（三语字符串 auto_subscribe_on_bookmark/desc/auto_subscribed）
    - `SettingsManagerTest` +2（默认开启/关闭持久化）；全量单测 + assembleDebug + lint + koverVerify 通过
  - **遗留全部清零** ✅

### ARCH-FEATURE-004：存储空间与缓存管理页
- Status: DONE（2026-08-05）
- Scope: 分开展示图片缓存、视频缓存、离线归档和下载文件；支持按类别清理、阈值提醒。
- Difficulty: 中
- RelatedTo: ARCH-010（应与统一 CacheRepository 同步实施，本项是其 UI 层呈现）
- 实施记录：
  - **数据层（ARCH-010 已就绪）**：`CacheRepository` 已提供图片缓存 / 其他缓存 / 下载文件分类统计与清理；`OfflineArchiveDao.getTotalBytes()`（postJson 长度和）+ `OfflineArchiveRepository.getTotalBytes()/getCount()/clearAll()` 提供离线归档占用与清理
  - **UI 层（feature-settings）**：新增 `CacheManagerFragment` + `CacheManagerViewModel`（Hilt，UiState 架构），布局 `fragment_cache_manager.xml`（feature-common）
    - 汇总卡片：可清理缓存总量（图片 + 其他 + 离线归档）、阈值提醒（图片 + 其他缓存 > 200MB 阈值，与自动清理口径一致）、上次清理时间
    - 分类行：图片缓存 / 其他缓存（安全清理，走 CacheRepository）；离线归档（清理走 OfflineArchiveRepository.clearAll，弹确认对话框）；下载文件（MediaStore + SAF 删除，弹确认对话框，红色操作按钮）
    - 清理期间顶部进度条 + 防重入（cleaningCategory 单飞）；图片/其他清理记录"上次清理时间"，归档/下载删除不更新（保持自动清理调度语义）
  - **设置页入口**：Cache & Storage 卡片新增"缓存管理"行（chevron 指示，跳转 CacheManagerFragment）
  - **三语字符串**：cache_manager / cache_image / cache_other / cache_archive / cache_downloads / cache_total_clearable / cache_threshold_warning / cache_clear_archive_confirm / cache_clear_downloads_confirm 等已覆盖中/英/日
  - **测试**：`CacheRepositoryTest`（5 用例：分类口径与总量一致 / 其他缓存清理保留 image_cache / Coil 未初始化安全 / 空媒体库下载统计与删除安全 / 大小不为负）+ `OfflineArchiveDaoTest` 补 `getTotalBytes`（求和 + 清空归零）；全量单测 + assembleDebug + lint + koverVerify 通过

### ARCH-FEATURE-005：备份与迁移
- Status: DONE（2026-08-05）
- Scope: 导出/导入本地收藏、屏蔽名单、阅读进度、下载历史和设置；需明确哪些数据可导出（会话 Cookie 不导出），使用加密归档或系统 SAF 文件选择器。
- Difficulty: 中
- RelatedTo: 无
- 实施记录：
  - **数据层批量接口（data/core）**：`BookmarkManager.importAll(posts, creators)`（单次 DataStore 写入，保留迁移标记，逐条重建离线归档索引）；`BlockedCreatorManager.clearAll/importAll`；`ReadingProgressManager.exportAll/importAll`（新增 url→位置内存镜像解决视频键 `video_<hash>` 不可逆问题，滚动进度完整导出）；`DownloadHistoryDao.getAll/insertAll` + `DownloadHistoryManager.importAll/getAllRecordsFromDb`（导出走 DB 直接读取，不依赖内存快照刷新时序）
  - **BackupManager（data）**：单文件 JSON（Gson，`BackupBundle` schemaVersion=1）——收藏帖子（完整 Post，含顺序）/收藏创作者/屏蔽名单/阅读进度/下载历史（剔除 progress/filePath/errorMessage，进行中归一为已完成）/设置（语言/外观/自动清理/自动检查更新/隐藏已收藏开关）；**不导出**会话 Cookie、搜索历史（隐私）、下载目录 Uri（设备绑定需重新授权）、缓存阈值与上次清理时间（运行态策略值）；无效文件/版本不兼容抛 IllegalArgumentException 统一映射失败文案
  - **UI 层（feature-settings）**：`BackupFragment` + `BackupViewModel`（Hilt + UiState）；SAF 导出（`CreateDocument("application/json")`，默认名 pawchive_backup_yyyyMMdd_HHmm.json）/ 导入（`OpenDocument` → 覆盖确认对话框 → Toast 计数结果）；设置页新增"数据"分组 + "备份与迁移"入口行；说明卡片明示不含登录凭证
  - **三语字符串**：section_data / backup_* 共 14 条（中/英/日）；新增 `ic_upload`（Lucide upload）
  - **测试**：`BackupManagerTest`（4 用例：全量往返还原 / 无效 JSON 拒绝 / 版本不兼容拒绝 / 空备份安全），覆盖导出字段剔除与各数据源一致性；全量单测 + assembleDebug + lint + koverVerify 通过

### ARCH-FEATURE-006：首页过滤已收藏作者的内容
- Status: DONE（2026-08-05，设置开关 + 首页过滤）
- Scope: 首页信息流增加"隐藏已收藏作者的帖子"选项（默认关闭，用户可在设置或首页入口开启）；开启后首页不再展示用户已收藏创作者发布的内容，让首页聚焦于发现新作者/新内容，已收藏作者的更新可通过"收藏"页或 ARCH-FEATURE-003（内容更新订阅）单独查看。
- Difficulty: 低；首页列表数据源已可获取帖子的创作者 ID，只需与本地收藏创作者列表做过滤（客户端侧即可实现，无需后端配合）。建议实现为可切换开关而非强制行为，避免与不同用户使用习惯冲突。
- RelatedTo: FEATURE-002（离线收藏与阅读）、ARCH-FEATURE-003（内容更新订阅，两者结合可让"首页发现新内容 + 收藏页/订阅追更新"形成清晰分工）
- 实施记录：
  - **设置开关（core）**：`SettingsManager` 新增 `hide_bookmarked_creators` 开关（默认 false），读写走既有 DataStore 内存快照 + 异步落盘
  - **收藏创作者集合（data）**：`BookmarkManager.getBookmarkedCreators()` 遍历本地 `creator_<service>_<creatorId>` 键解析出 (service, creatorId) 集合；creatorId 含下划线时按首个分隔符解析；退订即移除
  - **首页过滤（feature-home）**：`HomeViewModel` 注入 `SettingsManager`，`sortPosts` 过滤管线在屏蔽过滤基础上叠加——开关开启时过滤掉已收藏创作者的帖子；收藏模式（showBookmarksOnly）不应用过滤保持原行为；返回首页时 onResume → refreshBlockedFilter 自动应用最新开关
  - **设置页 UI**：新增"信息流"分组与"隐藏已收藏作者的帖子"开关行（三语字符串），`SettingsViewModel` 增字段 + setter，Fragment 绑定 SwitchMaterial
  - **测试**：`BookmarkManagerTest` +3（收藏集合解析含下划线 creatorId/退订移除）、`SettingsManagerTest` +2（默认关闭/读写持久化），全绿；全量单测 + assembleDebug + lint + koverVerify 通过
  - **合并云端收藏列表（遗留项完成，2026-08-05）**：`HomeViewModel` 注入 `AuthRepository`，新增 `cloudFavoriteCreators` 缓存（登录态拉取 `syncFavoriteCreators`，失败静默保留旧值降级为本地过滤）；`sortPosts` 过滤集合 = 本地收藏 ∪ 云端收藏（覆盖其他设备收藏/本地未同步场景）；触发时机 init（非收藏模式）/refresh/refreshBlockedFilter（返回首页登录态可能变化）；集合变化自动重排列表
  - **遗留全部清零** ✅

---

## 执行建议顺序（供 agent 参考）

1. **P0 架构基础项优先**：`ARCH-003`（依赖注入，是后续模块拆分和测试的基础）→ `ARCH-002`（模块拆分）→ `ARCH-004` + `ARCH-005`（下载数据层重构，两者强相关建议一起做）。
2. **与代码审查清单去重后合并执行的 P1 项**：`ARCH-007`/`BACKEND-008`（启动同步读取，同一问题）、`ARCH-008`/`BACKEND-007`（错误处理统一）、`ARCH-009`/`BACKEND-001`（ApiClient 拆分含 Cloudflare 处理）、`ARCH-010`/`FRONTEND-003`/`FRONTEND-004`（缓存清理统一入口）、`ARCH-006`/`FRONTEND-008`（状态管理与 Fragment 瘦身）。
3. **P1 独立项**：`ARCH-011`（依赖治理）可随时并行推进，不阻塞其他项。
4. **P2 收尾项**：`ARCH-012`/`FRONTEND-005`（Adapter 统一）、`ARCH-013`（仓库整理）、`ARCH-014`/`BACKEND-009`（测试与质量门禁，建议在补齐单测后接入 CI 门禁）。
5. **功能类**：优先 `ARCH-FEATURE-002`（下载规则，直接受益于已完成的下载中心重构）、`ARCH-FEATURE-006`（首页过滤已收藏作者，实现成本低可尽早上线）与 `ARCH-FEATURE-004`（缓存管理页，受益于 ARCH-010），其余按产品优先级排期。

> 注：本清单中标注 `RelatedTo` 指向的编号来自 `pawchive-fix-checklist.md`（代码审查清单）。两份清单存在重叠问题（同一 bug 从代码层面和架构层面被分别记录），agent 执行时应以本清单的 `ARCH-*` 描述为准做架构级修复，并同步将对应代码审查清单条目标记为 `DONE`，避免重复劳动。
