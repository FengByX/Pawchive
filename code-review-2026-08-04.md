# Pawchive 客户端代码审查报告（v1.4.9）

> 范围：Android 客户端（`app/src/main`），项目无自建后端，第三方 API（pawchive.pw / file.pawchive.pw / img.pawchive.pw）+ Cloudflare 为事实上的"后端"。
> 方法：静态阅读全部 42 个 Kotlin 文件、18 个布局、三语 strings、构建脚本；广度探索 + 反模式检索 + 关键路径逐一核实。所有标注"已核实"的发现均可在源码中定位。
> 说明：前几轮已修复的 P0（缓存跨用户泄露）、P1（403 关闭流 / 多 Manager 的 runBlocking+GlobalScope / logout 清缓存）、Cloudflare 单飞、BookmarkManager 单例、UI 触控区等**不在本报告重复列出**，本报告聚焦尚未处理或新发现的问题。

---

## 一、后端 / 数据与网络层

### 🔴 安全

**S1. Release 使用 debug 签名且未混淆（P0）**
- 位置：`app/build.gradle.kts:25-30`（`buildTypes.release`）
- 现象：`isMinifyEnabled = false`、`optimization.enable = false`、`signingConfig = signingConfigs.getByName("debug")`。
- 影响：用公开 debug 密钥签署正式包，任何持有该密钥的人都可替换/伪造应用；未启用 R8，接口路径、实现细节、Cookie 处理逻辑完全暴露。
- 建议：为发布建立独立 `release` 签名配置（keystore 放 CI/环境变量，不入库），至少开启 `isMinifyEnabled = true` + `shrinkResources = true`。

**S2. 网络拦截器内 `runBlocking` 驱动 WebView 过盾（P0/P1，已核实）✅ 已修复（ARCH-009，2026-08-05）**
- 位置：`core/src/main/java/com/pawchive/core/api/ClearanceRetryInterceptor.kt`（原 `ApiClient.kt` 内 `cloudflareRetryInterceptor`）
- 现象：该拦截器运行在 OkHttp 调度线程，内部 `runBlocking` 阻塞当前网络线程去跑一个需要创建/轮询 `WebView` 的挂起操作（WebView 本身还在主线程跑，见 S6）。
- 影响：OkHttp 默认 `maxRequestsPerHost=5`，并发 403（图片+接口同时）时多个网络线程被长期占用，可能耗尽连接池、放大超时，存在 ANR 风险。
- 修复：拦截器移除 runBlocking，403 时仅非阻塞触发过盾预热（`ClearanceCoordinator.preheat()`）并返回 403；过盾前移调用层——`ApiCallHandler` 全部方法请求前 `ensureClearance()`（直接调用经 `withClearance` 自动重试一次），登录/注册/下载/创作者名预取均已接入，启动时异步预热。

**S3. DEBUG 构建 Http 日志级别为 BODY，泄露凭证（P1，已核实）**
- 位置：`app/src/main/java/com/pawchive/data/api/ApiClient.kt:257-263`（主客户端 `buildOkHttpClient()`）
- 现象：`if (BuildConfig.DEBUG) Level.BODY`；`imageOkHttpClient` 已是 `BASIC`（做得对），但主客户端仍为 `BODY`。
- 影响：BODY 会打印完整请求/响应头与体，其中含 `Set-Cookie: session=...`、`cf_clearance` 等。任何能抓取 logcat 或接入崩溃收集 SDK 的人都能拿到登录态。
- 建议：即使 DEBUG 也用 `HEADERS`/`BASIC`，并对 `Cookie`/`Set-Cookie` 头做脱敏正则替换。

**S5. 登录态明文回退 + 异步落盘（P1，已核实）**
- 位置：`app/src/main/java/com/pawchive/data/repository/SessionManager.kt:14,18,51,54,75,89,93`
- 现象：`EncryptedSharedPreferences` 初始化失败（主密钥损坏等）时回退到 `pawchive_session_fallback` **明文** `SharedPreferences`；且 `saveSession`/`clearSession` 全部用 `.apply()`（异步）。
- 影响：回退路径下高敏 session cookie 明文落盘；登录后立即被杀进程场景 `.apply()` 可能未落盘导致登录态丢失。
- 建议：登录态写入在关键路径用 `.commit()`（至少 `saveSession`）；回退明文时上报遥测并向用户告警；考虑登录后强制用 session 头校验一次服务端登录态，而非信任本地明文。

