# MiniMax 编程套餐余额查询

> 实测日期：2026-08-25（国内站 `api.minimaxi.com`，HTTP 200 验证通过）
>
> 参考实现：[cc-switch](https://github.com/farion1231/cc-switch) `src-tauri/src/services/coding_plan.rs` 中的 `query_minimax` / `parse_minimax_tiers`

## 接口

```
GET https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains   # 国内站
GET https://api.minimax.io/v1/api/openplatform/coding_plan/remains     # 国际站
```

国内 / 国际站账号体系独立，按 Key 所属站点选择域名。

## 鉴权

```
Authorization: Bearer <API_KEY>
```

编程套餐 Key 在 MiniMax 开放平台控制台获取，形如 `sk-cp-...`，长度 100+ 字符。

## 复现命令

```bash
curl -s -H "Authorization: Bearer <API_KEY>" \
  "https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains"
```

## 响应示例（实测，2026-08-25）

```json
{
  "model_remains": [
    {
      "start_time": 1787623200000,
      "end_time": 1787641200000,
      "remains_time": 15436231,
      "current_interval_total_count": 0,
      "current_interval_usage_count": 0,
      "model_name": "general",
      "current_weekly_total_count": 0,
      "current_weekly_usage_count": 0,
      "weekly_start_time": 1787500800000,
      "weekly_end_time": 1788105600000,
      "weekly_remains_time": 479836231,
      "current_interval_status": 1,
      "current_interval_remaining_percent": 100,
      "current_weekly_status": 3,
      "current_weekly_remaining_percent": 100
    },
    {
      "start_time": 1787587200000,
      "end_time": 1787673600000,
      "remains_time": 47836231,
      "model_name": "video",
      "current_interval_status": 3,
      "current_interval_remaining_percent": 100,
      "current_weekly_status": 3,
      "current_weekly_remaining_percent": 100
    }
  ],
  "base_resp": { "status_code": 0, "status_msg": "success" }
}
```

（video 条目的无关字段已省略）

## 字段与解析规则

- **业务错误**：`base_resp.status_code != 0` 时 `status_msg` 为错误信息。鉴权失败为 HTTP 401/403。
- **`model_remains[]`**：按模型分条目，**只取 `model_name == "general"`**（编程套餐额度），`video` 等非编程模型跳过。
- **5 小时桶**（每个套餐都有）：
  - `current_interval_remaining_percent`：**剩余**百分比（0–100）→ 已用 = `100 - x`
  - `end_time`：当前窗口结束时间（毫秒时间戳），即重置时间
- **周桶**（并非所有套餐都有）：
  - 仅当 `current_weekly_status == 1` 才存在；`== 3` 表示该套餐**无周限额**，必须跳过
  - `current_weekly_remaining_percent`：剩余百分比 → 已用 = `100 - x`
  - `weekly_end_time`：周窗口结束时间（毫秒时间戳）
- `remains_time` / `weekly_remains_time`：当前窗口剩余时长（毫秒），可用来展示"距重置约 X 小时"。

## 坑

1. 字段语义是**剩余**而不是已用，直接当已用展示会完全取反
2. 无周限额套餐的 `current_weekly_remaining_percent` 恒为 100，绝不能当成"周额度还剩 100%"展示，必须靠 `current_weekly_status == 1` 过滤
3. 百分比是数值型（非字符串）；时间戳均为**毫秒**
4. 周窗口按北京时间自然周对齐（实测周一 00:00 CST → 下周一 00:00 CST）
