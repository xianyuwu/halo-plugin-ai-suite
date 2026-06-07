<template>
  <header class="ai-topbar">
    <div>
      <div class="ai-page-title">{{ title }}</div>
      <div class="ai-page-desc">{{ desc }}</div>
    </div>
    <slot name="actions" />
  </header>
</template>

<script setup lang="ts">
defineProps<{
  title: string;
  desc: string;
}>();
</script>
