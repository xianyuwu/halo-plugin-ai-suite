<script setup lang="ts">
import { computed } from "vue";
import { useData } from "vitepress";

const { theme } = useData();
const versionMeta = computed(() => theme.value.versionMeta || {});
</script>

<template>
  <div v-if="versionMeta.historical" class="docs-version-banner" role="status">
    <span>你正在查看 {{ versionMeta.historical }} 的历史文档。</span>
    <a :href="versionMeta.latestUrl">前往最新版</a>
  </div>
</template>
