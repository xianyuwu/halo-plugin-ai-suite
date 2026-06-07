/**
 * 通用配置读写工具函数
 * 所有配置页面共用，避免重复代码
 */
const API_BASE = "/apis/console.api.ai-assistant.halo.run/v1alpha1/config";
const USAGE_LIMITS_API = "/apis/console.api.ai-assistant.halo.run/v1alpha1/usage/limits";
const USAGE_TODAY_API = "/apis/console.api.ai-assistant.halo.run/v1alpha1/usage/today";
const USAGE_STATS_API = "/apis/console.api.ai-assistant.halo.run/v1alpha1/usage/stats";

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
    }
  } catch {}
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
  byModel: Record<string, { p: number; c: number; e: number; calls: number }>;
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