**S6. Cloudflare WebView 安全设置过宽（P1，已核实）**
- 位置：`app/src/main/java/com/pawchive/data/api/CloudflareManager.kt:188,191-193`
- 现象：`setAcceptThirdPartyCookies=true`、`javaScriptEnabled=true`、`databaseEnabled=true`，全文未显式设置 `allowFileAccess`/`allowContentAccess`（默认 `true`）。
- 影响：该隐藏 WebView 加载 `https://pawchive.pw/`，JS 为过盾所必需；但 `databaseEnabled` 非必需，且 `allowFileAccess` 默认开启——若域名被攻陷或 XSS，可经 `file://` 读取应用私有目录。
- 建议：显式 `settings.allowFileAccess = false`、`allowFileAccessFromFileURLs = false`、`allowUniversalAccessFromFileURLs = false`；移除 `databaseEnabled`；`setAcceptThirdPartyCookies` 在过盾完成后按需关闭。

### 🟡 质量 / 可维护性

**Q1. AppMemoryCache 类型不安全（P2，已核实）**
- 位置：`app/src/main/java/com/pawchive/data/repository/AppMemoryCache.kt:23,30`（`@Suppress("UNCHECKED_CAST")` + `entry.data as? T`）
- 现象：泛型缓存用 `as?` 强转，类型错配时静默返回 `null` 而非报错，难排查；键不含账号命名空间（帖子/创作者属公开数据，泄露风险低，但登出不会失效）。
- 建议：用 `inline reified` 或类型化包装；为账号相关数据加命名空间，登出时 `clear()`。

**Q2. `getFavorites(): List<Any>` 类型不安全死代码（P2，已核实）**
- 位置：`app/src/main/java/com/pawchive/data/api/PawchiveApi.kt:118-120`
- 现象：返回 `List<Any>`，全仓仅声明、无任何调用方（已有类型安全的 `getFavoritePosts` / `getFavoriteCreators`）。
- 建议：删除该接口，避免后续误用无类型信息的数据。

**Q5. 图片/文件 URL 硬编码分散 6+ 处（P2）**
- 位置：`PostDetailFragment.kt`、`PostDetailViewModel.kt`、`PostAdapter.kt`、`FavoritePostAdapter.kt`、`CreatorProfileFragment.kt`、`CreatorAdapter.kt`、`AccountFragment.kt` 等
- 现象：`https://file.pawchive.pw/data`、`https://img.pawchive.pw/...`、`/banners/...` 直接拼字符串。
- 建议：集中到 `object ApiConstants` 派生常量，换域名时一处改全。

**V5. ErrorMessageHelper 子串误匹配（P3，已核实）**
- 位置：`app/src/main/java/com/pawchive/utils/ErrorMessageHelper.kt:67-69`
- 现象：只要消息含 "auth"/"login" 即归为 `error_auth`，且整体基于异常 message 文本而非真实 HTTP 状态码匹配。
- 建议：优先按 HTTP 状态码（401/403）分类；文本匹配作为兜底并收紧关键词。

**S4. 长期存活的单例 CoroutineScope 未提供取消入口（P3）**
- 位置：`PawchiveApplication.kt:28`、`BookmarkManager.kt:26`、`BlockedCreatorManager.kt:24`、`CloudflareManager.kt:73`（`cfScope`）等
- 现象：均为 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 且无 `cancel()`。`cfScope` 驱动的过盾若异常未正常结束，WebView/回调可能泄漏到进程结束。
- 建议：长期单例 scope 提供显式清理入口；过盾 scope 应能用 `Job.cancel()` 主动中断。

