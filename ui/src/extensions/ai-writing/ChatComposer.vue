<script setup lang="ts">
/**
 * ChatComposer — AI 写作 chat composer 主组件
 *
 * <p>通过 createApp 挂载到 ProseMirror widget decoration 的 DOM 里。
 *
 * <p>结构：
 * <ul>
 *   <li>头部：标题 + 关闭按钮</li>
 *   <li>历史：ChatTurn 列表</li>
 *   <li>输入：textarea + 发送按钮</li>
 * </ul>
 */

import { computed, nextTick, ref, watch } from "vue";
import ChatTurn from "./ChatTurn.vue";
import { useAIChat } from "./useAIChat";
import type { WritingStore } from "./ai-writing-store";
import RiCloseLine from "~icons/ri/close-line";

const props = defineProps<{ store: WritingStore }>();

const cs = props.store.chatState;
const { close, apply, retry, sendFollowUp, setInput } = useAIChat(props.store);

const inputValue = ref("");
const inputEl = ref<HTMLTextAreaElement | null>(null);
const historyEl = ref<HTMLElement | null>(null);
const isStreaming = computed(() => cs.value?.status === "streaming");

// 同步外部 state 的 input 到本地 ref
watch(
  () => cs.value?.input,
  (v) => {
    if (v !== undefined && v !== inputValue.value) {
      inputValue.value = v;
    }
  }
);

// 流式新内容到达时滚到底部
watch(
  () => cs.value?.turns,
  () => {
    nextTick(() => {
      if (historyEl.value) {
        historyEl.value.scrollTop = historyEl.value.scrollHeight;
      }
    });
  },
  { deep: true }
);

function handleInputChange(e: Event) {
  const v = (e.target as HTMLTextAreaElement).value;
  inputValue.value = v;
  setInput(v);
}

function handleSend() {
  if (isStreaming.value) return;
  if (!inputValue.value.trim()) return;
  sendFollowUp();
}

function handleKeyDown(e: KeyboardEvent) {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    handleSend();
  } else if (e.key === "Escape") {
    e.preventDefault();
    close();
  }
}

function handleApply(turnId: string) {
  apply(turnId);
}

function handleRetry(turnId: string) {
  retry(turnId);
}

const canSend = computed(
  () => !isStreaming.value && inputValue.value.trim().length > 0
);
</script>

<template>
  <div v-if="cs" class="ai-chat-composer" @keydown="handleKeyDown">
    <!-- 头部 -->
    <div class="ai-chat-header">
      <span class="ai-chat-title">AI 写作助手</span>
      <button
        class="ai-chat-close"
        title="关闭"
        @click="close"
      >
        <RiCloseLine />
      </button>
    </div>

    <!-- 历史 -->
    <div ref="historyEl" class="ai-chat-history">
      <ChatTurn
        v-for="(turn, idx) in cs.turns"
        :key="turn.id"
        :turn="turn"
        :can-apply="true"
        :can-retry="
          turn.role === 'ai' &&
          idx > 0 &&
          cs.turns[idx - 1].role === 'user' &&
          !isStreaming
        "
        @apply="handleApply"
        @retry="handleRetry"
      />
    </div>

    <!-- 输入区 -->
    <div class="ai-chat-input-row">
      <textarea
        ref="inputEl"
        :value="inputValue"
        class="ai-chat-input"
        :placeholder="isStreaming ? 'AI 处理中…' : '继续告诉 AI 怎么改…（Shift+Enter 换行）'"
        :disabled="isStreaming"
        rows="2"
        @input="handleInputChange"
        @keydown="handleKeyDown"
      />
      <button
        class="ai-chat-send"
        :disabled="!canSend"
        title="发送（Enter）"
        @click="handleSend"
      >
        <span v-if="isStreaming" class="ai-chat-spinner"></span>
        <span v-else>↑</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.ai-chat-composer {
  display: flex;
  flex-direction: column;
  width: 480px;
  max-width: 90vw;
  margin: 8px 0;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans",
    "PingFang SC", "Microsoft YaHei", sans-serif;
  user-select: none;
  overflow: hidden;
}

.ai-chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 1px solid #f3f4f6;
  background: #fafbfc;
}

.ai-chat-title {
  font-size: 13px;
  font-weight: 600;
  color: #111827;
}

.ai-chat-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: #6b7280;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.ai-chat-close:hover {
  background: #f3f4f6;
  color: #111827;
}

.ai-chat-history {
  max-height: 360px;
  overflow-y: auto;
  padding: 14px;
  background: #f9fafb;
  /* 让滚动条更细 */
  scrollbar-width: thin;
}

.ai-chat-input-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding: 10px 12px;
  border-top: 1px solid #f3f4f6;
  background: #ffffff;
}

.ai-chat-input {
  flex: 1;
  min-width: 0;
  min-height: 38px;
  max-height: 120px;
  padding: 8px 10px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.5;
  font-family: inherit;
  color: #111827;
  background: #fff;
  outline: none;
  resize: none;
  box-sizing: border-box;
}

.ai-chat-input:focus {
  border-color: #111827;
  box-shadow: 0 0 0 3px rgba(17, 24, 39, 0.06);
}

.ai-chat-input:disabled {
  background: #f9fafb;
  color: #6b7280;
  cursor: not-allowed;
}

.ai-chat-send {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  border: none;
  border-radius: 8px;
  background: #111827;
  color: #ffffff;
  font-size: 18px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
}

.ai-chat-send:hover:not(:disabled) {
  background: #1f2937;
}

.ai-chat-send:disabled {
  background: #d1d5db;
  color: #9ca3af;
  cursor: not-allowed;
}

.ai-chat-spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #ffffff;
  border-radius: 50%;
  animation: ai-spin 0.8s linear infinite;
}

@keyframes ai-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 520px) {
  .ai-chat-composer {
    width: calc(100vw - 24px);
    max-width: calc(100vw - 24px);
  }
  .ai-chat-history {
    max-height: 52vh;
    padding: 12px;
  }
  .ai-chat-input-row {
    padding: 10px;
  }
}
</style>
