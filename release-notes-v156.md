# Pawchive v1.5.6

## 🔐 修复登录失效循环（Login Expiry Loop）

- **根因**：WebView 过盾时捕获的 Cloudflare cookie 中混入了 Flask 匿名 `session` cookie，被原样注入 API 请求，与真实登录 session 形成重复 cookie（`session=<匿名>; session=<真实>`），服务端取到匿名会话 → 401 → 误判"登录已失效"并清除真实会话 → 登录后立即被踢出、无限循环
- **修复**：
  - 捕获/持久化/恢复 Cloudflare 凭据时一律剔除 `session=` 段，真实会话只由登录流程提供
  - 请求合并 cookie 时二次过滤，杜绝任何路径的 session 泄漏
  - 登录后 60 秒宽限期内单个 401 不再立即清除会话（防误判循环，可自愈）
- **兼容性**：旧版本已污染的本机凭据会在升级后自动清洗，无需重新安装或清除数据

> 安装包：`Pawchive-v1.5.6.apk`（versionCode 56）
