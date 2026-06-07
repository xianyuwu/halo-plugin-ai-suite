<template>
  <div class="ai-dashboard">
    <div class="ai-content">
      <!-- 索引中心状态 -->
      <section class="ai-section">
        <div class="ai-section-header">
          <div>
            <div class="ai-section-title">索引中心状态</div>
            <div class="ai-section-desc">实时查看当前索引、切片与异常状态</div>
          </div>
          <VButton @click="refreshStats" :loading="loading">
            刷新状态
          </VButton>
        </div>
        <div class="ai-section-body">
          <div class="ai-metric-grid">
            <MetricCard
              color="blue"
              icon="database"
              label="已索引文章"
              :value="`${overviewStats.indexedArticles} / ${overviewStats.totalArticles}`"
            />
            <MetricCard
              color="purple"
              icon="puzzle"
              label="切片总数"
              :value="overviewStats.chunkCount"
            />
            <MetricCard
              color="red"
              icon="alert"
              label="失败文章"
              :value="overviewStats.failedArticles"
              value-class="fail"
            />
            <MetricCard
              color="green"
              icon="sparkles"
              label="索引状态"
              :value="overviewStats.status === 'indexing' ? '索引中' : '就绪'"
            />
          </div>
        </div>
      </section>

      <!-- 访客问答状态 -->
      <section class="ai-section">
        <div class="ai-section-header">
          <div>
            <div class="ai-section-title">访客问答状态</div>
            <div class="ai-section-desc">最近 7 天访客提问、反馈分布与点踩率</div>
          </div>
          <router-link to="/ai-assistant/chat-logs" class="ai-btn">查看详情 →</router-link>
        </div>
        <div class="ai-section-body">
          <div class="ai-metric-grid">
            <MetricCard
              color="blue"
              icon="message"
              label="今日问答"
              :value="chatStats.todayNew"
            />
            <MetricCard
              color="gray"
              icon="zap"
              label="近 7 天"
              :value="chatStats.last7Days"
            />
            <MetricCard
              color="red"
              icon="percent"
              label="点踩率"
              :value="`${safeNumber(chatStats.dislikeRate).toFixed(1)}%`"
              :value-class="safeNumber(chatStats.dislikeRate) > 20 ? 'fail' : ''"
            />
            <MetricCard
              color="red"
              icon="thumbDown"
              label="点踩数"
              :value="chatStats.dislikes"
              value-class="fail"
            />
          </div>
        </div>
      </section>

      <!-- 快速操作 -->
      <section class="ai-section">
        <div class="ai-section-header">
          <div>
            <div class="ai-section-title">快速操作</div>
            <div class="ai-section-desc">常用配置入口，快速完成助手能力搭建</div>
          </div>
        </div>
        <div class="ai-section-body">
          <div class="ai-quick-grid">
            <router-link to="/ai-assistant/usage" class="ai-quick-card ai-quick-card--usage">
              <div>
                <div class="ai-quick-icon"><RiBarChartLine /></div>
                <div class="ai-quick-title">今日用量</div>
                <div class="ai-quick-desc">
                  <span class="ai-usage-stat">{{ usageSummary.calls }} 次调用</span>
                  <span class="ai-usage-stat">{{ formatTokens(usageSummary.tokens) }} Token</span>
                  <span class="ai-usage-stat" :class="{ 'ai-usage-stat--warn': usageSummary.failureRate > 5 }">失败率 {{ usageSummary.failureRate.toFixed(1) }}%</span>
                </div>
              </div>
              <div class="ai-quick-arrow">查看详情 →</div>
            </router-link>
            <router-link to="/ai-assistant/knowledge" class="ai-quick-card">
              <div>
                <div class="ai-quick-icon"><RiDatabase2Line /></div>
                <div class="ai-quick-title">索引中心</div>
                <div class="ai-quick-desc">查看索引状态、预览切片、手动重建索引。</div>
              </div>
              <div class="ai-quick-arrow">进入管理 →</div>
            </router-link>
            <router-link to="/ai-assistant/models" class="ai-quick-card">
              <div>
                <div class="ai-quick-icon"><RiRobotLine /></div>
                <div class="ai-quick-title">模型配置</div>
                <div class="ai-quick-desc">切换 LLM、测试连接、调整参数与回复策略。</div>
              </div>
              <div class="ai-quick-arrow">开始配置 →</div>
            </router-link>
            <router-link to="/ai-assistant/chat" class="ai-quick-card">
              <div>
                <div class="ai-quick-icon"><RiChatSmileLine /></div>
                <div class="ai-quick-title">对话与外观</div>
                <div class="ai-quick-desc">自定义浮窗样式、欢迎语、快捷问题入口。</div>
              </div>
              <div class="ai-quick-arrow">编辑外观 →</div>
            </router-link>
            <router-link to="/ai-assistant/excerpt" class="ai-quick-card">
              <div>
                <div class="ai-quick-icon"><RiFileTextLine /></div>
                <div class="ai-quick-title">AI 摘要</div>
                <div class="ai-quick-desc">文章发布时自动生成摘要，写入 excerpt 字段。</div>
              </div>
              <div class="ai-quick-arrow">查看配置 →</div>
            </router-link>
            <router-link to="/ai-assistant/writing" class="ai-quick-card">
              <div>
                <div class="ai-quick-icon"><RiQuillPenLine /></div>
                <div class="ai-quick-title">写作辅助</div>
                <div class="ai-quick-desc">配置润色/续写/扩写/译英等动作，启用后可在文章编辑器中调用。</div>
              </div>
              <div class="ai-quick-arrow">开始配置 →</div>
            </router-link>
          </div>
        </div>
      </section>

      <!-- 配置概览 -->
      <section class="ai-section">
        <div class="ai-section-header">
          <div>
            <div class="ai-section-title">配置概览</div>
            <div class="ai-section-desc">当前助手核心参数与能力配置状态</div>
          </div>
        </div>
        <div class="ai-section-body">
          <div class="ai-config-grid">
            <router-link :to="item.to" class="ai-config-item" v-for="item in configSummary" :key="item.label" :class="{ 'is-muted': !item.ok }">
              <div>
                <div class="ai-config-label">{{ item.label }}</div>
                <div class="ai-config-value">{{ item.value }}</div>
              </div>
              <VTag :theme="item.ok ? 'primary' : 'default'">{{ item.tag }}</VTag>
            </router-link>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { VButton, VTag } from "@halo-dev/components";
