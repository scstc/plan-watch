import { useEffect, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import * as api from "./api";
import type { Account, AccountStatus, AppConfig } from "./types";

export const EVENT_STATUS = "status-updated";

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

/** 预览窗 / 悬浮球共用的数据源：加载配置与状态，订阅 status-updated */
export function useQuotas(): Quotas {
  const [config, setConfig] = useState<AppConfig | null>(null);
  const [statuses, setStatuses] = useState<Record<string, AccountStatus>>({});
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let unlisten: (() => void) | null = null;
    (async () => {
      try {
        const [cfg, sts] = await Promise.all([api.getConfig(), api.getStatuses()]);
        setConfig(cfg);
        setStatuses(byId(sts));
      } catch (e) {
        setError(String(e));
        return;
      }
      unlisten = await listen<AccountStatus[]>(EVENT_STATUS, (ev) =>
        setStatuses(byId(ev.payload)),
      );
    })();
    return () => unlisten?.();
  }, []);

  return { config, statuses, error };
}

/** 账号列表（含未启用的，供渲染） */
export function accountsOf(config: AppConfig | null): Account[] {
  return config?.accounts ?? [];
}
