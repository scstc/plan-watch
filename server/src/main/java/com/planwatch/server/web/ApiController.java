package com.planwatch.server.web;

import com.planwatch.server.model.Account;
import com.planwatch.server.model.AccountStatus;
import com.planwatch.server.model.AppConfig;
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
 * 加解密由 CryptoFilter 统一处理，控制器只见到明文（CORS 见 CorsConfig）。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

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
    public AppConfig getConfig() {
        return configStore.get();
    }

    /** 保存配置：规范化 → 落盘 → 异步触发一轮刷新。 */
    @PutMapping("/config")
    public ResponseEntity<AppConfig> saveConfig(@RequestBody AppConfig config) {
        AppConfig saved = configStore.save(config);
        refreshService.kickRefresh();
        return ResponseEntity.ok(saved);
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
}
