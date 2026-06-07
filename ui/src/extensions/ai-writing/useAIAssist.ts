/**
 * useAIAssist — 流式 AI 写作辅助的核心 composable
 *
 * <p>封装：fetch SSE、状态机、AbortController、20s 无数据心跳检测。
 *
 * <h3>SSE 协议</h3>
 * <ul>
 *   <li>{@code data: <token>} — 增量 token</li>
 *   <li>{@code data: [DONE]} — 流结束</li>
 *   <li>{@code event: error\ndata: <msg>} — 错误（其后会有 [DONE]）</li>
 * </ul>
 */

import { ref, computed, type Ref, type ComputedRef } from "vue";

export type AssistStatus =
  | "idle"
  | "streaming"
  | "stalled"
  | "done"
  | "error";

export interface UseAIAssistReturn {
  status: Ref<AssistStatus>;
  resultText: Ref<string>;
  errorMessage: Ref<string | null>;
  elapsedSeconds: Ref<number>;
  canApply: ComputedRef<boolean>;
  start: (params: AssistParams, callbacks?: StreamCallbacks) => void;
  abort: () => void;
  reset: () => void;
}

export interface AssistParams {
  text: string;
  action: string;
  instruction?: string;
}

export interface StreamCallbacks {
  onToken?: (token: string) => void;
  onError?: (msg: string) => void;
  onDone?: () => void;
}

const STALL_TIMEOUT_MS = 20_000;

export function useAIAssist(): UseAIAssistReturn {
  const status = ref<AssistStatus>("idle");
  const resultText = ref("");
  const errorMessage = ref<string | null>(null);
  const elapsedSeconds = ref(0);

  let abortController: AbortController | null = null;
  let timerId: number | null = null;
  let stallTimerId: number | null = null;
  let startTime = 0;

  const canApply = computed(
    () => (status.value === "done" || status.value === "error") && resultText.value.trim().length > 0
  );

  function clearTimers() {
    if (timerId !== null) {
      window.clearInterval(timerId);
      timerId = null;
    }
    if (stallTimerId !== null) {
      window.clearTimeout(stallTimerId);
      stallTimerId = null;
    }
  }

  function reset() {
    abort();
    status.value = "idle";
    resultText.value = "";
    errorMessage.value = null;
    elapsedSeconds.value = 0;
  }

  function abort() {
    if (abortController) {
      abortController.abort();
      abortController = null;
    }
    clearTimers();
  }

  function start(params: AssistParams, callbacks?: StreamCallbacks) {
    abort();
    status.value = "streaming";
    resultText.value = "";
    errorMessage.value = null;
    elapsedSeconds.value = 0;
    startTime = Date.now();

    abortController = new AbortController();

    // 计时
    timerId = window.setInterval(() => {
      elapsedSeconds.value = (Date.now() - startTime) / 1000;
    }, 100);

    // 心跳超时
    function armStallTimer() {
      if (stallTimerId !== null) window.clearTimeout(stallTimerId);
      stallTimerId = window.setTimeout(() => {
        if (status.value === "streaming") {
          status.value = "stalled";
        }
      }, STALL_TIMEOUT_MS);
    }
    armStallTimer();

    fetchStream(params, abortController.signal, {
      onToken: (token) => {
        if (status.value === "stalled") status.value = "streaming";
        armStallTimer();
        resultText.value += token;
        callbacks?.onToken?.(token);
      },
      onError: (msg) => {
        errorMessage.value = msg;
        status.value = "error";
        callbacks?.onError?.(msg);
      },
      onDone: () => {
        if (status.value !== "error") status.value = "done";
        clearTimers();
        callbacks?.onDone?.();
      },
    }).catch((e) => {
      if (e?.name === "AbortError") return;
      const msg = e?.message ?? "网络错误";
      errorMessage.value = msg;
      status.value = "error";
      callbacks?.onError?.(msg);
      clearTimers();
    });
  }

  return { status, resultText, errorMessage, elapsedSeconds, canApply, start, abort, reset };
}

interface InternalStreamCallbacks {
  onToken: (token: string) => void;
  onError: (msg: string) => void;
  onDone: () => void;
}

async function fetchStream(
  params: AssistParams,
  signal: AbortSignal,
  cb: InternalStreamCallbacks
): Promise<void> {
  const resp = await fetch(
    "/apis/console.api.ai-assistant.halo.run/v1alpha1/writing/assist/stream",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        text: params.text,
        action: params.action,
        instruction: params.instruction ?? "",
      }),
      signal,
    }
  );

  if (!resp.ok || !resp.body) {
    let msg = `请求失败 (${resp.status})`;
    try {
      const errBody = await resp.json();
      if (errBody?.error) msg = errBody.error;
    } catch {
      // 忽略
    }
    cb.onError(msg);
    cb.onDone();
    return;
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  // SSE 解析状态机：区分 "data:" 行 与 "event:" 行
  let currentEvent = "message";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop() ?? "";

    for (const rawLine of lines) {
      const line = rawLine.replace(/\r$/, "");
      if (line === "") {
        currentEvent = "message";
        continue;
      }
      if (line.startsWith("event:")) {
        currentEvent = line.substring(6).trim();
        continue;
      }
      if (line.startsWith("data:")) {
        const data = line.substring(5).trim();
        if (data === "") continue;

        if (currentEvent === "error") {
          cb.onError(data);
          continue;
        }

        if (data === "[DONE]") {
          cb.onDone();
          return;
        }

        cb.onToken(data);
      }
    }
  }

  // 流自然结束（没有 [DONE] —— 旧后端兼容）
  cb.onDone();
}
