import { useState } from "react";
import type { Account, AccountStatus } from "../types";
import {
  ERROR_KIND_LABEL,
  PROVIDER_LABEL,
  REGION_LABEL,
  fmtClock,
  fmtNum,
  fmtPercent,
  fmtReset,
  maskKey,
} from "../format";

interface Props {
  account: Account;
  status: AccountStatus | undefined;
  onEdit: () => void;
  onDelete: () => void;
  onToggle: (enabled: boolean) => void;
}

export function AccountCard({ account, status, onEdit, onDelete, onToggle }: Props) {
  const [confirming, setConfirming] = useState(false);

  return (
    <div className={`account-card${account.enabled ? "" : " disabled"}`}>
      <div className="account-head">
        <div className="account-title">
          <span className="account-name">{account.name}</span>
          <span className="badge">{PROVIDER_LABEL[account.provider]}</span>
          <span className="badge subtle">{REGION_LABEL[account.region]}</span>
          {status?.planLevel && <span className="badge subtle">{status.planLevel}</span>}
        </div>
        <div className="account-actions">
          <button
            className="toggle"
            title={account.enabled ? "暂停监控" : "恢复监控"}
            onClick={() => onToggle(!account.enabled)}
          >
            {account.enabled ? "⏸" : "▶"}
          </button>
          <button onClick={onEdit}>编辑</button>
          <button
            className={confirming ? "danger solid" : "danger"}
            onClick={() => {
              if (confirming) onDelete();
              else {
                setConfirming(true);
                setTimeout(() => setConfirming(false), 3000);
              }
            }}
          >
            {confirming ? "确认删除" : "删除"}
          </button>
        </div>
      </div>

      {/* 脱敏展示；不要放 title 之类携带完整 Key 的属性 */}
      <div className="account-key">{maskKey(account.apiKey)}</div>

      <div className="account-body">
        {!account.enabled ? (
          <p className="muted">已停用，不参与刷新</p>
        ) : !status ? (
          <p className="muted">等待首次刷新…</p>
        ) : (
          <>
            {/* 成功，或网络失败但保留了上次数据时都展示额度条 */}
            {status.tiers.length > 0 && (status.ok || status.stale) ? (
              <div className="tiers">
                {status.tiers.map((t) => (
                  <div className="tier" key={t.window}>
                    <span className="tier-name">{t.window === "five_hour" ? "5 小时" : "每周"}</span>
                    <div className="tier-bar">
                      {t.unlimited ? (
                        <div className="inf" />
                      ) : (
                        <div
                          className="grad"
                          style={{ width: `${Math.max(0, Math.min(100, t.usedPercent))}%` }}
                        />
                      )}
                    </div>
                    <span className="tier-pct">{t.unlimited ? "∞" : fmtPercent(t.usedPercent)}</span>
                    <span className="tier-reset">
                      {t.resetsAt ? `重置 ${fmtReset(t.resetsAt)}` : "—"}
                    </span>
                    {t.total != null && (
                      <span className="tier-abs">
                        {fmtNum(t.used ?? 0)} / {fmtNum(t.total)}
                      </span>
                    )}
                  </div>
                ))}
              </div>
            ) : status.ok ? (
              <p className="muted">接口未返回额度窗口</p>
            ) : null}
            {!status.ok && status.error && (
              <p className={`error-banner ${status.error.kind}`}>
                {`${ERROR_KIND_LABEL[status.error.kind] ?? status.error.kind}：${status.error.message}`}
              </p>
            )}
            {/* 首次查询就网络失败时没有旧数据可展示，只显示错误横幅即可 */}
            {status.stale && status.tiers.length > 0 && (
              <p className="stale-banner">网络异常，额度为上次成功数据（{fmtClock(status.queriedAt)}）</p>
            )}
            {status.ok && !status.stale && status.queriedAt != null && (
              <p className="muted small">更新于 {fmtClock(status.queriedAt)}</p>
            )}
          </>
        )}
      </div>
    </div>
  );
}
