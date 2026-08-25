# plan-watch

> 任务栏里的 AI 编程套餐余额监控 · AI coding-plan quota in your tray

在 Windows / macOS 系统托盘常驻显示你的 AI 编程套餐（Coding Plan）剩余额度。
支持配置多个供应商、多把密钥，一眼看到各套餐还剩多少。

## 目标特性

- [ ] 系统托盘常驻：直显各套餐「已用百分比 / 剩余量」
- [ ] 多账号：同一供应商可配多把 Key，多供应商并存
- [ ] 定时刷新 + 手动刷新
- [ ] 低额度阈值提醒
- [ ] Windows 任务栏托盘 & macOS 菜单栏

## 初始支持供应商

| 供应商 | 站点 | 接口文档 |
|---|---|---|
| MiniMax | `api.minimaxi.com`（国内）/ `api.minimax.io`（国际） | [docs/providers/minimax.md](docs/providers/minimax.md) |
| 智谱 GLM | `open.bigmodel.cn`（国内）/ `api.z.ai`（国际） | [docs/providers/zhipu.md](docs/providers/zhipu.md) |

> 两个接口均于 2026-08-25 用真实 Key 实测通过，文档含完整响应示例与解析规则。

## 技术方向（草案）

[Tauri 2](https://v2.tauri.app/)（Rust + TypeScript）：

- 双平台托盘原生支持，安装包小、内存占用低
- 额度解析逻辑参考 [cc-switch](https://github.com/farion1231/cc-switch) 的成熟实现
  （`src-tauri/src/services/coding_plan.rs`，覆盖了大量实测边界 case）

## 目录结构

```
docs/
  providers/       # 各供应商余额查询接口调研文档
```

## License

[MIT](LICENSE)
