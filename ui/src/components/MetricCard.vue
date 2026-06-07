<script setup lang="ts">
/**
 * MetricCard 统一指标卡 — 顶部统计页通用.
 *
 * 用法:
 *   <MetricCard
 *     color="blue"
 *     icon="zap"
 *     label="今日总调用"
 *     :value="1234"
 *     :value-fmt="(n) => formatNum(n)"
 *     delta="vs 昨日"
 *     :delta-value="12"
 *     delta-suffix="%"
 *     delta-direction="up"   // up / down / flat
 *   />
 */

import { computed } from "vue";

const props = withDefaults(defineProps<{
  color?: "blue" | "green" | "red" | "purple" | "gray" | "orange";
  icon?: string;
  label: string;
  value: number | string;
  valueClass?: "" | "fail" | "ok";
  /** 自定义数字格式化 (默认千分位) */
  valueFmt?: (n: number | string) => string;
  /** 对比行前缀, 例如 "vs 昨日" / "占反馈" */
  delta?: string;
  /** 对比数值, e.g. 12 */
  deltaValue?: number;
  /** 对比数值后缀, e.g. "%" / "条" */
  deltaSuffix?: string;
  /** 对比方向 — 控制上下箭头与颜色 */
  deltaDirection?: "up" | "down" | "flat";
  /** 对比行自由文本 (覆盖 delta/Value/Suffix), 例如 "12 失败 / 522 总" */
  deltaText?: string;
  /** info 提示, 鼠标悬停时通过 title 显示 */
  info?: string;
}>(), {
  color: "blue",
  icon: "",
  valueClass: "",
  deltaDirection: "flat",
});

const displayValue = computed(() => {
  if (props.valueFmt) return props.valueFmt(props.value);
  if (typeof props.value === "number") return props.value.toLocaleString();
  return String(props.value);
});

const deltaArrow = computed(() => {
  if (props.deltaDirection === "up") return "↑";
  if (props.deltaDirection === "down") return "↓";
  return "—";
});

const deltaClass = computed(() => {
  if (props.deltaDirection === "up") return "up";
  if (props.deltaDirection === "down") return "down";
  return "flat";
});

const ICONS: Record<string, string> = {
  zap: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>',
  database: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v6c0 1.66 4.03 3 9 3s9-1.34 9-3V5"/><path d="M3 11v6c0 1.66 4.03 3 9 3s9-1.34 9-3v-6"/></svg>',
  alert: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>',
  puzzle: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M19.439 7.85c-.049.322.059.648.289.878l1.568 1.568c.47.47.706 1.087.706 1.704s-.235 1.233-.706 1.704l-1.611 1.611a.98.98 0 0 1-.837.276c-.47-.07-.802-.48-.968-.925a2.501 2.501 0 1 0-3.214 3.214c.446.166.855.497.925.968a.979.979 0 0 1-.276.837l-1.61 1.61a2.404 2.404 0 0 1-1.705.707 2.402 2.402 0 0 1-1.704-.706l-1.568-1.568a1.026 1.026 0 0 0-.877-.29c-.493.074-.84.504-1.02.968a2.5 2.5 0 1 1-3.237-3.237c.464-.18.894-.527.967-1.02a1.026 1.026 0 0 0-.289-.877l-1.568-1.568A2.402 2.402 0 0 1 1.998 12c0-.617.236-1.234.706-1.704L4.23 8.77c.24-.24.581-.353.917-.303.515.077.877.528 1.073 1.01a2.5 2.5 0 1 0 3.259-3.259c-.482-.196-.933-.558-1.01-1.073-.05-.336.062-.676.303-.917l1.525-1.525A2.402 2.402 0 0 1 12 1.998c.617 0 1.234.236 1.704.706l1.568 1.568c.23.23.556.338.877.29.493-.074.84-.504 1.02-.968a2.5 2.5 0 1 1 3.237 3.237c-.464.18-.894.527-.967 1.02Z"/></svg>',
  message: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>',
  pen: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19l7-7 3 3-7 7-3-3z"/><path d="M18 13l-1.5-7.5L2 2l3.5 14.5L13 18l5-5z"/><path d="M2 2l7.586 7.586"/><circle cx="11" cy="11" r="2"/></svg>',
  sparkles: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l1.8 5.4L19 10l-5.2 1.6L12 17l-1.8-5.4L5 10l5.2-1.6z"/></svg>',
  thumbUp: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"/><path d="M7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/></svg>',
  thumbDown: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3H10z"/><path d="M17 2h3a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-3"/></svg>',
  circle: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/></svg>',
  percent: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><line x1="19" y1="5" x2="5" y2="19"/><circle cx="6.5" cy="6.5" r="2.5"/><circle cx="17.5" cy="17.5" r="2.5"/></svg>',
  info: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>',
};

const iconHtml = computed(() => (props.icon && ICONS[props.icon]) || "");
</script>

<template>
  <div class="ai-metric-card" :class="color">
    <div class="ai-metric-head">
      <div class="ai-metric-icon" v-if="icon" v-html="iconHtml" />
      <div class="ai-metric-label">{{ label }}</div>
      <span v-if="info" class="ai-metric-info" :title="info" v-html="ICONS.info" />
    </div>
    <div class="ai-metric-value" :class="valueClass">{{ displayValue }}</div>
    <div class="ai-metric-delta" v-if="delta || deltaText">
      <template v-if="deltaText">
        {{ deltaText }}
      </template>
      <template v-else>
        <span :class="deltaClass">{{ deltaArrow }}</span>
        <span v-if="delta" class="ai-metric-delta-label">{{ delta }}</span>
        <strong :class="deltaClass">{{ deltaValue }}{{ deltaSuffix || "" }}</strong>
      </template>
    </div>
  </div>
</template>
