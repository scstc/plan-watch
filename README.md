# plan-watch

> 任务栏里的 AI 编程套餐余额监控 · AI coding-plan quota in your tray

桌面常驻浮动列表 + 系统托盘，一眼看清各家 AI 编程套餐（Coding Plan）还剩多少：
MiniMax、智谱 GLM，多账号多供应商，额度条 + 重置倒计时 + 低额度系统通知。

[![CI](https://github.com/scstc/plan-watch/actions/workflows/ci.yml/badge.svg)](https://github.com/scstc/plan-watch/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/scstc/plan-watch)](https://github.com/scstc/plan-watch/releases)

## 组成与架构

```
┌─────────────────┐   本地查询（默认）    ┌──────────────────┐
│  桌面应用 app/    │ ─────────────────── │  供应商开放平台     │
│  Tauri 2         │                      │  MiniMax / 智谱    │
│  React + Rust    │   服务端模式（可选）   └──────────────────┘
│                 │ ──────┐                      ▲
└─────────────────┘       ▼                      │
                    ┌──────────────┐  定时代理查询 │
                    │ 服务端 server/ │ ────────────┘
                    │ Spring Boot  │
                    └──────────────┘
```

- **桌面应用**（`app/`）：独立可用，直接在本地查询供应商接口
- **服务端**（`server/`）：可选部署。代理查询并暴露 REST API，多个客户端共用一份配置；
  桌面端在「设置 → 后端接口地址」填上服务端地址即切换为服务端取数
- 两种模式数据结构完全同构，随时互切

## 桌面应用

### 安装

从 [Releases](https://github.com/scstc/plan-watch/releases) 下载：

| 文件 | 平台 |
|---|---|
| `plan-watch_x.y.z_x64-setup.exe` | Windows x64（推荐，NSIS 安装包） |
| `plan-watch_x.y.z_aarch64.dmg` | macOS（Apple Silicon） |
| `plan-watch_x.y.z_amd64.dmg` | macOS（Intel） |

> 安装包未签名：Windows SmartScreen 首次运行选「仍要运行」；
> macOS 首次打开需在「系统设置 → 隐私与安全性」里允许。

### 交互速览

| 入口 | 行为 |
|---|---|
| 浮动列表 · 标题栏 | 按住拖动到任意位置（重启记忆） |
| 浮动列表 · 账号行 | 每账号两条渐变状态条（5h / 周）+ 已用百分比 + 重置倒计时；点击打开设置编辑 |
| 浮动列表 · 底部 | ⟳ 立即刷新 · ⚙ 设置 · ⏻ 退出 |
| 托盘左键 | 显示 / 隐藏浮动列表 |
| 托盘右键 | 打开设置窗口 |

无周限额的窗口（如 MiniMax 部分套餐）显示 **∞**。

## 服务端部署

### 要求

- JDK 26+（[Temurin](https://adoptium.net/) 等均可）

### 启动（推荐：直接用 Release 的 jar）

```bash
# 下载 plan-watch-server.jar 后
java -jar plan-watch-server.jar
```

默认监听 `http://0.0.0.0:8787`，配置与数据存放在**工作目录**的 `data/` 下。

### 常用启动参数

```bash
# 换端口
java -jar plan-watch-server.jar --server.port=9000

# 换数据目录（config.json 所在）
java -jar plan-watch-server.jar --planwatch.data-dir=/var/lib/plan-watch

# 也可用环境变量：SERVER_PORT=9000  PLANWATCH_DATADIR=/var/lib/plan-watch
```

### 源码构建

```bash
mvn -f server/pom.xml package
# 产物：server/target/plan-watch-server.jar（测试随 package 一并执行）
```

### 客户端接入

桌面应用 → 设置 → 通用设置 → **后端接口地址** 填 `http://<服务端IP>:8787` 保存，
标题栏出现「服务端」徽标即切换成功（清空地址回到本地查询）。

> 安全提示：服务端 API **未做鉴权、CORS 全开**，适合内网/个人使用；
> 暴露公网请自行加反向代理鉴权。`data/config.json` 含明文 API Key，注意文件权限。

## 配置文件

桌面端与服务端使用**同一份结构**（服务端保存在 `<数据目录>/config.json`）：

- 桌面端 Windows：`%APPDATA%\com.planwatch.app\config.json`
- 桌面端 macOS：`~/Library/Application Support/com.planwatch.app/config.json`
- 服务端：`<数据目录>/config.json`（默认 `./data/config.json`）

### 完整示例

```json
{
  "refreshIntervalSecs": 300,
  "lowQuotaThreshold": 80,
  "accounts": [
    {
      "id": "11111111-1111-4111-8111-111111111111",
      "name": "智谱主力",
      "provider": "zhipu",
      "region": "cn",
      "apiKey": "71afecd4xxxxxxxxxxxxxxxxxxxxxxxx.rNfHZroijs1uNXRw",
      "enabled": true
    },
    {
      "id": "22222222-2222-4222-8222-222222222222",
      "name": "MiniMax 工作",
      "provider": "minimax",
      "region": "cn",
      "apiKey": "sk-cp-xxxxxxxxxxxx",
      "enabled": true
    }
  ]
}
```

### 字段说明

**顶层**

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `refreshIntervalSecs` | number | 300 | 刷新间隔（秒），保存时自动钳制到 60–86400 |
| `lowQuotaThreshold` | number | 80 | 低额度提醒阈值（已用百分比），钳制到 10–99；越线弹一次系统通知，带迟滞去重 |
| `accounts` | array | `[]` | 账号列表，见下 |

**账号（accounts[]）**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 任意唯一 ID（UUID 即可；为空时保存会自动生成） |
| `name` | string | 显示名称（浮动列表 / 设置页） |
| `provider` | string | `zhipu`（智谱 GLM）或 `minimax` |
| `region` | string | `cn`（国内站）或 `global`（国际站；两站账号体系独立，Key 不通用） |
| `apiKey` | string | 供应商 API Key，见下表 |
| `enabled` | boolean | `true` | `false` 时跳过查询（保留配置） |

**供应商与站点对应的查询域名**

| provider | region | 查询域名 | Key 获取 | Key 形态 |
|---|---|---|---|---|
| `zhipu` | `cn` | `open.bigmodel.cn` | [开放平台控制台](https://open.bigmodel.cn/usercenter/apikeys) | `{32位hex}.{secret}` |
| `zhipu` | `global` | `api.z.ai` | z.ai 控制台 | 同上 |
| `minimax` | `cn` | `api.minimaxi.com` | MiniMax 开放平台 · 编程套餐 | `sk-cp-…`（100+ 字符） |
| `minimax` | `global` | `api.minimax.io` | MiniMax 国际站 | 同上 |

**写入安全**：保存时原子替换（临时文件 + rename），旧版本自动备份为 `config.json.bak`；
文件损坏时自动隔离为 `config.json.corrupt` 并回退默认配置。

> 手改配置文件时：桌面端需重启应用生效；服务端下次保存/重启生效（推荐直接走 API 或 UI 修改）。

## 服务端 REST API

接口与桌面端 Tauri commands 同构，JSON 字段与配置文件一致（camelCase）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/config` | 读取配置 |
| PUT | `/api/config` | 保存配置（服务端会做钳制/去重规范化） |
| GET | `/api/statuses` | 全部账号最近一次查询状态（按配置顺序） |
| POST | `/api/refresh` | 立即刷新全部启用账号（同步完成后返回最新状态） |
| POST | `/api/test` | 临时测试一个账号（body 为单个 account 对象，不落盘） |

```bash
# 示例：读取状态
curl http://127.0.0.1:8787/api/statuses

# 示例：新增/修改配置（整份提交）
curl -X PUT http://127.0.0.1:8787/api/config \
  -H "Content-Type: application/json" \
  -d @config.json

# 示例：测试一把 Key 是否有效
curl -X POST http://127.0.0.1:8787/api/test \
  -H "Content-Type: application/json" \
  -d '{"id":"t","name":"t","provider":"zhipu","region":"cn","apiKey":"你的KEY","enabled":true}'
```

`/api/statuses` 响应示例（`unlimited: true` 即 ∞；`stale: true` 表示网络异常时沿用的上次成功数据）：

```json
[
  {
    "accountId": "11111111-1111-4111-8111-111111111111",
    "ok": true,
    "error": null,
    "planLevel": "lite",
    "tiers": [
      { "window": "five_hour", "usedPercent": 0.0, "resetsAt": null,
        "used": 0.0, "total": 2000.0, "remaining": 2000.0, "unlimited": false },
      { "window": "weekly", "usedPercent": 47.0, "resetsAt": "2026-08-28T12:18:49.998Z",
        "used": 4788.0, "total": 10000.0, "remaining": 5211.0, "unlimited": false }
    ],
    "queriedAt": 1787640792735,
    "stale": false
  }
]
```

错误三分类（`error.kind`）：`auth` Key 无效/过期 · `business` 接口业务失败 · `network` 瞬时网络失败（保留旧数据）。

## 开发

```bash
# 桌面应用（前端 + Rust）
cd app && npm install
npm run tauri dev          # 开发
npm run tauri build        # 打包
npm run typecheck          # 前端类型检查

# Rust 单测（38 个，覆盖两个供应商全部已实测坑点）
cargo test --manifest-path app/src-tauri/Cargo.toml

# 服务端
mvn -f server/pom.xml test         # 9 个解析单测
mvn -f server/pom.xml spring-boot:run
```

目录结构：

```
├── app/                    # Tauri 桌面应用（自包含，可独立构建）
│   ├── src/                # 前端：shared / settings / float / styles 按窗口分层
│   └── src-tauri/          # Rust：quota/（查询与解析）· tray · scheduler · config
├── server/                 # Spring Boot 4.1.x（model / service / web）
├── docs/providers/         # 供应商接口调研文档（含实测响应与全部坑）
└── tools/gen-icons.ps1     # 双色胶囊图标生成脚本
```

## 发布

推送 `v*` 标签触发 GitHub Actions：三平台安装包（Windows x64 / macOS ARM / macOS Intel）
+ 服务端 jar 自动构建并挂到**草稿** Release，检查后在 Releases 页点 Publish（或
`gh release edit vX.Y.Z --draft=false --latest`）对外发布。

```bash
git tag v0.4.1 && git push origin v0.4.1
```

## License

[MIT](LICENSE)
