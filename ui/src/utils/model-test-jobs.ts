export const TEST_KINDS = ["chat", "embedding", "rerank", "queryRewrite", "image"] as const;
export type TestKind = typeof TEST_KINDS[number];
export interface TestResult {
  ok: boolean | null;
  reply?: string;
  model?: string;
  dimensions?: number;
  relevanceScore?: number;
  imageCount?: number;
  error?: string;
}
interface Job {
  id: string;
  kind: TestKind;
  status: "pending" | "done" | "failed";
  result?: Omit<TestResult, "ok">;
  error?: string;
}
// getRandomValues also works on non-secure Halo origins where randomUUID is unavailable.
export function createTestRequestId(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export class TestHttpError extends Error {
  constructor(message: string, public status: number) { super(message); }
}

export async function readTestResponse(response: Response): Promise<{ job: Job }> {
  const text = await response.text();
  let data: any;
  try { data = text.trim() ? JSON.parse(text) : undefined; } catch { /* Proxy error pages are not JSON. */ }
  if (!response.ok) {
    const reason = response.status === 401 || response.status === 403
      ? "登录已失效或没有权限，请重新登录后继续查询"
      : response.status === 404
        ? "任务记录不存在或已失效（可能因服务重启或记录过期）；无法确认原测试结果"
        : typeof data?.error === "string" ? data.error : "服务或代理返回异常";
    throw new TestHttpError(`${reason}（HTTP ${response.status}）`, response.status);
  }
  if (!text.trim()) throw new TestHttpError("服务返回空响应，可能是代理超时或连接中断", response.status);
  if (!data) throw new TestHttpError("服务返回非 JSON 响应，可能是代理错误页面", response.status);
  const job = data.job;
  if (!job || typeof job.id !== "string" || !TEST_KINDS.includes(job.kind)
      || !["pending", "done", "failed"].includes(job.status)
      || (job.status === "done" && (!job.result || typeof job.result !== "object"))) {
    throw new TestHttpError("服务返回的任务状态无效", response.status);
  }
  return data;
}

interface Dependencies {
  fetch: typeof fetch;
  storage: Pick<Storage, "getItem" | "setItem" | "removeItem">;
  uuid: () => string;
  update: (kind: TestKind, active: boolean, id: string | null, result: TestResult | null) => void;
  interval?: number;
  requestTimeout?: number;
  waitTimeout?: number;
}
const STORAGE_KEY = "ai-suite:model-test-jobs";
const API = "/apis/console.api.ai-suite.halo.run/v1alpha1/config/test-jobs";

/** Only GET is retried. An uncertain POST keeps its ID so recovery cannot create another paid call. */
export class ModelTestRunner {
  private ids: Partial<Record<TestKind, string>> = {};
  private controllers = new Map<TestKind, AbortController>();
  private timers = new Map<TestKind, ReturnType<typeof setTimeout>>();
  private disposed = false;
  constructor(private deps: Dependencies) {
    try {
      const saved = JSON.parse(deps.storage.getItem(STORAGE_KEY) || "{}");
      for (const kind of TEST_KINDS) {
        if (typeof saved[kind] === "string" && /^[0-9a-f-]{36}$/i.test(saved[kind])) this.ids[kind] = saved[kind];
      }
    } catch { /* Storage may be unavailable. Current-page recovery still works. */ }
  }
  private save() {
    try { this.deps.storage.setItem(STORAGE_KEY, JSON.stringify(this.ids)); } catch { /* optional persistence */ }
  }
  restore() {
    for (const kind of TEST_KINDS) if (this.ids[kind]) this.resume(kind);
  }
  async start(kind: TestKind, params: { model: string; dimensions?: number }, newTest = false) {
    if (this.disposed || this.controllers.has(kind)) return;
    if (this.ids[kind] && !newTest) { this.resume(kind); return; }
    const id = this.deps.uuid();
    this.ids[kind] = id;
    this.save();
    const controller = new AbortController();
    this.controllers.set(kind, controller);
    this.deps.update(kind, true, id, { ok: null, error: "正在提交测试…" });
    try {
      const data = await this.request(API, controller, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...params, kind, requestId: id }),
      });
      if (!this.disposed) this.consume(kind, id, data.job, Date.now(), 0);
    } catch (error) {
      if (this.disposed) return;
      // Explicit rejection means no job was admitted. Network/5xx/invalid responses remain uncertain.
      if (error instanceof TestHttpError && [400, 401, 403, 409, 429].includes(error.status)) {
        delete this.ids[kind]; this.save();
        this.deps.update(kind, false, null, { ok: null, error: error.message });
      } else this.pause(kind, id, "提交结果尚未确认：" + this.message(error));
    } finally { this.controllers.delete(kind); }
  }
  resume(kind: TestKind) {
    if (this.disposed || this.controllers.has(kind)) return;
    const id = this.ids[kind];
    if (!id) return;
    this.clearTimer(kind);
    this.deps.update(kind, true, id, { ok: null, error: "正在查询原测试任务…" });
    void this.poll(kind, id, Date.now(), 0);
  }
  private async request(url: string, controller: AbortController, options: RequestInit = {}) {
    const timer = setTimeout(() => controller.abort(), this.deps.requestTimeout ?? 10000);
    try {
      return await readTestResponse(await this.deps.fetch(url, { ...options, signal: controller.signal, cache: "no-store" }));
    } finally { clearTimeout(timer); }
  }
  private async poll(kind: TestKind, id: string, started: number, failures: number) {
    if (this.disposed || this.ids[kind] !== id) return;
    const controller = new AbortController();
    this.controllers.set(kind, controller);
    try {
      const data = await this.request(`${API}/${encodeURIComponent(id)}`, controller);
      if (!this.disposed) this.consume(kind, id, data.job, started, 0);
    } catch (error) {
      if (this.disposed) return;
      if (error instanceof TestHttpError && [401, 403, 404].includes(error.status)) {
        this.pause(kind, id, this.message(error));
      } else if (failures + 1 >= 5 || Date.now() - started >= (this.deps.waitTimeout ?? 7 * 60 * 1000)) {
        this.pause(kind, id, "暂时无法获取任务状态：" + this.message(error));
      } else this.schedule(kind, id, started, failures + 1);
    } finally { this.controllers.delete(kind); }
  }
  private consume(kind: TestKind, id: string, job: Job, started: number, failures: number) {
    if (job.id !== id || job.kind !== kind) { this.pause(kind, id, "服务返回的任务编号或类型不匹配"); return; }
    if (job.status === "done" || job.status === "failed") {
      delete this.ids[kind]; this.save();
      this.deps.update(kind, false, null, job.status === "done"
        ? { ...job.result, ok: true } : { ok: false, error: job.error || "模型测试失败" });
    } else if (Date.now() - started >= (this.deps.waitTimeout ?? 7 * 60 * 1000)) {
      this.pause(kind, id, "等待测试结果超时");
    } else {
      this.deps.update(kind, true, id, { ok: null, error: "测试正在后台执行，请稍候…" });
      this.schedule(kind, id, started, failures);
    }
  }
  private schedule(kind: TestKind, id: string, started: number, failures: number) {
    this.clearTimer(kind);
    this.timers.set(kind, setTimeout(() => {
      this.timers.delete(kind);
      void this.poll(kind, id, started, failures);
    }, this.deps.interval ?? 2000));
  }
  private pause(kind: TestKind, id: string, reason: string) {
    this.deps.update(kind, false, id, { ok: null,
      error: `${reason}。任务可能仍在执行，请优先继续查询；开始新测试可能产生额外费用。` });
  }
  private message(error: unknown) {
    return error instanceof Error && error.name !== "AbortError"
      ? error.message : "网络中断或请求超时";
  }
  private clearTimer(kind: TestKind) {
    const timer = this.timers.get(kind);
    if (timer) clearTimeout(timer);
    this.timers.delete(kind);
  }
  dispose() {
    this.disposed = true;
    for (const kind of TEST_KINDS) this.clearTimer(kind);
    for (const controller of this.controllers.values()) controller.abort();
    this.controllers.clear();
  }
}
