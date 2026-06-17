/**
 * useAIChat — chat composer 多轮对话 composable (per-editor, 接收 store)
 *
 * <p>复用 useAIAssist 的 SSE 流式解析. useAIAssist 实例存入 store.assistAbort,
 * 保证 close 时能 abort 正在进行的流 (原 AIBubbleMenu 和 ChatComposer 各 new 一份
 * useAIAssist 导致 abort 对不齐).
 */

import type { WritingStore } from "./ai-writing-store";
import { makeTurnId, type ActionKey } from "./chat-state";
import { useAIAssist } from "./useAIAssist";
import { showChatComposer, hideChatComposer } from "./ChatComposerPlugin";

/** 5 个动作的元数据 */
export const ACTION_META: Record<
  ActionKey,
  { label: string; icon: string; firstPrompt: string }
> = {
  polish: {
    label: "润色",
    icon: "✨",
    firstPrompt: "请把这段文字润色一下，使表达更流畅、有文采。",
  },
  continue: {
    label: "续写",
    icon: "✏️",
    firstPrompt: "请基于上一段自然续写。",
  },
  expand: {
    label: "扩写",
    icon: "📝",
    firstPrompt: "请把这段扩写得更详细、丰富。",
  },
  simplify: {
    label: "简化",
    icon: "📄",
    firstPrompt: "请把这段简化得更通俗易懂。",
  },
  "translate-en": {
    label: "译英",
    icon: "🌐",
    firstPrompt: "请把这段翻译成英文。",
  },
};

