<script setup lang="ts">
/**
 * OutlineToolbarButton — Halo 编辑器工具栏的「AI 生成大纲」按钮
 *
 * <p>✨ sparkle + "AI 大纲" 文字标签 + 紫色 hover 暗示 AI 属性。
 * 点击打开 OutlineModal（懒挂载）。
 *
 * <p>响应式禁用：组件内 computed 读 getWritingEnabled()，
 * 总开关关闭时按钮置灰 + 改 tooltip + 阻止点击。
 */

import { computed } from "vue";
import { openOutline } from "./outline-state";
import { getWritingEnabled } from "./writing-enabled";
import RiSparkling2Line from "~icons/ri/sparkling-2-line";

const props = defineProps<{
  editor: any;
  isActive?: boolean;
  disabled?: boolean;
}>();

const enabled = computed(() => getWritingEnabled().value);
const isDisabled = computed(() => !enabled.value || props.disabled);
const tooltip = computed(() =>
  enabled.value ? "AI 生成文章大纲" : "AI 写作辅助已关闭（请在「写作辅助」配置页开启）"
);

function handleClick() {
  if (isDisabled.value) return;
  // 全局单 modal, editor 作为参数传入, apply 时插入到该 editor
  openOutline(props.editor);
}
</script>

<template>
  <button
    type="button"
    class="ai-outline-toolbar-btn"
    :class="{ active: isActive, disabled: isDisabled }"
    :disabled="isDisabled"
    :title="tooltip"
    @click="handleClick"
  >
    <RiSparkling2Line class="ai-outline-toolbar-btn-icon" />
    <span class="ai-outline-toolbar-btn-label">AI 大纲</span>
  </button>
</template>

<style scoped>
.ai-outline-toolbar-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 32px;
  padding: 0 10px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #ffffff;
  color: #374151;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.ai-outline-toolbar-btn:hover:not(:disabled) {
  background: #f5f3ff; /* 淡紫 */
  border-color: #c4b5fd; /* 紫边 */
  color: #5b21b6; /* 紫文字 */
}

.ai-outline-toolbar-btn.active {
  background: #ede9fe;
  color: #5b21b6;
  border-color: #a78bfa;
}

.ai-outline-toolbar-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: #f9fafb;
}

.ai-outline-toolbar-btn-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  /* hover/active 状态继承父级 color，自然变紫 */
}

.ai-outline-toolbar-btn-label {
  font-size: 13px;
  line-height: 1;
}
</style>
