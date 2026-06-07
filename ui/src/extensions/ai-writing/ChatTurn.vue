<script setup lang="ts">
/**
 * ChatTurn — 单条对话气泡
 *
 * <p>user / ai 两种角色，渲染位置和样式不同。
 * <ul>
 *   <li>user：右对齐，浅灰底</li>
 *   <li>ai：左对齐，白底带边框，ai 消息旁有 [应用] [复制] [重新生成] 按钮</li>
 * </ul>
 */

import { computed } from "vue";
import type { ChatTurn as Turn } from "./chat-state";
import RiCheckLine from "~icons/ri/check-line";
import RiFileCopyLine from "~icons/ri/file-copy-line";

const props = defineProps<{
  turn: Turn;
  canApply: boolean;
  canRetry: boolean;
}>();

const emit = defineEmits<{
  (e: "apply", turnId: string): void;
  (e: "retry", turnId: string): void;
  (e: "copy", turnId: string): void;
}>();

const isUser = computed(() => props.turn.role === "user");
const isAi = computed(() => props.turn.role === "ai");
const isStreaming = computed(() => props.turn.status === "streaming");
const isError = computed(() => props.turn.status === "error");
const isDone = computed(() => props.turn.status === "done");

const showActions = computed(
  () => isAi.value && (isDone.value || isError.value)
);

function handleCopy() {
  if (!props.turn.content) return;
  navigator.clipboard.writeText(props.turn.content).catch(() => {
    // 忽略复制失败
  });
  emit("copy", props.turn.id);
}
</script>

<template>
  <div class="ai-turn" :class="[`role-${turn.role}`, turn.status]">
    <div class="ai-turn-bubble">
      <!-- 流式中：闪烁光标 -->
      <template v-if="isStreaming">
        <span class="ai-turn-text">{{ turn.content || " " }}</span>
        <span class="ai-cursor">|</span>
      </template>

      <!-- 错误 -->
      <template v-else-if="isError">
        <span class="ai-turn-error-icon">!</span>
        <span class="ai-turn-error-msg">{{ turn.error || "生成失败" }}</span>
      </template>

      <!-- 完成（user 或 ai） -->
      <template v-else>
        <span class="ai-turn-text">{{ turn.content }}</span>
      </template>
    </div>

    <!-- AI 消息的操作按钮：应用 / 复制 / 重新生成 -->
    <div v-if="showActions" class="ai-turn-actions">
      <button
        v-if="canApply && turn.content.trim()"
        class="ai-turn-action-btn apply"
        title="替换选区为这条 AI 输出"
        @click="emit('apply', turn.id)"
      >
        <RiCheckLine /> 应用
      </button>
      <button
        v-if="turn.content.trim()"
        class="ai-turn-action-btn copy"
        title="复制到剪贴板"
        @click="handleCopy"
      >
        <RiFileCopyLine />
      </button>
      <button
        v-if="canRetry"
        class="ai-turn-action-btn retry"
        title="基于上一条 user 消息重新生成"
        @click="emit('retry', turn.id)"
      >
        ↻
      </button>
    </div>
  </div>
</template>

<style scoped>
.ai-turn {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;
}

.ai-turn.role-user {
  align-items: flex-end;
}

.ai-turn.role-ai {
  align-items: flex-start;
}

.ai-turn-bubble {
  max-width: 85%;
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.role-user .ai-turn-bubble {
  background: #f3f4f6;
  color: #1f2937;
  border-bottom-right-radius: 4px;
}

.role-ai .ai-turn-bubble {
  background: #ffffff;
  color: #1f2937;
  border: 1px solid #e5e7eb;
  border-bottom-left-radius: 4px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

.role-ai.error .ai-turn-bubble {
  background: #fef2f2;
  color: #991b1b;
  border-color: #fecaca;
}

.ai-turn-error-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #dc2626;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  margin-right: 6px;
  flex-shrink: 0;
}

.ai-cursor {
  display: inline-block;
  margin-left: 1px;
  font-weight: 600;
  color: #2563eb;
  animation: ai-blink 0.8s infinite;
}

@keyframes ai-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.ai-turn-actions {
  display: flex;
  gap: 4px;
  align-items: center;
  margin-top: 2px;
  margin-left: 4px;
}

.ai-turn-action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 22px;
  min-width: 22px;
  padding: 0 7px;
  border: 1px solid #e5e7eb;
  border-radius: 5px;
  background: #ffffff;
  color: #4b5563;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
}

.ai-turn-action-btn:hover {
  background: #f9fafb;
  border-color: #9ca3af;
}

.ai-turn-action-btn.apply {
  background: #111827;
  color: #fff;
  border-color: #111827;
}

.ai-turn-action-btn.apply:hover {
  background: #1f2937;
}
</style>
