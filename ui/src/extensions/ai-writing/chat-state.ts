/**
 * AI 写作 chat composer 类型定义 + 工具
 *
 * <p>原模块级 chatState 单例已移除 — 现在每 editor 持有独立的 chatState
 * (见 ai-writing-store.ts 的 WritingStore.chatState). 本文件只保留类型和工具函数.
 */

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

export function makeTurnId() {
  return Math.random().toString(36).slice(2, 10);
}
