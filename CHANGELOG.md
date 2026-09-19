# 更新日志

本文件记录本项目的所有重要变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [1.7.2] - 2026-09-19

### Added
- 动图播放支持：新增 `coil-gif` 依赖并在全局 `ImageLoader` 注册 `ImageDecoderDecoder`
  （minSdk 30 ≥ 28，走 ImageDecoder 通道）。作品图、图查看器与创作者头像位置的
  GIF / 动画 WebP / 动画 HEIF 均可正常循环播放（此前一律被解成静态首帧）
- 列表 / 网格的 GIF 帖新增「GIF」角标，在列表即可分辨动图（列表仍只加载静态缩略图、
  不拉取原图，避免一个列表消耗数百 MB 流量）

### Changed
- 版本号提升至 1.7.2（versionCode 72）

### Fixed
- 帖子详情页与全屏大图里的 GIF 仍不播放：上游「缩略图 CDN」对动图源只产出静态单帧
  （实测 GIF 源返回的是仅含 `VP8 ` 块的静态 WebP，且 Content-Type 谎报为 image/gif），
  而详情页原先把缩略图排在候选链首位、命中即停，永远走不到带动画的原图。
  现将详情页候选链改为**原图优先、缩略图兜底**，并移除实测为死链的
  `img.pawchive.pw/data`（GIF 与 JPEG 实测均 404）
- 全屏大图查看器此前接收的是缩略图 URL，导致「点开看大图」看到的是缩略图，
  长按「保存图片」落盘的也是缩略图。现改为接收候选链并以原图为准，
  原图缺失（`has_full=false`，实测约占 4%）时自动回退缩略图

- 账号收藏（帖子 / 创作者）不同步：新增或移除收藏后，收藏页最长滞后 5 分钟才更新。根因是三层叠加——
  收藏列表走 GET 且被 `ApiMemoryCache` 缓存 5 分钟，而写入走 POST/DELETE 却从不失效该缓存；
  收藏接口缺少 `Cache-Control` 透传，下拉刷新会命中旧缓存形成"假刷新"；
  返回收藏页时 `ensureCurrentTabLoaded()` 在已有数据下只重排、从不重新请求。
  现改为：写入成功后按路径精准失效缓存（`ApiMemoryCache.invalidateByPathPrefix`）并自增写入序号、
  收藏 GET 支持 `no-cache`、返回本页时静默刷新（5 秒节流仅用于吸收重复 resume，
  并被"期间发生过收藏写入"击穿，避免刚收藏完返回就跳过刷新；失败不打扰，分页数据不截断）
- CI Lint `MissingPermission` 报错：`DownloadNotificationController.updateSummary()` 改为复用已显式
  捕获 `SecurityException` 的 `notify()` 通道（Lint 无法识别 `runCatching` 的异常处理），
  并移除该文件未使用的 `PackageManager` 导入
- 系统 WebView 被替换为第三方实现（如用 Magisk 模块把系统 WebView 换成 Cromite / Bromite）
  时，应用启动后即闪退。根因是过盾用的隐藏 WebView 两处缺少兜底：其一，WebView 构造与配置
  跑在主线程且无 try/catch，第三方实现抛出的 `MissingWebViewPackageException` /
  `UnsatisfiedLinkError`（native 库缺失，属 `Error`）会从 Runnable 直接逃逸到未捕获异常
  处理器并终结进程；其二，`WebViewClient` 未覆写 `onRenderProcessGone`，按 Android 默认语义
  渲染进程一崩溃系统就会连带终止整个应用，且该路径不产生 Java 堆栈、崩溃日志中无法留痕。
  现改为：构造与配置整体降级保护（失败即视为过盾失败，不再传播）、覆写
  `onRenderProcessGone` 返回 `true` 并自行回收 WebView、新增 `onReceivedError`（仅主文档，
  子资源失败不中断）与 `onReceivedSslError` 兜底，使过盾尽早失败而非空等 30 秒超时

## [1.7.1] - 2026-09-17

### Added
- 通知栏实时下载进度：百分比、已下载/总大小、速度（EMA 平滑）、预计剩余时间
- 每个下载任务独立通知；通知内提供**暂停 / 继续 / 取消**操作
- HTTP Range 断点续传：暂停保留临时文件，继续时从断点续传（服务端不支持 Range 时自动降级为全量下载）
- 应用退到后台后下载不中断（`dataSync` 前台服务保活）
- 设置页新增「显示与缩放」：整体 UI 缩放与全局文字大小两个连续滑杆，拖动实时预览、松手即时全局生效、各自支持"恢复默认"，重启后保持

