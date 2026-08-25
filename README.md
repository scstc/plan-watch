# plan-watch

> 任务栏里的 AI 编程套餐余额监控 · AI coding-plan quota in your tray

在 Windows / macOS 系统托盘常驻显示你的 AI 编程套餐（Coding Plan）剩余额度。
支持配置多个供应商、多把密钥，一眼看到各套餐还剩多少。

## 目标特性

- [x] 系统托盘常驻：图标颜色随额度变化，菜单 / tooltip 直显各套餐「已用百分比 / 重置时间」
- [x] 多账号：同一供应商可配多把 Key，多供应商并存
- [x] 定时刷新（间隔可配）+ 手动刷新
- [x] 低额度阈值提醒（托盘变色 + 系统通知）
- [x] Windows 任务栏托盘 & macOS 菜单栏

> 分阶段任务清单见 [TODO.md](TODO.md)。

## 快速开始

```bash
npm install
npm run tauri dev     # 开发（首次运行会拉取 Rust 依赖，耐心等待）
npm run tauri build   # 打包
```

- Rust 单测：`cargo test --manifest-path src-tauri/Cargo.toml`（含两个供应商解析与错误分类的 37 个边界用例）
- 冒烟查询（无 GUI，走真实接口）：设置 `PW_ZHIPU_KEY` / `PW_MINIMAX_KEY` 环境变量后
  `cargo run --manifest-path src-tauri/Cargo.toml --example query`

配置文件（明文 JSON，v0.x 既定方案）：

- Windows：`%APPDATA%\com.planwatch.app\config.json`
- macOS：`~/Library/Application Support/com.planwatch.app/config.json`

## 初始支持供应商

| 供应商 | 站点 | 接口文档 |
|---|---|---|
| MiniMax | `api.minimaxi.com`（国内）/ `api.minimax.io`（国际） | [docs/providers/minimax.md](docs/providers/minimax.md) |
| 智谱 GLM | `open.bigmodel.cn`（国内）/ `api.z.ai`（国际） | [docs/providers/zhipu.md](docs/providers/zhipu.md) |

> 两个接口均于 2026-08-25 用真实 Key 实测通过，文档含完整响应示例与解析规则。

## 技术方向

[Tauri 2](https://v2.tauri.app/)（Rust + TypeScript + React）：

- 双平台托盘原生支持，安装包小、内存占用低
- 额度解析逻辑移植自 [cc-switch](https://github.com/farion1231/cc-switch) 的成熟实现
  （`src-tauri/src/services/coding_plan.rs`，覆盖了大量实测边界 case）

```
├── src/                    # 设置窗口（React + TS + Vite）
│   ├── App.tsx             # 主容器：通用设置 + 账号列表
│   └── components/         # 账号卡片 / 账号表单
├── src-tauri/
│   ├── src/
│   │   ├── quota/          # 统一数据模型 + MiniMax / 智谱查询解析（含单测）
│   │   ├── config.rs       # 明文 JSON 配置读写
│   │   ├── state.rs        # 共享状态
│   │   ├── scheduler.rs    # 定时刷新 + 低额度通知（迟滞去重）
│   │   ├── tray.rs         # 托盘菜单 / tooltip / 状态图标
│   │   └── commands.rs     # Tauri commands
│   └── examples/query.rs   # 真实接口冒烟工具
├── docs/providers/         # 供应商余额查询接口调研文档
└── tools/gen-icons.ps1     # 图标生成脚本（System.Drawing）
```

## License

[MIT](LICENSE)
