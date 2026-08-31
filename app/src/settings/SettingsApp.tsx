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
  /** 账号变更被拒绝的就地提示（与 loadError 分开：30s 轮询会刷新后者） */
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    let unlisten: (() => void) | null = null;

    const loadAll = async () => {
      try {
        // 先尝试获取配置
        let cfg: AppConfig;
        let banner: string | null = null;
        try {
          cfg = await api.getConfig();
        } catch (configError) {
          if (!api.isServerMode()) throw configError;
          // 服务端模式失败（未配对 / 后端瞬断）：一律保留后端地址，本地配置仅作显示兜底。
          // 地址保留时下方 30s 轮询会在配对/网络恢复后自愈。
          // 不能在这里清地址（旧版 setServerUrl("") 会让后端一次瞬断就"配置全部消失"）
          cfg = await api.getConfigLocal();
          banner = configError instanceof api.PwAuthError
            ? "设备未配对：请在下方「通用设置 → 设备配对」输入服务端启动日志中的配对码"
            : `后端连接失败，暂以本地数据兜底（后端地址已保留，恢复后自动重连）：${String(configError)}`;
        }

        if (!alive) return;

        // 然后尝试获取状态（这个可以失败，不影响基本功能）
        try {
          const sts = await api.getStatuses();
          if (alive) setStatuses(byId(sts));
        } catch (statusError) {
          console.warn("获取状态失败（可以忽略）:", statusError);
          // 状态获取失败不影响配置显示
        }

        if (alive) {
          setConfig(cfg);
          setLoadError(banner);
        }
      } catch (e) {
        if (alive) setLoadError(String(e));
      }
    };
    void loadAll();
    // 轮询兜底（服务端模式的主通道）
    const timer = setInterval(() => {
      if (alive) void loadAll();
    }, 30_000); // 增加轮询间隔，避免频繁失败

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

  const persist = useCallback(async (next: AppConfig): Promise<boolean> => {
    setConfig(next); // 乐观更新，失败则回滚
    try {
      const saved = await api.saveConfig(next);
      // 服务端模式返回服务端规范化后的配置（钳制/去重后的值）
      if (saved) setConfig(saved);
      return true;
    } catch (e) {
      setLoadError(`保存失败：${String(e)}`);
      // 回滚拉取也可能失败（如后端刚断开），退回本地配置避免二次抛错
      setConfig(await api.getConfig().catch(() => api.getConfigLocal()));
      return false;
    }
  }, []);

  /**
   * 服务端模式下账号变更必须基于最新服务端配置：兜底渲染的 0 账号基线一旦
   * PUT 会整体覆盖服务端（静默丢掉其余账号）。取不到最新配置就拒绝本次变更。
   */
  const freshBase = useCallback(async (): Promise<AppConfig | null> => {
    if (!api.isServerMode()) return config;
    try {
      const base = await api.getConfig();
      setActionError(null);
      return base;
    } catch (e) {
      setActionError(e instanceof api.PwAuthError
        ? "设备未配对，账号变更未保存（请先在下方「通用设置 → 设备配对」完成配对）"
        : `后端不可达，账号变更未保存（${String(e)}）`);
      return null;
    }
  }, [config]);

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
      const base = await freshBase();
      if (!base) return;
      const others = base.accounts.filter((a) => a.id !== account.id);
      await persist({ ...base, accounts: [...others, account] });
      setEditing(null);
    },
    [config, freshBase, persist],
  );

  const deleteAccount = useCallback(
    async (id: string) => {
      if (!config) return;
      const base = await freshBase();
      if (!base) return;
      await persist({ ...base, accounts: base.accounts.filter((a) => a.id !== id) });
      // 正在编辑这个账号的表单持有打开时的快照，不同步关闭会在保存时"复活"它
      setEditing((e) => (e !== null && e !== "new" && e.id === id ? null : e));
    },
    [config, freshBase, persist],
  );

  const toggleAccount = useCallback(
    async (id: string, enabled: boolean) => {
      if (!config) return;
      const base = await freshBase();
      if (!base) return;
      await persist({
        ...base,
        accounts: base.accounts.map((a) => (a.id === id ? { ...a, enabled } : a)),
      });
    },
    [config, freshBase, persist],
  );

  if (loadError && !config) {
    const isServerError = loadError.includes("Failed to fetch") || loadError.includes("net::ERR");
    return (
      <div className="app">
        <div className="card">
          <div className="card-head">
            <h2>⚠️ 加载失败</h2>
          </div>
          <div style={{ padding: "2rem" }}>
            <p className="error-banner">{loadError}</p>
            {isServerError && (
              <>
                <p className="muted">
                  后端服务器连接失败，请尝试以下操作：
                </p>
                <ol style={{ marginLeft: "1.5rem", marginTop: "1rem" }}>
                  <li>检查后端地址是否正确</li>
                  <li>确认后端服务是否正在运行</li>
                  <li>点击下方"清除后端配置"切换到本地模式</li>
                </ol>
                <button
                  className="primary"
                  onClick={() => {
                    api.setServerUrl("");
                    window.location.reload();
                  }}
                  style={{ marginTop: "1rem" }}
                >
                  清除后端配置并重试
                </button>
              </>
            )}
          </div>
        </div>
      </div>
    );
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
        {actionError && <p className="error-banner">{actionError}</p>}

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
        关闭窗口即隐藏到托盘继续监控；额度变化看浮动列表。plan-watch v26.8.5
        <span style={{ marginLeft: 12 }}>
          <button
            className="danger"
            onClick={() => {
              if (confirm("确定要退出 plan-watch 吗？\n（所有监控将停止，下次需要手动重启）")) {
                void api.quitApp();
              }
            }}
            title="退出应用（托盘右键菜单里也有）"
          >
            退出 plan-watch
          </button>
        </span>
      </footer>
    </div>
  );
}

