/**
 * AI 写作扩展 — Per-editor Store
 *
 * <p>历史问题: chat-state/editor-ref/outline-state 等模块级单例 ref, 以及两个 Plugin
 * 的模块级变量(bubbleApp/isSelecting/composerApp 等), 在多编辑器实例(同时开两篇文章)
 * 时互相覆盖, 导致 chat composer 串台、apply 写错编辑器、window 监听泄漏.
 *
 * <p>改造: 用 WeakMap<Editor, WritingStore> 为每个 editor 绑定独立 Store. editor 被
 * GC 时 Store 自动释放. 所有原模块单例状态搬进 Store, 两个 Plugin 改为工厂创建
 * (每 editor 一个 Plugin 实例). createApp 挂载的组件(ChatComposer/AIBubbleMenu/OutlineModal)
 * 通过 props.store 拿到本 editor 的 Store, 不再依赖模块单例.
 */

import { ref, shallowRef, type App, type Ref, type ShallowRef } from "vue";
import type { Editor } from "@halo-dev/richtext-editor";
import { ChatState, ChatTurn, makeTurnId } from "./chat-state";
import { OutlineState } from "./outline-state";
import { createBubbleMenuPlugin } from "./AIBubbleMenuPlugin";
import { createChatComposerPlugin } from "./ChatComposerPlugin";

/** Per-editor 写作状态容器 */
export interface WritingStore {
  /** 所属编辑器实例 (apply/close 等操作时用) */
  editor: Editor;

  // ===== Chat composer 状态 (原 chat-state.ts 单例) =====
  chatState: Ref<ChatState | null>;

  // ===== Bubble menu 状态 (原 AIBubbleMenuPlugin.ts 模块变量) =====
  bubbleApp: App | null;
  bubbleEl: HTMLDivElement | null;
  isSelecting: boolean;
  /** window 监听器引用, 卸载时 removeEventListener 用 */
  windowListeners: Array<{ event: string; handler: EventListener; capture?: boolean }> | null;

  // ===== Chat composer DOM (原 ChatComposerPlugin.ts 模块变量) =====
  composerApp: App | null;
  composerEl: HTMLDivElement | null;

  // ===== 大纲状态 (原 outline-state.ts 单例) =====
  outlineVisible: Ref<boolean>;
  outlineState: Ref<OutlineState>;
  outlineApp: App | null;
  outlineContainer: HTMLDivElement | null;
  outlineMounted: ShallowRef<boolean>;
  /** 当前大纲流式会话的 abort 句柄 (原 outline-controller.ts 模块变量) */
  outlineCancel: (() => void) | null;

  // ===== useAIAssist 实例对齐 (原 AIBubbleMenu/ChatComposer 各 new 一份导致 abort 对不齐) =====
  /** 当前活跃的 useAIAssist 实例, abort 时调它; 新 start 时覆盖 */
  assistAbort: (() => void) | null;

  // ===== Plugin 实例 (工厂创建, 每 editor 独立) =====
  bubblePlugin: ReturnType<typeof createBubbleMenuPlugin>;
  composerPlugin: ReturnType<typeof createChatComposerPlugin>;
}

const stores = new WeakMap<Editor, WritingStore>();

/** 获取(或懒创建) editor 对应的 Store */
export function getStore(editor: Editor): WritingStore {
  let s = stores.get(editor);
  if (!s) {
    s = createStore(editor);
    stores.set(editor, s);
  }
  return s;
}

/** 是否已为该 editor 创建过 Store */
export function hasStore(editor: Editor): boolean {
  return stores.has(editor);
}

function createStore(editor: Editor): WritingStore {
  const store: WritingStore = {
    editor,
    chatState: ref<ChatState | null>(null),
    bubbleApp: null,
    bubbleEl: null,
    isSelecting: false,
    windowListeners: null,
    composerApp: null,
    composerEl: null,
    outlineVisible: ref(false),
    outlineState: ref<OutlineState>({
      topic: "",
      content: "",
      status: "idle",
      error: null,
    }),
    outlineApp: null,
    outlineContainer: null,
    outlineMounted: shallowRef(false),
    outlineCancel: null,
    assistAbort: null,
    bubblePlugin: null as any, // 下面赋值, 避免循环引用
    composerPlugin: null as any,
  };
  // 工厂创建 Plugin (Plugin 持有 store 引用, 故先建 store 再建 Plugin)
  store.bubblePlugin = createBubbleMenuPlugin(store);
  store.composerPlugin = createChatComposerPlugin(store);
  return store;
}

/**
 * 卸载 editor 的 Store — 清理 window 监听 + Vue app 实例.
 * 在编辑器/插件销毁时调用 (见 index.ts 的 deactivated/cleanupAiWriting).
 */
export function disposeStore(editor: Editor): void {
  const store = stores.get(editor);
  if (!store) return;
  // 移除 window 监听 (修复原 AIBubbleMenuPlugin 匿名函数无法 remove 的泄漏)
  if (store.windowListeners) {
    for (const { event, handler, capture } of store.windowListeners) {
      try {
        window.removeEventListener(event, handler, capture);
      } catch {
        // 忽略
      }
    }
    store.windowListeners = null;
  }
  // 卸载 Vue app
  unmountApp(store.bubbleApp);
  store.bubbleApp = null;
  store.bubbleEl = null;
  unmountApp(store.composerApp);
  store.composerApp = null;
  store.composerEl = null;
  unmountApp(store.outlineApp);
  if (store.outlineContainer) {
    store.outlineContainer.remove();
    store.outlineContainer = null;
  }
  store.outlineApp = null;
  store.outlineMounted.value = false;
  // 取消进行中的流
  store.assistAbort?.();
  store.outlineCancel?.();
  stores.delete(editor);
}

function unmountApp(app: App | null) {
  if (!app) return;
  try {
    app.unmount();
  } catch {
    // 忽略
  }
}

// 重新导出常用类型/工具, 方便调用方单点 import
export type { ChatState, ChatTurn } from "./chat-state";
export type { OutlineState } from "./outline-state";
export { makeTurnId } from "./chat-state";
