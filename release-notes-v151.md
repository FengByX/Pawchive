# Pawchive v1.5.1

## 新增功能

### ARCH-FEATURE-001 离线归档与全文搜索
- 收藏内容离线索引（Room + FTS4）：标题/正文/创作者/附件元数据本地化
- 中文 CJK bigram 分词，兼容中英日三语全文搜索，支持前缀匹配
- 搜索页新增"离线搜索"入口，无网络也能检索收藏内容
- 离线详情依赖完整 Post 归档，断网可读收藏帖
- 设置页"数据"分组新增"离线归档"管理页：集中查看收藏帖离线副本（标题/创作者/收藏时间）、实时搜索、点击行跳帖子详情、删除单条或一键清空（均二次确认，仅删离线副本不影响收藏）
- 升级后的历史收藏自动补齐离线索引（一次性后台回填），无需重新收藏即可离线搜索/阅读
- 离线搜索按相关性排序：标题命中 > 创作者命中 > 正文/附件命中，结果更精准

### ARCH-FEATURE-002 下载规则与批量任务
- 下载规则引擎：按创作者 / 服务 / 文件类型自动匹配帖子附件并批量入队
- 帖子详情页新增"按规则下载"按钮，一键将匹配附件加入下载队列
- 设置页新增"下载规则"管理页：规则列表、添加/编辑（名称 + 创作者/服务可空 + 文件类型）、启用开关、删除确认
- 下载任务统一走 DownloadCenter（WorkManager 前台下载，通知栏进度），与手动下载同管线去重

### ARCH-FEATURE-003 内容更新订阅
- 创作者主页新增订阅铃铛：订阅后周期自动检测新帖（增量同步，历史帖不误报）
- 设置页新增"内容更新"入口与未读徽标，订阅更新实时可见
- 内容更新列表页：未读圆点、相对时间、点击跳转帖子详情、全部已读
- 周期同步走 WorkManager（30 分钟 + 联网约束），不依赖登录态
- 系统通知栏推送：同步发现新帖时推送提醒（单条显示帖子标题，多条聚合数量），点击直达内容更新页；Android 13+ 订阅时引导授权通知权限
- 订阅管理页：设置页"订阅更新"分组新增"订阅管理"入口，集中查看全部订阅（平台徽标 + 创作者名 + 订阅时间）、点击跳创作者主页、一键取消订阅（确认后同时清除历史通知）

### ARCH-FEATURE-003/006 联动：收藏即订阅
- 设置页新增"收藏创作者时自动订阅"开关（默认开启）
- 收藏创作者即自动订阅其更新，新帖自动进入内容更新提醒与通知栏推送
- 取消收藏不会自动退订（退订是用户显式意图，可在订阅管理页操作）

### ARCH-FEATURE-006 首页过滤已收藏作者
- 设置页新增"隐藏已收藏作者的帖子"开关（默认关闭）
- 开启后首页信息流不再展示已收藏创作者的帖子，聚焦发现新内容
- 已收藏作者的更新仍可通过收藏页 / 内容更新订阅查看
- 过滤集合合并云端收藏：登录后其他设备收藏的创作者同样被过滤（覆盖本地未同步场景）

### ARCH-FEATURE-004 存储空间与缓存管理页
- 设置页新增"缓存管理"入口，按类别展示并清理：图片缓存 / 其他缓存 / 离线归档 / 下载文件
- 汇总卡片展示可清理缓存总量与上次清理时间
- 缓存超过阈值（默认 200MB）时页面内展示红色提醒
- 离线归档与下载文件删除需二次确认，避免误删用户数据

### ARCH-FEATURE-005 备份与迁移
- 设置页新增"数据"分组与"备份与迁移"入口，单文件导出/导入本地数据
- 备份内容：收藏帖子与创作者、屏蔽名单、阅读进度、下载历史与设置
- 不含登录凭证与搜索历史；下载记录剔除设备绑定路径，进行中任务归一为已完成
- 导出经系统文件选择器创建 pawchive_backup_*.json；导入前二次确认并展示还原计数

## 架构改进

### ARCH-003 多模块架构拆分
- 单模块重构为多模块：core / data / feature-common / feature-account / feature-downloads / feature-home / feature-post / feature-search / feature-settings
- 全部 8 个 Fragment（Home/Search/Settings/Login/Account/CreatorProfile/PostDetail/AccountFavorites）保留 XML + ViewBinding 实现
- 依赖注入统一使用 Hilt（@HiltAndroidApp / @AndroidEntryPoint / @HiltViewModel / @HiltWorker），核心 Manager/Repository 移除 getInstance() 单例
- 网络层拆分：ApiClient / CloudflareManager 独立组件，拦截器链非阻塞预热过盾（无 runBlocking）
- 统一数据层错误类型 AppError，全仓空 catch 吞错清零（ARCH-008）

### BACKEND-009 测试覆盖
- 核心业务层测试迁移至 core/data 模块，覆盖 DAO、仓库、下载规则引擎、备份、离线索引、Cloudflare 等
- `koverVerify` 覆盖率门禁：核心业务层 line 覆盖率 ≥ 18%

### FRONTEND-008 ViewModel + UiState 架构
- Home/Search/PostDetail/Settings/Downloads/Favorites 全部 6 页面重构
- 统一为 `ViewModel -> StateFlow<UiState> + UiEffect` 模式
- ViewModel 处理网络/缓存/过滤/排序/分页/错误转换，Fragment 专注 UI 绑定与用户交互

## 工程治理

### ARCH-011 依赖治理
- 接入 Dependabot 自动依赖更新（gradle + github-actions，每周一检查并提交升级 PR）
- Retrofit 3.x / OkHttp 5.x / KSP 3.x 破坏性大版本加入 Dependabot ignore，按季度计划人工升级

### ARCH-012 Adapter 统一
- 全部 7 个列表适配器统一 DiffUtil 差量更新（ListAdapter 或 calculateDiff 增量通知），`notifyDataSetChanged()` 全仓清零

### ARCH-014 质量门禁与 CI
- 接入 Kover 0.9.9 覆盖率工具，聚合报告聚焦核心业务层（core + data）
- 全模块 `lintDebug` 无 Error 级问题（abortOnError 门禁生效）
- 新增 `quality-check.ps1` 一键验证脚本（单测 → lint → 覆盖率报告 → 覆盖率门禁 → 依赖扫描）
- 新增 GitHub Actions CI（`.github/workflows/ci.yml`）：PR 与 main 推送时自动执行单测 + lint + 覆盖率门禁作为合入门槛

## 安全加固
- 会话加密失败不再回退明文，要求重新登录
- 日志不再输出 Authorization/Cookie/Set-Cookie 等敏感头
- 富文本渲染严格白名单，仅允许 https 链接
- 缓存键脱敏，不含明文 session
- Cloudflare 拦截器移除 runBlocking，过盾前移调用层非阻塞预热（ARCH-009）
- 下载历史迁移至 Room 并显式 Migration，防止历史丢失（ARCH-004/005）

## 体验优化
- 启动路径移除 runBlocking 同步读盘：语言走 AppCompat locale 持久化，主题走轻量启动缓存（ARCH-007）
- 设置页隐藏 versionCode，仅展示 versionName
- 手动清缓存结果反馈（释放空间大小）
- 创作者名称缓存预取
- 列表适配器迁移到 ListAdapter + DiffUtil，滑动更顺滑

## 版本信息
- versionCode: 51
- versionName: 1.5.1
