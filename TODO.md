# 开发路线（TODO）

> 按 milestone 分阶段推进，勾选表示完成。接口调研文档见 [docs/providers/](docs/providers/)。

## v0.1 — 骨架跑通

- [ ] Tauri 2 项目初始化（Rust + TypeScript）
- [ ] 托盘常驻：Windows 任务栏托盘 + macOS 菜单栏图标
- [ ] 托盘菜单 / tooltip 显示一条 mock 数据
- [ ] 本地配置文件读写（多供应商、多账号的数据结构定义）

## v0.2 — 余额查询跑通

- [ ] 统一数据模型：5h 桶 / 周桶、已用百分比、重置时间、套餐等级
- [ ] MiniMax 查询 + 解析（按 [docs/providers/minimax.md](docs/providers/minimax.md)）
- [ ] 智谱个人版查询 + 解析（按 [docs/providers/zhipu.md](docs/providers/zhipu.md)）
- [ ] 错误分开展示：业务层错误 / 鉴权失败（凭证过期）/ 网络失败
- [ ] 智谱"业务 401 但 HTTP 200"的凭证失效识别

## v0.3 — 日常可用

- [ ] 定时刷新（间隔可配）+ 手动刷新
- [ ] 多账号同屏展示（托盘聚合 + 菜单明细）
- [ ] 配置管理 UI（增删改供应商账号，密钥脱敏显示）
- [ ] 低额度阈值提醒（托盘图标变色 / 系统通知）

## v0.4+ — 扩展

- [ ] 智谱团队版（`?type=2` + `bigmodel-organization` / `bigmodel-project` 头）
- [ ] MiniMax / 智谱国际站切换（`api.minimax.io`、`api.z.ai`）
- [ ] 更多供应商：Kimi For Coding、火山方舟（cc-switch 有成熟实现可参考）
- [ ] 开机自启
- [ ] 打包发布：Windows installer + macOS dmg，GitHub Actions CI

## 待定决策

- 前端框架：Vue / React / Svelte
- 密钥存储：明文 JSON 配置 vs 系统钥匙串（keyring）
- 国际站支持的优先级（是否进 v0.2）
