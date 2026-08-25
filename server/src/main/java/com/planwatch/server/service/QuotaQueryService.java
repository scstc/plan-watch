package com.planwatch.server.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.planwatch.server.model.Account;
import com.planwatch.server.model.AccountStatus;
import com.planwatch.server.model.ErrorKind;
import com.planwatch.server.model.QuotaTier;
import com.planwatch.server.model.Region;
import com.planwatch.server.model.WindowKind;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 供应商额度查询与解析，规则与桌面端 src-tauri/src/quota/ 完全一致
 * （调研文档：docs/providers/*.md，2026-08-25 真机实测）。
 *
 * 关键规则：
 * - 智谱：HTTP 200 + success:false + code:401 也是凭证失效；窗口分类靠 unit（3=5h, 6=周），
 *   unit 缺失才用「无 reset 优先 5h + reset 升序」兜底
 * - MiniMax：只取 model_name=general；remaining_percent 是"剩余"要取反；
 *   weekly_status==1 周桶激活，其他值=无周限额（unlimited ∞）；业务码 1004=凭证失效
 * - HTTP 401/403 → 鉴权失败；429/5xx → 瞬时网络失败（调用方保留旧数据）；其余 4xx → 业务错误
 */
@Service
public class QuotaQueryService {

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public QuotaQueryService() {
        this.restClient = RestClient.builder().build();
    }

    /** 查询单个账号（15s 超时）。 */
    public AccountStatus query(Account account) {
        return switch (account.provider()) {
            case ZHIPU -> queryZhipu(account);
            case MINIMAX -> queryMinimax(account);
        };
    }

    // ── 智谱 ──────────────────────────────────────────────

