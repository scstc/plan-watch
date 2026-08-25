import { useState } from "react";
import * as api from "../api";
import type { Account, AccountStatus, ProviderKind, Region } from "../types";
import { PROVIDER_LABEL, REGION_LABEL, fmtPercent, fmtReset } from "../format";

interface Props {
  /** null = 新建 */
  initial: Account | null;
  onSave: (account: Account) => Promise<void>;
  onCancel: () => void;
}

const KEY_PLACEHOLDER: Record<ProviderKind, string> = {
  zhipu: "开放平台 API Key，形如 32位hex.secret",
  minimax: "编程套餐 API Key，形如 sk-cp-…",
};

export function AccountForm({ initial, onSave, onCancel }: Props) {
  const [name, setName] = useState(initial?.name ?? "");
  const [provider, setProvider] = useState<ProviderKind>(initial?.provider ?? "zhipu");
  const [region, setRegion] = useState<Region>(initial?.region ?? "cn");
  const [apiKey, setApiKey] = useState(initial?.apiKey ?? "");
  const [showKey, setShowKey] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<AccountStatus | null>(null);
  const [testError, setTestError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const valid = name.trim().length > 0 && apiKey.trim().length > 0;

  function buildAccount(): Account {
    return {
      id: initial?.id ?? crypto.randomUUID(),
      name: name.trim(),
      provider,
      region,
      apiKey: apiKey.trim(),
      enabled: initial?.enabled ?? true,
    };
  }

  async function handleTest() {
    setTesting(true);
    setTestResult(null);
    setTestError(null);
    try {
      setTestResult(await api.testAccount(buildAccount()));
    } catch (e) {
      setTestError(String(e));
    } finally {
      setTesting(false);
    }
  }

  async function handleSave() {
    if (!valid) return;
    setSaving(true);
    try {
      await onSave(buildAccount());
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="account-form">
      <h3>{initial ? "编辑账号" : "添加账号"}</h3>
      <div className="form-grid">
        <label>
          名称
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="如：智谱主力 / MiniMax 工作"
          />
        </label>
        <label>
          供应商
          <select value={provider} onChange={(e) => setProvider(e.target.value as ProviderKind)}>
            {(Object.keys(PROVIDER_LABEL) as ProviderKind[]).map((p) => (
              <option key={p} value={p}>
                {PROVIDER_LABEL[p]}
              </option>
            ))}
          </select>
        </label>
        <label>
          站点
          <select value={region} onChange={(e) => setRegion(e.target.value as Region)}>
            {(Object.keys(REGION_LABEL) as Region[]).map((r) => (
              <option key={r} value={r}>
                {REGION_LABEL[r]}
              </option>
            ))}
          </select>
        </label>
        <label className="span-2">
          API Key
          <div className="key-row">
            <input
              type={showKey ? "text" : "password"}
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              placeholder={KEY_PLACEHOLDER[provider]}
              autoComplete="off"
              spellCheck={false}
            />
            <button type="button" className="ghost" onClick={() => setShowKey(!showKey)}>
              {showKey ? "隐藏" : "显示"}
            </button>
          </div>
        </label>
      </div>

      <div className="form-result">
        {testing && <p className="muted">正在测试连接…</p>}
        {testError && <p className="error-banner">调用失败：{testError}</p>}
        {testResult &&
          (testResult.ok ? (
            <p className="ok-banner">
              连接成功
              {testResult.planLevel ? ` · ${testResult.planLevel}` : ""}
              {testResult.tiers.map((t) => (
                <span key={t.window}>
                  {" · "}
                  {t.window === "five_hour" ? "5h" : "周"} {fmtPercent(t.usedPercent)}
                  {t.resetsAt ? `（重置 ${fmtReset(t.resetsAt)}）` : ""}
                </span>
              ))}
            </p>
          ) : (
            <p className="error-banner">{testResult.error?.message ?? "查询失败"}</p>
          ))}
      </div>

      <div className="form-actions">
        <button className="ghost" onClick={handleTest} disabled={!valid || testing}>
          测试连接
        </button>
        <span className="spacer" />
        <button className="ghost" onClick={onCancel} disabled={saving}>
          取消
        </button>
        <button className="primary" onClick={handleSave} disabled={!valid || saving}>
          {saving ? "保存中…" : "保存"}
        </button>
      </div>
    </div>
  );
}
