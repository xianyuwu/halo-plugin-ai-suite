<template>
  <div class="theme-color-picker">
    <div class="ai-color-swatches" aria-label="常用主题色">
      <button
        v-for="color in COMMON_THEME_COLORS"
        :key="color.value"
        class="ai-color-swatch"
        :class="{ active: normalizedModelValue === color.value.toLowerCase() }"
        :style="{ background: color.value }"
        type="button"
        :title="`${color.label} ${color.value}`"
        @click="selectColor(color.value)"
      >
        <span class="ai-color-swatch-check">✓</span>
      </button>
    </div>
    <div class="ai-color-row">
      <div
        class="ai-color-preview"
        :class="{ inherited: allowInherit && !modelValue }"
        :style="{ background: previewColor }"
      ></div>
      <input
        class="ai-input"
        :class="{ invalid }"
        :value="modelValue"
        :placeholder="placeholder"
        @input="updateValue"
      />
      <button
        v-if="allowInherit"
        class="ai-inherit-btn"
        type="button"
        :disabled="!modelValue"
        @click="clearColor"
      >
        {{ inheritLabel }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";

const COMMON_THEME_COLORS = [
  { label: "靛蓝", value: "#4F46E5" },
  { label: "紫色", value: "#7C3BED" },
  { label: "蓝色", value: "#2563EB" },
  { label: "青色", value: "#0891B2" },
  { label: "绿色", value: "#16A34A" },
  { label: "橙色", value: "#EA580C" },
  { label: "红色", value: "#DC2626" },
  { label: "粉色", value: "#DB2777" },
  { label: "石墨", value: "#334155" },
];

const props = withDefaults(defineProps<{
  modelValue: string;
  effectiveColor?: string;
  allowInherit?: boolean;
  inheritLabel?: string;
  invalid?: boolean;
  placeholder?: string;
}>(), {
  effectiveColor: "#4F46E5",
  allowInherit: false,
  inheritLabel: "继承",
  invalid: false,
  placeholder: "#4F46E5",
});

const emit = defineEmits<{
  (event: "update:modelValue", value: string): void;
}>();

const normalizedModelValue = computed(() => normalizeHexColor(props.modelValue));
const previewColor = computed(() => props.modelValue || props.effectiveColor || "#4F46E5");

function selectColor(color: string) {
  emit("update:modelValue", color);
}

function clearColor() {
  emit("update:modelValue", "");
}

function updateValue(event: Event) {
  emit("update:modelValue", (event.target as HTMLInputElement).value.trim());
}

function normalizeHexColor(hex: string) {
  const value = (hex || "").trim();
  if (!/^#([0-9a-f]{3}|[0-9a-f]{6})$/i.test(value)) return "";
  if (value.length === 4) {
    return `#${value[1]}${value[1]}${value[2]}${value[2]}${value[3]}${value[3]}`.toLowerCase();
  }
  return value.toLowerCase();
}
</script>

<style scoped>
.theme-color-picker {
  width: 100%;
}

.ai-color-swatches {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.ai-color-swatch {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px #d8dee8, 0 2px 6px rgba(15, 23, 42, 0.12);
  cursor: pointer;
  padding: 0;
  position: relative;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.ai-color-swatch:hover {
  transform: translateY(-1px);
  box-shadow: 0 0 0 1px #94a3b8, 0 4px 10px rgba(15, 23, 42, 0.16);
}

.ai-color-swatch.active {
  box-shadow: 0 0 0 2px #111827, 0 5px 14px rgba(15, 23, 42, 0.22);
}

.ai-color-swatch-check {
  opacity: 0;
  color: #fff;
  font-size: 14px;
  font-weight: 800;
  line-height: 1;
  text-shadow: 0 1px 3px rgba(15, 23, 42, 0.55);
}

.ai-color-swatch.active .ai-color-swatch-check {
  opacity: 1;
}

.ai-color-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ai-color-preview {
  width: 42px;
  height: 42px;
  border-radius: 8px;
  flex-shrink: 0;
  box-shadow: inset 0 0 0 4px #fff, 0 0 0 1px #e5e7eb;
  border: 1px solid #e5e7eb;
  position: relative;
}

.ai-color-preview.inherited::after {
  content: "";
  position: absolute;
  inset: 5px;
  border-radius: 5px;
  border: 1px dashed rgba(255, 255, 255, 0.75);
}

.ai-color-row .ai-input {
  flex: 1;
}

.ai-input.invalid {
  border-color: #ef4444 !important;
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.12);
}

.ai-inherit-btn {
  height: 42px;
  padding: 0 12px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  background: #fff;
  color: #374151;
  font-size: 13px;
  cursor: pointer;
}

.ai-inherit-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
