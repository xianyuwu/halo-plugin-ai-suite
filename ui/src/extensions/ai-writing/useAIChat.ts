/**
 * useAIChat — chat composer 多轮对话 composable
 *
 * <p>复用 useAIAssist 的 SSE 流式解析。每次"发送"是独立 API 调用：
 * <ul>
 *   <li>首轮：text = 选区原文，action = 触发的动作，instruction = ""</li>
 *   <li>后续轮：text = 上一条 AI 消息内容，action = 首轮动作，instruction = 用户新输入</li>
 * </ul>
 *
 * <p>每条 AI 消息有独立 status；apply 按钮作用于指定 turn.content。
 */

import { getChatState, setChatState, makeTurnId, type ActionKey } from "./chat-state";
import { getActiveEditor } from "./editor-ref";
import { useAIAssist } from "./useAIAssist";
import { showChatComposer, hideChatComposer } from "./ChatComposerPlugin";

/** 5 个动作的元数据（label + 用于首轮 user 消息展示 + LLM 实际动作） */
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

export function useAIChat() {
  const { start, abort } = useAIAssist();

  function open(action: ActionKey) {
    const editor = getActiveEditor().value;
    if (!editor) return;

    const { state } = editor;
    const { from, to } = state.selection;
    if (state.selection.empty) return;
    const text = state.doc.textBetween(from, to);
    if (!text.trim()) return;

    const meta = ACTION_META[action];

    setChatState({
      range: { from, to },
      originalText: text,
      initialAction: action,
      turns: [],
      input: "",
      status: "idle",
    });

    // 触发 plugin 显示 composer
    showChatComposer(editor.view);

    // 折叠选区到末尾 → Halo 原生 bubble menu 的 shouldShow 因 empty=true 自动隐藏
    // （widget decoration 不依赖选区，位置不变仍显示）
    editor.commands.setTextSelection(to);

    // 自动发首轮
    sendFirstTurn(action, meta.firstPrompt);
  }

  function sendFirstTurn(action: ActionKey, prompt: string) {
    const cs = getChatState().value;
    if (!cs) return;
    const editor = getActiveEditor().value;
    if (!editor) return;

    // 添加 user 消息
    const userTurn = {
      id: makeTurnId(),
      role: "user" as const,
      content: `${ACTION_META[action].icon} ${ACTION_META[action].label}`,
      status: "done" as const,
    };
    // 添加 ai 消息（streaming）
    const aiTurnId = makeTurnId();
    const aiTurn = {
      id: aiTurnId,
      role: "ai" as const,
      content: "",
      status: "streaming" as const,
    };
    setChatState({
      ...cs,
      turns: [...cs.turns, userTurn, aiTurn],
      status: "streaming",
    });

    // 启动 SSE 流
    start(
      { text: cs.originalText, action },
      {
        onToken: (token) => {
          appendToTurn(aiTurnId, token);
        },
        onError: (msg) => {
          markTurnError(aiTurnId, msg);
        },
        onDone: () => {
          markTurnDone(aiTurnId);
        },
      }
    );
  }

  function sendFollowUp() {
    const cs = getChatState().value;
    if (!cs) return;
    if (!cs.input.trim()) return;

    const userInput = cs.input.trim();
    // 清空 input
    setChatState({ ...cs, input: "", status: "streaming" });

    // 添加 user 消息
    const userTurn = {
      id: makeTurnId(),
      role: "user" as const,
      content: userInput,
      status: "done" as const,
    };
    // 添加 ai 消息（streaming）
    const aiTurnId = makeTurnId();
    const aiTurn = {
      id: aiTurnId,
      role: "ai" as const,
      content: "",
      status: "streaming" as const,
    };
    setChatState({
      ...getChatState().value!,
      turns: [...cs.turns, userTurn, aiTurn],
      status: "streaming",
    });

    // 取最后一条 AI 消息作为下一轮的 text
    const lastAiTurn = [...cs.turns].reverse().find((t) => t.role === "ai");
    const baseText = lastAiTurn?.content || cs.originalText;

    start(
      {
        text: baseText,
        action: cs.initialAction,
        instruction: userInput,
      },
      {
        onToken: (token) => {
          appendToTurn(aiTurnId, token);
        },
        onError: (msg) => {
          markTurnError(aiTurnId, msg);
        },
        onDone: () => {
          markTurnDone(aiTurnId);
        },
      }
    );
  }

  function appendToTurn(turnId: string, token: string) {
    const cs = getChatState().value;
    if (!cs) return;
    const newTurns = cs.turns.map((t) =>
      t.id === turnId ? { ...t, content: t.content + token } : t
    );
    setChatState({ ...cs, turns: newTurns });
  }

  function markTurnDone(turnId: string) {
    const cs = getChatState().value;
    if (!cs) return;
    const newTurns = cs.turns.map((t) =>
      t.id === turnId ? { ...t, status: "done" as const } : t
    );
    setChatState({ ...cs, turns: newTurns, status: "done" });
  }

  function markTurnError(turnId: string, msg: string) {
    const cs = getChatState().value;
    if (!cs) return;
    const newTurns = cs.turns.map((t) =>
      t.id === turnId ? { ...t, status: "error" as const, error: msg } : t
    );
    setChatState({ ...cs, turns: newTurns, status: "error" });
  }

  function setInput(value: string) {
    const cs = getChatState().value;
    if (!cs) return;
    setChatState({ ...cs, input: value });
  }

  function apply(turnId: string) {
    const cs = getChatState().value;
    if (!cs) return;
    const editor = getActiveEditor().value;
    if (!editor) return;

    const turn = cs.turns.find((t) => t.id === turnId);
    if (!turn || !turn.content.trim()) return;

    applyTextToEditor(editor, cs.range, turn.content);
    close();
  }

  function retry(turnId: string) {
    const cs = getChatState().value;
    if (!cs) return;
    const editor = getActiveEditor().value;
    if (!editor) return;

    // 找到该 turn 的上一条 user 消息
    const idx = cs.turns.findIndex((t) => t.id === turnId);
    if (idx <= 0) return;
    const prevUserTurn = cs.turns[idx - 1];
    if (prevUserTurn.role !== "user") return;

    // 截断 turns 到该 user 消息，然后重新调用
    const truncatedTurns = cs.turns.slice(0, idx);
    setChatState({ ...cs, turns: truncatedTurns, status: "streaming" });

    // 添加新的 streaming ai 消息
    const aiTurnId = makeTurnId();
    const aiTurn = {
      id: aiTurnId,
      role: "ai" as const,
      content: "",
      status: "streaming" as const,
    };
    setChatState({
      ...getChatState().value!,
      turns: [...truncatedTurns, aiTurn],
      status: "streaming",
    });

    // 计算 base text
    const prevAiTurn = [...truncatedTurns].reverse().find((t) => t.role === "ai");
    const baseText = prevAiTurn?.content || cs.originalText;

    // 首轮 vs 后续轮的 instruction
    const isFirstTurn = truncatedTurns.length === 1; // 截断后只剩 user 消息
    const instruction = isFirstTurn ? "" : prevUserTurn.content;

    start(
      { text: baseText, action: cs.initialAction, instruction },
      {
        onToken: (token) => appendToTurn(aiTurnId, token),
        onError: (msg) => markTurnError(aiTurnId, msg),
        onDone: () => markTurnDone(aiTurnId),
      }
    );
  }

  function close() {
    abort();
    setChatState(null);
    const editor = getActiveEditor().value;
    if (editor) hideChatComposer(editor.view);
  }

  return { open, close, apply, retry, sendFollowUp, setInput };
}

