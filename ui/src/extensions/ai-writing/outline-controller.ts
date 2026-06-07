/**
 * 大纲生成器 — 操作入口
 *
 * <p>由 OutlineToolbarButton 触发 openOutline()，由 OutlineModal 调用
 * startOutline() 启动流式生成。
 */

import { parseOutlineJson, outlineToHtml } from "./outline-json";
import { markdownToHtml } from "./outline-markdown";
import { useAIAssist } from "./useAIAssist";
import { getActiveEditor } from "./editor-ref";
import {
  getOutlineState,
  setStatus,
  appendContent,
  closeOutline,
} from "./outline-state";

// 当前会话的 cancel 句柄（避免 cancelOutline 时拿到新的 useAIAssist 实例）
let cancelCurrent: (() => void) | null = null;

export function startOutline() {
  const editor = getActiveEditor().value;
  if (!editor) return;

  const state = getOutlineState().value;
  if (!state.topic.trim()) return;

  // 重置
  setStatus("streaming", "", null);

  const { start, abort } = useAIAssist();
  cancelCurrent = abort;

  start(
    { text: state.topic, action: "outline" },
    {
      onToken: (token) => {
        appendContent(token);
      },
      onError: (msg) => {
        setStatus("error", undefined, msg);
      },
      onDone: () => {
        setStatus("done", undefined, undefined);
        cancelCurrent = null;
      },
    }
  );
}

export function applyOutline() {
  const editor = getActiveEditor().value;
  if (!editor) return;
  const state = getOutlineState().value;
  if (!state.content.trim()) return;

  // 优先用 JSON 解析，失败则 fallback 到 markdown 渲染
  const data = parseOutlineJson(state.content);
  const html = data ? outlineToHtml(data) : markdownToHtml(state.content);

  // Tiptap insertContent 不解析 markdown，需要 HTML
  editor.chain().focus().insertContent(html).run();

  cancelCurrent = null;
  closeOutline();
}

export function cancelOutline() {
  cancelCurrent?.();
  cancelCurrent = null;
  closeOutline();
}