### Changed
- 版本号提升至 1.7.1（versionCode 71）
- 依赖管理：全部硬编码依赖收口到 `gradle/libs.versions.toml`（单一事实来源）
- `androidx.security:security-crypto` 由 `1.1.0-alpha06` 提升至最终稳定版 `1.1.0`（消除 alpha 生产依赖）

### Fixed
- 下载长按多选批量屏蔽（搜索结果）此前不生效

### Engineering
- **覆盖率门禁口径修正**：根项目 `kover(project(...))` 聚合依赖只保留 `:core` 与 `:data`，
  移除 7 个 feature/app 聚合行。原因：Kover 0.9.9 的 `:koverVerify` 聚合合并不能对全部附加模块
  稳定应用根级 includes 过滤器，feature/app 的类（无单元测试）按"未覆盖"泄漏进 verify 口径
  （实测泄漏 1476 行 → 门禁 35.53%），与按报告口径（core+data 手写业务代码）校准的 45% 阈值
  基线不一致；剔除后核心层实测 **46.5% ≥ 45**，门禁口径与校准基线恢复一致。feature/app 模块
  仍各自应用 kover 插件，可独立生成报告查看
- 设置项扩展三批落地（文件命名格式 / 更新通道 STABLE-BETA / 一键清除全部本地数据 / 每日自动备份 /
  强调色主题 / 减少动效 / 网格列数），含三语文案与设置页 UI
- CI 覆盖率门禁：`koverVerify` 纳入 CI（此前仅存在于本地脚本）；核心层（core+data）阈值由 18% 上调至**当前实测 45%** 作为只增不减的棘轮；CI 同步产出 XML/HTML 覆盖率报告
- CI 归档 R8 `mapping.txt`：使线上崩溃堆栈可反混淆定位
- CI 新增依赖审查（`dependency-review-action`），PR 阶段拦截高危依赖
- Gradle wrapper 增加 `distributionSha256Sum` 完整性校验
- 版本号兜底值移除：`VERSION_NAME`/`VERSION_CODE` 缺失或非法时构建直接失败，不再静默产出低版本号
- 清理 `proguard-rules.pro` 中已移除依赖（okdownload）的残留规则

## [1.7.0] - 2026-09-12

### Changed
- **下载引擎替换**：移除 okdownload 1.0.7，改为直连 OkHttp 流式下载（单连接单请求）。
  移除原因（源码级取证）：okdownload 的 sync 线程阻塞无超时（`LockSupport.park()`）、
  多块下载对同一文件并发发起多个 Range 请求（与文件服务器"同一文件每秒不超过 1 次请求"的要求冲突）、
  断点校验在响应截断时抛隐晦错误。

### Fixed
- 下载进度永久卡在 99%：进度回调硬封顶 99 且 100% 的唯一写点在"临时文件 → 目标流写出"之后，写出阶段无进度写库
- 写出阶段取消失效：整段 `copyTo` 为不可中断阻塞 IO，改为 64KB 分块 + 每块检查协程活跃性
- 进度竞态覆盖完成态：`COMPLETED/100` 被后续 `RUNNING/99` 覆盖（Channel 串行化 + 取消时不再提前清去重标记）

### Engineering
- 文件服务器策略合规：自定义 User-Agent、并发下载上限 5、同文件重试间隔 ≥1 秒

## [1.6.10] - 2026-09-06

### Fixed
- `StringFormatInvalid`：下载完成通知文案缺少格式化参数

## [1.6.9] - 2026-09-06

### Added
- 下载中心改进、网络客户端调整、帖子详情增强

### Engineering
- 文件服务器策略合规项前置（UA / 并发上限 / 重试间隔）

## [1.6.8] - 2026-09-05

### Fixed
- 多选操作栏行为异常
- 搜索空态提示被内容覆盖
- 画中画（PiP）返回后底部导航未恢复

## [1.6.7] - 2026-08-31

### Fixed
- 创作者搜索返回空结果
- 搜索结果新增多选屏蔽

---

## 更早版本

1.6.6 及更早版本的变更记录见
[GitHub Releases](https://github.com/FengByX/Pawchive/releases)。
本文件不对早期版本做追溯补录，以避免记录失实。
