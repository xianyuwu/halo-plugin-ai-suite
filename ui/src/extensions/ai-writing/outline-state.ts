/**
 * AI 大纲 — 类型定义 + 操作函数
 *
 * <p>原模块级单例(visible/state/app/container/mounted)已移除, 改为 store 字段
 * (见 ai-writing-store.ts). 操作函数接收 store 参数.
 */

import { createApp } from "vue";
import type { WritingStore } from "./ai-writing-store";

export interface OutlineState {
  topic: string;
  content: string;
  status: "idle" | "streaming" | "done" | "error";
  error: string | null;
}

// 懒 import OutlineModal: 顶层 import 会循环 (OutlineModal → outline-controller → 本文件).
// 运行时函数调用时再 import, 此时所有模块已加载, 无循环问题.
let OutlineModalComp: typeof import("./OutlineModal.vue").default | null = null;
async function getOutlineModal() {
  if (!OutlineModalComp) {
    OutlineModalComp = (await import("./OutlineModal.vue")).default;
  }
  return OutlineModalComp;
}

/**
 * 懒挂载 OutlineModal: 第一次 openOutline 时渲染到 body.
 * modal DOM 全局(body 下), 但 store 每 editor 独立, 通过 props 传入.
 */
export function ensureOutlineMounted(store: WritingStore) {
  if (store.outlineMounted.value) return;
  const container = document.createElement("div");
  container.className = "ai-outline-modal-root";
  document.body.appendChild(container);
  store.outlineContainer = container;
  store.outlineMounted.value = true;
  // 异步加载组件再挂载 (避免循环依赖)
  getOutlineModal().then((Comp) => {
    // 加载期间可能已被 dispose, 二次检查
    if (!store.outlineMounted.value || store.outlineContainer !== container) return;
    const app = createApp(Comp, { store });
    app.mount(container);
    store.outlineApp = app;
  }).catch((e) => {
    console.error("[ai-writing] 挂载 OutlineModal 失败:", e);
  });
}

/** 卸载大纲 modal (单 editor 专属, disposeStore 会调) */
export function disposeOutline(store: WritingStore) {
  if (store.outlineApp && store.outlineContainer) {
    try {
      store.outlineApp.unmount();
    } catch {
      // 忽略
    }
    store.outlineApp = null;
    store.outlineContainer.remove();
    store.outlineContainer = null;
    store.outlineMounted.value = false;
  }
}

export function openOutline(store: WritingStore) {
  ensureOutlineMounted(store);
  store.outlineState.value = { topic: "", content: "", status: "idle", error: null };
  store.outlineVisible.value = true;
}

export function closeOutline(store: WritingStore) {
  store.outlineVisible.value = false;
}

export function setOutlineTopic(store: WritingStore, topic: string) {
  store.outlineState.value.topic = topic;
}

export function setOutlineStatus(
  store: WritingStore,
  status: OutlineState["status"],
  content?: string,
  error?: string | null
) {
  store.outlineState.value.status = status;
  if (content !== undefined) store.outlineState.value.content = content;
  if (error !== undefined) store.outlineState.value.error = error;
}

export function appendOutlineContent(store: WritingStore, token: string) {
  store.outlineState.value.content += token;
}
