import { useCallback, useEffect, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import * as api from "../shared/api";
import type { Account, AccountStatus, AppConfig } from "../shared/types";
import { AccountCard } from "./AccountCard";
import { AccountForm } from "./AccountForm";
import { GeneralSettingsCard } from "./GeneralSettingsCard";
import { byId, EVENT_STATUS } from "../shared/useQuotas";
import { fmtClock } from "../shared/format";

/** 设置窗口（main）：数据加载 + 账号管理 + 通用设置 */
export default function SettingsApp() {
  const [config, setConfig] = useState<AppConfig | null>(null);
  const [statuses, setStatuses] = useState<Record<string, AccountStatus>>({});
  const [editing, setEditing] = useState<Account | "new" | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    let unlisten: (() => void) | null = null;

    const loadAll = async () => {
      try {
        const [cfg, sts] = await Promise.all([api.getConfig(), api.getStatuses()]);
        if (!alive) return;
        setConfig(cfg);
        setStatuses(byId(sts));
        setLoadError(null);
      } catch (e) {
        if (alive) setLoadError(String(e));
      }
    };
    void loadAll();
    // 轮询兜底（服务端模式的主通道）
    const timer = setInterval(() => {
      if (alive) void loadAll();
    }, 15_000);

    void listen<AccountStatus[]>(EVENT_STATUS, (ev) => {
      if (alive && !api.isServerMode()) setStatuses(byId(ev.payload));
    }).then((u) => {
      if (alive) unlisten = u;
    });

    return () => {
      alive = false;
      clearInterval(timer);
      unlisten?.();
    };
  }, []);

  const persist = useCallback(async (next: AppConfig) => {
    setConfig(next); // 乐观更新，失败则回滚
    try {
      const saved = await api.saveConfig(next);
      // 服务端模式返回服务端规范化后的配置（钳制/去重后的值）
      if (saved) setConfig(saved);
    } catch (e) {
      setLoadError(`保存失败：${String(e)}`);
      setConfig(await api.getConfig());
    }
  }, []);

  const handleRefresh = useCallback(async () => {
    setRefreshing(true);
    try {
      await api.refreshNow();
      setStatuses(byId(await api.getStatuses()));
    } catch (e) {
      setLoadError(String(e));
    } finally {
      setRefreshing(false);
    }
  }, []);

  const saveAccount = useCallback(
    async (account: Account) => {
      if (!config) return;
      const others = config.accounts.filter((a) => a.id !== account.id);
      await persist({ ...config, accounts: [...others, account] });
      setEditing(null);
    },
    [config, persist],
  );

  const deleteAccount = useCallback(
    async (id: string) => {
      if (!config) return;
      await persist({ ...config, accounts: config.accounts.filter((a) => a.id !== id) });
      // 正在编辑这个账号的表单持有打开时的快照，不同步关闭会在保存时"复活"它
      setEditing((e) => (e !== null && e !== "new" && e.id === id ? null : e));
    },
    [config, persist],
  );

  const toggleAccount = useCallback(
    async (id: string, enabled: boolean) => {
      if (!config) return;
      await persist({
        ...config,
        accounts: config.accounts.map((a) => (a.id === id ? { ...a, enabled } : a)),
      });
    },
    [config, persist],
  );

  if (loadError && !config) {
    return <div className="app">加载失败：{loadError}</div>;
  }
  if (!config) {
    return <div className="app">加载中…</div>;
  }

  const lastRefreshMs = Math.max(0, ...Object.values(statuses).map((s) => s.queriedAt ?? 0));

  return (
    <div className="app">
      <header>
        <div>
          <h1>
            plan-watch
            {api.isServerMode() && <span className="badge" title={api.getServerUrl()}>服务端</span>}
          </h1>
          <p className="muted">
            {lastRefreshMs > 0 ? `上次刷新 ${fmtClock(lastRefreshMs)}` : "尚未刷新"}
            {statuses && Object.keys(statuses).length > 0 ? ` · ${Object.keys(statuses).length} 个账号` : ""}
          </p>
        </div>
        <button className="primary" onClick={handleRefresh} disabled={refreshing}>
          {refreshing ? "刷新中…" : "立即刷新"}
        </button>
      </header>

      {loadError && <p className="error-banner">{loadError}</p>}

      <GeneralSettingsCard config={config} persist={persist} onSaved={() => void handleRefresh()} />

      <section className="card">
        <div className="card-head">
          <h2>账号（{config.accounts.length}）</h2>
          <button onClick={() => setEditing("new")} disabled={editing !== null}>
            + 添加账号
          </button>
        </div>

        {editing !== null && (
          <AccountForm
            initial={editing === "new" ? null : editing}
            onSave={saveAccount}
            onCancel={() => setEditing(null)}
          />
        )}

        {config.accounts.length === 0 && editing === null && (
          <p className="muted empty">还没有账号。添加一把 API Key，托盘里就能看到套餐余额。</p>
        )}

        <div className="account-list">
          {config.accounts.map((a) => (
            <AccountCard
              key={a.id}
              account={a}
              status={statuses[a.id]}
              onEdit={() => setEditing(a)}
              onDelete={() => void deleteAccount(a.id)}
              onToggle={(en) => void toggleAccount(a.id, en)}
            />
          ))}
        </div>
      </section>

      <footer className="muted small">
        关闭窗口即隐藏到托盘继续监控；额度变化看浮动列表。plan-watch v0.3.0
      </footer>
    </div>
  );
}
