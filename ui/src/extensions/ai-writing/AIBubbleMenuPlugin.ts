/**
 * AIBubbleMenu ProseMirror 插件
 *
 * <p>在选区结束位置（range.to）渲染一个 5 按钮的浮动工具条，
 * 与 Halo 原生 bubble menu（出现在选区上方）分离，避免互相遮挡。
 *
 * <p>显示条件：编辑器可编辑 + 有非空选区 + chat composer 未打开 + 用户已松手（不是拖动过程中）。
 * 关闭条件：失去选区 / 取消选区 / chat composer 打开 / 正在拖动选区。
 *
 * <p>"等所有字选完再出现弹窗"通过 mousedown/mouseup 跟踪 isSelecting 实现：
 * mousedown 标记 isSelecting=true → 隐藏菜单；mouseup 标记 false → 派发事务触发重算 → 显示菜单。
 */

import {
  Plugin,
  PluginKey,
  Decoration,
  DecorationSet,
} from "@halo-dev/richtext-editor";
import { createApp, type App } from "vue";
import AIBubbleMenu from "./AIBubbleMenu.vue";
import { getChatState } from "./chat-state";
import { setActiveEditor } from "./editor-ref";
import { getWritingEnabled } from "./writing-enabled";

const pluginKey = new PluginKey("ai-writing-bubble-menu");

let bubbleApp: App | null = null;
let bubbleEl: HTMLDivElement | null = null;
let isSelecting = false;
let editorRef: any = null;
let listenersInstalled = false;

function mountBubble(): HTMLDivElement {
  if (bubbleApp && bubbleEl) return bubbleEl;
  bubbleEl = document.createElement("div");
  bubbleEl.className = "ai-bubble-menu-host";
  bubbleEl.setAttribute("contenteditable", "false");
  bubbleApp = createApp(AIBubbleMenu);
  bubbleApp.mount(bubbleEl);
  return bubbleEl;
}

function unmountBubble() {
  if (bubbleApp) {
    try {
      bubbleApp.unmount();
    } catch {
      // 忽略
    }
    bubbleApp = null;
  }
  bubbleEl = null;
}

function forceRecheck() {
  // 派发一个空事务触发 plugin 的 apply 重算
  // （mouseup 不会改变选区，ProseMirror 不会自动重跑 apply，需要手动触发）
  if (editorRef?.view) {
    try {
      const tr = editorRef.view.state.tr.setMeta(pluginKey, "recheck");
      editorRef.view.dispatch(tr);
    } catch {
      // 忽略
    }
  }
}

function installGlobalListeners() {
  if (listenersInstalled || typeof window === "undefined") return;
  listenersInstalled = true;
  // 鼠标/触摸在 window 上释放时 → 用户已松手 → 重新评估菜单
  window.addEventListener("mouseup", () => {
    if (isSelecting) {
      isSelecting = false;
      forceRecheck();
    }
  }, true);
  window.addEventListener("touchend", () => {
    if (isSelecting) {
      isSelecting = false;
      forceRecheck();
    }
  }, true);
  // 兜底：窗口失焦时也认为已松手（防止用户拖到窗口外释放、mouseup 没触发）
  window.addEventListener("blur", () => {
    if (isSelecting) {
      isSelecting = false;
      forceRecheck();
    }
  });
}

export function disposeAIBubbleMenu() {
  unmountBubble();
}

export const AIBubbleMenuPlugin = (editor: any) => {
  editorRef = editor;
  installGlobalListeners();
  return new Plugin({
    key: pluginKey,
    state: {
      init: () => DecorationSet.empty,
      apply(tr, old, oldState, newState) {
        // 总开关关闭：动态卸载气泡菜单（不依赖编辑器刷新）
        if (!getWritingEnabled().value) {
          if (bubbleApp) unmountBubble();
          return DecorationSet.empty;
        }
        const cs = getChatState().value;
        const { selection, doc } = newState;
        const { empty, from, to } = selection;

        const editable = editor?.isEditable ?? true;
        const hasText = !empty && doc.textBetween(from || 0, to || 0).length > 0;
        const composerClosed = !cs;
        const notSelecting = !isSelecting;

        if (!editable || !hasText || !composerClosed || !notSelecting) {
          if (bubbleApp) unmountBubble();
          return DecorationSet.empty;
        }

        // 确保 editor ref 已存（useAIChat.open 会用）
        setActiveEditor(editor);

        // 锚在 range.to 之后，让按钮条出现在选区正下方
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
        return pluginKey.getState(state) ?? DecorationSet.empty;
      },
      handleDOMEvents: {
        // 用户在编辑器内按下鼠标 → 标记为"正在选择"，隐藏菜单
        mousedown() {
          isSelecting = true;
          return false;
        },
        touchstart() {
          isSelecting = true;
          return false;
        },
      },
    },
  });
};
