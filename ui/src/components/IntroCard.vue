<template>
  <div class="ai-intro-card">
    <div>
      <div class="ai-intro-title">{{ title }}</div>
      <div class="ai-intro-desc">{{ desc }}</div>
    </div>
    <div class="ai-intro-meta">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title: string;
  desc: string;
}>();
</script>
