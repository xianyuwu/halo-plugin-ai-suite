<script setup lang="ts">
/**
 * AI 智能套件侧边栏菜单图标（单色简化版）。
 *
 * 与插件 logo (static/images/logo.svg) 的核心元素呼应：天线 + 机器人头 +
 * 双眼 + 对话三点，但去掉渐变/闪光，使用 currentColor 跟随侧边栏文字色，
 * 保证在 16px 菜单位、深/浅主题下都清晰可辨。
 */
</script>

<template>
  <svg
    xmlns="http://www.w3.org/2000/svg"
    viewBox="0 0 24 24"
    width="1em"
    height="1em"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <!-- 天线 -->
    <line x1="12" y1="2.5" x2="12" y2="5" />
    <circle cx="12" cy="2.2" r="1" fill="currentColor" stroke="none" />
    <!-- 头部 -->
    <rect x="4" y="5" width="16" height="13" rx="4" />
    <!-- 双眼 -->
    <circle cx="9" cy="11" r="1.3" fill="currentColor" stroke="none" />
    <circle cx="15" cy="11" r="1.3" fill="currentColor" stroke="none" />
    <!-- 对话三点（智能问答） -->
    <circle cx="9.5" cy="14.5" r="0.6" fill="currentColor" stroke="none" />
    <circle cx="12" cy="14.5" r="0.6" fill="currentColor" stroke="none" />
    <circle cx="14.5" cy="14.5" r="0.6" fill="currentColor" stroke="none" />
  </svg>
</template>
