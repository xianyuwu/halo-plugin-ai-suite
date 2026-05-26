import { definePlugin } from "@halo-dev/console-shared";
import { markRaw } from "vue";
import RiRobotLine from "~icons/ri/robot-line";
import "./styles.css";

import AIAssistantLayout from "./views/AIAssistantLayout.vue";
import DashboardView from "./views/DashboardView.vue";
import ModelConfigView from "./views/ModelConfigView.vue";
import ChunkingView from "./views/ChunkingView.vue";
import RetrievalView from "./views/RetrievalView.vue";
import EnhancementView from "./views/EnhancementView.vue";
import ChatView from "./views/ChatView.vue";
import KnowledgeView from "./views/KnowledgeView.vue";
import WritingView from "./views/WritingView.vue";

export default definePlugin({
  name: "ai-assistant",
  components: {},
  routes: [
    {
      parentName: "Root",
      route: {
        path: "/ai-assistant",
        name: "AIAssistantRoot",
        component: AIAssistantLayout,
        meta: {
          title: "AI 智能助手",
          menu: {
            name: "AI 智能助手",
            group: "tool",
            icon: markRaw(RiRobotLine),
            priority: 0,
          },
        },
        children: [
          {
            path: "",
            redirect: "/ai-assistant/dashboard",
          },
          {
            path: "dashboard",
            name: "AIAssistantDashboard",
            component: DashboardView,
            meta: { title: "总览", menu: { name: "总览" } },
          },
          {
            path: "models",
            name: "AIAssistantModels",
            component: ModelConfigView,
            meta: { title: "模型配置", menu: { name: "模型配置" } },
          },
          {
            path: "chunking",
            name: "AIAssistantChunking",
            component: ChunkingView,
            meta: { title: "文档切片", menu: { name: "文档切片" } },
          },
          {
            path: "retrieval",
            name: "AIAssistantRetrieval",
            component: RetrievalView,
            meta: { title: "检索设置", menu: { name: "检索设置" } },
          },
          {
            path: "enhance",
            name: "AIAssistantEnhance",
            component: EnhancementView,
            meta: { title: "检索增强", menu: { name: "检索增强" } },
          },
          {
            path: "chat",
            name: "AIAssistantChat",
            component: ChatView,
            meta: { title: "对话与外观", menu: { name: "对话与外观" } },
          },
          {
            path: "knowledge",
            name: "AIAssistantKnowledge",
            component: KnowledgeView,
            meta: { title: "知识库", menu: { name: "知识库" } },
          },
          {
            path: "writing",
            name: "AIAssistantWriting",
            component: WritingView,
            meta: { title: "写作辅助", menu: { name: "写作辅助" } },
          },
        ],
      },
    },
  ],
  extensionPoints: {},
});
