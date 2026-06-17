/**
 * 大纲生成器 — 操作入口 (per-editor, 接收 store)
 */

import { parseOutlineJson, outlineToHtml } from "./outline-json";
import { markdownToHtml } from "./outline-markdown";
import { useAIAssist } from "./useAIAssist";
import type { WritingStore } from "./ai-writing-store";
import {
  setOutlineStatus,
  appendOutlineContent,
  closeOutline,
} from "./outline-state";

export function startOutline(store: WritingStore) {
  const editor = store.editor;
  const state = store.outlineState.value;
  if (!state.topic.trim()) return;

  setOutlineStatus(store, "streaming", "", null);

  const { start, abort } = useAIAssist();
  store.outlineCancel = abort;

  start(
    { text: state.topic, action: "outline" },
    {
      onToken: (token) => {
        appendOutlineContent(store, token);
      },
      onError: (msg) => {
        setOutlineStatus(store, "error", undefined, msg);
      },
      onDone: () => {
        setOutlineStatus(store, "done", undefined, undefined);
        store.outlineCancel = null;
      },
    }
  );
}

export function applyOutline(store: WritingStore) {
  const editor = store.editor;
  const state = store.outlineState.value;
  if (!state.content.trim()) return;

  const data = parseOutlineJson(state.content);
  const html = data ? outlineToHtml(data) : markdownToHtml(state.content);

  editor.chain().focus().insertContent(html).run();

  store.outlineCancel = null;
  closeOutline(store);
}

export function cancelOutline(store: WritingStore) {
  store.outlineCancel?.();
  store.outlineCancel = null;
  closeOutline(store);
}