**V6. 无任何自动化测试（P3，已核实）**
- 位置：全仓无 `app/src/test`、`app/src/androidTest`，`build.gradle.kts:91-93` 仅引入 junit/espresso 依赖却无用例。
- 影响：缓存/分页/书签/排序等核心逻辑回归只能靠手测，重构风险高。
- 建议：至少补 `ApiClient` 缓存命名空间、`HomeViewModel` 分页、`BookmarkManager` 写串行化的单元测试（这些已有纯逻辑、易测）。

---

## 二、前端 / UI 与客户端

### 🟠 性能

**P1. SettingsManager 首次构造同步读 DataStore（P1，已核实）✅ 已修复（ARCH-007，2026-08-05）**
- 位置：`core/src/main/java/com/pawchive/core/store/SettingsManager.kt`
- 现象：前几轮已把 `read()` 改为读内存快照（好），但**首构造**仍 `runBlocking` 同步读盘。若 `SettingsManager.getInstance()` 首次在主线线程触发（启动期 `attachBaseContext`/`applyAppearance`），即阻塞主线程做磁盘 I/O。
- 影响：冷启动/首屏在低端机或 DataStore 首次迁移时可能卡顿甚至 ANR。
- 修复：构造移除 runBlocking，改为异步预加载；语言走 AppCompat locale 持久化（autoStoreLocales），主题走轻量 SharedPreferences 启动缓存；`attachBaseContext`/`applyAppearance` 零磁盘 I/O。

**P2. 搜索全量拉取创作者 + 主线程线性过滤（P2）**
- 位置：`app/src/main/java/com/pawchive/ui/search/SearchFragment.kt:354-387`
- 现象：`getCreators()` 无分页一次性缓存全部创作者；每次输入 `contains` 全表扫描。
- 影响：创作者规模增长后内存与每次按键 O(n) 扫描变慢。
- 建议：服务端分页/搜索接口；本地过滤放到 IO 线程并对查询做 `debounce`（kotlinx-coroutines `debounce`）。

**P3. PostAdapter 每次 bind 重新编译正则（P3，已核实）**
- 位置：`app/src/main/java/com/pawchive/ui/adapter/PostAdapter.kt:152`（`post.content?.replace(Regex("<[^>]*>"), "")`）
- 建议：提升为 `companion object` 常量 `private val TAG_REGEX = Regex("<[^>]*>")`；或直接用 `Html.fromHtml` 去标签。

**P4. 进度条 200ms 忙等轮询（P4）**
- 位置：`app/src/main/java/com/pawchive/ui/post/PostDetailFragment.kt:671-690`（`while(true){ delay(200); ... }`）
- 建议：监听播放器 `onPositionDisclosure` 或用受控 `flow { while(active) { delay(250); emit(...) } }`，避免忙等。

### 🟡 代码质量 / 可维护性

**Q4. 详情/创作者导航用 add + 深返回栈（P2）**
- 位置：`app/src/main/java/com/pawchive/ui/MainActivity.kt:256-259`（`navigateToDetail`：`beginTransaction().add(...).addToBackStack(null)`），十余处 `loadFragment(new XxxFragment())` 不断 new 实例。
- 影响：深层浏览（帖→创作者→帖…）返回栈很深，多个 Fragment 实例 + viewBinding 同时存活，内存与返回栈膨胀，底层状态难恢复。
- 建议：同级高频导航用 `replace` 或单实例管理；详情页考虑 `replace` 或限制返回栈深度（`popBackStack` 合并）。

**Q6. PostDetailFragment 整段重绘附件/图片（P2）**
- 位置：`app/src/main/java/com/pawchive/ui/post/PostDetailFragment.kt:184-426`（`displayPost` 每次 uiState 发射 `removeAllViews()` 重建 + 重新触发 Coil 加载；书签状态变化也触发整页重绘）
- 影响：不必要的 View 分配与图片重载，返回时闪动、主线程开销大。
- 建议：拆分"帖子内容"与"书签/加载状态"为独立可观察项，书签变化只更新图标；附件区用 `RecyclerView`/`ViewStub`，仅数据变化时重建。

