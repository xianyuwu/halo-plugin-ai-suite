/**
 * AIBubbleMenu ProseMirror 插件 (工厂化, per-editor)
 *
 * <p>在选区结束位置渲染 5 按钮浮动工具条. 状态存入 WritingStore (per-editor),
 * 不再用模块级单例. window 监听改具名函数, disposeStore 时移除 (修复泄漏).
 */

import {
  Plugin,
  PluginKey,
  Decoration,
  DecorationSet,
} from "@halo-dev/richtext-editor";
import { createApp } from "vue";
import type { WritingStore } from "./ai-writing-store";
import { getWritingEnabled } from "./writing-enabled";

export const bubbleMenuKey = new PluginKey("ai-writing-bubble-menu");

export function createBubbleMenuPlugin(store: WritingStore) {
  const editor = store.editor;

  function mountBubble(): HTMLDivElement {
    if (store.bubbleApp && store.bubbleEl) return store.bubbleEl;
    const el = document.createElement("div");
    el.className = "ai-bubble-menu-host";
    el.setAttribute("contenteditable", "false");
    // 动态 import 避免循环依赖
    import("./AIBubbleMenu.vue").then(({ default: AIBubbleMenu }) => {
      // dispose 后可能已清空, 二次检查
      if (store.bubbleEl !== el) return;
      const app = createApp(AIBubbleMenu, { store });
      app.mount(el);
      store.bubbleApp = app;
    });
    store.bubbleEl = el;
    return el;
  }

  function unmountBubble() {
    if (store.bubbleApp) {
      try {
        store.bubbleApp.unmount();
      } catch {
        // 忽略
      }
      store.bubbleApp = null;
    }
    store.bubbleEl = null;
  }

  function forceRecheck() {
    const view = store.editor.view;
    if (view) {
      try {
        const tr = view.state.tr.setMeta(bubbleMenuKey, "recheck");
        view.dispatch(tr);
      } catch {
        // 忽略
      }
    }
  }

  // 具名 window 监听器 (便于 disposeStore removeEventListener)
  const onMouseUp = () => {
    if (store.isSelecting) {
      store.isSelecting = false;
      forceRecheck();
    }
  };
  const onTouchEnd = () => {
    if (store.isSelecting) {
      store.isSelecting = false;
      forceRecheck();
    }
  };
  const onBlur = () => {
    if (store.isSelecting) {
      store.isSelecting = false;
      forceRecheck();
    }
  };
  // 注册 window 监听 (每 editor 一份, dispose 时移除)
  window.addEventListener("mouseup", onMouseUp, true);
  window.addEventListener("touchend", onTouchEnd, true);
  window.addEventListener("blur", onBlur);
  store.windowListeners = [
    { event: "mouseup", handler: onMouseUp as EventListener, capture: true },
    { event: "touchend", handler: onTouchEnd as EventListener, capture: true },
    { event: "blur", handler: onBlur as EventListener },
  ];

  return new Plugin({
    key: bubbleMenuKey,
    state: {
      init: () => DecorationSet.empty,
      apply(tr, old, oldState, newState) {
        if (!getWritingEnabled().value) {
          if (store.bubbleApp) unmountBubble();
          return DecorationSet.empty;
        }
        const cs = store.chatState.value;
        const { selection, doc } = newState;
        const { empty, from, to } = selection;

        const editable = editor?.isEditable ?? true;
        const hasText = !empty && doc.textBetween(from || 0, to || 0).length > 0;
        const composerClosed = !cs;
        const notSelecting = !store.isSelecting;

        if (!editable || !hasText || !composerClosed || !notSelecting) {
          if (store.bubbleApp) unmountBubble();
          return DecorationSet.empty;
        }

        return DecorationSet.create(newState.doc, [
          Decoration.widget(to, () => mountBubble(), {
            side: 1,
            ignoreSelection: true,
            key: "ai-bubble-menu-widget",
          }),
        ]);
      },
    },
    props: {
      decorations(state) {
        return bubbleMenuKey.getState(state) ?? DecorationSet.empty;
      },
      handleDOMEvents: {
        mousedown() {
          store.isSelecting = true;
          return false;
        },
        touchstart() {
          store.isSelecting = true;
          return false;
        },
      },
    },
  });
}
