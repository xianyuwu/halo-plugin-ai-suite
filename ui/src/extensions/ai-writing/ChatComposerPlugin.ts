/**
 * ChatComposer ProseMirror 插件 (工厂化, per-editor)
 *
 * <p>用 Decoration.widget 在选区起始位置挂载 ChatComposer.vue. 状态存入
 * WritingStore, 组件通过 props.store 拿到 (原靠模块单例 chatState).
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

export const composerKey = new PluginKey("ai-writing-chat-composer");

export function showChatComposer(store: WritingStore) {
  const view = store.editor.view;
  const tr = view.state.tr.setMeta(composerKey, "show");
  view.dispatch(tr);
}

export function hideChatComposer(store: WritingStore) {
  const view = store.editor.view;
  const tr = view.state.tr.setMeta(composerKey, "hide");
  view.dispatch(tr);
}

export function createChatComposerPlugin(store: WritingStore) {
  function mountComposer(): HTMLDivElement {
    if (store.composerApp && store.composerEl) return store.composerEl;
    const el = document.createElement("div");
    el.className = "ai-chat-composer-host";
    el.setAttribute("contenteditable", "false");
    store.composerEl = el;
    // 动态 import 避免循环依赖
    import("./ChatComposer.vue").then(({ default: ChatComposer }) => {
      if (store.composerEl !== el) return; // dispose 后已清空
      const app = createApp(ChatComposer, { store });
      app.mount(el);
      store.composerApp = app;
    });
    return el;
  }

  function unmountComposer() {
    if (store.composerApp) {
      try {
        store.composerApp.unmount();
      } catch {
        // 忽略
      }
      store.composerApp = null;
    }
    store.composerEl = null;
  }

  function buildDecorations(doc: any, cs: any): DecorationSet {
    const fadeOriginal = Decoration.inline(cs.range.from, cs.range.to, {
      class: "ai-preview-original",
    });
    const widget = Decoration.widget(
      cs.range.to,
      () => mountComposer(),
      {
        side: 1,
        ignoreSelection: true,
        key: "ai-chat-composer-widget",
      }
    );
    return DecorationSet.create(doc, [fadeOriginal, widget]);
  }

  return new Plugin({
    key: composerKey,
    state: {
      init: () => DecorationSet.empty,
      apply(tr, old) {
        if (!getWritingEnabled().value) {
          if (store.composerApp) unmountComposer();
          return DecorationSet.empty;
        }
        const meta = tr.getMeta(composerKey);
        if (meta === "hide") {
          unmountComposer();
          return DecorationSet.empty;
        }
        if (meta === "show") {
          const cs = store.chatState.value;
          if (!cs) return DecorationSet.empty;
          return buildDecorations(tr.doc, cs);
        }
        const cs = store.chatState.value;
        if (!cs) {
          if (store.composerApp) unmountComposer();
          return DecorationSet.empty;
        }
        return buildDecorations(tr.doc, cs);
      },
    },
    props: {
      decorations(state) {
        return composerKey.getState(state) ?? DecorationSet.empty;
      },
      handleDOMEvents: {
        mousedown(_view: any, event: MouseEvent) {
          const cs = store.chatState.value;
          if (!cs) return false;
          const target = event.target as HTMLElement | null;
          if (target?.closest(".ai-chat-composer-host")) return false;
          event.preventDefault();
          return true;
        },
      },
    },
  });
}
