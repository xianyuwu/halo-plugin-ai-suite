<template>
  <div class="ai-sidebar-card">
    <div class="ai-sidebar-header">{{ title }}</div>
    <slot />
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title: string;
}>();
</script>
