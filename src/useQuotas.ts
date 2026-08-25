import { useEffect, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import * as api from "./api";
import type { Account, AccountStatus, AppConfig } from "./types";

export const EVENT_STATUS = "status-updated";
/** 轮询间隔：服务端模式的唯一更新通道；本地模式下作为自愈兜底 */
const POLL_MS = 15_000;

export function byId(list: AccountStatus[]): Record<string, AccountStatus> {
  const map: Record<string, AccountStatus> = {};
  for (const s of list) map[s.accountId] = s;
  return map;
}

export interface Quotas {
  config: AppConfig | null;
  statuses: Record<string, AccountStatus>;
  error: string | null;
}

/** 预览窗 / 悬浮列表共用的数据源：加载配置与状态 + 轮询 + 本地事件（非服务端模式） */
export function useQuotas(): Quotas {
  const [config, setConfig] = useState<AppConfig | null>(null);
  const [statuses, setStatuses] = useState<Record<string, AccountStatus>>({});
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    let unlisten: (() => void) | null = null;

    const loadAll = async () => {
      try {
        const [cfg, sts] = await Promise.all([api.getConfig(), api.getStatuses()]);
        if (!alive) return;
        setConfig(cfg);
        setStatuses(byId(sts));
        setError(null);
      } catch (e) {
        if (alive) setError(String(e));
      }
    };
    void loadAll();
    const timer = setInterval(() => {
      if (alive) void loadAll();
    }, POLL_MS);

    // 本地模式订阅事件；服务端模式下本地事件来自空配置，必须忽略
    if (typeof window !== "undefined" && "__TAURI_INTERNALS__" in window) {
      void listen<AccountStatus[]>(EVENT_STATUS, (ev) => {
        if (alive && !api.isServerMode()) setStatuses(byId(ev.payload));
      }).then((u) => {
        if (alive) unlisten = u;
      });
    }

    return () => {
      alive = false;
      clearInterval(timer);
      unlisten?.();
    };
  }, []);

  return { config, statuses, error };
}

/** 账号列表（含未启用的，供渲染） */
export function accountsOf(config: AppConfig | null): Account[] {
  return config?.accounts ?? [];
}
