<template>
  <div class="ai-layout">
    <aside class="ai-sidebar">
      <div class="ai-sidebar-header">
        <div class="ai-logo">AI</div>
        <div class="ai-brand">
          <div class="ai-brand-title">AI智能套件</div>
          <div class="ai-brand-subtitle">AI Suite Console</div>
        </div>
      </div>
      <nav class="ai-nav">
        <!-- 区 1 -->
        <router-link
          v-for="item in itemsArea1"
          :key="item.name"
          :to="{ name: item.name }"
          class="ai-nav-item"
          active-class="active"
        >
          <span class="ai-nav-icon"><component :is="item.icon" /></span>
          <span class="ai-nav-label">{{ item.label }}</span>
        </router-link>

        <div class="ai-nav-divider"></div>

        <!-- 区 2 - RAG 流水线（始终展开） -->
        <div class="ai-nav-group-label">
          <span class="ai-nav-group-text">RAG 流水线</span>
        </div>
        <router-link
          v-for="child in itemsRag"
          :key="child.name"
          :to="{ name: child.name }"
          class="ai-nav-item ai-nav-item-child"
          active-class="active"
        >
          <span class="ai-nav-icon"><component :is="child.icon" /></span>
          <span class="ai-nav-label">{{ child.label }}</span>
        </router-link>

        <div class="ai-nav-divider"></div>

        <!-- 区 3 -->
        <router-link
          v-for="item in itemsArea3"
          :key="item.name"
          :to="{ name: item.name }"
          class="ai-nav-item"
          active-class="active"
        >
          <span class="ai-nav-icon"><component :is="item.icon" /></span>
          <span class="ai-nav-label">{{ item.label }}</span>
        </router-link>
      </nav>
    </aside>
    <div class="ai-layout-main">
      <PageTopbar :title="pageTitle" :desc="pageDesc" />
      <router-view />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, markRaw } from "vue";
import { useRoute } from "vue-router";
import RiDashboardLine from "~icons/ri/dashboard-line";
import RiRobotLine from "~icons/ri/robot-line";
import RiScissorsLine from "~icons/ri/scissors-line";
import RiFolderLine from "~icons/ri/folder-line";
import RiSearchLine from "~icons/ri/search-line";
import RiSparkling2Line from "~icons/ri/sparkling-2-line";
import RiChatSmileLine from "~icons/ri/chat-smile-line";
import RiFileTextLine from "~icons/ri/file-text-line";
import RiQuillPenLine from "~icons/ri/quill-pen-line";
import RiBarChartLine from "~icons/ri/bar-chart-line";
import RiMessage2Line from "~icons/ri/message-2-line";
import RiFilter3Line from "~icons/ri/filter-3-line";
import RiMindMap from "~icons/ri/mind-map";
import RiFlaskLine from "~icons/ri/flask-line";
import RiRobot2Line from "~icons/ri/robot-2-line";
import PageTopbar from "../components/PageTopbar.vue";

const route = useRoute();

const pageTitle = computed(() => (route.meta.title as string) || "AI智能套件");
const pageDesc = computed(() => (route.meta.desc as string) || "");

// 区 1 - 顶层基础项
const itemsArea1 = [
  { name: "AISuiteDashboard", icon: markRaw(RiDashboardLine), label: "总览" },
  { name: "AISuiteModels", icon: markRaw(RiRobotLine), label: "模型配置" },
];

// 区 2 - RAG 流水线（始终展开，无徽章）
const itemsRag = [
  { name: "AISuiteChunking", icon: markRaw(RiScissorsLine), label: "切片设置" },
  { name: "AISuiteKnowledge", icon: markRaw(RiFolderLine), label: "索引中心" },
  { name: "AISuiteRetrieval", icon: markRaw(RiFilter3Line), label: "检索策略" },
  { name: "AISuiteEnhance", icon: markRaw(RiSparkling2Line), label: "检索增强" },
];

// 区 3 - 其余顶层项
const itemsArea3 = [
  { name: "AISuiteChat", icon: markRaw(RiChatSmileLine), label: "对话与外观" },
  { name: "AISuiteSearch", icon: markRaw(RiSearchLine), label: "AI 搜索" },
  { name: "AISuiteMindMap", icon: markRaw(RiMindMap), label: "AI 脑图" },
  { name: "AISuiteExcerpt", icon: markRaw(RiFileTextLine), label: "AI 摘要" },
  { name: "AISuiteWriting", icon: markRaw(RiQuillPenLine), label: "写作辅助" },
  { name: "AISuiteChatLogs", icon: markRaw(RiMessage2Line), label: "问答记录" },
  { name: "AISuiteEvaluation", icon: markRaw(RiFlaskLine), label: "效果评测" },
  { name: "AISuiteAgent", icon: markRaw(RiRobot2Line), label: "运营智能体" },
  { name: "AISuiteUsage", icon: markRaw(RiBarChartLine), label: "用量统计" },
];
</script>