/**
 * 把文本应用到编辑器指定 range。
 *
 * <p>用 Tiptap chain（setTextSelection + deleteSelection + insertContent），
 * 让 Tiptap 自己处理 JSON spec → ProseMirror Node 的 schema 转换。
 * 修复了之前 tr.replaceWith 直接传 JSON 数组导致"只删不插"的 bug。
 *
 * <p>mark 处理：捕获 range.from 处的 marks，apply 之后用 setMark 重新应用。
 * 跨 block 时退化为简单 insertContent，丢失 block 类型但不会清除内容。
 */
function applyTextToEditor(
  editor: any,
  range: { from: number; to: number },
  newText: string
): void {
  if (!newText) return;
  const doc = editor.state.doc;
  if (range.from < 0 || range.to > doc.content.size || range.from >= range.to) return;

  // 捕获原选区首个字符的 marks
  const $from = doc.resolve(range.from);
  const originalMarks = $from.marks();

  // 1. setTextSelection + deleteSelection + insertContent（最可靠的写法）
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

  // 2. 重新应用 marks 到新内容（如果原选区有 marks）
  if (originalMarks.length > 0) {
    const newTo = range.from + newText.length;
    try {
      for (const mark of originalMarks) {
        editor.chain()
          .setTextSelection({ from: range.from, to: newTo })
          .setMark(mark.type.name, mark.attrs)
          .run();
      }
      // 取消选区高亮（避免用户看到一片灰）
      editor.commands.setTextSelection(newTo);
    } catch (e) {
      console.warn("[ai-writing] reapply marks failed", e);
    }
  }
}
