import { useEffect, useRef } from "react";
import { currentMonitor, getCurrentWindow } from "@tauri-apps/api/window";
import { LogicalSize, PhysicalPosition } from "@tauri-apps/api/dpi";
import * as api from "./api";
import { accountsOf, useQuotas } from "./useQuotas";
import type { Account, AccountStatus, QuotaTier } from "./types";
import { ERROR_KIND_LABEL, fmtCountdown, fmtPercent, fmtReset } from "./format";

/** 首次放置标记：之后的位置交给 window-state 插件持久化 */
const PLACED_KEY = "pw-float-placed";
/** 点击 vs 拖动的位移阈值（px） */
const DRAG_THRESHOLD = 4;
/** 窗口宽度与高度上下限（logical px，高度随账号数自适应） */
const WIDTH = 236;
const MIN_HEIGHT = 96;
const MAX_HEIGHT = 420;

type DotState = "ok" | "warn" | "crit" | "idle" | "err";

function accountDot(status: AccountStatus | undefined, threshold: number): DotState {
  if (!status) return "idle";
  if (!status.ok && status.tiers.length === 0) return "err";
  const worst = Math.max(
    ...status.tiers.filter((t) => !t.unlimited).map((t) => t.usedPercent),
    -Infinity,
  );
  if (worst === -Infinity) return "idle";
  if (worst >= 90) return "crit";
  if (worst >= threshold) return "warn";
  return "ok";
}

/**
 * 一条状态条（单行）：标签 + 渐变进度/∞ + 百分比 + 重置倒计时。
 * 重置时间缺失显示 "—" 占位（两个窗口都保持行结构稳定）；∞ 无重置概念留空。
 * 悬停可见绝对重置时间。
 */
function BarRow({ tier }: { tier: QuotaTier }) {
  const countdown = fmtCountdown(tier.resetsAt);
  return (
    <div className="fl-bar-row">
      <span className="fl-bar-label">{tier.window === "five_hour" ? "5h" : "周"}</span>
      <div className="fl-bar">
        {tier.unlimited ? (
          <div className="inf" />
        ) : (
          <div
            className="grad"
            style={{ width: `${Math.max(0, Math.min(100, tier.usedPercent))}%` }}
          />
        )}
      </div>
      <span className="fl-bar-pct">{tier.unlimited ? "∞" : fmtPercent(tier.usedPercent)}</span>
      <span
        className="fl-cd"
        title={tier.resetsAt ? `重置 ${fmtReset(tier.resetsAt)}` : undefined}
      >
        {tier.unlimited ? "" : countdown ?? "—"}
      </span>
    </div>
  );
}

/** 一组：名称行 + 每个限额窗口一条状态条 */
function AccountRow({
  account,
  status,
  threshold,
}: {
  account: Account;
  status: AccountStatus | undefined;
  threshold: number;
}) {
  const dot = account.enabled ? accountDot(status, threshold) : "idle";
  const showBars = account.enabled && status && status.tiers.length > 0 && (status.ok || status.stale);

  return (
    <div className="fl-account" onClick={() => void api.openSettings()} title="点击打开设置">
      <div className="fl-row">
        <span className={`fl-dot ${dot}`} />
        <span className="fl-name">{account.name}</span>
        {!account.enabled ? (
          <span className="fl-tag">已停用</span>
        ) : status && !status.ok && status.error && status.tiers.length === 0 ? (
          <span className="fl-tag err">{ERROR_KIND_LABEL[status.error.kind] ?? status.error.kind}</span>
        ) : status?.stale && status.tiers.length > 0 ? (
          <span className="fl-tag stale" title="网络异常，展示上次成功数据">
            旧数据
          </span>
        ) : null}
      </div>
      {showBars && status.tiers.map((t) => <BarRow key={t.window} tier={t} />)}
      {account.enabled && status && !status.ok && status.error && status.tiers.length === 0 && (
        <p className="fl-error" title={status.error.message}>
          {status.error.message}
        </p>
      )}
    </div>
  );
}

export default function FloatList() {
  const { config, statuses, error } = useQuotas();
  const rootRef = useRef<HTMLDivElement>(null);

  // 高度随内容自适应（宽度固定），并把窗口尺寸同步给 OS
  useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const h = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, el.scrollHeight));
    void getCurrentWindow().setSize(new LogicalSize(WIDTH, h));
    // 首次运行放到当前显示器右下角（要在尺寸确定后再算位置）
    if (!localStorage.getItem(PLACED_KEY)) {
      (async () => {
        const win = getCurrentWindow();
        const mon = await currentMonitor();
        if (!mon) return;
        const size = await win.outerSize();
        const margin = Math.round(24 * mon.scaleFactor);
        const x = mon.position.x + mon.size.width - size.width - margin;
        const y = mon.position.y + mon.size.height - size.height - margin;
        await win.setPosition(new PhysicalPosition(x, y));
        localStorage.setItem(PLACED_KEY, "1");
      })();
    }
  }, [config, statuses]);

  // 标题栏拖动：位移超阈值进入系统拖动，否则视为点击（无动作）
  const onHeaderMouseDown = (e: React.MouseEvent) => {
    if (e.button !== 0) return;
    e.preventDefault();
    const startX = e.screenX;
    const startY = e.screenY;
    let started = false;

    const cleanup = () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
    const onMove = (ev: MouseEvent) => {
      if (
        !started &&
        Math.hypot(ev.screenX - startX, ev.screenY - startY) > DRAG_THRESHOLD
      ) {
        started = true;
        cleanup();
        void getCurrentWindow().startDragging();
      }
    };
    const onUp = cleanup;
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  };

  const accounts = accountsOf(config);
  const threshold = config?.lowQuotaThreshold ?? 80;

  return (
    <div className="float-list" ref={rootRef}>
      <div className="fl-head" onMouseDown={onHeaderMouseDown}>
        <span className="fl-title">plan-watch</span>
        <button
          className="fl-close"
          title="隐藏（托盘左键再次显示）"
          onClick={() => void getCurrentWindow().hide()}
        >
          ✕
        </button>
      </div>

      <div className="fl-body">
        {error && <p className="fl-error">加载失败：{error}</p>}
        {config && accounts.length === 0 && (
          <div className="fl-empty">
            <span>还没有账号</span>
            <button className="primary" onClick={() => void api.openSettings()}>
              打开设置
            </button>
          </div>
        )}
        {accounts.map((a) => (
          <AccountRow key={a.id} account={a} status={statuses[a.id]} threshold={threshold} />
        ))}
      </div>

      <div className="fl-foot">
        <button title="立即刷新" onClick={() => void api.refreshNow()}>
          ⟳
        </button>
        <button title="设置" onClick={() => void api.openSettings()}>
          ⚙
        </button>
        <span className="spacer" />
        <button className="fl-quit" title="退出 plan-watch" onClick={() => void api.quitApp()}>
          ⏻
        </button>
      </div>
    </div>
  );
}
