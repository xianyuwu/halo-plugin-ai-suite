/**
 * AI 大纲生成器 — 全局状态
 *
 * <p>OutlineModal 用 createApp 挂载到 body；通过 ensureMounted() 懒挂载，
 * 不依赖 {@code definePlugin.activated} 的触发时机（修复"activated 未触发
 * 时点击按钮无反应"的 bug）。
 */

import { createApp, ref, shallowRef, type App, type Ref } from "vue";
import OutlineModal from "./OutlineModal.vue";

export interface OutlineState {
  topic: string;
  content: string;
  status: "idle" | "streaming" | "done" | "error";
  error: string | null;
}

const visible = ref(false);
const state: Ref<OutlineState> = ref({
  topic: "",
  content: "",
  status: "idle",
  error: null,
});

let app: App | null = null;
let container: HTMLDivElement | null = null;
const mounted = shallowRef(false);

/**
 * 懒挂载：第一次 openOutline() 时把 OutlineModal 渲染到 body。
 * 重复调用幂等。
 */
export function ensureMounted() {
  if (mounted.value) return;
  try {
    container = document.createElement("div");
    container.id = "ai-outline-modal-root";
    document.body.appendChild(container);
    app = createApp(OutlineModal);
    app.mount(container);
    mounted.value = true;
  } catch (e) {
    console.error("[ai-writing] ensureMounted failed:", e);
  }
}

/** 卸载（cleanup 钩子用） */
export function disposeOutline() {
  if (app && container) {
    try {
      app.unmount();
    } catch {
      // 忽略
    }
    app = null;
    container.remove();
    container = null;
    mounted.value = false;
  }
}

export function getOutlineVisible() {
  return visible;
}

export function getOutlineState() {
  return state;
}

export function openOutline() {
  // 懒挂载：第一次被调用时才把 OutlineModal 渲染到 body
  ensureMounted();
  state.value = { topic: "", content: "", status: "idle", error: null };
  visible.value = true;
}

export function closeOutline() {
  visible.value = false;
}

export function setTopic(topic: string) {
  state.value.topic = topic;
}

export function setStatus(
  status: OutlineState["status"],
  content?: string,
  error?: string | null
) {
  state.value.status = status;
  if (content !== undefined) state.value.content = content;
  if (error !== undefined) state.value.error = error;
}

export function appendContent(token: string) {
  state.value.content += token;
}

