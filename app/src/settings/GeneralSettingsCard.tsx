import { useEffect, useRef, useState } from "react";
import * as api from "../shared/api";
import type { AppConfig } from "../shared/types";

/** 数字输入就地钳制（空输入 Number("")=0 也会被拉回下限） */
const clamp = (v: number, lo: number, hi: number): number =>
  Number.isNaN(v) ? lo : Math.max(lo, Math.min(hi, Math.round(v)));

/** 配对码：服务端打印的「1234-5678」格式，分两段输入 */
const PAIR_SEG_LEN = 4;

interface Props {
  config: AppConfig;
  /** 保存配置（父层负责乐观更新/回滚/错误展示） */
  persist: (next: AppConfig) => Promise<void>;
  /** 保存完成后的回调（数据源可能切换，父层需要重载） */
  onSaved: () => void;
}

/** 配对码两段输入：4 + 4，中间静态横杠。仅数字，满位自动跳下一格；Backspace 到 0 回退到上一格 */
function PairCodeInput({
  value,
  onChange,
}: {
  value: string;
  onChange: (next: string) => void;
}) {
  const refA = useRef<HTMLInputElement>(null);
  const refB = useRef<HTMLInputElement>(null);
  const [a, b] = [
    value.slice(0, PAIR_SEG_LEN).padEnd(PAIR_SEG_LEN, " "),
    value.slice(PAIR_SEG_LEN, PAIR_SEG_LEN * 2),
  ];

  const setSeg = (idx: 0 | 1, seg: string) => {
    const digits = seg.replace(/\D/g, "").slice(0, PAIR_SEG_LEN);
    const next = idx === 0 ? digits + value.slice(PAIR_SEG_LEN) : value.slice(0, PAIR_SEG_LEN) + digits;
    onChange(next);
    return digits;
  };

  const onSegInput = (idx: 0 | 1) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const digits = setSeg(idx, e.target.value);
    if (digits.length === PAIR_SEG_LEN) {
      (idx === 0 ? refB : refA).current?.focus();
    }
  };

  const onSegKeyDown = (idx: 0 | 1) => (e: React.KeyboardEvent<HTMLInputElement>) => {
    const el = e.currentTarget;
    if (e.key === "Backspace" && el.value.length === 0) {
      e.preventDefault();
      (idx === 0 ? refA : refB).current?.focus();
    }
  };

  return (
    <div className="pair-code" aria-label="配对码">
      <input
        ref={refA}
        type="text"
        inputMode="numeric"
        pattern="[0-9]*"
        maxLength={PAIR_SEG_LEN}
        value={a}
        onChange={onSegInput(0)}
        onKeyDown={onSegKeyDown(0)}
        onFocus={(e) => e.currentTarget.select()}
        autoComplete="off"
        spellCheck={false}
        aria-label="配对码前 4 位"
      />
      <span className="pair-code-sep" aria-hidden="true">-</span>
      <input
        ref={refB}
        type="text"
        inputMode="numeric"
        pattern="[0-9]*"
        maxLength={PAIR_SEG_LEN}
        value={b}
        onChange={onSegInput(1)}
        onKeyDown={onSegKeyDown(1)}
        onFocus={(e) => e.currentTarget.select()}
        autoComplete="off"
        spellCheck={false}
        aria-label="配对码后 4 位"
      />
    </div>
  );
}

/** 通用设置：刷新间隔 / 低额度阈值 / 后端接口地址 / 设备配对 */
export function GeneralSettingsCard({ config, persist, onSaved }: Props) {
  const [intervalMin, setIntervalMin] = useState(5);
  const [threshold, setThreshold] = useState(80);
  const [serverUrl, setServerUrlDraft] = useState(api.getServerUrl());
  // 设备配对：配对码输入 / 配对中状态 / 结果消息
  const [pairCode, setPairCode] = useState("");
  const [pairing, setPairing] = useState(false);
  const [pairMsg, setPairMsg] = useState<string | null>(null);
  const [tokenPresent, setTokenPresent] = useState(api.hasToken());

  const pair = async () => {
    setPairing(true);
    setPairMsg(null);
    try {
      await api.pairServer(pairCode);
      setPairMsg("配对成功，正在重新加载…");
      setTimeout(() => window.location.reload(), 600);
    } catch (e) {
      setPairMsg(String(e));
    } finally {
      setPairing(false);
    }
  };

  const clearServerConfig = () => {
    if (confirm("确定要清除后端配置吗？这将切换回本地查询模式。")) {
      api.setServerUrl("");
      setServerUrlDraft("");
      setTokenPresent(false);
    }
  };

  // config 变化时同步草稿（初始加载 / 服务端返回规范化值 / 回滚）
  useEffect(() => {
    setIntervalMin(Math.max(1, Math.round(config.refreshIntervalSecs / 60)));
    setThreshold(config.lowQuotaThreshold);
  }, [config.refreshIntervalSecs, config.lowQuotaThreshold]);

  const dirty =
    Math.round(intervalMin * 60) !== config.refreshIntervalSecs ||
    Math.round(threshold) !== config.lowQuotaThreshold ||
    serverUrl.trim() !== api.getServerUrl();

  const save = async () => {
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
    setTokenPresent(api.hasToken());
    onSaved();
  };

  return (
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
          <button className="primary" onClick={() => void save()} disabled={!dirty}>
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
        {serverUrl && (
          <>
            <p className="span-3" style={{ margin: 0 }}>
              <strong>设备配对</strong>
              <span className="muted">
                （{tokenPresent ? "已配对 ✅" : "未配对 ⚠️"}）
              </span>
            </p>
            {!tokenPresent && (
              <label className="span-2">
                配对码（服务端启动日志「配对码」行，8 位数字）
                <PairCodeInput value={pairCode} onChange={setPairCode} />
              </label>
            )}
            <div className="form-actions inline span-3">
              {!tokenPresent && (
                <button
                  className="secondary"
                  onClick={() => void pair()}
                  disabled={pairing || pairCode.replace(/\D/g, "").length !== 8}
                >
                  {pairing ? "配对中…" : "配对"}
                </button>
              )}
            </div>
            {pairMsg && (
              <p className={`span-3 ${pairMsg.includes("成功") ? "ok-banner" : "error-banner"}`}>
                {pairMsg}
              </p>
            )}
          </>
        )}
        <div className="form-actions inline span-3">
          {serverUrl && (
            <button className="secondary" onClick={clearServerConfig}>
              清除后端配置
            </button>
          )}
        </div>
      </div>
    </section>
  );
}