import MetricCard from "../components/MetricCard.vue";
import RiDatabase2Line from "~icons/ri/database-2-line";
import RiRobotLine from "~icons/ri/robot-line";
import RiChatSmileLine from "~icons/ri/chat-smile-line";
import RiFileTextLine from "~icons/ri/file-text-line";
import RiQuillPenLine from "~icons/ri/quill-pen-line";
import RiBarChartLine from "~icons/ri/bar-chart-line";
import { loadUsageToday } from "../utils/config";

const CONFIG_API = "/apis/console.api.ai-assistant.halo.run/v1alpha1/config";
const KNOWLEDGE_API = "/apis/console.api.ai-assistant.halo.run/v1alpha1/knowledge";
const CHAT_LOGS_API = "/apis/console.api.ai-assistant.halo.run/v1alpha1/chat-logs/stats";

// 防御：后端缺字段时不让 toFixed 抛错
function safeNumber(v: unknown): number {
  return typeof v === "number" && isFinite(v) ? v : 0;
}

// 配置数据
const config = ref<any>({});
// 知识库统计数据
const overviewStats = ref({
  chunkCount: 0,
  indexedArticles: 0,
  totalArticles: 0,
  failedArticles: 0,
  status: "idle",
});
// 访客问答统计
const chatStats = ref({
  totalLogs: 0,
  likes: 0,
  dislikes: 0,
  none: 0,
  last7Days: 0,
  todayNew: 0,
  dislikeRate: 0,
});
// 今日用量统计
const usageToday = ref<{ date: string; models: Array<{ model: string; promptTokens: number; completionTokens: number; calls: number; failures: number; embeddingTokens: number }> }>({ date: "", models: [] });
const loading = ref(false);

