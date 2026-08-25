# 开发路线（TODO）

> 按 milestone 分阶段推进，勾选表示完成。接口调研文档见 [docs/providers/](docs/providers/)。

## v0.1 — 骨架跑通

- [x] Tauri 2 项目初始化（Rust + TypeScript）
- [x] 托盘常驻：Windows 任务栏托盘 + macOS 菜单栏图标
- [x] 托盘菜单 / tooltip 显示一条 mock 数据
- [x] 本地配置文件读写（多供应商、多账号的数据结构定义）

## v0.2 — 余额查询跑通

- [x] 统一数据模型：5h 桶 / 周桶、已用百分比、重置时间、套餐等级
- [x] MiniMax 查询 + 解析（按 [docs/providers/minimax.md](docs/providers/minimax.md)）
- [x] 智谱个人版查询 + 解析（按 [docs/providers/zhipu.md](docs/providers/zhipu.md)）
- [x] 错误分开展示：业务层错误 / 鉴权失败（凭证过期）/ 网络失败（保留上次成功数据）
- [x] 智谱"业务 401 但 HTTP 200"的凭证失效识别
- [x] MiniMax"业务 1004（cookie is missing）但 HTTP 200"的凭证失效识别（2026-08-25 实测补充）

## v0.3 — 日常可用

- [x] 定时刷新（间隔可配）+ 手动刷新
- [x] 多账号同屏展示（浮动列表聚合）
- [x] 配置管理 UI（增删改供应商账号，密钥脱敏显示）
- [x] 低额度阈值提醒（系统通知，带迟滞去重）

## v0.3.1 — 交互重塑（2026-08-25）

- [x] 浮动额度列表：桌面常驻置顶、标题栏拖动、位置记忆（window-state 插件，只记位置）
- [x] 每账号两条状态条：绿→黄→红渐变填充 + 已用百分比 + 重置倒计时（悬停看绝对时间）
- [x] 无周限额窗口显示 ∞（斜纹条，MiniMax `weekly_status != 1`）
- [x] 托盘交互：左键切换浮动列表显隐，右键打开设置（弃用原生菜单）
- [x] 双色胶囊品牌图标（左绿右红）：应用图标 + 托盘图标统一（`tools/gen-icons.ps1`）
- [x] macOS：Accessory 激活策略（不占 Dock）；窗口在 manage() 之后动态创建（修复启动竞态 panic）

## v0.4+ — 扩展

- [x] MiniMax / 智谱国际站切换（`api.minimax.io`、`api.z.ai`，账号 region 字段）
- [ ] 智谱团队版（`?type=2` + `bigmodel-organization` / `bigmodel-project` 头）
- [ ] 更多供应商：Kimi For Coding、火山方舟（cc-switch 有成熟实现可参考）
- [ ] 开机自启
- [ ] 打包发布：Windows installer + macOS dmg，GitHub Actions CI
- [ ] 已知小坑：webview 在窗口隐藏期间创建时，Windows 上 `show()` 后可能白屏，
      已用 ±1px 伪 resize 缓解（`tray_util.rs` 的 `nudge_window`），macOS/Linux 待真机验证
- [ ] 已知小坑：透明窗口（`transparent: true`）在 Windows WebView2 上渲染不可靠
      （实测白底），浮动列表已改实色面板；若后续想要圆角/亚克力效果再研究
- [ ] 浮动列表宽度固定 236px，账号名过长会截断；后续可做宽度自适应或配置项
- [ ] 浮动列表暂无开关配置（想彻底关掉只能 ⏻ 退出），可加 `show_float` 配置项

## 历史决策（已定）

- ~~前端框架：Vue / React / Svelte~~ → **React + TypeScript**（2026-08-25）
- ~~密钥存储：明文 JSON 配置 vs 系统钥匙串（keyring）~~ → **明文 JSON**（与 cc-switch 一致，v0.x；keyring 留作后续升级）
- ~~国际站支持的优先级（是否进 v0.2）~~ → 已随 v0.3 实现
