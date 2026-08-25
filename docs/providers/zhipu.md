# 智谱 GLM 套餐余额查询

> 实测日期：2026-08-25（国内站 `open.bigmodel.cn`，lite 积分套餐验证通过）
>
> 参考实现：[cc-switch](https://github.com/farion1231/cc-switch) `src-tauri/src/services/coding_plan.rs` 中的 `query_zhipu` / `parse_zhipu_token_tiers`

## 接口

```
GET https://open.bigmodel.cn/api/monitor/usage/quota/limit   # 国内站
GET https://api.z.ai/api/monitor/usage/quota/limit           # 国际站
```

两站共用同一后端，接口路径与响应 shape 完全一致。按账号所属站选择域名，账号体系独立。

## 鉴权

```
Authorization: <API_KEY>
```

- Key 为智谱经典格式：`{32位hex}.{secret}`，在 [开放平台控制台](https://open.bigmodel.cn/usercenter/apikeys) 获取
- 原样放入 Authorization 头，**不加 `Bearer` 前缀**（实测带 Bearer 也能通过，但不带是更稳的写法）
- 可选 `Accept-Language: en-US,en` 让错误消息返回英文

## 复现命令

```bash
curl -s -H "Authorization: <API_KEY>" \
  "https://open.bigmodel.cn/api/monitor/usage/quota/limit"
```

## 响应示例（实测，2026-08-25，lite 积分套餐）

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "limits": [
      {
        "type": "CREDIT_LIMIT",
        "unit": 3,
        "number": 5,
        "usage": 2000,
        "currentValue": 0,
        "remaining": 2000,
        "percentage": 0
      },
      {
        "type": "CREDIT_LIMIT",
        "unit": 6,
        "number": 1,
        "usage": 10000,
        "currentValue": 4788,
        "remaining": 5211,
        "percentage": 47,
        "nextResetTime": 1787919529998
      }
    ],
    "level": "lite"
  },
  "success": true
}
```

解读：lite 套餐，5 小时桶已用 0%（0/2000 积分，无重置时间），周桶已用 47%（4788/10000 积分，2026-08-28 20:18 北京时间重置）。

## 字段与解析规则

- **业务错误**：HTTP 状态码**不反映**业务错误——Key 无效时返回 HTTP 200 + `{"code":401,"msg":"令牌已过期或验证不正确","success":false}`。解析必须先看 `success` 字段。
- **`data.level`**：套餐档位（如 `lite`），可直接展示。
- **`data.limits[]`**，每条一个限额窗口：
  - `type`：`TOKENS_LIMIT`（Token 套餐）或 `CREDIT_LIMIT`（积分套餐），**大小写不敏感，两种都要识别**
  - `percentage`：**已用**百分比（注意与 MiniMax 的"剩余"语义相反），直接展示
  - `nextResetTime`：重置时间（毫秒时间戳）
  - **窗口分类靠 `unit` 字段**：`unit:3` → 5 小时窗口；`unit:6` → 每周窗口（`number` 实测有 7 和 1 两种取值，只锚定 `unit`，不要绑 `number`）
  - 积分套餐额外有 `usage`（总额度）/ `currentValue`（已用）/ `remaining`（剩余），可展示绝对量

## 坑

1. **不能按 `nextResetTime` 排序区分窗口**：周期末尾时每周窗口会比 5 小时窗口更早重置，按时间排序必然把两桶标反（cc-switch issue #3036）。必须靠 `unit` 显式分类，`unit` 缺失才用兜底启发式。
2. 兜底启发式（`unit` 缺失/不认识时）：无 `nextResetTime` 的条目优先归 5 小时桶（5 小时桶在 0% 等状态下**没有 reset 字段**），其余按 reset 时间升序填入空缺槽位。
3. 老套餐（2026-02-12 前订阅）只回 1 条 limit → 降级只展示 5 小时桶；新套餐回 2 条。
4. Key 无效时报错是业务层 `code:401`，HTTP 层仍是 200——不要依赖 HTTP 状态码判断凭证有效性。
5. 国内站（bigmodel.cn）与国际站（z.ai）账号体系独立，Key 不通用。

## 团队版（zhipu_team）

> 未实测，以下来自 cc-switch 源码（`query_zhipu_team`，含单测覆盖）。

- 接口同个人版，加查询参数 `?type=2`，**仅国内站**（团队版只存在于国内站）
- 额外请求头（与 API Key 三者缺一不可）：
  ```
  bigmodel-organization: <组织 ID>
  bigmodel-project: <项目 ID>
  ```
- 响应 shape 与个人版完全一致，可复用同一套解析
