<template>
  <ul class="ai-tips-list">
    <li v-for="(tip, index) in tips" :key="index">{{ tip }}</li>
  </ul>
</template>

<script setup lang="ts">
defineProps<{
  tips: string[];
}>();
</script>
