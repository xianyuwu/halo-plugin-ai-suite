<template>
  <div class="ai-option-card" :class="{ active: modelValue }" @click="$emit('update:modelValue', !modelValue)">
    <div class="ai-option-check">
      <span v-if="modelValue" class="ai-check-inner">&#10003;</span>
    </div>
    <div class="ai-option-body">
      <div class="ai-option-title">{{ title }}</div>
      <div class="ai-option-desc">{{ desc }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  modelValue: boolean;
  title: string;
  desc: string;
}>();

defineEmits<{
  'update:modelValue': [value: boolean];
}>();
</script>
