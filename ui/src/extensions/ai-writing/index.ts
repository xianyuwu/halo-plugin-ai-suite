/**
 * AI 写作辅助 — Halo 文章编辑器 Tiptap Extension
 *
 * <p>注册到 {@code definePlugin} 的 {@code default:editor:extension:create} 扩展点.
 * 每个 editor 实例通过 {@link getStore} 获得独立的 WritingStore (per-editor 隔离),
 * onDestroy 时 {@link disposeStore} 清理 window 监听 + Vue app 实例.
 */

import { Extension } from "@halo-dev/richtext-editor";
import OutlineToolbarButton from "./OutlineToolbarButton.vue";
import { getStore, disposeStore } from "./ai-writing-store";
import { getWritingEnabled } from "./writing-enabled";

const TOOLBAR_PRIORITY = 60;
const TOOLBAR_KEY = "ai-outline-button";

export const AiWritingExtension = Extension.create({
  name: "ai-writing-assistant",

  // 挂上 ProseMirror 插件: 从本 editor 的 store 取 (per-editor 独立实例)
  addProseMirrorPlugins() {
    const store = getStore(this.editor);
    return [store.bubblePlugin, store.composerPlugin];
  },

  // 编辑器销毁时清理本 editor 的 store (移除 window 监听 + 卸载 Vue app)
  onDestroy() {
    if (this.editor) disposeStore(this.editor);
  },

  addOptions() {
    return {
      // Halo 编辑器工具栏右侧加「📋 大纲」按钮
      getToolbarItems: ({ editor }: { editor: any }) => {
        if (!getWritingEnabled().value) return null;
        return {
          priority: TOOLBAR_PRIORITY,
          key: TOOLBAR_KEY,
          component: OutlineToolbarButton,
          props: {
            editor,
            isActive: false,
            title: "生成文章大纲",
          },
        };
      },
    };
  },
});

/**
 * 兼容旧调用方 (cleanupAiWriting) — 现已无需全局清理, 每个编辑器在 onDestroy 自行 dispose.
 * 保留导出避免外部 import 报错.
 * @deprecated 清理逻辑已移至 AiWritingExtension.onDestroy, 无需手动调用.
 */
export function cleanupAiWriting() {
  // no-op: per-editor 清理在 onDestroy 完成
}
