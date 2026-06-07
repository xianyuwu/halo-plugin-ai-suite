/**
 * AI 写作 chat composer 全局状态（v3：多轮对话）
 *
 * <p>每个 chatState 实例对应一次"用户点动作 → 多轮修改 → 应用"完整会话。
 * 模块级 ref 让 ChatComposerPlugin + ChatComposer.vue 共享同一份响应式状态。
 */

import { ref, type Ref } from "vue";

export type ActionKey =
  | "polish"
  | "continue"
  | "expand"
  | "simplify"
  | "translate-en";

export type TurnStatus = "streaming" | "done" | "error";
export type ChatStatus = "idle" | "streaming" | "done" | "error";

export interface ChatTurn {
  id: string;
  role: "user" | "ai";
  /** 用户输入或 AI 输出内容 */
  content: string;
  status: TurnStatus;
  error?: string;
}

export interface ChatState {
  /** 原始选区，应用按钮的写入位置 */
  range: { from: number; to: number };
  /** 选区原文（用于 apply 时构造 prompt） */
  originalText: string;
  /** 第一个动作（决定 prompt persona） */
  initialAction: ActionKey;
  /** 多轮历史（最新在末尾） */
  turns: ChatTurn[];
  /** 输入框当前值 */
  input: string;
  /** 全局状态：最近一次 send 的状态 */
  status: ChatStatus;
}

const chatState: Ref<ChatState | null> = ref(null);

export function getChatState() {
  return chatState;
}

export function setChatState(next: ChatState | null) {
  chatState.value = next;
}

export function makeTurnId() {
  return Math.random().toString(36).slice(2, 10);
}
