<template>
  <div class="knowledge-page">
    <VPageHeader title="知识库管理">
    </VPageHeader>

    <div class="page-body">
      <!-- 选项卡导航 -->
      <div class="tab-nav">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          :class="['tab-btn', { active: activeTab === tab.key }]"
          @click="switchTab(tab.key)"
        >
          {{ tab.label }}
        </button>
      </div>

      <!-- Tab 1: 概览 -->
      <div v-show="activeTab === 'overview'" class="tab-content">
        <!-- 统计卡片 -->
        <VCard>
          <div class="overview-stats">
            <div class="stat-item">
              <div class="stat-value">{{ stats.indexedArticles }}</div>
              <div class="stat-label">已索引文章</div>
            </div>
            <div class="stat-item">
              <div class="stat-value">{{ stats.chunkCount }}</div>
              <div class="stat-label">切片总数</div>
            </div>
            <div class="stat-item" :class="{ 'stat-warn': stats.failedArticles > 0 }">
              <div class="stat-value">{{ stats.failedArticles }}</div>
              <div class="stat-label">失败文章</div>
            </div>
            <div class="stat-item">
              <div class="stat-value" :class="stats.status === 'indexing' ? 'text-blue' : 'text-green'">
                {{ stats.status === 'indexing' ? '索引中' : '就绪' }}
              </div>
              <div class="stat-label">索引状态</div>
            </div>
          </div>
        </VCard>

        <!-- 索引操作 -->
        <VCard title="索引操作">
          <div class="action-row">
            <div class="action-info">
              <p>清空现有索引，重新索引所有已发布的博客文章。适用于首次使用或配置变更后。</p>
              <p class="action-note">重建过程中会调用 Embedding API，文章较多时可能需要几分钟</p>
            </div>
            <VButton type="primary" size="sm" :loading="reindexing" @click="triggerReindex">
              {{ reindexing ? '索引中...' : '开始重建' }}
            </VButton>
          </div>
          <div class="info-box">
            <strong>自动索引：</strong>插件会自动监听文章的发布、更新、删除事件，实时同步索引。以下情况需要手动重建：
            首次配置 Embedding 模型、修改切片参数、更换 Embedding 模型或向量维度。
          </div>
        </VCard>

        <!-- 失败文章 -->
        <VCard v-if="failedArticles.length > 0" title="失败文章">
          <table class="ai-table">
            <thead>
              <tr>
                <th>文章标题</th>
                <th style="width: 80px">状态</th>
                <th style="width: 100px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="article in failedArticles" :key="article.postName">
                <td>{{ article.title }}</td>
                <td><span class="status-badge status-failed">失败</span></td>
                <td>
                  <VButton size="xs" :loading="retryingPost === article.postName"
                           @click="retryPost(article.postName)">
                    重试
                  </VButton>
                </td>
              </tr>
            </tbody>
          </table>
        </VCard>
      </div>

      <!-- Tab 2: 索引管理 -->
      <div v-show="activeTab === 'index'" class="tab-content">
        <VCard>
          <div class="toolbar">
            <input v-model="searchQuery" type="text" class="search-input" placeholder="搜索文章标题..." />
            <div class="filter-stats">
              共 {{ filteredArticles.length }} 篇，
              <span class="text-green">{{ indexStats.indexed }} 已索引</span>
              <span v-if="indexStats.failed > 0" class="text-red">，{{ indexStats.failed }} 失败</span>
              <span v-if="indexStats.notIndexed > 0">，{{ indexStats.notIndexed }} 未索引</span>
            </div>
          </div>

          <div v-if="loadingArticles" class="loading-text">加载文章列表...</div>

          <table v-else class="ai-table">
            <thead>
              <tr>
                <th>文章标题</th>
                <th style="width: 90px">状态</th>
                <th style="width: 70px">切片</th>
                <th style="width: 120px">操作</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="article in filteredArticles" :key="article.postName">
                <tr>
                  <td class="article-title-cell">{{ article.title }}</td>
                  <td>
                    <span :class="['status-badge', getStatusClass(article.status)]">
                      {{ getStatusLabel(article.status) }}
                    </span>
                  </td>
                  <td class="text-center">{{ article.chunkCount || '-' }}</td>
                  <td class="actions-cell">
                    <VButton v-if="article.status === 'indexed'" size="xs"
                      @click="previewChunks(article)">
                      {{ previewingPost === article.postName ? '收起' : '预览' }}
                    </VButton>
                    <VButton v-if="article.status === 'failed' || article.status === 'not_indexed'"
                      size="xs" type="primary" outline
                      :loading="retryingPost === article.postName"
                      @click="retryPost(article.postName)">
                      {{ article.status === 'failed' ? '重试' : '索引' }}
                    </VButton>
                  </td>
                </tr>
                <!-- 切片预览行 -->
                <tr v-if="previewingPost === article.postName" class="preview-row">
                  <td colspan="4">
                    <div class="chunk-preview">
                      <div v-if="loadingChunks" class="loading-text">加载切片中...</div>
                      <div v-else-if="chunks.length === 0" class="empty-text">暂无切片数据</div>
                      <template v-else>
                        <div class="chunk-count-info">共 {{ chunks.length }} 个切片</div>
                        <div v-for="chunk in chunks" :key="chunk.id" class="chunk-item">
                          <span class="chunk-index">#{{ chunk.chunkIndex + 1 }}</span>
                          <span class="chunk-content">{{ truncate(chunk.content, 300) }}</span>
                        </div>
                      </template>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>

          <div v-if="articles.length === 0 && !loadingArticles" class="empty-state">
            暂无已发布的文章
          </div>
        </VCard>
      </div>

      <!-- Tab 3: 摘要管理 -->
      <div v-show="activeTab === 'summary'" class="tab-content">
        <VCard>
          <div class="toolbar">
            <input v-model="summarySearchQuery" type="text" class="search-input" placeholder="搜索文章标题..." />
            <VButton type="primary" size="sm" :loading="batchSummarizing" @click="batchSummarize">
              {{ batchSummarizing ? '批量生成中...' : '批量生成摘要' }}
            </VButton>
          </div>

          <div class="info-box">
            AI 文章摘要功能使用 LLM 为文章自动生成摘要，可用于 SEO description 或社交分享。
            摘要在点击生成后即时返回，暂不支持自动保存到文章。
          </div>

          <div v-if="loadingArticles" class="loading-text">加载文章列表...</div>

          <table v-else class="ai-table">
            <thead>
              <tr>
                <th>文章标题</th>
                <th>摘要</th>
                <th style="width: 180px">操作</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="article in filteredSummaryArticles" :key="article.postName">
                <tr>
                  <td class="article-title-cell">{{ article.title }}</td>
                  <td>
                    <span v-if="summaries[article.postName]" class="summary-text">
                      {{ truncate(summaries[article.postName], 100) }}
                    </span>
                    <span v-else class="text-muted">未生成</span>
                  </td>
                  <td class="actions-cell">
                    <VButton size="xs"
                      :loading="summarizingPost === article.postName"
                      @click="generateSummary(article.postName)">
                      {{ summarizingPost === article.postName ? '生成中...' : (summaries[article.postName] ? '重新生成' : '生成摘要') }}
                    </VButton>
                    <VButton v-if="summaries[article.postName]" size="xs"
                      @click="toggleFullSummary(article.postName)">
                      {{ fullSummaryPost === article.postName ? '收起' : '查看' }}
                    </VButton>
                  </td>
                </tr>
                <!-- 完整摘要展开 -->
                <tr v-if="fullSummaryPost === article.postName && summaries[article.postName]" class="preview-row">
                  <td colspan="3">
                    <div class="summary-preview">
                      <p>{{ summaries[article.postName] }}</p>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </VCard>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { VPageHeader, VCard, VButton, Toast } from "@halo-dev/components";

