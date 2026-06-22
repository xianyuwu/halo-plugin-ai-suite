import { defineConfig } from "vitepress";
import { readFileSync } from "node:fs";

const projectProperties = readFileSync(new URL("../../gradle.properties", import.meta.url), "utf8");
const projectVersion = projectProperties.match(/^version=(.+)$/m)?.[1]?.trim();

if (!projectVersion) {
  throw new Error("Unable to read project version from gradle.properties");
}

const siteOrigin = "https://ai-suite-docs.rainwu.cn";
const siteBase = process.env.DOCS_BASE || "/";
const historicalVersion = process.env.DOCS_HISTORICAL_VERSION || "";
const publishedVersions = JSON.parse(
  readFileSync(new URL("../versions.json", import.meta.url), "utf8"),
) as string[];

const userGuide = [
  { text: "模型、切片与检索", link: "/user-guide/models-and-retrieval" },
  { text: "访客问答与浮窗", link: "/user-guide/rag-chat" },
  { text: "AI 搜索", link: "/user-guide/ai-search" },
  { text: "索引中心", link: "/user-guide/knowledge-index" },
  { text: "AI 脑图", link: "/user-guide/mindmap" },
  { text: "AI 摘要", link: "/user-guide/excerpt" },
  { text: "写作辅助", link: "/user-guide/writing-assistant" },
  { text: "意图路由", link: "/user-guide/intent-routing" },
  { text: "效果评测", link: "/user-guide/evaluation" },
  { text: "运营智能体", link: "/user-guide/content-agent" },
  { text: "用量统计与限流", link: "/user-guide/usage-and-limits" },
  { text: "问答记录与反馈", link: "/user-guide/chat-logs" },
];

export default defineConfig({
  lang: "zh-CN",
  title: "AI 智能套件",
  description: "Halo AI 智能套件完整文档",
  base: siteBase,
  cleanUrls: true,
  lastUpdated: process.env.DOCS_LAST_UPDATED !== "false",
  ignoreDeadLinks: false,
  head: [
    ["meta", { name: "theme-color", content: "#111c35" }],
    ["meta", { property: "og:type", content: "website" }],
    ["meta", { property: "og:title", content: "AI 智能套件文档" }],
    ["meta", { property: "og:description", content: "Halo AI 智能套件安装、配置、运维、架构与 API 文档" }],
  ],
  themeConfig: {
    siteTitle: "AI 智能套件",
    nav: [
      { text: "快速开始", link: "/getting-started/installation" },
      { text: "用户手册", link: "/user-guide/rag-chat" },
      { text: "架构", link: "/architecture/overview" },
      { text: "API", link: "/api/overview" },
      {
        text: historicalVersion ? `版本 ${historicalVersion}` : `版本 ${projectVersion}`,
        items: [
          { text: `最新版 (${projectVersion})`, link: `${siteOrigin}/`, target: "_self" },
          ...publishedVersions.map(version => ({
            text: version === historicalVersion ? `${version}（当前）` : version,
            link: `${siteOrigin}/versions/${version}/`,
            target: "_self",
          })),
        ],
      },
      { text: "GitHub", link: "https://github.com/rainwu/plugin-ai-suite" },
    ],
    sidebar: [
      {
        text: "快速开始",
        collapsed: false,
        items: [
          { text: "文档首页", link: "/" },
          { text: "安装与首次配置", link: "/getting-started/installation" },
          { text: "第一次 RAG 问答", link: "/getting-started/first-rag-chat" },
        ],
      },
      { text: "用户手册", collapsed: false, items: userGuide },
      {
        text: "生产运维",
        collapsed: true,
        items: [
          { text: "生产部署", link: "/operations/production-deployment" },
          { text: "故障排查", link: "/operations/troubleshooting" },
          { text: "升级与迁移", link: "/operations/upgrade-and-migration" },
          { text: "备份与恢复", link: "/operations/backup-and-restore" },
          { text: "监控与安全", link: "/operations/monitoring-and-security" },
        ],
      },
      {
        text: "系统架构",
        collapsed: true,
        items: [
          { text: "架构总览", link: "/architecture/overview" },
          { text: "RAG 管线", link: "/architecture/rag-pipeline" },
          { text: "意图路由", link: "/architecture/intent-routing" },
          { text: "数据存储", link: "/architecture/data-storage" },
        ],
      },
      {
        text: "API",
        collapsed: true,
        items: [
          { text: "API 总览", link: "/api/overview" },
          { text: "Public API", link: "/api/public-api" },
          { text: "Console API", link: "/api/console-api" },
          { text: "SSE 协议", link: "/api/sse-protocol" },
        ],
      },
      {
        text: "开发指南",
        collapsed: true,
        items: [
          { text: "本地开发", link: "/development/local-development" },
          { text: "测试", link: "/development/testing" },
          { text: "Widget 开发", link: "/development/widget-development" },
          { text: "新增意图处理器", link: "/development/adding-intent-processor" },
          { text: "发布流程", link: "/development/release-process" },
        ],
      },
      {
        text: "参考资料",
        collapsed: true,
        items: [
          { text: "配置参考", link: "/reference/configuration-reference" },
          { text: "用量场景", link: "/reference/usage-scenarios" },
          { text: "兼容矩阵", link: "/reference/compatibility-matrix" },
          { text: "Extension 参考", link: "/reference/extension-resources" },
        ],
      },
    ],
    search: {
      provider: "local",
      options: {
        translations: {
          button: { buttonText: "搜索文档", buttonAriaLabel: "搜索文档" },
          modal: {
            noResultsText: "没有找到相关内容",
            resetButtonTitle: "清除查询",
            footer: {
              selectText: "选择",
              navigateText: "切换",
              closeText: "关闭",
            },
          },
        },
      },
    },
    outline: { level: [2, 3], label: "本页目录" },
    docFooter: { prev: "上一篇", next: "下一篇" },
    lastUpdated: { text: "最后更新" },
    returnToTopLabel: "回到顶部",
    sidebarMenuLabel: "文档导航",
    darkModeSwitchLabel: "主题",
    lightModeSwitchTitle: "切换到亮色模式",
    darkModeSwitchTitle: "切换到暗色模式",
    socialLinks: [
      { icon: "github", link: "https://github.com/rainwu/plugin-ai-suite" },
    ],
    footer: {
      message: "基于 GPL-3.0 许可发布",
      copyright: "AI 智能套件",
    },
    versionMeta: {
      current: projectVersion,
      historical: historicalVersion || null,
      latestUrl: `${siteOrigin}/`,
    },
  },
});
