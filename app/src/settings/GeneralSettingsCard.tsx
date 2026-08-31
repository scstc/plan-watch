import { Fragment, useEffect, useRef, useState } from "react";
import * as api from "../shared/api";
import type { AppConfig } from "../shared/types";

/** 数字输入就地钳制（空输入 Number("")=0 也会被拉回下限） */
const clamp = (v: number, lo: number, hi: number): number =>
  Number.isNaN(v) ? lo : Math.max(lo, Math.min(hi, Math.round(v)));

/** 配对码位数（服务端打印的 8 位数字） */
const PAIR_LEN = 8;

interface Props {
  config: AppConfig;
  /** 保存配置（父层负责乐观更新/回滚/错误展示）；返回是否保存成功 */
  persist: (next: AppConfig) => Promise<boolean>;
  /** 保存完成后的回调（数据源可能切换，父层需要重载） */
  onSaved: () => void;
}

/** 配对码输入：8 个独立 1 位方框。仅数字，满位自动跳下一格；Backspace 到 0 回退到上一格 */
function PairCodeInput({
  value,
  onChange,
}: {
  value: string;
  onChange: (next: string) => void;
}) {
  const refs = useRef<Array<HTMLInputElement | null>>([]);

  const setDigit = (idx: number, ch: string) => {
    const digit = ch.replace(/\D/g, "").slice(-1);
    if (!digit) return;
    const arr = value.padEnd(PAIR_LEN, " ").split("");
    arr[idx] = digit;
    onChange(arr.join("").replace(/ /g, ""));
    if (idx < PAIR_LEN - 1) refs.current[idx + 1]?.focus();
  };

  const onInput = (idx: number) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setDigit(idx, e.target.value);
    // 清空 input.value（受控 value 是空字符串 " "），让下一次敲键能正常触发 onChange
    e.target.value = "";
  };

  const onKeyDown = (idx: number) => (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Backspace") {
      e.preventDefault();
      if (value[idx]) {
        // 当前格有数字：清空它
        const arr = value.padEnd(PAIR_LEN, " ").split("");
        arr[idx] = " ";
        onChange(arr.join("").replace(/ /g, ""));
      } else if (idx > 0) {
        // 当前格已空：回退并清空上一格
        refs.current[idx - 1]?.focus();
        const arr = value.padEnd(PAIR_LEN, " ").split("");
        arr[idx - 1] = " ";
        onChange(arr.join("").replace(/ /g, ""));
      }
    } else if (e.key === "ArrowLeft" && idx > 0) {
      e.preventDefault();
      refs.current[idx - 1]?.focus();
    } else if (e.key === "ArrowRight" && idx < PAIR_LEN - 1) {
      e.preventDefault();
      refs.current[idx + 1]?.focus();
    }
  };

  return (
    <div className="pair-code" aria-label="配对码">
      {Array.from({ length: PAIR_LEN }, (_, i) => {
        const ch = value[i] ?? "";
        // 中点分隔符：1234 - 5678
        const isMid = i === PAIR_LEN / 2;
        return (
          <Fragment key={i}>
            {isMid && (
              <span className="pair-code-sep" aria-hidden="true">-</span>
            )}
            <input
              ref={(el) => {
                refs.current[i] = el;
              }}
              type="text"
              inputMode="numeric"
              pattern="[0-9]*"
              maxLength={1}
              value={ch}
              onChange={onInput(i)}
              onKeyDown={onKeyDown(i)}
              onFocus={(e) => e.currentTarget.select()}
              onPaste={(e) => {
                const pasted = e.clipboardData.getData("text").replace(/\D/g, "").slice(0, PAIR_LEN);
                if (!pasted) return;
                e.preventDefault();
                onChange((value + pasted).slice(0, PAIR_LEN));
                refs.current[Math.min(pasted.length, PAIR_LEN - 1)]?.focus();
              }}
              autoComplete="off"
              spellCheck={false}
              aria-label={`配对码第 ${i + 1} 位`}
            />
          </Fragment>
        );
      })}
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
  // 「保存设置」的结果提示（拒绝保存 / 保存失败），与配对消息分开
  const [saveMsg, setSaveMsg] = useState<string | null>(null);
  const [tokenPresent, setTokenPresent] = useState(api.hasToken());
  // 用户敲过数字草稿后暂停 config→草稿同步：服务端故障↔恢复的 30s 轮询翻转
  // 不应冲掉未保存的编辑（保存成功后复位，服务端规范化值得以同步回来）
  const touched = useRef(false);

  const pair = async () => {
    setPairing(true);
    setPairMsg(null);
    try {
      // 配对走输入框当前地址（未 blur 直接点配对也生效）
      api.setServerUrl(serverUrl);
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
    if (touched.current) return;
    setIntervalMin(Math.max(1, Math.round(config.refreshIntervalSecs / 60)));
    setThreshold(config.lowQuotaThreshold);
  }, [config.refreshIntervalSecs, config.lowQuotaThreshold]);

  // config 每轮轮询都是新对象：借其对齐 localStorage 真实配对状态。
  // 401 吊销清 token 后 ≤30s 内翻转为"未配对"，配对入口（banner 指引的）才看得见
  useEffect(() => {
    if (serverUrl) setTokenPresent(api.hasToken());
  }, [config, serverUrl]);

  const dirty =
    Math.round(intervalMin * 60) !== config.refreshIntervalSecs ||
    Math.round(threshold) !== config.lowQuotaThreshold ||
    serverUrl.trim() !== api.getServerUrl();

  const save = async () => {
    setSaveMsg(null);
    // 后端接口地址是本机偏好（localStorage），不进 AppConfig
    api.setServerUrl(serverUrl);
    // 数据源可能切换：先按新模式取最新配置做基底，
    // 避免把本地账号清单覆盖到服务端（或反之）
    let base = config;
    try {
      base = await api.getConfig();
    } catch (e) {
      // 服务端模式取不到最新配置就拒绝保存：兜底渲染的 0 账号基线一旦
      // PUT 会整体覆盖服务端账号（与 SettingsApp 账号变更的 freshBase 同语义）
      if (api.isServerMode()) {
        setSaveMsg(
          e instanceof api.PwAuthError
            ? "设备未配对，设置未保存（请先完成下方「设备配对」）"
            : `后端不可达，设置未保存（${String(e)}）`,
        );
        return;
      }
      // 本地模式 get_config 失败属理论路径，维持旧行为继续保存
    }
    const ok = await persist({
      ...base,
      refreshIntervalSecs: Math.max(60, Math.min(86_400, Math.round(intervalMin * 60))),
      lowQuotaThreshold: Math.max(10, Math.min(99, Math.round(threshold))),
    });
    if (!ok) return; // 保存失败：banner 已提示；不触发刷新（其失败文案会盖掉"保存失败"）
    touched.current = false;
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
            onChange={(e) => {
              touched.current = true;
              setIntervalMin(clamp(Number(e.target.value), 1, 1440));
            }}
          />
        </label>
        <label>
          低额度提醒阈值（% 已用）
          <input
            type="number"
            min={10}
            max={99}
            value={threshold}
            onChange={(e) => {
              touched.current = true;
              setThreshold(clamp(Number(e.target.value), 10, 99));
            }}
          />
        </label>
        <div className="form-actions inline">
          <button className="primary" onClick={() => void save()} disabled={!dirty}>
            保存设置
          </button>
        </div>
        {saveMsg && (
          <p className={`span-3 ${saveMsg.includes("成功") ? "ok-banner" : "error-banner"}`}>{saveMsg}</p>
        )}
        <label className="span-3">
          后端接口地址（填了走服务端取数，留空使用本地查询）
          <input
            value={serverUrl}
            onChange={(e) => setServerUrlDraft(e.target.value)}
            onKeyDown={(e) => {
              // 回车即落盘（与 onBlur 共用同一提交路径）
              if (e.key === "Enter") e.currentTarget.blur();
            }}
            onBlur={() => {
              // 落盘放在 blur 而不是每次键击：逐键持久化会把 "http://19…" 等
              // 中间态写进 localStorage（该地址无 token，会触发持续的未配对失败）
              api.setServerUrl(serverUrl);
              setTokenPresent(api.hasToken());
            }}
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