const API_BASE = "/apis/console.api.ai-assistant.halo.run/v1alpha1";

// 选项卡
const tabs = [
  { key: "overview", label: "概览" },
  { key: "index", label: "索引管理" },
  { key: "summary", label: "摘要管理" },
];
const activeTab = ref("overview");

// 概览统计
const stats = ref({
  chunkCount: 0,
  indexedArticles: 0,
  failedArticles: 0,
  status: "idle",
});
const reindexing = ref(false);

// 文章列表
interface Article {
  postName: string;
  title: string;
  status: "indexed" | "failed" | "not_indexed";
  chunkCount: number;
}
const articles = ref<Article[]>([]);
const loadingArticles = ref(false);
const searchQuery = ref("");

// 索引管理
const previewingPost = ref<string | null>(null);
const chunks = ref<{ id: string; postId: string; content: string; chunkIndex: number }[]>([]);
const loadingChunks = ref(false);
const retryingPost = ref<string | null>(null);

// 摘要管理
const summaries = ref<Record<string, string>>({});
const summarizingPost = ref<string | null>(null);
const fullSummaryPost = ref<string | null>(null);
const summarySearchQuery = ref("");
const batchSummarizing = ref(false);

// ===== 计算属性 =====

const failedArticles = computed(() => articles.value.filter((a) => a.status === "failed"));

const filteredArticles = computed(() => {
  const q = searchQuery.value.trim().toLowerCase();
  if (!q) return articles.value;
  return articles.value.filter((a) => a.title.toLowerCase().includes(q));
});

const filteredSummaryArticles = computed(() => {
  const q = summarySearchQuery.value.trim().toLowerCase();
  if (!q) return articles.value;
  return articles.value.filter((a) => a.title.toLowerCase().includes(q));
});

