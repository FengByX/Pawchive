# Pawchive v1.5.0

## 新增功能

### FEATURE-001 下载中心
- 统一下载队列管理，支持图片/视频/附件下载
- 下载历史持久化，支持暂停/取消/重试
- 下载完成后支持打开/分享文件
- 使用 WorkManager 前台任务管理，通知栏展示进度

### FEATURE-002 离线收藏与阅读
- 网络不可用时自动回退到本地收藏数据
- 帖子详情页离线模式横幅提示
- 收藏内容支持离线浏览搜索

### FEATURE-003 账号与数据边界
- 多账号切换支持，独立收藏/历史/下载数据隔离
- 账号切换时清除当前账号本地数据
- 登出时清理缓存与用户数据

### FEATURE-004 搜索增强
- 多维度筛选：服务来源、仅含附件、仅看收藏
- 筛选芯片 UI，支持快速重置

### FEATURE-005 内容阅读体验
- 视频播放位置记忆，恢复至上次播放位置
- 阅读滚动位置恢复
- 外链点击安全提示对话框
- 批量保存帖子图片
- 稍后读与阅读进度管理

### FEATURE-006 可观察的错误与反馈
- 全局崩溃捕获（CrashHandler），日志持久化至 crash_logs/
- 统一内嵌错误页，替代 Toast 打断
- 列表为空且请求失败时展示错误页 + 重试按钮
- 已有内容时刷新失败仅 Toast 提示，不打断浏览

## 架构改进

### BACKEND-007 统一数据层错误类型
- 定义 AppError sealed class，封装网络/服务器/认证/Cloudflare/业务/未知错误
- 替代散落的 Exception 与字符串匹配
- 统一映射到用户友好文案

### BACKEND-009 测试覆盖
- 新增 Robolectric 4.14.1 测试依赖
- AppErrorTest（22 用例）：错误分类与消息映射
- AppMemoryCacheTest（6 用例）：缓存操作
- SafeHtmlHelperTest（12 用例）：链接过滤与 HTML 消毒

### FRONTEND-003 缓存清理策略
- 改用 WorkManager 受约束任务 + 容量阈值策略
- 不再每次启动无条件清空
- 记录上次清理时间戳

### FRONTEND-006 视频下载
- 改用 WorkManager 前台任务 + 通知栏进度
- 应用切后台下载不中断

### FRONTEND-008 ViewModel + UiState 架构
- Search/PostDetail/Settings 三页面重构
- ViewModel 处理网络/缓存/过滤/排序/错误转换
- Fragment 专注 UI 绑定与用户交互

## 安全加固
- 会话加密失败不再回退明文，要求重新登录
- 日志不再输出 Authorization/Cookie/Set-Cookie 等敏感头
- 富文本渲染严格白名单，仅允许 https 链接
- 缓存键脱敏，不含明文 session
- Cloudflare 拦截器移除 runBlocking，单飞异步处理

## 体验优化
- 设置页屏蔽列表多选 bug 修复
- 启动路径 runBlocking 超时控制（500ms）
- 列表适配器迁移到 ListAdapter + DiffUtil
- 设置页隐藏 versionCode，仅展示 versionName
- 手动清缓存结果反馈（释放空间大小）
- 创作者名称缓存预取

## 版本信息
- versionCode: 50
- versionName: 1.5.0
