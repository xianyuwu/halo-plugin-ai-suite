/**
 * ChatComposer ProseMirror 插件
 *
 * <p>用 Decoration.widget 在选区起始位置挂载 ChatComposer.vue。
 * 选区淡灰 + 锁定编辑行为，避免流式过程中用户误改选区。
 */

import {
  Plugin,
  PluginKey,
  Decoration,
  DecorationSet,
} from "@halo-dev/richtext-editor";
import { createApp, type App } from "vue";
import ChatComposer from "./ChatComposer.vue";
import { getChatState } from "./chat-state";
import { getWritingEnabled } from "./writing-enabled";

const pluginKey = new PluginKey("ai-writing-chat-composer");

let composerApp: App | null = null;
let composerEl: HTMLDivElement | null = null;

function mountComposer(): HTMLDivElement {
  if (composerApp && composerEl) return composerEl;
  composerEl = document.createElement("div");
  composerEl.className = "ai-chat-composer-host";
  composerEl.setAttribute("contenteditable", "false");
  composerApp = createApp(ChatComposer);
  composerApp.mount(composerEl);
  return composerEl;
}

function unmountComposer() {
  if (composerApp) {
    try {
      composerApp.unmount();
    } catch {
      // 忽略
    }
    composerApp = null;
  }
  composerEl = null;
}

export function disposeChatComposer() {
  unmountComposer();
}

export function showChatComposer(view: any) {
  const tr = view.state.tr.setMeta(pluginKey, "show");
  view.dispatch(tr);
}

export function hideChatComposer(view: any) {
  const tr = view.state.tr.setMeta(pluginKey, "hide");
  view.dispatch(tr);
}

export const ChatComposerPlugin = new Plugin({
  key: pluginKey,
  state: {
    init: () => DecorationSet.empty,
    apply(tr, old) {
      // 总开关关闭：动态卸载 chat composer（不依赖编辑器刷新）
      if (!getWritingEnabled().value) {
        if (composerApp) unmountComposer();
        return DecorationSet.empty;
      }
      const meta = tr.getMeta(pluginKey);
      if (meta === "hide") {
        unmountComposer();
        return DecorationSet.empty;
      }
      if (meta === "show") {
        const cs = getChatState().value;
        if (!cs) return DecorationSet.empty;
        return buildDecorations(tr.doc, cs);
      }
      // 常规 transaction
      const cs = getChatState().value;
      if (!cs) {
        if (composerApp) unmountComposer();
        return DecorationSet.empty;
      }
      // 重新构建（range 可能因外部原因变了）
      return buildDecorations(tr.doc, cs);
    },
  },
  props: {
    decorations(state) {
      return pluginKey.getState(state) ?? DecorationSet.empty;
    },
    // 锁定选区 / 拦截编辑
    handleDOMEvents: {
      mousedown(view, event) {
        const cs = getChatState().value;
        if (!cs) return false;
        const target = event.target as HTMLElement | null;
        if (target?.closest(".ai-chat-composer-host")) return false;
        event.preventDefault();
        return true;
      },
    },
  },
});

function buildDecorations(doc: any, cs: any): DecorationSet {
  const fadeOriginal = Decoration.inline(cs.range.from, cs.range.to, {
    class: "ai-preview-original",
  });
  // 锚在 range.to 之后，让 composer 整张卡片出现在选区正下方（而不是挤在选区中间）
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
