/**
 * AI 大纲 — 全局状态 + 操作函数
 *
 * <p>大纲 modal 保持全局单例设计(一次只为一个文章生成大纲, 不像 chat composer 需要
 * per-editor 隔离). 当前激活的 editor 在点按钮时存入 activeEditor, apply 时用它.
 *
 * <p>注意: activeEditor 是模块级变量, 多标签页同时操作大纲时会以后点击的为准 — 但
 * 大纲是"点按钮→生成→应用→关闭"的一次性操作, 并发概率极低, 此设计可接受.
 */

import { createApp, ref, shallowRef, type App, type Ref } from "vue";
import OutlineModal from "./OutlineModal.vue";
import type { Editor } from "@halo-dev/richtext-editor";

export interface OutlineState {
  topic: string;
  content: string;
  status: "idle" | "streaming" | "done" | "error";
  error: string | null;
}

// ===== 全局状态 (modal 单例) =====
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

// ===== 当前激活的 editor (点按钮时设置, apply 时用) =====
let activeEditor: Editor | null = null;

/** 卸载大纲 modal (plugin deactivated 时调) */
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

function ensureMounted() {
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

export function getOutlineVisible() {
  return visible;
}

export function getOutlineState() {
  return state;
}

export function getActiveOutlineEditor(): Editor | null {
  return activeEditor;
}

/**
 * 打开大纲 modal. editor 为触发打开的编辑器实例, apply 时会用它插入内容.
 */
export function openOutline(editor: Editor) {
  activeEditor = editor;
  ensureMounted();
  state.value = { topic: "", content: "", status: "idle", error: null };
  visible.value = true;
}

export function closeOutline() {
  visible.value = false;
}

export function setOutlineTopic(topic: string) {
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
