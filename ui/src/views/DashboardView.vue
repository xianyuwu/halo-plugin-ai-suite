<template>
  <div class="dashboard-page">
    <VPageHeader title="AI 智能助手">
      <template #icon>
        <span class="header-icon">🤖</span>
      </template>
      <template #actions>
        <VButton size="sm" @click="$router.push('/ai-assistant/knowledge')">
          知识库管理
        </VButton>
      </template>
    </VPageHeader>

    <div class="page-body">
      <!-- 未完成配置：步骤引导 -->
      <template v-if="!isFullyConfigured">
        <VCard title="快速配置向导">
          <p class="guide-desc">按以下步骤完成初始化，即可启用 AI 智能问答功能</p>
          <div class="steps">
            <div
              v-for="(step, idx) in setupSteps"
              :key="step.path"
              :class="['step-card', { 'step-done': step.done, 'step-current': !step.done && idx === firstIncompleteStep }]"
            >
              <div class="step-num">{{ step.done ? '✓' : idx + 1 }}</div>
              <div class="step-body">
                <h3>{{ step.title }}</h3>
                <p>{{ step.desc }}</p>
              </div>
              <router-link :to="step.path" class="ai-btn" :class="{ 'ai-btn-primary': !step.done }">
                {{ step.done ? '查看' : '去配置' }}
              </router-link>
            </div>
          </div>
        </VCard>
      </template>

      <!-- 已完成配置：状态看板 -->
      <template v-else>
        <VCard title="知识库状态">
          <div class="overview-stats">
            <div class="stat-item">
              <div class="stat-value">{{ overviewStats.indexedArticles }}</div>
              <div class="stat-label">已索引文章</div>
            </div>
            <div class="stat-item">
              <div class="stat-value">{{ overviewStats.chunkCount }}</div>
              <div class="stat-label">切片总数</div>
            </div>
            <div class="stat-item" :class="{ 'stat-warn': overviewStats.failedArticles > 0 }">
              <div class="stat-value">{{ overviewStats.failedArticles }}</div>
              <div class="stat-label">失败文章</div>
            </div>
            <div class="stat-item">
              <div class="stat-value" :class="overviewStats.status === 'indexing' ? 'text-blue' : 'text-green'">
                {{ overviewStats.status === 'indexing' ? '索引中' : '就绪' }}
              </div>
              <div class="stat-label">索引状态</div>
            </div>
          </div>
        </VCard>

        <VCard title="快速操作">
          <div class="action-grid">
            <router-link to="/ai-assistant/knowledge" class="action-item">
              <span class="action-icon">📚</span>
              <span>知识库管理</span>
              <span class="action-desc">查看索引状态、预览切片、重建索引</span>
            </router-link>
            <router-link to="/ai-assistant/models" class="action-item">
              <span class="action-icon">⚙️</span>
              <span>模型配置</span>
              <span class="action-desc">切换 LLM、测试连接、调整参数</span>
            </router-link>
            <router-link to="/ai-assistant/chat" class="action-item">
              <span class="action-icon">💬</span>
              <span>对话与外观</span>
              <span class="action-desc">自定义浮窗样式、欢迎语、快捷问题</span>
            </router-link>
            <router-link to="/ai-assistant/writing" class="action-item">
              <span class="action-icon">✍️</span>
              <span>写作辅助</span>
              <span class="action-desc">润色、续写、扩写、翻译文章内容</span>
            </router-link>
          </div>
        </VCard>

        <VCard title="配置概览">
          <div class="summary-grid">
            <div class="summary-item" v-for="item in configSummary" :key="item.label">
              <span class="summary-label">{{ item.label }}</span>
              <span class="summary-value">{{ item.value || '未配置' }}</span>
            </div>
          </div>
        </VCard>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { VPageHeader, VButton, VCard } from "@halo-dev/components";

const CONFIG_API = "/apis/console.api.ai-assistant.halo.run/v1alpha1/config";
const KNOWLEDGE_API = "/apis/console.api.ai-assistant.halo.run/v1alpha1/knowledge";

const config = ref<any>({});
const overviewStats = ref({
  chunkCount: 0,
  indexedArticles: 0,
  failedArticles: 0,
  status: "idle",
});

const isFullyConfigured = computed(() => !!config.value.models?.chatBaseUrl);

const setupSteps = computed(() => [
  { title: "配置 LLM 模型", desc: "设置聊天模型的 API 地址和密钥，这是使用 AI 功能的前提", path: "/ai-assistant/models", done: !!config.value.models?.chatBaseUrl },
  { title: "配置 Embedding 模型", desc: "设置向量嵌入模型，用于知识库检索（RAG）", path: "/ai-assistant/models", done: !!config.value.models?.embeddingBaseUrl },
  { title: "构建知识库索引", desc: "索引已发布的博客文章，启用 RAG 智能问答", path: "/ai-assistant/knowledge", done: overviewStats.value.indexedArticles > 0 },
  { title: "自定义对话外观", desc: "设置浮窗样式、欢迎语和快捷问题", path: "/ai-assistant/chat", done: !!config.value.chat?.systemPrompt },
]);

const firstIncompleteStep = computed(() => {
  const idx = setupSteps.value.findIndex((s) => !s.done);
  return idx === -1 ? -1 : idx;
});

const configSummary = computed(() => {
  const m = config.value.models || {};
  const c = config.value.chat || {};
  const r = config.value.retrieval || {};
  return [
    { label: "聊天模型", value: m.chatModel || m.chatBaseUrl ? "已配置" : "" },
    { label: "Embedding 模型", value: m.embeddingModel || m.embeddingBaseUrl ? "已配置" : "" },
    { label: "检索模式", value: r.searchMode || "" },
    { label: "切片大小", value: config.value.chunking?.chunkSize ? `${config.value.chunking.chunkSize} 字符` : "" },
    { label: "系统提示词", value: c.systemPrompt ? "已配置" : "" },
    { label: "浮窗主题", value: c.widgetTheme || "" },
  ].filter((i) => i.value);
});

async function loadConfig() {
  try {
    const resp = await fetch(CONFIG_API);
    if (resp.ok) config.value = await resp.json();
  } catch {}
}

async function loadKnowledgeStats() {
  try {
    const resp = await fetch(`${KNOWLEDGE_API}/stats`);
    if (resp.ok) {
      const data = await resp.json();
      overviewStats.value = {
        chunkCount: data.chunkCount || 0,
        indexedArticles: data.indexedArticles || 0,
        failedArticles: data.failedArticles || 0,
        status: data.status || "idle",
      };
    }
  } catch {}
}

onMounted(() => {
  loadConfig();
  loadKnowledgeStats();
});
</script>
