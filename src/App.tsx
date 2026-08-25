import { useCallback, useEffect, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import * as api from "./api";
import type { Account, AccountStatus, AppConfig } from "./types";
import { AccountCard } from "./components/AccountCard";
import { AccountForm } from "./components/AccountForm";
import { byId, EVENT_STATUS } from "./useQuotas";
import { fmtClock } from "./format";

/** 数字输入就地钳制（空输入 Number("")=0 也会被拉回下限） */
const clamp = (v: number, lo: number, hi: number): number =>
  Number.isNaN(v) ? lo : Math.max(lo, Math.min(hi, Math.round(v)));

export default function App() {
  const [config, setConfig] = useState<AppConfig | null>(null);
  const [statuses, setStatuses] = useState<Record<string, AccountStatus>>({});
  const [editing, setEditing] = useState<Account | "new" | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [serverUrl, setServerUrlDraft] = useState(api.getServerUrl());

  // 通用设置的草稿（从 config 同步，保存后落盘）
  const [intervalMin, setIntervalMin] = useState(5);
  const [threshold, setThreshold] = useState(80);

  // config 变化时同步草稿（初始加载 / 服务端返回规范化值 / 回滚）
  useEffect(() => {
    if (config) {
      setIntervalMin(Math.max(1, Math.round(config.refreshIntervalSecs / 60)));
      setThreshold(config.lowQuotaThreshold);
    }
  }, [config?.refreshIntervalSecs, config?.lowQuotaThreshold]);

  useEffect(() => {
    let alive = true;
    let unlisten: (() => void) | null = null;

    const loadAll = async () => {
      try {
        const [cfg, sts] = await Promise.all([api.getConfig(), api.getStatuses()]);
        if (!alive) return;
        setConfig(cfg);
        setIntervalMin(Math.max(1, Math.round(cfg.refreshIntervalSecs / 60)));
        setThreshold(cfg.lowQuotaThreshold);
        setStatuses(byId(sts));
        setLoadError(null);
      } catch (e) {
        if (alive) setLoadError(String(e));
        return;
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

  const saveGeneral = useCallback(async () => {
    if (!config) return;
    // 后端接口地址是本机偏好（localStorage），不进 AppConfig
    api.setServerUrl(serverUrl);
    // 数据源可能切换：先按新模式取最新配置做基底，
    // 避免把本地账号清单覆盖到服务端（或反之）
    let base = config;
    try {
      base = await api.getConfig();
    } catch {
      // 地址不可达等：继续用当前 config 保存，错误经 persist 的回滚路径浮出
    }
    await persist({
      ...base,
      refreshIntervalSecs: Math.max(60, Math.min(86_400, Math.round(intervalMin * 60))),
      lowQuotaThreshold: Math.max(10, Math.min(99, Math.round(threshold))),
    });
  }, [config, intervalMin, threshold, serverUrl, persist]);

  if (loadError && !config) {
    return <div className="app">加载失败：{loadError}</div>;
  }
  if (!config) {
    return <div className="app">加载中…</div>;
  }

  const lastRefreshMs = Math.max(0, ...Object.values(statuses).map((s) => s.queriedAt ?? 0));
  const generalDirty =
    Math.round(intervalMin * 60) !== config.refreshIntervalSecs ||
    Math.round(threshold) !== config.lowQuotaThreshold ||
    serverUrl.trim() !== api.getServerUrl();

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

      <section className="card">
        <div className="card-head">
          <h2>通用设置</h2>
        </div>
        <div className="form-grid">
          <label>
            刷新间隔（分钟）
            <input
              type="number"
              min={1}
              max={1440}
              value={intervalMin}
              onChange={(e) => setIntervalMin(clamp(Number(e.target.value), 1, 1440))}
            />
          </label>
          <label>
            低额度提醒阈值（% 已用）
            <input
              type="number"
              min={10}
              max={99}
              value={threshold}
              onChange={(e) => setThreshold(clamp(Number(e.target.value), 10, 99))}
            />
          </label>
          <div className="form-actions inline">
            <button className="primary" onClick={saveGeneral} disabled={!generalDirty}>
              保存设置
            </button>
          </div>
          <label className="span-3">
            后端接口地址（填了走服务端取数，留空使用本地查询）
            <input
              value={serverUrl}
              onChange={(e) => setServerUrlDraft(e.target.value)}
              placeholder="http://192.168.1.100:8787"
              spellCheck={false}
            />
          </label>
        </div>
      </section>

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
        关闭窗口即隐藏到托盘继续监控；额度变化从托盘菜单查看。plan-watch v0.3.0
      </footer>
    </div>
  );
}
