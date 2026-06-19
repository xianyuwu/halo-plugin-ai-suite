import { definePlugin } from "@halo-dev/console-shared";
import { markRaw } from "vue";
import AiSuiteMenuIcon from "./components/AiSuiteMenuIcon.vue";
import "./styles/index.css";

import AISuiteLayout from "./views/AISuiteLayout.vue";
import DashboardView from "./views/DashboardView.vue";
import ModelConfigView from "./views/ModelConfigView.vue";
import ChunkingView from "./views/ChunkingView.vue";
import RetrievalView from "./views/RetrievalView.vue";
import EnhancementView from "./views/EnhancementView.vue";
import ChatView from "./views/ChatView.vue";
import KnowledgeView from "./views/KnowledgeView.vue";
import ExcerptView from "./views/ExcerptView.vue";
import WritingView from "./views/WritingView.vue";
import UsageView from "./views/UsageView.vue";
import SearchView from "./views/SearchView.vue";
import MindMapView from "./views/MindMapView.vue";
import EvaluationView from "./views/EvaluationView.vue";
import AgentView from "./views/AgentView.vue";
import ChatLogsView from "./views/ChatLogsView.vue";
import IntentRoutesView from "./views/IntentRoutesView.vue";
import { AiWritingExtension } from "./extensions/ai-writing";
import { disposeOutline } from "./extensions/ai-writing/outline-state";
import { getWritingEnabled } from "./extensions/ai-writing/writing-enabled";

export default definePlugin({
  name: "ai-suite",
  components: {},
  deactivated: () => {
    // 清理全局大纲 modal (chat composer / bubble menu 已在 editor onDestroy 清理)
    disposeOutline();
  },
  routes: [
    {
      parentName: "Root",
      route: {
        path: "/ai-suite",
        name: "AISuiteRoot",
        component: AISuiteLayout,
        meta: {
          title: "AI智能套件",
          desc: "集中管理知识库索引、模型能力、检索策略与对话体验",
          menu: {
            name: "AI智能套件",
            group: "tool",
            icon: markRaw(AiSuiteMenuIcon),
            priority: 0,
          },
        },
        children: [
          {
            path: "",
            redirect: "/ai-suite/dashboard",
          },
          {
            path: "dashboard",
            name: "AISuiteDashboard",
            component: DashboardView,
            meta: { title: "总览", desc: "集中管理知识库索引、模型能力、检索策略与对话体验" },
          },
          {
            path: "models",
            name: "AISuiteModels",
            component: ModelConfigView,
            meta: { title: "模型配置", desc: "配置对话模型、向量模型、重排序与查询改写能力" },
          },
          {
            path: "chunking",
            name: "AISuiteChunking",
            component: ChunkingView,
            meta: { title: "文档切片", desc: "配置文档解析、分段切片、重叠策略与清洗规则" },
          },
          {
            path: "retrieval",
            name: "AISuiteRetrieval",
            component: RetrievalView,
            meta: { title: "检索设置", desc: "配置混合检索、相似度阈值、候选数量与最终返回策略" },
          },
          {
            path: "enhance",
            name: "AISuiteEnhance",
            component: EnhancementView,
            meta: { title: "检索增强", desc: "配置查询改写、HyDE、Rerank、跨语言检索与引用展示" },
          },
          {
            path: "search",
            name: "AISuiteSearch",
            component: SearchView,
            meta: { title: "AI 搜索", desc: "配置 AI 搜索弹框的功能开关、AI 回答与关键词结果" },
          },
          {
            path: "mindmap",
            name: "AISuiteMindMap",
            component: MindMapView,
            meta: { title: "AI 脑图", desc: "配置文章页 AI 脑图的生成参数、主题色与深浅色模式" },
          },
          {
            path: "evaluation",
            name: "AISuiteEvaluation",
            component: EvaluationView,
            meta: { title: "效果评测", desc: "评测问答、搜索与生成能力的质量、稳定性和引用效果" },
          },
          {
            path: "agent",
            name: "AISuiteAgent",
            component: AgentView,
            meta: { title: "运营智能体", desc: "运行博客内容运营任务，生成内容缺口、选题与优化建议" },
          },
          {
            path: "chat",
            name: "AISuiteChat",
            component: ChatView,
            meta: { title: "对话与外观", desc: "定义 AI 助手的对话行为、浮窗外观与访客交互体验" },
          },
          {
            path: "excerpt",
            name: "AISuiteExcerpt",
            component: ExcerptView,
            meta: { title: "AI 摘要", desc: "AI 自动生成文章摘要，写入 excerpt 字段用于 SEO 和社交分享" },
          },
          {
            path: "writing",
            name: "AISuiteWriting",
            component: WritingView,
            meta: { title: "写作辅助", desc: "配置 AI 写作辅助模型与大纲生成规则" },
          },
          {
            path: "knowledge",
            name: "AISuiteKnowledge",
            component: KnowledgeView,
            meta: { title: "索引中心", desc: "管理索引重建、切片状态与关键词覆盖，查看运行状态与维护建议" },
          },
          {
            path: "usage",
            name: "AISuiteUsage",
            component: UsageView,
            meta: { title: "用量统计", desc: "查看每模型每日 token 消耗、调用次数、失败率，并设置每日上限" },
          },
          {
            path: "chat-logs",
            name: "AISuiteChatLogs",
            component: ChatLogsView,
            meta: { title: "问答记录", desc: "查看访客问答历史、点赞/点踩分布与模型使用情况" },
          },
          {
            path: "intent-routes",
            name: "AISuiteIntentRoutes",
            component: IntentRoutesView,
            meta: { title: "意图路由", desc: "配置自定义问答意图，命中后跳过 RAG 走可编排的处理器 pipeline" },
          },
        ],
      },
    },
  ],
  extensionPoints: {
    // Halo 文章编辑器 Tiptap 扩展点 — 在编辑器工具栏 + 选区气泡菜单
    // 提供"AI 写作辅助"入口，唤起 AIDialog 弹层
    // 读 getWritingEnabled().value 动态决定是否挂载：用户在写作辅助页关掉总开关后，
    // 返回 [] 让 Halo 不挂载任何 AI 写作相关 extension
    "default:editor:extension:create": () =>
      getWritingEnabled().value ? [AiWritingExtension] : [],
  },
});
