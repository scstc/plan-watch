package com.planwatch.server.service;

import com.planwatch.server.model.Account;
import com.planwatch.server.model.AccountStatus;
import com.planwatch.server.model.ErrorKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 定时刷新与状态持有：
 * - 网络失败保留上次成功数据（stale=true，旧 tiers/planLevel）
 * - 到期检查每 10s 一次，实际间隔跟配置走（改配置下一轮生效）
 * - 手动刷新与定时刷新通过 synchronized 合并为串行
 */
@Service
public class RefreshService {

    private static final Logger log = LoggerFactory.getLogger(RefreshService.class);

    private final ConfigStore configStore;
    private final QuotaQueryService queryService;
    private final Map<String, AccountStatus> statuses = new ConcurrentHashMap<>();
    private final AtomicLong lastRefreshAt = new AtomicLong(0);

    public RefreshService(ConfigStore configStore, QuotaQueryService queryService) {
        this.configStore = configStore;
        this.queryService = queryService;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 3_000)
    void refreshIfDue() {
        long intervalMs = configStore.get().refreshIntervalSecs() * 1000;
        if (System.currentTimeMillis() - lastRefreshAt.get() >= intervalMs) {
            try {
                refreshAll();
            } catch (Exception e) {
                log.warn("定时刷新失败: {}", e.getMessage());
            }
        }
    }

    /** 刷新全部启用账号（串行合并；网络失败沿用旧数据）。 */
    public synchronized void refreshAll() {
        List<Account> enabled = configStore.get().accounts().stream()
                .filter(Account::enabled)
                .toList();
        for (Account account : enabled) {
            try {
                AccountStatus next = queryService.query(account);
                statuses.put(account.id(), mergeNetworkFailure(next, statuses.get(account.id())));
            } catch (Exception e) {
                // 查询内部已分类，这里是兜底：意外异常按业务错误展示，不吞
                statuses.put(account.id(), new AccountStatus(account.id(), false,
                        new com.planwatch.server.model.AccountError(ErrorKind.BUSINESS,
                                "Unexpected error: " + e.getMessage()),
                        null, List.of(), System.currentTimeMillis(), false));
            }
        }
        lastRefreshAt.set(System.currentTimeMillis());
    }

    /** 网络失败时保留上次成功数据（tiers/planLevel），标记 stale。 */
    private AccountStatus mergeNetworkFailure(AccountStatus next, AccountStatus old) {
        if (next.error() != null && next.error().kind() == ErrorKind.NETWORK && old != null) {
            return new AccountStatus(next.accountId(), false, next.error(), old.planLevel(),
                    old.tiers(), next.queriedAt(), true);
        }
        return next;
    }

    /** 按配置顺序输出全部账号状态（前端渲染顺序稳定）。 */
    public List<AccountStatus> statusesOrdered() {
        return configStore.get().accounts().stream()
                .map(a -> statuses.getOrDefault(a.id(), AccountStatus.empty(a.id())))
                .toList();
    }

    /** 配置变更后立即刷一轮（异步，不阻塞保存请求）。 */
    public void kickRefresh() {
        Thread.ofVirtual().start(this::refreshAll);
    }
}
