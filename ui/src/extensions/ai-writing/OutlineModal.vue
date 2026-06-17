<script setup lang="ts">
/**
 * OutlineModal — 大纲生成器弹窗
 *
 * <p>通过 createApp 独立挂载到 body（避免污染 Halo 主 Vue 实例）。
 * 内容：主题输入 + 流式预览（自写 markdown 解析为 HTML）+ 应用/取消按钮。
 *
 * <p>visible 用 computed get/set 包一层，绕开 v-model 写不回外部 ref 的问题。
 */

import { computed, nextTick, ref, watch } from "vue";
import { VModal, VButton, VSpace } from "@halo-dev/components";
import {
  parseOutlineJson,
  outlineToHtml,
  countStreamingSections,
} from "./outline-json";
import {
  getOutlineVisible,
  getOutlineState,
  setOutlineTopic,
  closeOutline,
} from "./outline-state";
import { startOutline, applyOutline, cancelOutline } from "./outline-controller";

const visibleRef = getOutlineVisible();
const state = getOutlineState();
const inputEl = ref<HTMLTextAreaElement | null>(null);
const previewEl = ref<HTMLElement | null>(null);

// v-model 不能写回模块 ref, 用 computed get/set 包一层, 关闭时调 closeOutline
const visible = computed({
  get: () => visibleRef.value,
  set: (v) => {
    if (!v) closeOutline();
  },
});

const isStreaming = computed(() => state.value.status === "streaming");
const isDone = computed(() => state.value.status === "done");
const isError = computed(() => state.value.status === "error");
const canGenerate = computed(
  () => !isStreaming.value && state.value.topic.trim().length > 0
);
const canApply = computed(
  () => isDone.value && state.value.content.trim().length > 0
);

const topicValue = computed({
  get: () => state.value.topic,
  set: (v) => setOutlineTopic(v),
});

// 流式阶段统计已生成的章节数
const streamingSectionCount = computed(() => {
  if (state.value.status !== "streaming") return 0;
  return countStreamingSections(state.value.content);
});

// done 阶段解析 JSON
const parsedOutline = computed(() => {
  if (state.value.status !== "done") return null;
  return parseOutlineJson(state.value.content);
});

// 预览 HTML：done 且 JSON 解析成功 → 结构化渲染；否则空（由模板显示进度或错误）
const renderedHtml = computed(() => {
  if (parsedOutline.value) return outlineToHtml(parsedOutline.value);
  // JSON 解析失败的 fallback：尝试原始 markdown 渲染
  if (state.value.status === "done" && state.value.content) {
    return state.value.content
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }
  return "";
});

// 流式新内容到达时滚到预览区底部
watch(
  () => state.value.content,
  () => {
    nextTick(() => {
      if (previewEl.value) {
        previewEl.value.scrollTop = previewEl.value.scrollHeight;
      }
    });
  }
);

function handleInput(e: Event) {
  topicValue.value = (e.target as HTMLTextAreaElement).value;
}

function handleKeyDown(e: KeyboardEvent) {
  if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
    e.preventDefault();
    if (canGenerate.value) {
      startOutline();
    }
  } else if (e.key === "Escape") {
    e.preventDefault();
    if (isStreaming.value) {
      cancelOutline();
    } else {
      closeOutline();
    }
  }
}

function handleGenerate() {
  if (canGenerate.value) startOutline();
}

function handleApply() {
  applyOutline();
}

function handleCancel() {
  cancelOutline();
}
</script>

<template>
  <VModal
    v-model:visible="visible"
    :width="640"
    :layer-closable="!isStreaming"
    :title="'生成文章大纲'"
    @close="handleCancel"
  >
    <div class="ai-outline-modal" @keydown="handleKeyDown">
      <!-- 主题输入 -->
      <div class="ai-outline-input-row">
        <label class="ai-outline-label">文章主题</label>
        <textarea
          ref="inputEl"
          class="ai-outline-input"
          :value="topicValue"
          :disabled="isStreaming"
          rows="2"
          placeholder="如：云原生架构的演进与实践 / 用 Vue 3 + Vite 重构 Halo 插件..."
          @input="handleInput"
          @keydown="handleKeyDown"
        />
        <div class="ai-outline-tip">⌘/Ctrl + Enter 快速生成</div>
      </div>

      <!-- 预览区 -->
      <div class="ai-outline-preview-wrap">
        <div class="ai-outline-preview-header">
          <span>预览</span>
          <span v-if="state.content" class="ai-outline-preview-meta">
            {{ state.content.length }} 字
          </span>
        </div>
        <div
          ref="previewEl"
          class="ai-outline-preview"
          :class="{ 'is-empty': !state.content, 'is-error': isError }"
        >
          <!-- 空状态 -->
          <span v-if="!state.content && !isError" class="ai-outline-placeholder">
            大纲会在这里生成（# 标题 / ## 二级标题 + 简短提要）
          </span>

          <!-- 流式生成中 — 显示进度 -->
          <div v-else-if="isStreaming" class="ai-outline-streaming">
            <span class="ai-outline-spinner" />
            <span>正在生成大纲...</span>
            <span v-if="streamingSectionCount > 0" class="ai-outline-progress">
              已生成 {{ streamingSectionCount }} 个章节
            </span>
          </div>

          <!-- 错误 -->
          <div v-else-if="isError" class="ai-outline-error">
            <span class="ai-outline-error-icon">!</span>
            <span class="ai-outline-error-msg">{{ state.error || "生成失败" }}</span>
          </div>

          <!-- JSON 解析失败 — 显示原始文本 -->
          <div v-else-if="!parsedOutline && renderedHtml" class="ai-outline-fallback">
            <div class="ai-outline-fallback-warn">JSON 解析失败，显示原始输出：</div>
            <div class="ai-outline-content" v-html="renderedHtml" />
          </div>

          <!-- 内容（JSON 解析成功，结构化渲染） -->
          <div v-else class="ai-outline-content" v-html="renderedHtml" />
        </div>
      </div>
    </div>

    <template #footer>
      <div class="ai-outline-footer">
        <VSpace>
          <VButton :disabled="isStreaming" @click="handleCancel">
            取消
          </VButton>
          <VButton
            v-if="!canApply"
            type="primary"
            :disabled="!canGenerate"
            :loading="isStreaming"
            @click="handleGenerate"
          >
            {{ isStreaming ? "生成中..." : "生成大纲" }}
          </VButton>
          <VButton
            v-else
            type="primary"
            @click="handleApply"
          >
            插入到编辑器
          </VButton>
        </VSpace>
        <span class="ai-outline-brand">Powered by AI智能套件插件</span>
      </div>
    </template>
  </VModal>
