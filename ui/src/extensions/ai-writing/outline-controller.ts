/**
 * 大纲生成器 — 操作入口 (全局单 modal, editor 从 activeEditor 取)
 */

import { parseOutlineJson, outlineToHtml } from "./outline-json";
import { markdownToHtml } from "./outline-markdown";
import { useAIAssist } from "./useAIAssist";
import {
  getActiveOutlineEditor,
  getOutlineState,
  setStatus,
  appendContent,
  closeOutline,
} from "./outline-state";

// 当前会话的 cancel 句柄
let cancelCurrent: (() => void) | null = null;

export function startOutline() {
  const editor = getActiveOutlineEditor();
  if (!editor) return;

  const state = getOutlineState().value;
  if (!state.topic.trim()) return;

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
  const editor = getActiveOutlineEditor();
  if (!editor) return;
  const state = getOutlineState().value;
  if (!state.content.trim()) return;

  const data = parseOutlineJson(state.content);
  const html = data ? outlineToHtml(data) : markdownToHtml(state.content);

  editor.chain().focus().insertContent(html).run();

  cancelCurrent = null;
  closeOutline();
}

export function cancelOutline() {
  cancelCurrent?.();
  cancelCurrent = null;
  closeOutline();
}
