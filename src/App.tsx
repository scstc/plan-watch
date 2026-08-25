import { useCallback, useEffect, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import * as api from "./api";
import type { Account, AccountStatus, AppConfig } from "./types";
import { AccountCard } from "./components/AccountCard";
import { AccountForm } from "./components/AccountForm";
import { fmtClock } from "./format";

const EVENT_STATUS = "status-updated";

/** 数字输入就地钳制（空输入 Number("")=0 也会被拉回下限） */
const clamp = (v: number, lo: number, hi: number): number =>
  Number.isNaN(v) ? lo : Math.max(lo, Math.min(hi, Math.round(v)));

function byId(list: AccountStatus[]): Record<string, AccountStatus> {
  const map: Record<string, AccountStatus> = {};
  for (const s of list) map[s.accountId] = s;
  return map;
}

export default function App() {
  const [config, setConfig] = useState<AppConfig | null>(null);
  const [statuses, setStatuses] = useState<Record<string, AccountStatus>>({});
  const [editing, setEditing] = useState<Account | "new" | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  // 通用设置的草稿（从 config 同步，保存后落盘）
  const [intervalMin, setIntervalMin] = useState(5);
  const [threshold, setThreshold] = useState(80);

  useEffect(() => {
    let unlisten: (() => void) | null = null;
    (async () => {
      try {
        const [cfg, sts] = await Promise.all([api.getConfig(), api.getStatuses()]);
        setConfig(cfg);
        setIntervalMin(Math.max(1, Math.round(cfg.refreshIntervalSecs / 60)));
        setThreshold(cfg.lowQuotaThreshold);
        setStatuses(byId(sts));
      } catch (e) {
        setLoadError(String(e));
        return;
      }
      unlisten = await listen<AccountStatus[]>(EVENT_STATUS, (ev) => setStatuses(byId(ev.payload)));
    })();
    return () => unlisten?.();
  }, []);

  const persist = useCallback(async (next: AppConfig) => {
    setConfig(next); // 乐观更新，失败则回滚
    try {
      await api.saveConfig(next);
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
    await persist({
      ...config,
      refreshIntervalSecs: Math.max(60, Math.min(86_400, Math.round(intervalMin * 60))),
      lowQuotaThreshold: Math.max(10, Math.min(99, Math.round(threshold))),
    });
  }, [config, intervalMin, threshold, persist]);

  if (loadError && !config) {
    return <div className="app">加载失败：{loadError}</div>;
  }
  if (!config) {
    return <div className="app">加载中…</div>;
  }

  const lastRefreshMs = Math.max(0, ...Object.values(statuses).map((s) => s.queriedAt ?? 0));
  const generalDirty =
    Math.round(intervalMin * 60) !== config.refreshIntervalSecs ||
    Math.round(threshold) !== config.lowQuotaThreshold;

  return (
    <div className="app">
      <header>
        <div>
          <h1>plan-watch</h1>
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
              threshold={config.lowQuotaThreshold}
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