const indexStats = computed(() => {
  const list = articles.value;
  return {
    indexed: list.filter((a) => a.status === "indexed").length,
    failed: list.filter((a) => a.status === "failed").length,
    notIndexed: list.filter((a) => a.status === "not_indexed").length,
  };
});

// ===== 方法 =====

function switchTab(key: string) {
  activeTab.value = key;
}

function getStatusClass(status: string) {
  return (
    {
      indexed: "status-indexed",
      failed: "status-failed",
      not_indexed: "status-not-indexed",
    }[status] || ""
  );
}

function getStatusLabel(status: string) {
  return (
    {
      indexed: "已索引",
      failed: "失败",
      not_indexed: "未索引",
    }[status] || status
  );
}

function truncate(text: string, len: number) {
  if (!text) return "";
  return text.length > len ? text.slice(0, len) + "..." : text;
}

// 加载统计
async function loadStats() {
  try {
    const resp = await fetch(`${API_BASE}/knowledge/stats`);
    if (resp.ok) {
      const data = await resp.json();
      stats.value = {
        chunkCount: data.chunkCount || 0,
        indexedArticles: data.indexedArticles || 0,
        failedArticles: data.failedArticles || 0,
        status: data.status || "idle",
      };
    }
  } catch {}
}

// 加载文章列表
async function loadArticles() {
  loadingArticles.value = true;
  try {
    const resp = await fetch(`${API_BASE}/knowledge/articles`);
    if (resp.ok) {
      const data = await resp.json();
      articles.value = data.articles || [];
    }
  } catch {} finally {
    loadingArticles.value = false;
  }
}

// 全量重建
async function triggerReindex() {
  reindexing.value = true;
  stats.value.status = "indexing";
  try {
    const resp = await fetch(`${API_BASE}/knowledge/reindex`, { method: "POST" });
    const data = await resp.json();
    if (data.success) {
      Toast.success(data.message || `重建完成，共 ${data.chunkCount} 个切片`);
    } else {
      Toast.error(data.message || "重建失败");
    }
    await Promise.all([loadStats(), loadArticles()]);
  } catch (e) {
    Toast.error("请求失败: " + (e as Error).message);
    stats.value.status = "idle";
  } finally {
    reindexing.value = false;
  }
}

// 重试单篇文章
async function retryPost(postName: string) {
  retryingPost.value = postName;
  try {
    const resp = await fetch(`${API_BASE}/knowledge/reindex-post/${postName}`, { method: "POST" });
    const data = await resp.json();
    if (data.success) {
      Toast.success(`索引完成，${data.chunkCount} 个切片`);
      await loadArticles();
    } else {
      Toast.error(data.message || "索引失败");
    }
  } catch (e) {
    Toast.error("请求失败: " + (e as Error).message);
  } finally {
    retryingPost.value = null;
  }
}

// 预览切片
async function previewChunks(article: Article) {
  if (previewingPost.value === article.postName) {
    previewingPost.value = null;
    return;
  }
  previewingPost.value = article.postName;
  loadingChunks.value = true;
  chunks.value = [];
  try {
    const resp = await fetch(`${API_BASE}/knowledge/articles/${article.postName}/chunks`);
    if (resp.ok) {
      const data = await resp.json();
      chunks.value = data.chunks || [];
    }
  } catch {
    chunks.value = [];
  } finally {
    loadingChunks.value = false;
  }
}

// 生成单篇文章摘要
async function generateSummary(postName: string) {
  summarizingPost.value = postName;
  try {
    const resp = await fetch(`${API_BASE}/knowledge/summarize`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ postName }),
    });
    const data = await resp.json();
    if (data.success) {
      summaries.value[postName] = data.summary;
    } else {
      Toast.error(data.message || "生成失败");
    }
  } catch (e) {
    Toast.error("请求失败: " + (e as Error).message);
  } finally {
    summarizingPost.value = null;
  }
}

// 批量生成摘要
async function batchSummarize() {
  batchSummarizing.value = true;
  try {
    const resp = await fetch(`${API_BASE}/knowledge/summarize-all`, { method: "POST" });
    const data = await resp.json();
    if (data.success) {
      for (const result of data.results || []) {
        if (result.summary) {
          summaries.value[result.postName] = result.summary;
        }
      }
      Toast.success(`批量生成完成，共 ${data.count} 篇`);
    } else {
      Toast.error(data.message || "批量生成失败");
    }
  } catch (e) {
    Toast.error("请求失败: " + (e as Error).message);
  } finally {
    batchSummarizing.value = false;
  }
}

function toggleFullSummary(postName: string) {
  fullSummaryPost.value = fullSummaryPost.value === postName ? null : postName;
}

onMounted(() => {
  loadStats();
  loadArticles();
});
</script>
