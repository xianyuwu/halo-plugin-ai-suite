/**
 * AI 写作辅助 — Halo 文章编辑器 Tiptap Extension（v4：加 outline 大纲生成器）
 *
 * <p>注册到 {@code definePlugin} 的 {@code default:editor:extension:create} 扩展点。
 *
 * <p>功能：
 * <ul>
 *   <li>5 个 AI 动作按钮（润色/续写/扩写/简化/译英）— 选区下方气泡菜单</li>
 *   <li>chat composer 多轮对话框 — 选区下方面板</li>
 *   <li>📋 大纲生成器 — Halo 工具栏新按钮 + 居中模态</li>
 * </ul>
 */

import { Extension } from "@halo-dev/richtext-editor";
import { ChatComposerPlugin, disposeChatComposer } from "./ChatComposerPlugin";
import { AIBubbleMenuPlugin, disposeAIBubbleMenu } from "./AIBubbleMenuPlugin";
import OutlineToolbarButton from "./OutlineToolbarButton.vue";
import { getWritingEnabled } from "./writing-enabled";

const TOOLBAR_PRIORITY = 60;
const TOOLBAR_KEY = "ai-outline-button";

export const AiWritingExtension = Extension.create({
  name: "ai-writing-assistant",

  // 挂上 ProseMirror 插件：AI 气泡菜单 + chat composer
  addProseMirrorPlugins() {
    const editor = this.editor;
    return [AIBubbleMenuPlugin(editor), ChatComposerPlugin];
  },

  addOptions() {
    return {
      // Halo 编辑器工具栏右侧加「📋 大纲」按钮
      // 读 getWritingEnabled().value 动态决定是否注册；Halo 工具栏在编辑器加载
      // 时调用此函数一次，已打开的编辑器需刷新才能让按钮消失
      //
      // 不使用 markRaw 包装 OutlineToolbarButton — 组件内部 computed 订阅了
      // getWritingEnabled() ref，markRaw 会让 Halo 工具栏对组件的响应式更新失效，
      // 导致 enabled 变化时按钮置灰样式不生效
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

/** 清理所有 AI 写作相关资源（plugin 卸载时调用） */
export function cleanupAiWriting() {
  disposeAIBubbleMenu();
  disposeChatComposer();
}