**M3. FavoritePostAdapter 全量刷新（P3，已核实）**
- 位置：`app/src/main/java/com/pawchive/ui/adapter/FavoritePostAdapter.kt:33-36`（`updatePosts` 直接 `notifyDataSetChanged()`）
- 影响：收藏列表大时掉帧（对比 `PostAdapter` 已用 `DiffUtil`）。
- 建议：迁移到 `DiffUtil`/`ListAdapter`。

**M5. 深色模式/平台配色逻辑 4 处重复（P3）**
- 位置：`PostAdapter` / `PostDetailFragment` / `CreatorProfileFragment` / `FavoritePostAdapter` 各自的 `setServiceBadgeColor`
- 建议：抽取 `ServiceBadgeStyleProvider`，单点维护 patreon/fanbox/默认配色映射。

**M2. 大量 `e.printStackTrace()` 代替日志框架**
- 位置：`BookmarkManager.kt`、`SettingsManager.kt`、`SearchFragment.kt`、`BlockedCreatorManager.kt`、`UpdateChecker.kt` 等多处
- 建议：统一 `Log` + TAG 或 Timber；生产构建可过滤。

### 🟢 错误处理 / 用户体验

**M1. 多处静默吞异常，用户无感知（P2）✅ 已修复（ARCH-008，2026-08-05）**
- 位置：`CreatorNameCache.kt:67`、`PostDetailFragment.kt:198`、`PostDetailViewModel.kt:70-81`、`CreatorProfileViewModel.kt:80-98` 等（catch 后直接 `emptyList()` 或空处理）
- 影响：评论/创作者名/公告加载失败，界面"看起来正常但数据不全"，用户无法重试。
- 修复：全仓空 catch 清零（17 处）；失败路径统一 `Log.w`；下载页打开/分享失败经 `toastMessage` 通道 Toast 提示；JSON 解析降级补日志；后台下载失败已写历史 errorMessage。

**M4. 9+ 处 ImageView 缺 contentDescription（P3，Lint 已报）**
- 位置：`fragment_account.xml:177`、`fragment_post_detail.xml:331`、`fragment_settings.xml`（多处）及动态创建的 `playIcon` 等
- 建议：装饰性图标 `android:contentDescription="@null"`，功能性图标给语义描述，过 TalkBack 验证。

**UX. 加载/空/错误态与离线体验（P2，整体）**
- 现象：`_errorMessage` 多为裸 `e.message` 直接展示（如 `HomeViewModel`），未走 `ErrorMessageHelper` 友好化；无全局离线指示；下拉刷新已支持 `no-cache`，但失败无重试按钮占位。
- 建议：统一通过 `ErrorMessageHelper.getFriendlyMessage` 展示；列表失败提供"重试"按钮；无网络时明确离线提示。

---

## 三、功能添加与增强建议（结合业务场景）

> 业务定位：Pawchive 是 Pixiv 风格、面向特定创作者的作品浏览/收藏/搜索客户端，支持 Pixiv / Fanbox / Patreon 等多平台来源，含登录、收藏、搜索历史、创作者屏蔽、设置下载目录等。建议均可在现有代码基础上叠加。

1. **离线收藏包（最值得做）**
   - 现状：`BookmarkManager` 已用 DataStore 存收藏 id，`SettingsManager` 已有 `download_tree_uri`（SAF 下载目录）。
   - 建议：收藏时提供"同时下载到本地"，把帖子正文+图片/视频预取到用户下载目录，飞行模式/弱网下仍可浏览；列表项标记"已离线"。

2. **下载管理（队列 / 进度 / 断点续传）**
   - 现状：存在 `btn_download` 与 `download_tree_uri`，但无可见的下载队列与进度管理。
   - 建议：新增"下载"页，展示进行中/完成/失败，支持暂停、重试、清除；用 `WorkManager` 保证进程被杀后继续。

3. **收藏分组 / 标签**
   - 现状：`BookmarkManager` 仅存 id 集合，无分类。
   - 建议：允许给收藏打标签（如"参考/已完成/待看"），按标签筛选；契合创作者素材收集的使用场景。