// 今日用量卡片摘要指标
const usageSummary = computed(() => {
  const models = usageToday.value.models;
  const calls = models.reduce((s, m) => s + m.calls, 0);
  const tokens = models.reduce((s, m) => s + m.promptTokens + m.completionTokens + m.embeddingTokens, 0);
  const failures = models.reduce((s, m) => s + m.failures, 0);
  const failureRate = calls === 0 ? 0 : (failures * 100) / calls;
  return { calls, tokens, failures, failureRate };
});

// 格式化 token 数量：大数用 k/M 缩写
function formatTokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + "M";
  if (n >= 1_000) return (n / 1_000).toFixed(1) + "k";
  return String(n);
}

// 配置概览：每项包含显示值 + 标签样式
const configSummary = computed(() => {
  const m = config.value.models || {};
  const c = config.value.chat || {};
  const r = config.value.retrieval || {};
  const chatOk = !!(m.chatModel || m.chatBaseUrl);
  const embOk = !!(m.embeddingModel || m.embeddingBaseUrl);
  const promptOk = !!c.systemPrompt;

  const modeLabel: Record<string, string> = { hybrid: "混合检索", vector: "语义检索", keyword: "关键词检索" };
  const themeLabel: Record<string, string> = { auto: "跟随系统", light: "浅色", dark: "深色", system: "跟随系统" };

  return [
    { label: "聊天模型", value: chatOk ? "已配置" : "未配置", tag: chatOk ? "已配置" : "待配置", ok: chatOk, to: "/ai-assistant/models" },
    { label: "Embedding 模型", value: embOk ? "已配置" : "未配置", tag: embOk ? "已配置" : "待配置", ok: embOk, to: "/ai-assistant/models" },
    { label: "检索模式", value: modeLabel[r.searchMode] || r.searchMode || "未设置", tag: r.searchMode || "默认", ok: !!r.searchMode, to: "/ai-assistant/retrieval" },
    { label: "切片大小", value: config.value.chunking?.chunkSize ? `${config.value.chunking.chunkSize} 字符` : "500 字符", tag: config.value.chunking?.chunkSize ? "自定义" : "默认", ok: true, to: "/ai-assistant/chunking" },
    { label: "系统提示词", value: promptOk ? "已配置" : "未配置", tag: promptOk ? "已配置" : "待配置", ok: promptOk, to: "/ai-assistant/chat" },
    { label: "浮窗主题", value: themeLabel[c.widgetTheme] || c.widgetTheme || "auto", tag: c.widgetTheme || "auto", ok: true, to: "/ai-assistant/chat" },
  ];
});

async function loadConfig() {
  try {
    const resp = await fetch(CONFIG_API);
    if (resp.ok) config.value = await resp.json();
  } catch { /* 静默处理 */ }
}

async function loadKnowledgeStats() {
  try {
    const resp = await fetch(`${KNOWLEDGE_API}/stats`);
    if (resp.ok) {
      const data = await resp.json();
      overviewStats.value = {
        chunkCount: data.chunkCount || 0,
        indexedArticles: data.indexedArticles || 0,
        totalArticles: data.totalArticles || 0,
        failedArticles: data.failedArticles || 0,
        status: data.status || "idle",
      };
    }
  } catch { /* 静默处理 */ }
}

// 刷新按钮：重新加载统计数据
async function refreshStats() {
  loading.value = true;
  await Promise.all([loadKnowledgeStats(), loadChatStats(), loadUsage()]);
  loading.value = false;
}

async function loadChatStats() {
  try {
    const resp = await fetch(CHAT_LOGS_API);
    if (resp.ok) {
      const data = await resp.json();
      chatStats.value = {
        totalLogs: data.totalLogs || 0,
        likes: data.feedbackDistribution?.like || 0,
        dislikes: data.feedbackDistribution?.dislike || 0,
        none: data.feedbackDistribution?.none || 0,
        last7Days: data.last7Days || 0,
        todayNew: data.todayNew || 0,
        dislikeRate: data.dislikeRate || 0,
      };
    }
  } catch { /* 静默处理 */ }
}

onMounted(async () => {
  await Promise.all([loadConfig(), loadKnowledgeStats(), loadChatStats(), loadUsage()]);
});

async function loadUsage() {
  usageToday.value = await loadUsageToday();
}
</script>
