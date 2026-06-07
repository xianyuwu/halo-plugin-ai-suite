/**
 * 当前激活的 Tiptap editor 单例引用
 *
 * <p>因为 PreviewWidget 是用 createApp 独立挂载的（没有父组件 provide），
 * 它需要通过模块级 ref 拿到当前编辑器实例来执行 apply/cancel/retry 操作。
 *
 * <p>同一时间只有一个 editor 处于活跃状态（Halo 文章编辑器是单例），
 * 所以全局单例够用。
 */

import { shallowRef, type ShallowRef } from "vue";
import type { Editor } from "@halo-dev/richtext-editor";

const editorRef: ShallowRef<Editor | null> = shallowRef(null);

export function setActiveEditor(editor: Editor | null) {
  editorRef.value = editor;
}

export function getActiveEditor(): ShallowRef<Editor | null> {
  return editorRef;
}