4. **创作者关注 + 新作提醒**
   - 现状：有 `BlockedCreatorManager`（屏蔽），但无"关注"。
   - 建议：关注创作者后，首页/通知页提示新作品；结合现有 `UpdateChecker` 机制做轻量轮询。

5. **搜索增强：来源/标签筛选 + 保存搜索**
   - 现状：`SearchFragment` 刚完成排序底部弹层重构，已有帖子/创作者两类 Tab。
   - 建议：增加按平台（Pixiv/Fanbox/Patreon）筛选、按标签搜索；把常用搜索条件保存为"快捷搜索"。

6. **多账号快速切换**
   - 现状：`ApiClient` 缓存已按 `u:<session>` 命名空间隔离，`logout()` 已清缓存，基础设施就绪但未暴露 UI。
   - 建议：账号页支持添加多个账号并一键切换（注意 `SessionManager` 当前是单实例明文回退，需先解决 S5）。

7. **浏览偏好与主题增强**
   - 现状：`SettingsManager` 已有语言/外观（日间/夜间/跟随系统）。
   - 建议：加 AMOLED 纯黑主题、列表/网格切换记忆、字体缩放（配合日语用户较多的场景）、滚动位置记忆。

8. **图片/视频查看器增强**
   - 现状：`PhotoViewerFragment` 已有基本查看，`PostDetailFragment` 有 ExoPlayer 视频。
   - 建议：双击缩放/手势缩放、视频画中画（PiP）、后台音频播放、长按保存/分享。

9. **数据备份与迁移**
   - 建议：将收藏、屏蔽列表、搜索历史导出为文件/导入，换机不丢数据（注意 `SessionManager` 明文回退时导出需脱敏）。

10. **无障碍与本地化补全**
    - 建议：以日语为主要用户群之一，补全 `values-ja` 中缺失文案、统一术语；对全量交互控件过 TalkBack，确保 `contentDescription` 完整（见 M4）。

---

## 优先级速览

| 优先级 | 编号 | 位置 | 一句话 |
|---|---|---|---|
| P0 | S1 | build.gradle.kts:25-30 | Release 用 debug 签名 + 无混淆 |
| P0 | S2 | ApiClient.kt:198 | 拦截器内 runBlocking 阻塞网络线程驱动 WebView |
| P1 | S3 | ApiClient.kt:257-263 | DEBUG BODY 日志泄露 session/cookie |
| P1 | S5 | SessionManager.kt | 登录态明文回退 + `.apply()` 异步丢失 |
| P1 | S6 | CloudflareManager.kt:188-193 | WebView 未禁用 file 访问、开启 DB/三方 Cookie |
| P1 | P1 | SettingsManager.kt:51 | 首构造 runBlocking 读 DataStore（主线程卡顿风险） |
| P2 | Q2 | PawchiveApi.kt:118-120 | `getFavorites(): List<Any>` 死代码 |
| P2 | Q4 | MainActivity.kt:256-259 | add + 深返回栈，实例膨胀 |
| P2 | Q6 | PostDetailFragment.kt:184-426 | 整段重绘附件/图片 |
| P2 | M1 | 多处 | 静默吞异常，用户无感知 |
| P2 | P2 | SearchFragment.kt:354-387 | 全量创作者 + 主线程线性过滤 |
| P2 | Q5 | 多处 | 图片/文件 URL 硬编码 |
| P3 | Q1 | AppMemoryCache.kt:30 | `as?` 类型不安全 |
| P3 | M3 | FavoritePostAdapter.kt:33-36 | notifyDataSetChanged 全量刷新 |
| P3 | M4 | 布局 + 动态 View | 9+ 处缺 contentDescription |
| P3 | M5 | 4 处 | 深色模式/配色逻辑重复 |
| P3 | V5 | ErrorMessageHelper.kt:67-69 | 子串误匹配 error 类型 |
| P3 | S4 | 多处 scope | 单例 CoroutineScope 未取消 |
| P3 | V6 | 全仓 | 无任何测试 |
| P4 | P3 | PostAdapter.kt:152 | 每次 bind 编译 Regex |
| P4 | P4 | PostDetailFragment.kt:671-690 | 200ms 忙等进度轮询 |