</template>

<style scoped>
.ai-outline-modal {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 380px;
  max-height: 70vh; /* 弹窗最多占视口 70%，超出后预览区内部滚动 */
}

.ai-outline-input-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0; /* 主题输入区不被压缩 */
}

.ai-outline-label {
  font-size: 13px;
  font-weight: 600;
  color: #374151;
}

.ai-outline-input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.5;
  font-family: inherit;
  color: #111827;
  background: #fff;
  outline: none;
  resize: vertical;
  box-sizing: border-box;
}

.ai-outline-input:focus {
  border-color: #111827;
  box-shadow: 0 0 0 3px rgba(17, 24, 39, 0.06);
}

.ai-outline-input:disabled {
  background: #f9fafb;
  color: #6b7280;
  cursor: not-allowed;
}

.ai-outline-tip {
  font-size: 11px;
  color: #9ca3af;
  text-align: right;
}

.ai-outline-preview-wrap {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0; /* 关键：让 flex 子项可被压缩，从而预览区能滚动 */
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  background: #fafafa;
}

.ai-outline-preview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  background: #f3f4f6;
  border-bottom: 1px solid #e5e7eb;
  font-size: 11px;
  font-weight: 700;
  color: #6b7280;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.ai-outline-preview-meta {
  font-size: 11px;
  color: #6b7280;
}

.ai-outline-preview {
  flex: 1;
  overflow-y: auto; /* 垂直滚动；横向不滚动 */
  padding: 14px 16px;
  min-height: 200px;
  max-height: 100%; /* 不超过父容器 */
  font-size: 13px;
  line-height: 1.7;
  color: #1f2937;
}

.ai-outline-preview.is-empty {
  display: flex;
  align-items: center;
  justify-content: center;
}

.ai-outline-preview.is-error {
  background: #fef2f2;
}

.ai-outline-placeholder {
  color: #9ca3af;
  font-style: italic;
}

.ai-outline-error {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #991b1b;
}

.ai-outline-error-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #dc2626;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  flex-shrink: 0;
}

.ai-cursor-append {
  display: inline-block;
  margin-top: 4px;
  font-weight: 600;
  color: #2563eb;
  font-size: 14px;
  line-height: 1;
  animation: ai-blink 0.8s infinite;
}

.ai-outline-streaming {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 40px 0;
  color: #6b7280;
  font-size: 14px;
}

.ai-outline-spinner {
  width: 24px;
  height: 24px;
  border: 3px solid #e5e7eb;
  border-top-color: #2563eb;
  border-radius: 50%;
  animation: ai-spin 0.8s linear infinite;
}

.ai-outline-progress {
  font-size: 12px;
  color: #2563eb;
  font-weight: 500;
}

.ai-outline-fallback-warn {
  padding: 8px 12px;
  margin-bottom: 12px;
  background: #fef3c7;
  border: 1px solid #fbbf24;
  border-radius: 6px;
  font-size: 12px;
  color: #92400e;
}

@keyframes ai-spin {
  to { transform: rotate(360deg); }
}

.ai-outline-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ai-outline-brand {
  font-size: 11px;
  color: #b0b0b0;
}

@keyframes ai-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
</style>

<!--
  v-html 渲染的 HTML 不受 scoped 影响，markdown 元素样式需要 unscoped。
  复制到编辑器时也用 .ai-outline-content 容器套住，让视觉一致。
-->
<style>
/* 完全对齐 Halo 编辑器 .markdown-body 主题（GitHub 风格） */
.ai-outline-content {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans",
    Helvetica, Arial, "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 16px;
  line-height: 1.5;
  color: #24292f;
}

.ai-outline-content h1,
.ai-outline-content h2,
.ai-outline-content h3 {
  font-weight: 600 !important;
  line-height: 1.25 !important;
  margin-top: 24px !important;
  margin-bottom: 16px !important;
}

.ai-outline-content h1 {
  font-size: 1.5em !important;
  border-bottom: 1px solid #d8dee4 !important;
  padding-bottom: 0.3em !important;
}

.ai-outline-content h2 {
  font-size: 1.25em !important;
}

.ai-outline-content h3 {
  font-size: 1.1em !important;
}

/* 主标题：非 heading 标签，靠 class 模拟 h1 视觉（2em + 下边框） */
.ai-outline-content .ai-outline-master-title {
  font-size: 2em !important;
  font-weight: 600 !important;
  line-height: 1.25 !important;
  margin: 0.67em 0 !important;
  padding-bottom: 0.3em !important;
  border-bottom: 1px solid #d8dee4 !important;
}

.ai-outline-content p {
  margin-top: 0 !important;
  margin-bottom: 16px !important;
  font-size: 1em !important;
  line-height: 1.5 !important;
  color: #24292f !important;
}

.ai-outline-content > *:first-child {
  margin-top: 0 !important;
}
</style>
