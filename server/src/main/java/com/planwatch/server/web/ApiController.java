package com.planwatch.server.web;

import com.planwatch.server.model.Account;
import com.planwatch.server.model.AccountStatus;
import com.planwatch.server.model.AppConfig;
import com.planwatch.server.model.ProviderKind;
import com.planwatch.server.model.Region;
import com.planwatch.server.service.ConfigStore;
import com.planwatch.server.service.QuotaQueryService;
import com.planwatch.server.service.RefreshService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API —— 与桌面端 Tauri commands（get_config / save_config / get_statuses /
 * refresh_now / test_account）一一同构，前端按同一套 TS 类型渲染。
 * 鉴权由 AuthFilter 统一处理，控制器只关心业务；CORS 见 CorsConfig。
 *
 * 密钥脱敏：GET /api/config 不下发供应商 apiKey（置空，附 apiKeyMasked 预览），
 * 供应商调用全部在服务端完成；PUT 时空 apiKey = 沿用已存 key（见 ConfigStore）。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    /** 响应视图：apiKey 恒为空串，真实密钥以 apiKeyMasked（…+末4位）示意。 */
    public record AccountView(String id, String name, ProviderKind provider,
                              Region region, String apiKey, String apiKeyMasked, boolean enabled) {}

    public record AppConfigView(long refreshIntervalSecs, int lowQuotaThreshold,
                                List<AccountView> accounts) {}

    private final ConfigStore configStore;
    private final RefreshService refreshService;
    private final QuotaQueryService queryService;

    public ApiController(ConfigStore configStore, RefreshService refreshService,
                         QuotaQueryService queryService) {
        this.configStore = configStore;
        this.refreshService = refreshService;
        this.queryService = queryService;
    }

    @GetMapping("/config")
    public AppConfigView getConfig() {
        return masked(configStore.get());
    }

    /** 保存配置：合并空 key → 规范化 → 落盘 → 异步触发一轮刷新。返回脱敏视图。 */
    @PutMapping("/config")
    public ResponseEntity<AppConfigView> saveConfig(@RequestBody AppConfig config) {
        AppConfig saved = configStore.save(config);
        refreshService.kickRefresh();
        return ResponseEntity.ok(masked(saved));
    }

    @GetMapping("/statuses")
    public List<AccountStatus> getStatuses() {
        return refreshService.statusesOrdered();
    }

    /** 手动刷新（同步完成后返回最新状态）。 */
    @PostMapping("/refresh")
    public List<AccountStatus> refresh() {
        refreshService.refreshAll();
        return refreshService.statusesOrdered();
    }

    /** 用表单当前值即时测试一个账号（不写状态、不落盘）。 */
    @PostMapping("/test")
    public AccountStatus testAccount(@RequestBody Account account) {
        return queryService.query(account);
    }

    private static AppConfigView masked(AppConfig c) {
        List<AccountView> accounts = c.accounts().stream()
                .map(a -> new AccountView(a.id(), a.name(), a.provider(), a.region(),
                        "", mask(a.apiKey()), a.enabled()))
                .toList();
        return new AppConfigView(c.refreshIntervalSecs(), c.lowQuotaThreshold(), accounts);
    }

    private static String mask(String apiKey) {
        if (apiKey == null || apiKey.length() <= 4) {
            return "…";
        }
        return "…" + apiKey.substring(apiKey.length() - 4);
    }
}