export function useAIChat(store: WritingStore) {
  const chatState = store.chatState;
  const cs = () => chatState.value;
  const setCs = (v: typeof chatState.value) => { chatState.value = v; };

  function startStream(text: string, action: ActionKey, instruction: string, aiTurnId: string) {
    const { start, abort } = useAIAssist();
    store.assistAbort = abort;
    start(
      { text, action, instruction },
      {
        onToken: (token) => appendToTurn(aiTurnId, token),
        onError: (msg) => markTurnError(aiTurnId, msg),
        onDone: () => markTurnDone(aiTurnId),
      }
    );
  }

  function open(action: ActionKey) {
    const editor = store.editor;
    const { state } = editor;
    const { from, to } = state.selection;
    if (state.selection.empty) return;
    const text = state.doc.textBetween(from, to);
    if (!text.trim()) return;

    const meta = ACTION_META[action];
    setCs({
      range: { from, to },
      originalText: text,
      initialAction: action,
      turns: [],
      input: "",
      status: "idle",
    });

    showChatComposer(store);
    editor.commands.setTextSelection(to);

    // 自动发首轮
    sendFirstTurn(action);
  }

  function sendFirstTurn(action: ActionKey) {
    const cur = cs();
    if (!cur) return;
    const userTurn = {
      id: makeTurnId(),
      role: "user" as const,
      content: `${ACTION_META[action].icon} ${ACTION_META[action].label}`,
      status: "done" as const,
    };
    const aiTurnId = makeTurnId();
    const aiTurn = {
      id: aiTurnId,
      role: "ai" as const,
      content: "",
      status: "streaming" as const,
    };
    setCs({
      ...cur,
      turns: [...cur.turns, userTurn, aiTurn],
      status: "streaming",
    });

    startStream(cur.originalText, action, "", aiTurnId);
  }

  function sendFollowUp() {
    const cur = cs();
    if (!cur) return;
    if (!cur.input.trim()) return;

    const userInput = cur.input.trim();
    setCs({ ...cur, input: "", status: "streaming" });

    const userTurn = {
      id: makeTurnId(),
      role: "user" as const,
      content: userInput,
      status: "done" as const,
    };
    const aiTurnId = makeTurnId();
    const aiTurn = {
      id: aiTurnId,
      role: "ai" as const,
      content: "",
      status: "streaming" as const,
    };
    const latest = cs()!;
    setCs({
      ...latest,
      turns: [...cur.turns, userTurn, aiTurn],
      status: "streaming",
    });

    const lastAiTurn = [...cur.turns].reverse().find((t) => t.role === "ai");
    const baseText = lastAiTurn?.content || cur.originalText;

    startStream(baseText, cur.initialAction, userInput, aiTurnId);
  }

  function appendToTurn(turnId: string, token: string) {
    const cur = cs();
    if (!cur) return;
    const newTurns = cur.turns.map((t) =>
      t.id === turnId ? { ...t, content: t.content + token } : t
    );
    setCs({ ...cur, turns: newTurns });
  }

  function markTurnDone(turnId: string) {
    const cur = cs();
    if (!cur) return;
    const newTurns = cur.turns.map((t) =>
      t.id === turnId ? { ...t, status: "done" as const } : t
    );
    setCs({ ...cur, turns: newTurns, status: "done" });
  }

  function markTurnError(turnId: string, msg: string) {
    const cur = cs();
    if (!cur) return;
    const newTurns = cur.turns.map((t) =>
      t.id === turnId ? { ...t, status: "error" as const, error: msg } : t
    );
    setCs({ ...cur, turns: newTurns, status: "error" });
  }

  function setInput(value: string) {
    const cur = cs();
    if (!cur) return;
    setCs({ ...cur, input: value });
  }

  function apply(turnId: string) {
    const cur = cs();
    if (!cur) return;
    const editor = store.editor;
    const turn = cur.turns.find((t) => t.id === turnId);
    if (!turn || !turn.content.trim()) return;

    applyTextToEditor(editor, cur.range, turn.content);
    close();
  }

  function retry(turnId: string) {
    const cur = cs();
    if (!cur) return;

    const idx = cur.turns.findIndex((t) => t.id === turnId);
    if (idx <= 0) return;
    const prevUserTurn = cur.turns[idx - 1];
    if (prevUserTurn.role !== "user") return;

    const truncatedTurns = cur.turns.slice(0, idx);
    setCs({ ...cur, turns: truncatedTurns, status: "streaming" });

    const aiTurnId = makeTurnId();
    const aiTurn = {
      id: aiTurnId,
      role: "ai" as const,
      content: "",
      status: "streaming" as const,
    };
    const latest = cs()!;
    setCs({
      ...latest,
      turns: [...truncatedTurns, aiTurn],
      status: "streaming",
    });

    const prevAiTurn = [...truncatedTurns].reverse().find((t) => t.role === "ai");
    const baseText = prevAiTurn?.content || cur.originalText;

    const isFirstTurn = truncatedTurns.length === 1;
    const instruction = isFirstTurn ? "" : prevUserTurn.content;

    startStream(baseText, cur.initialAction, instruction, aiTurnId);
  }

  function close() {
    store.assistAbort?.();
    store.assistAbort = null;
    setCs(null);
    hideChatComposer(store);
  }

  return { open, close, apply, retry, sendFollowUp, setInput };
}

/**
 * 把文本应用到编辑器指定 range. mark 处理: 捕获 range.from 的 marks, apply 后重应用.
 */
function applyTextToEditor(
  editor: any,
  range: { from: number; to: number },
  newText: string
): void {
  if (!newText) return;
  const doc = editor.state.doc;
  if (range.from < 0 || range.to > doc.content.size || range.from >= range.to) return;

  const $from = doc.resolve(range.from);
  const originalMarks = $from.marks();

  const ok = editor.chain()
    .focus()
    .setTextSelection({ from: range.from, to: range.to })
    .deleteSelection()
    .insertContent(newText)
    .run();

  if (!ok) {
    console.warn("[ai-writing] insertContent failed", { range, newText });
    return;
  }

  if (originalMarks.length > 0) {
    const newTo = range.from + newText.length;
    try {
      for (const mark of originalMarks) {
        editor.chain()
          .setTextSelection({ from: range.from, to: newTo })
          .setMark(mark.type.name, mark.attrs)
          .run();
      }
      editor.commands.setTextSelection(newTo);
    } catch (e) {
      console.warn("[ai-writing] reapply marks failed", e);
    }
  }
}
