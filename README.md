# plan-watch

> 任务栏里的 AI 编程套餐余额监控 · AI coding-plan quota in your tray

在 Windows / macOS 系统托盘常驻显示你的 AI 编程套餐（Coding Plan）剩余额度。
支持配置多个供应商、多把密钥，一眼看到各套餐还剩多少。

## 目标特性

- [x] 桌面浮动列表：常驻置顶、可拖到任意位置（位置记忆）、一行一账号，
      每账号两条渐变状态条（5 小时 / 周）+ 已用百分比 + 重置倒计时，无周限额显示 ∞
- [x] 系统托盘：左键切换浮动列表显隐、右键打开设置、悬停 tooltip 摘要
- [x] 多账号：同一供应商可配多把 Key，多供应商并存
- [x] 定时刷新（间隔可配）+ 手动刷新
- [x] 低额度阈值提醒（系统通知，带迟滞去重）
- [x] Windows 任务栏托盘 & macOS 菜单栏（Accessory，不占 Dock）

> 分阶段任务清单见 [TODO.md](TODO.md)。

## 交互速览

| 入口 | 行为 |
|---|---|
| 浮动列表 · 标题栏 | 按住拖动到任意位置（重启记忆） |
| 浮动列表 · 账号行 | 点击打开设置编辑该账号 |
| 浮动列表 · 底部 | ⟳ 立即刷新 · ⚙ 设置 · ⏻ 退出 |
| 托盘左键 | 显示 / 隐藏浮动列表 |
| 托盘右键 | 打开设置窗口 |

## 快速开始

```bash
npm install
npm run tauri dev     # 开发（首次运行会拉取 Rust 依赖，耐心等待）
npm run tauri build   # 打包
```

- Rust 单测：`cargo test --manifest-path src-tauri/Cargo.toml`（含两个供应商解析、错误分类与 ∞ 周限的 38 个边界用例）
- 冒烟查询（无 GUI，走真实接口）：设置 `PW_ZHIPU_KEY` / `PW_MINIMAX_KEY` 环境变量后
  `cargo run --manifest-path src-tauri/Cargo.toml --example query`

配置文件（明文 JSON，v0.x 既定方案）：

- Windows：`%APPDATA%\com.planwatch.app\config.json`
- macOS：`~/Library/Application Support/com.planwatch.app/config.json`

## 服务端模式（可选）

同一套前端可以改从一个 Spring Boot 服务端取数（接口与本地查询完全同构）：

```powershell
# 启动服务端（Spring Boot 4.1.x，JDK 26；默认端口 8787，配置存 server/data/config.json）
$env:JAVA_HOME = "C:\Users\turin\.jdks\openjdk-26.0.2"
mvn -f server/pom.xml spring-boot:run
```

然后在应用「通用设置 → 后端接口地址」填 `http://127.0.0.1:8787`（或局域网内服务端地址）并保存：

- 标题栏出现「服务端」徽标，所有账号/状态/测试连接都走服务端（15s 轮询）
- 清空该地址即回到本地查询模式（地址是本机偏好，不随配置同步）
- REST API：`GET/PUT /api/config`、`GET /api/statuses`、`POST /api/refresh`、`POST /api/test`，
  CORS 全放开，可被任意前端复用；服务端单测 `mvn -f server/pom.xml test`（9 个解析用例）

> 注意：`server/data/` 含真实 API Key，已加入 .gitignore，不入库。

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
├── src/                    # 前端（React + TS + Vite，一个入口按窗口分流）
│   ├── App.tsx             # main：设置窗口
│   ├── FloatList.tsx       # float：桌面浮动额度列表
│   └── components/         # 账号卡片 / 账号表单
├── src-tauri/
│   ├── src/
│   │   ├── quota/          # 统一数据模型 + MiniMax / 智谱查询解析（含单测）
│   │   ├── config.rs       # 明文 JSON 配置读写（原子写入 + .bak）
│   │   ├── state.rs        # 共享状态
│   │   ├── scheduler.rs    # 定时刷新 + 低额度通知（迟滞去重）
│   │   ├── tray.rs         # 托盘交互 / tooltip
│   │   ├── tray_util.rs    # 窗口显示辅助（WebView2 白屏 nudge）
│   │   └── commands.rs     # Tauri commands
│   └── examples/query.rs   # 真实接口冒烟工具
├── docs/providers/         # 供应商余额查询接口调研文档
└── tools/gen-icons.ps1     # 双色胶囊图标生成脚本（System.Drawing）
```

## License

[MIT](LICENSE)
