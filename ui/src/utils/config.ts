/**
 * 通用配置读写工具函数
 * 所有配置页面共用，避免重复代码
 */
const API_BASE = "/apis/console.api.ai-suite.halo.run/v1alpha1/config";
const USAGE_LIMITS_API = "/apis/console.api.ai-suite.halo.run/v1alpha1/usage/limits";
const USAGE_TODAY_API = "/apis/console.api.ai-suite.halo.run/v1alpha1/usage/today";
const USAGE_STATS_API = "/apis/console.api.ai-suite.halo.run/v1alpha1/usage/stats";
const USAGE_CALLS_API = "/apis/console.api.ai-suite.halo.run/v1alpha1/usage/calls";
const USAGE_CLEANUP_API = "/apis/console.api.ai-suite.halo.run/v1alpha1/usage/cleanup";
const USAGE_FAILURE_DIAGNOSTICS_API = "/apis/console.api.ai-suite.halo.run/v1alpha1/usage/failure-diagnostics";

/**
 * 从后端加载指定 group 的配置，填充到 reactive form
 */
export async function loadGroup(group: string, form: Record<string, any>) {
  try {
    const resp = await fetch(API_BASE);
    if (resp.ok) {
      const data = await resp.json();
      const g = data[group] || {};
      Object.keys(form).forEach((k) => {
        if (g[k] !== undefined) form[k] = g[k];
      });
      return g;
    }
  } catch {}
  return {};
}

/**
 * 将 reactive form 保存到后端指定 group
 */
export async function saveGroup(
  group: string,
  form: Record<string, any>,
  saving: { value: boolean },
  saveMsg: { value: string },
  saveOk: { value: boolean }
) {
  saving.value = true;
  saveMsg.value = "";
  try {
    // 先读取全量配置，再更新指定 group
    let allConfig: Record<string, any> = {};
    try {
      const resp = await fetch(API_BASE);
      if (resp.ok) allConfig = await resp.json();
    } catch {}

    allConfig[group] = { ...form };

    const resp = await fetch(API_BASE + "/save", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(allConfig),
    });
    const data = await resp.json();
    saveOk.value = data.saved;
    saveMsg.value = data.saved ? "保存成功" : "保存失败: " + (data.error || "");
  } catch (e: any) {
    saveOk.value = false;
    saveMsg.value = "保存失败: " + e.message;
  } finally {
    saving.value = false;
  }
}

/**
 * 加载用量限制配置 — 后端用单独的 /usage/limits 端点(不走 config group 路径).
 * 新 shape: { enabled, chatModelLimits, visitor: { enabled, dailyLimit, hourlyLimit, whitelist } }.
 * 端点还顺带返回当前 ModelConfig.chatModel, 供 drawer 渲染模型列表.
 */
export async function loadUsageLimits(form: Record<string, any>) {
  try {
    const resp = await fetch(USAGE_LIMITS_API);
    if (!resp.ok) return;
    const data = await resp.json();
    form.enabled = !!data.enabled;
    form.chatModelLimits = data.chatModelLimits || {};
    form.chatModel = data.chatModel || "";
    form.embeddingModel = data.embeddingModel || "";
    form.rerankEnabled = !!data.rerankEnabled;
    form.rerankModel = data.rerankModel || "";
    form.queryRewriteEnabled = !!data.queryRewriteEnabled;
    form.queryRewriteModel = data.queryRewriteModel || "";
    form.writingModel = data.writingModel || "";
    const v = data.visitor || {};
    form.visitorEnabled = !!v.enabled;
    form.visitorDailyLimit = v.dailyLimit || 0;
    form.visitorHourlyLimit = v.hourlyLimit || 0;
    form.visitorWhitelist = Array.isArray(v.whitelist) ? v.whitelist : [];
  } catch {}
}

/**
 * 保存用量限制 — 发给后端: { enabled, chatModelLimits, visitor }.
 */