    private AccountStatus queryZhipu(Account account) {
        String base = account.region() == Region.CN ? "https://open.bigmodel.cn" : "https://api.z.ai";
        String url = base + "/api/monitor/usage/quota/limit";
        JsonNode body;
        try {
            String raw = restClient.get().uri(url)
                    // 智谱 Key 原样放 Authorization 头，不加 Bearer 前缀
                    .header("Authorization", account.apiKey())
                    .header("Accept-Language", "en-US,en")
                    .retrieve()
                    .body(String.class);
            body = mapper.readTree(raw == null ? "" : raw);
        } catch (RestClientResponseException e) {
            return statusFromHttpError(account, e.getStatusCode(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            return networkError(account, "Network error: " + e.getMessage());
        } catch (Exception e) {
            return businessError(account, "Failed to parse response: " + e.getMessage());
        }
        return zhipuFromBody(account, body);
    }

    private AccountStatus zhipuFromBody(Account account, JsonNode body) {
        // 业务层错误：Key 无效时 HTTP 200 + code:401 + success:false —— 必须识别为凭证失效
        if (body.has("success") && !body.path("success").asBoolean(true)) {
            String msg = body.path("msg").asText("Unknown error");
            long code = body.path("code").asLong(0);
            if (code == 401) {
                return authError(account, msg);
            }
            return businessError(account, "API error (code " + code + "): " + msg);
        }
        JsonNode data = body.get("data");
        if (data == null) {
            return businessError(account, "Missing 'data' field in response");
        }
        String planLevel = data.path("level").isTextual() ? data.path("level").asText() : null;
        return new AccountStatus(account.id(), true, null, planLevel,
                parseZhipuTiers(data), now(), false);
    }

    /**
     * 解析智谱 data.limits[]：
     * 分类优先级 unit 字段（3→5h，6→周；不能按 reset 时间排序，周期末尾会标反），
     * 兜底启发式：无 nextResetTime 的条目优先归 5h，其余按 reset 升序填空位。
     */
    public List<QuotaTier> parseZhipuTiers(JsonNode data) {
        record Entry(Long resetMs, double pct, String resetIso, Double used, Double total, Double remaining) {
        }
        Entry fiveHour = null;
        Entry weekly = null;
        List<Entry> unclassified = new ArrayList<>();

        for (JsonNode item : data.path("limits")) {
            String type = item.path("type").asText("");
            if (!type.equalsIgnoreCase("TOKENS_LIMIT") && !type.equalsIgnoreCase("CREDIT_LIMIT")) {
                continue;
            }
            double pct = parseDouble(item.get("percentage"), 0.0);
            Long resetMs = item.hasNonNull("nextResetTime") ? item.get("nextResetTime").asLong() : null;
            Entry entry = new Entry(resetMs, pct, resetMs == null ? null : millisToIso(resetMs),
                    parseDouble(item.get("currentValue"), null),
                    parseDouble(item.get("usage"), null),
                    parseDouble(item.get("remaining"), null));
            switch (item.path("unit").asInt(-1)) {
                case 3 -> { if (fiveHour == null) { fiveHour = entry; } else unclassified.add(entry); }
                case 6 -> { if (weekly == null) { weekly = entry; } else unclassified.add(entry); }
                default -> unclassified.add(entry);
            }
        }

        // 无 reset 的优先（归 5h 槽），其余按 reset 升序
        unclassified.sort(Comparator.comparing((Entry e) -> e.resetMs() != null)
                .thenComparing(e -> e.resetMs() == null ? Long.MIN_VALUE : e.resetMs()));
        for (Entry e : unclassified) {
            if (fiveHour == null) {
                fiveHour = e;
            } else if (weekly == null) {
                weekly = e;
            }
            // 最多两条，多余的忽略
        }

        List<QuotaTier> tiers = new ArrayList<>();
        if (fiveHour != null) {
            tiers.add(new QuotaTier(WindowKind.FIVE_HOUR, fiveHour.pct(), fiveHour.resetIso(),
                    fiveHour.used(), fiveHour.total(), fiveHour.remaining(), false));
        }
        if (weekly != null) {
            tiers.add(new QuotaTier(WindowKind.WEEKLY, weekly.pct(), weekly.resetIso(),
                    weekly.used(), weekly.total(), weekly.remaining(), false));
        }
        return tiers;
    }

    // ── MiniMax ───────────────────────────────────────────

    private AccountStatus queryMinimax(Account account) {
        String domain = account.region() == Region.CN ? "api.minimaxi.com" : "api.minimax.io";
        String url = "https://" + domain + "/v1/api/openplatform/coding_plan/remains";
        JsonNode body;
        try {
            String raw = restClient.get().uri(url)
                    .header("Authorization", "Bearer " + account.apiKey())
                    .retrieve()
                    .body(String.class);
            body = mapper.readTree(raw == null ? "" : raw);
        } catch (RestClientResponseException e) {
            return statusFromHttpError(account, e.getStatusCode(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            return networkError(account, "Network error: " + e.getMessage());
        } catch (Exception e) {
            return businessError(account, "Failed to parse response: " + e.getMessage());
        }
        return minimaxFromBody(account, body);
    }

    private AccountStatus minimaxFromBody(Account account, JsonNode body) {
        JsonNode baseResp = body.path("base_resp");
        if (baseResp.isObject() && baseResp.path("status_code").asLong(-1) != 0) {
            long code = baseResp.path("status_code").asLong(-1);
            String msg = baseResp.path("status_msg").asText("Unknown error");
            // 1004（"cookie is missing, log in again"，2026-08-25 实测）本质是凭证失效
            if (code == 1004) {
                return authError(account, msg);
            }
            return businessError(account, "API error (code " + code + "): " + msg);
        }
        return new AccountStatus(account.id(), true, null, null,
                parseMinimaxTiers(body), now(), false);
    }

    /**
     * 解析 MiniMax：只取 model_name=general；remaining_percent 是"剩余"取反成已用；
     * weekly_status==1 → 周桶激活；存在但非 1 → 无周限额（unlimited ∞）；缺失 → 不产出。
     */
    public List<QuotaTier> parseMinimaxTiers(JsonNode body) {
        List<QuotaTier> tiers = new ArrayList<>();
        JsonNode general = null;
        for (JsonNode item : body.path("model_remains")) {
            if ("general".equals(item.path("model_name").asText())) {
                general = item;
                break;
            }
        }
        if (general == null) {
            return tiers;
        }

        JsonNode remain5h = general.get("current_interval_remaining_percent");
        if (remain5h != null && remain5h.isNumber()) {
            String reset = general.hasNonNull("end_time")
                    ? millisToIso(general.get("end_time").asLong()) : null;
            tiers.add(new QuotaTier(WindowKind.FIVE_HOUR, 100.0 - remain5h.asDouble(), reset,
                    null, null, null, false));
        }

        JsonNode weeklyStatus = general.get("current_weekly_status");
        if (weeklyStatus != null) {
            if (weeklyStatus.asLong() == 1) {
                JsonNode remainW = general.get("current_weekly_remaining_percent");
                if (remainW != null && remainW.isNumber()) {
                    String reset = general.hasNonNull("weekly_end_time")
                            ? millisToIso(general.get("weekly_end_time").asLong()) : null;
                    tiers.add(new QuotaTier(WindowKind.WEEKLY, 100.0 - remainW.asDouble(), reset,
                            null, null, null, false));
                }
            } else {
                tiers.add(new QuotaTier(WindowKind.WEEKLY, 0.0, null, null, null, null, true));
            }
        }
        return tiers;
    }

    // ── 共享 ──────────────────────────────────────────────

    /** HTTP 层错误分类：401/403 鉴权；429/5xx 瞬时网络；其余 4xx 业务。body 截断 200 字符。 */
    private AccountStatus statusFromHttpError(Account account, HttpStatusCode status, String body) {
        if (status.value() == 401 || status.value() == 403) {
            return authError(account, "Authentication failed (HTTP " + status.value() + ")");
        }
        String truncated = truncate(body == null ? "" : body, 200);
        String msg = "API error (HTTP " + status.value() + "): " + truncated;
        if (status.value() == 429 || status.is5xxServerError()) {
            return networkError(account, msg);
        }
        return businessError(account, msg);
    }

    private AccountStatus authError(Account account, String message) {
        return new AccountStatus(account.id(), false,
                new com.planwatch.server.model.AccountError(ErrorKind.AUTH, message),
                null, List.of(), now(), false);
    }

    private AccountStatus businessError(Account account, String message) {
        return new AccountStatus(account.id(), false,
                new com.planwatch.server.model.AccountError(ErrorKind.BUSINESS, message),
                null, List.of(), now(), false);
    }

    private AccountStatus networkError(Account account, String message) {
        return new AccountStatus(account.id(), false,
                new com.planwatch.server.model.AccountError(ErrorKind.NETWORK, message),
                null, List.of(), now(), true);
    }

    private static Double parseDouble(JsonNode v, Double fallback) {
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isTextual()) {
            try {
                return Double.parseDouble(v.asText());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static String millisToIso(long ms) {
        return Instant.ofEpochMilli(ms).toString();
    }

    private static String truncate(String s, int max) {
        return s.codePoints().limit(max)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}


