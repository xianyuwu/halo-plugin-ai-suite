/**
 * 写作辅助「启用开关」全局状态
 *
 * <p>用于 WritingView 配置页控制是否挂载编辑器扩展：
 * <ul>
 *   <li>用户在写作辅助页关掉开关 → setWritingEnabled(false) → ref 变 false → 同时写 localStorage 兜底</li>
 *   <li>Halo 加载插件时调扩展点函数，读 ref 决定是否挂载 AiWritingExtension</li>
 *   <li>用户刷新编辑器时（已开 Halo 编辑器再回配置页改值）→ localStorage 读出来，ref 同步为 false</li>
 * </ul>
 *
 * <p>设计限制：Tiptap Extension 是 editor 创建时挂载的，运行时切换不动态卸载。
 * 用户关掉开关后，<strong>已打开的编辑器需要刷新</strong>才能让工具栏按钮和
 * 选区气泡菜单完全消失——这是接受的设计代价。
 */

import { ref, type Ref } from "vue";

const STORAGE_KEY = "ai-writing-enabled";
const DEFAULT_ENABLED = true;

const writingEnabled: Ref<boolean> = ref(loadFromStorage());

function loadFromStorage(): boolean {
  // SSR / Node 环境兜底
  if (typeof localStorage === "undefined") return DEFAULT_ENABLED;
  const v = localStorage.getItem(STORAGE_KEY);
  if (v === null) return DEFAULT_ENABLED;
  return v === "true";
}

export function getWritingEnabled(): Ref<boolean> {
  return writingEnabled;
}

export function setWritingEnabled(v: boolean): void {
  writingEnabled.value = v;
  if (typeof localStorage !== "undefined") {
    localStorage.setItem(STORAGE_KEY, String(v));
  }
}