export async function saveUsageLimits(
  form: Record<string, any>,
  saving: { value: boolean },
  saveMsg: { value: string }
) {
  saving.value = true;
  saveMsg.value = "";
  try {
    const chatModelMap: Record<string, number> = {};
    (form.chatModelLimits || []).forEach((item: any) => {
      const name = (item.model || "").trim();
      if (name) chatModelMap[name] = item.limit || 0;
    });
    const body = {
      enabled: !!form.enabled,
      chatModelLimits: chatModelMap,
      visitor: {
        enabled: !!form.visitorEnabled,
        dailyLimit: form.visitorDailyLimit || 0,
        hourlyLimit: form.visitorHourlyLimit || 0,
        whitelist: form.visitorWhitelist || [],
      },
    };
    const resp = await fetch(USAGE_LIMITS_API, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const data = await resp.json();
    saveMsg.value = data.saved ? "保存成功" : "保存失败: " + (data.error || "");
  } catch (e: any) {
    saveMsg.value = "保存失败: " + e.message;
  } finally {
    saving.value = false;
  }
}

/** 加载当日实时用量(用于顶卡) */
export async function loadUsageToday(): Promise<{
  date: string;
  models: Array<{
    model: string;
    promptTokens: number;
    completionTokens: number;
    calls: number;
    failures: number;
    embeddingTokens: number;
  }>;
}> {
  try {
    const resp = await fetch(USAGE_TODAY_API);
    if (!resp.ok) return { date: "", models: [] };
    return await resp.json();
  } catch {
    return { date: "", models: [] };
  }
}

/** 加载历史聚合 — 返回按天聚合 + 按模型拆分, 用于渲染多折线图 */
export interface DailyStatsEntry {
  date: string;
  promptTokens: number;
  completionTokens: number;
  calls: number;
  failures: number;
  embeddingTokens: number;
  modelCount: number;
  /** model -> {p, c, e, calls}, 0 数据模型不出现 */
  byModel: Record<string, { p: number; c: number; e: number; calls: number; failures?: number }>;
}
export interface UsageStatsResponse {
  range: string;
  days: number;
  daily: DailyStatsEntry[];
  totals: {
    calls: number;
    promptTokens: number;
    completionTokens: number;
    embeddingTokens: number;
    failures: number;
    modelCount: number;
    failureRate: number;
  };
  yesterday: {
    calls: number;
    promptTokens: number;
    completionTokens: number;
    embeddingTokens: number;
    failures: number;
    failureRate: number;
  };
  /** 跨 days 天出现过的所有模型名(去重, 按首次出现顺序) */
  modelsInRange: string[];
}
export async function loadUsageStats(
  start: string,
  end: string
): Promise<UsageStatsResponse | null> {
  try {
    const resp = await fetch(`${USAGE_STATS_API}?start=${start}&end=${end}`);
    if (!resp.ok) return null;
    return (await resp.json()) as UsageStatsResponse;
  } catch {
    return null;
  }
}

export interface UsageCleanupConfig {
  hiddenModels: string[];
}

export interface UsageCleanupResult {
  changedDays: number;
  affectedCalls: number;
  affectedLogs: number;
}

export async function loadUsageCleanup(): Promise<UsageCleanupConfig> {
  try {
    const resp = await fetch(USAGE_CLEANUP_API);
    if (!resp.ok) return { hiddenModels: [] };
    const data = await resp.json();
    return {
      hiddenModels: Array.isArray(data.hiddenModels) ? data.hiddenModels : [],
    };
  } catch {
    return { hiddenModels: [] };
  }
}

export async function saveHiddenUsageModels(hiddenModels: string[]): Promise<void> {
  const resp = await fetch(`${USAGE_CLEANUP_API}/hidden`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ hiddenModels }),
  });
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok || data.saved === false) {
    throw new Error(data.error || "隐藏模型保存失败");
  }
}

export async function mergeUsageModel(params: {
  sourceModel: string;
  targetModel: string;
  start: string;
  end: string;
}): Promise<UsageCleanupResult> {
  const resp = await fetch(`${USAGE_CLEANUP_API}/merge`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(params),
  });
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok || data.merged === false) {
    throw new Error(data.error || "模型用量合并失败");
  }
  return data as UsageCleanupResult;
}

export async function deleteUsageModel(params: {
  model: string;
  start: string;
  end: string;
}): Promise<UsageCleanupResult> {
  const resp = await fetch(`${USAGE_CLEANUP_API}/delete`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(params),
  });
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok || data.deleted === false) {
    throw new Error(data.error || "模型用量删除失败");
  }
  return data as UsageCleanupResult;
}

export interface UsageCallLog {
  id: string;
  date: string;
  time: string;
  model: string;
  type: string;
  scenario: string;
  promptTokens: number;
  completionTokens: number;
  embeddingTokens: number;
  totalTokens: number;
  failure: boolean;
  durationMs: number;
  error: string;
}

export interface UsageCallsResponse {
  start: string;
  end: string;
  retentionDays: number;
  sort?: "asc" | "desc";
  types: string[];
  scenarios: string[];
  total: number;
  page: number;
  size: number;
  items: UsageCallLog[];
}

export interface UsageFailureBucket {
  key: string;
  label: string;
  count: number;
  suggestion?: string;
  example?: string;
}

export interface UsageFailureRecent {
  time: string;
  model: string;
  type: string;
  typeLabel: string;
  scenario: string;
  scenarioLabel: string;
  diagnosis: string;
  diagnosisLabel: string;
  suggestion: string;
  error: string;
}

export interface UsageFailureDiagnostics {
  start: string;
  end: string;
  total: number;
  byType: UsageFailureBucket[];
  byScenario: UsageFailureBucket[];
  byDiagnosis: UsageFailureBucket[];
  recent: UsageFailureRecent[];
}

export async function loadUsageCalls(params: {
  model: string;
  start: string;
  end: string;
  type?: string;
  scenario?: string;
  status?: string;
  sort?: "asc" | "desc";
  page?: number;
  size?: number;
}): Promise<UsageCallsResponse> {
  const query = new URLSearchParams({
    model: params.model,
    start: params.start,
    end: params.end,
    type: params.type || "all",
    scenario: params.scenario || "all",
    status: params.status || "all",
    sort: params.sort || "desc",
    page: String(params.page || 1),
    size: String(params.size || 20),
  });
  const resp = await fetch(`${USAGE_CALLS_API}?${query.toString()}`);
  if (!resp.ok) {
    throw new Error(`请求失败：${resp.status}`);
  }
  return (await resp.json()) as UsageCallsResponse;
}

export async function loadUsageFailureDiagnostics(params: {
  start: string;
  end: string;
  model?: string;
}): Promise<UsageFailureDiagnostics> {
  const query = new URLSearchParams({
    start: params.start,
    end: params.end,
    model: params.model || "",
  });
  const resp = await fetch(`${USAGE_FAILURE_DIAGNOSTICS_API}?${query.toString()}`);
  if (!resp.ok) {
    throw new Error(`请求失败：${resp.status}`);
  }
  return (await resp.json()) as UsageFailureDiagnostics;
}
