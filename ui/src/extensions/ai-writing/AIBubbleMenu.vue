<script setup lang="ts">
/**
 * AIBubbleMenu — 选区下方的 5 个 AI 动作按钮条
 *
 * <p>由 AIBubbleMenuPlugin 渲染到 range.to 位置（紧贴选区结束下方），
 * 与 Halo 原生 bubble menu（出现在选区上方）分离，互不冲突。
 */

import { setActiveEditor } from "./editor-ref";
import { useAIChat } from "./useAIChat";
import { ACTION_META } from "./useAIChat";
import type { ActionKey } from "./chat-state";

const { open } = useAIChat();
const actions = Object.keys(ACTION_META) as ActionKey[];

function handleClick(action: ActionKey, e: MouseEvent) {
  e.preventDefault();
  e.stopPropagation();
  // editor 实例需要先存好（plugin 渲染 widget 时从 setActiveEditor 取）
  open(action);
}
</script>

<template>
  <div class="ai-bubble-menu" @mousedown.stop>
    <div class="ai-bubble-row">
      <button
        v-for="key in actions"
        :key="key"
        type="button"
        class="ai-bubble-btn"
        :title="`AI ${ACTION_META[key].label}`"
        @click="handleClick(key, $event)"
      >
        <span class="ai-bubble-btn-icon">{{ ACTION_META[key].icon }}</span>
        <span class="ai-bubble-btn-label">{{ ACTION_META[key].label }}</span>
      </button>
    </div>
    <div class="ai-bubble-divider" aria-hidden="true"></div>
    <div class="ai-bubble-attribution">AI 智能助手插件</div>
  </div>
</template>

<style scoped>
.ai-bubble-menu {
  display: inline-flex;
  flex-direction: column;
  min-width: 380px;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.08);
  margin: 6px 0;
  user-select: none;
  overflow: hidden;
}

.ai-bubble-row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px;
}

.ai-bubble-divider {
  height: 1px;
  background: linear-gradient(
    to right,
    transparent,
    #e5e7eb 20%,
    #e5e7eb 80%,
    transparent
  );
}

.ai-bubble-attribution {
  padding: 4px 10px 5px;
  font-size: 10px;
  font-weight: 500;
  color: #9ca3af;
  text-align: right;
  letter-spacing: 0.02em;
}

.ai-bubble-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 28px;
  padding: 0 9px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #374151;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.ai-bubble-btn:hover {
  background: #f3f4f6;
  color: #111827;
}

.ai-bubble-btn:active {
  background: #e5e7eb;
}

.ai-bubble-btn-icon {
  font-size: 13px;
  line-height: 1;
}

.ai-bubble-btn-label {
  font-size: 12px;
  line-height: 1;
}
</style>
