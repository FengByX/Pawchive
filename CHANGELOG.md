# 更新日志

本文件记录本项目的所有重要变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

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
