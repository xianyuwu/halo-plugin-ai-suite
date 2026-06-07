<template>
  <div class="ai-section-block">
    <div class="ai-section-heading">
      <h2>{{ title }}</h2>
      <span v-if="tag" class="ai-section-tag" :class="tagRequired ? 'ai-tag-required' : 'ai-tag-optional'">
        {{ tag }}
      </span>
      <slot name="heading-extra" />
    </div>
    <VCard class="ai-section-card">
      <template v-if="hasHeader" #header>
        <div class="ai-card-header">
          <span class="ai-card-header-icon">
            <component :is="iconComponent" v-if="iconComponent" />
            <template v-else>{{ icon }}</template>
          </span>
          <div>
            <div class="ai-card-header-title">{{ headerTitle }}</div>
            <div class="ai-card-header-desc">{{ headerDesc }}</div>
          </div>
          <slot name="header-extra" />
        </div>
      </template>
      <template v-else #header>
        <slot name="header" />
      </template>
      <slot />
    </VCard>
  </div>
</template>

<script setup lang="ts">
import { computed, type Component } from "vue";
import { VCard } from "@halo-dev/components";

const props = defineProps<{
  title: string;
  tag?: string;
  tagRequired?: boolean;
  /** 旧式 emoji 字符串（保留兼容） */
  icon?: string;
  /** 新式 iconify / SVG component，优先于 icon */
  iconComponent?: Component;
  headerTitle?: string;
  headerDesc?: string;
}>();

const hasHeader = computed(() => !!(props.icon || props.iconComponent));
</script>

<style scoped>
.ai-section-block {
  display: flex;
  flex-direction: column;
}

.ai-section-card {
  flex: 1;
}
</style>
