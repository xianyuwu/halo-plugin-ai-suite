<template>
  <div class="knowledge-page">
    <div class="ai-content">
      <div class="ai-excerpt-cols">
        <SectionCard title="索引概览" :icon-component="RiFolderLine" headerTitle="向量索引" headerDesc="管理文章向量索引，支持全量重建与实时同步">
          <div class="ai-card-body">
            <!-- 统计卡片 -->
            <div class="ai-stats-row">
              <div class="ai-stat-mini">
                <span class="ai-stat-mini-value">{{ stats.indexedArticles }}</span>
                <span class="ai-stat-mini-label">已索引</span>
              </div>
              <div class="ai-stat-mini">
                <span class="ai-stat-mini-value">{{ stats.chunkCount }}</span>
                <span class="ai-stat-mini-label">切片数</span>
              </div>
              <div class="ai-stat-mini" :class="{ 'ai-stat-mini-warn': stats.failedArticles > 0 }">
                <span class="ai-stat-mini-value">{{ stats.failedArticles }}</span>
                <span class="ai-stat-mini-label">失败</span>
              </div>
              <div class="ai-stat-mini">
                <span class="ai-stat-mini-value">{{ stats.keywordChunks }}</span>
                <span class="ai-stat-mini-label">关键词</span>
              </div>
              <div class="ai-stat-mini">
                <span class="ai-stat-mini-value" :class="stats.status === 'indexing' ? 'ai-stat-blue' : 'ai-stat-green'">
                  {{ stats.status === 'indexing' ? '索引中' : '就绪' }}
                </span>
                <span class="ai-stat-mini-label">状态</span>
              </div>
            </div>

            <!-- 关键词截断汇总建议 -->
            <div v-if="truncatedSummary" class="ai-kw-summary">
              <span class="ai-kw-summary-icon"><RiLightbulbLine /></span>
              <span>{{ truncatedSummary }}</span>
            </div>

            <!-- 进度条（重建中） -->
            <div v-if="reindexing && reindexProgress" class="ai-reindex-progress">
              <div class="ai-progress-header">
                <span class="ai-progress-phase">{{ phaseLabel(reindexProgress.phase) }}</span>
                <span class="ai-progress-pct">{{ reindexProgress.percentage }}%</span>
              </div>
              <div class="ai-progress-bar-track">
                <div class="ai-progress-bar-fill" :style="{ width: reindexProgress.percentage + '%' }"></div>
              </div>
              <div class="ai-progress-details">
                <span class="ai-progress-current">
                  <template v-if="reindexProgress.currentArticleTitle">
                    正在处理: {{ reindexProgress.currentArticleTitle }}
                    <span v-if="reindexProgress.detail" class="ai-progress-detail">{{ reindexProgress.detail }}</span>
                  </template>
                </span>
                <span class="ai-progress-counts">
                  {{ reindexProgress.processedArticles }} / {{ reindexProgress.totalArticles }} 篇
                  · {{ reindexProgress.totalChunks }} 切片
                  <span v-if="reindexProgress.failedArticles > 0" class="ai-progress-fail">
                    · {{ reindexProgress.failedArticles }} 失败
                  </span>
                </span>
              </div>
            </div>

            <!-- 重建按钮（非重建中） -->
            <div v-else class="ai-reindex-bar">
              <div class="ai-reindex-text">
                <strong>重建全部索引</strong>
                <span>清空现有索引并重新索引所有已发布文章，适用于首次使用或配置变更后</span>
              </div>
              <VButton type="primary" :disabled="reindexing" @click="triggerReindex">{{ reindexing ? '索引中...' : '开始重建' }}</VButton>
            </div>

            <div class="ai-notice">
              <span class="ai-notice-icon">ℹ️</span>
              <div class="ai-notice-text">
                <strong>自动索引：</strong>插件会自动监听文章的发布、更新、删除事件，实时同步索引。
                以下情况需要手动重建：首次配置 Embedding 模型、修改切片参数、更换模型或向量维度。
              </div>
            </div>

            <div class="ai-help-panel">
              <button type="button" class="ai-help-toggle" @click="tipsOpen = !tipsOpen">
                <span>维护建议</span>
                <span class="ai-help-chevron" :class="{ open: tipsOpen }">▾</span>
              </button>
              <ul v-if="tipsOpen" class="ai-tips-list">
                <li>文章更新后会自动同步索引，无需手动操作</li>
                <li>修改切片参数后需手动重建索引，让新参数生效</li>
                <li>更换 Embedding 模型后建议清空索引并全量重建</li>
                <li>失败的文章可在列表中单独重试，无需全量重建</li>
                <li>定期查看索引状态，保证文章数与博客文章总数一致</li>
              </ul>
            </div>
          </div>
        </SectionCard>
      </div>

      <!-- 文章索引列表 -->
      <div class="ai-section-block">
        <div class="ai-section-heading">
          <h2>文章索引列表</h2>
          <span class="ai-section-tag">共 {{ filteredArticles.length }} 篇</span>
        </div>
        <article class="ai-section-card">
          <div class="ai-card-body" style="padding: 0;">
            <div class="ai-batch-toolbar">
              <div class="ai-search-box">
                <span class="ai-search-icon"><RiSearchLine /></span>
                <input v-model="searchQuery" type="text" placeholder="搜索文章标题..." @input="onSearchChange" />
              </div>
              <div class="ai-batch-actions">
                <VButton type="default" size="xs" @click="refreshList">刷新列表</VButton>
              </div>
            </div>

            <div v-if="loadingArticles" class="ai-loading">加载文章列表...</div>

            <div v-else-if="articles.length === 0" class="ai-empty">
              <div class="ai-empty-icon"><RiInboxLine /></div>
              <div class="ai-empty-title">暂无已发布的文章</div>
              <div class="ai-empty-desc">发布博客文章后，这里会自动出现索引记录</div>
            </div>

            <div v-else class="ai-table-wrap">
              <table class="ai-table">
                <thead>
                  <tr>
                    <th>文章标题</th>
                    <th style="width: 12%" class="th-center">
                      <span class="th-filter-wrap" ref="statusFilterRef" @click.stop="toggleStatusFilter">
                        状态
                        <span class="th-filter-icon" :class="{ active: statusFilter !== 'all' }">▾</span>
                      </span>
                    </th>
                    <th style="width: 10%" class="th-center">切片</th>
                    <th style="width: 12%" class="th-center">
                      <span class="th-filter-wrap" ref="keywordFilterRef" @click.stop="toggleKeywordFilter">
                        关键词
                        <span class="th-filter-icon" :class="{ active: keywordFilter !== 'all' }">▾</span>
                      </span>
                    </th>
                    <th style="width: 13%" class="th-center">
                      <a class="ai-sort-link" @click="toggleSort">创建时间 {{ sortIcon }}</a>
                    </th>
                    <th style="width: 14%" class="th-center">更新时间</th>
                    <th style="width: 14%" class="th-actions">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <template v-for="article in pagedArticles" :key="article.postName">
                    <tr>
                      <td class="article-title-cell" data-label="文章标题">{{ article.title }}</td>
                      <td class="ai-cell-center" data-label="状态">
                        <span :class="['ai-status-badge', getStatusClass(article.status)]">
                          {{ getStatusLabel(article.status) }}
                        </span>
                      </td>
                      <td class="ai-cell-center" data-label="切片">{{ article.chunkCount || '-' }}</td>
                      <td class="ai-cell-center kw-cell" data-label="关键词">
                        <span v-if="article.chunkCount > 0" class="ai-keyword-pct" :class="keywordCoverageClass(article)">
                          {{ article.keywordChunks || 0 }}/{{ article.chunkCount }} ({{ keywordCoverage(article) }})
                        </span>
                        <span v-else class="ai-keyword-pct">-</span>
                        <span v-if="article.keywordStatus === 'truncated'" class="kw-tag kw-tag-truncated"
                          :title="truncatedTip(article)">截断{{ article.keywordTruncated }}</span>
                        <span v-else-if="article.keywordStatus === 'failed'" class="kw-tag kw-tag-failed">失败</span>
                      </td>
                      <td class="ai-cell-center ai-cell-date" data-label="创建时间">{{ formatDate(article.createTime) }}</td>
                      <td class="ai-cell-center ai-cell-date" data-label="更新时间">{{ formatDate(article.updateTime) }}</td>
                      <td class="actions-cell" data-label="操作">
                        <!-- 重建中：显示进度条 -->
                        <div v-if="isPostReindexing(article.postName) && getPostProgress(article.postName)" class="post-reindex-inline">
                          <span class="post-reindex-stage">{{ postStageLabel(getPostProgress(article.postName)!.stage) }}</span>
                          <div class="post-reindex-track">
                            <div class="post-reindex-fill" :class="{ 'is-done': getPostProgress(article.postName)!.stage === 'done', 'is-error': getPostProgress(article.postName)!.stage === 'error' }" :style="{ width: getPostProgress(article.postName)!.percent + '%' }"></div>
                          </div>
                          <span class="post-reindex-pct">{{ getPostProgress(article.postName)!.percent }}%</span>
                          <span v-if="getPostProgress(article.postName)!.stage === 'done'" class="post-reindex-ok">{{ getPostProgress(article.postName)!.chunks }} 切片</span>
                          <span v-else-if="getPostProgress(article.postName)!.stage === 'error'" class="post-reindex-err">失败</span>
                        </div>
                        <!-- 正常状态：显示操作按钮 -->
                        <template v-else>
                          <VButton
                            v-if="article.status === 'indexed'"
                            type="default"
                            size="xs"
                            @click="previewChunks(article)"
                          >
                            {{ previewingPost === article.postName ? '收起' : '预览切片' }}
                          </VButton>
                          <VButton
                            v-if="article.status === 'indexed'"
                            type="default"
                            size="xs"
                            @click="retryPost(article.postName)"
                          >
                            重建索引
                          </VButton>
                          <VButton
                            v-if="article.status === 'indexed'"
                            type="danger"
                            size="xs"
                            :loading="clearingPost === article.postName"
                            @click="openClearDialog(article)"
                          >
                            清除索引
                          </VButton>
                          <VButton
                            v-if="article.status === 'failed' || article.status === 'not_indexed'"
                            type="primary"
                            size="xs"
                            @click="retryPost(article.postName)"
                          >
                            {{ article.status === 'failed' ? '重试' : '索引' }}
                          </VButton>
                        </template>
                      </td>
                    </tr>
                    <!-- 切片预览行 -->
                    <tr v-if="previewingPost === article.postName" class="preview-row">
                      <td colspan="7">
                        <div class="chunk-preview">
                          <div v-if="loadingChunks" class="ai-loading-sm">加载切片中...</div>
                          <div v-else-if="chunks.length === 0" class="ai-empty-text">暂无切片数据</div>
                          <template v-else>
                            <div class="chunk-count-info">共 {{ chunks.length }} 个切片</div>
                            <div v-for="chunk in chunks" :key="chunk.id" class="chunk-item">
                              <span class="chunk-index">#{{ chunk.chunkIndex + 1 }}</span>
                              <div class="chunk-body">
                                <span class="chunk-content">{{ truncate(chunk.content, 300) }}</span>
                                <div v-if="chunk.keywords" class="chunk-keywords">
                                  <span class="keyword-tag" v-for="kw in chunk.keywords.split(',')" :key="kw">{{ kw.trim() }}</span>
                                </div>
                              </div>
                            </div>
                          </template>
                        </div>
                      </td>
                    </tr>
                  </template>
                </tbody>
              </table>
            </div>

            <div class="ai-table-footer">
              <span class="ai-filter-stat">
                <span class="ai-filter-green">{{ indexStats.indexed }} 已索引</span>
                <span v-if="indexStats.failed > 0" class="ai-filter-red"> · {{ indexStats.failed }} 失败</span>
                <span v-if="indexStats.notIndexed > 0"> · {{ indexStats.notIndexed }} 未索引</span>
              </span>
              <div class="ai-pagination-controls">
                <select class="ai-pagination-select" v-model.number="pageSize" @change="currentPage = 1">
                  <option v-for="size in pageSizeOptions" :key="size" :value="size">{{ size }} 条/页</option>
                </select>
                <VButton type="default" size="xs" :disabled="currentPage <= 1" @click="goToPage(currentPage - 1)">‹</VButton>
                <span class="ai-pagination-info">{{ currentPage }} / {{ totalPages }}</span>
                <VButton type="default" size="xs" :disabled="currentPage >= totalPages" @click="goToPage(currentPage + 1)">›</VButton>
              </div>
            </div>
          </div>
        </article>
      </div>
    </div>

    <!-- 状态筛选下拉（fixed 定位，不被 overflow 裁剪） -->
    <div
      v-if="statusFilterOpen"
      class="th-filter-dropdown"
      :style="statusDropdownPos"
      @click.stop
    >
      <div
        v-for="opt in (['all', 'indexed', 'not_indexed', 'failed'] as const)"
        :key="opt"
        class="th-filter-option"
        :class="{ selected: statusFilter === opt }"
        @click="setStatusFilter(opt)"
      >
        {{ opt === 'all' ? '全部' : opt === 'indexed' ? '已索引' : opt === 'not_indexed' ? '未索引' : '失败' }}
      </div>
    </div>

    <!-- 关键词覆盖率筛选下拉 -->
    <div
      v-if="keywordFilterOpen"
      class="th-filter-dropdown"
      :style="keywordDropdownPos"
      @click.stop
    >
      <div
        v-for="opt in (['all', 'incomplete', 'complete'] as const)"
        :key="opt"
        class="th-filter-option"
        :class="{ selected: keywordFilter === opt }"
        @click="setKeywordFilter(opt)"
      >
        {{ opt === 'all' ? '全部' : opt === 'incomplete' ? '未全覆盖' : '全覆盖' }}
      </div>
    </div>

    <!-- 清除索引确认弹窗 -->
    <VDialog
      v-model:visible="showClearDialog"
      type="warning"
      title="清除文章索引"
      :description="clearDescription"
      confirm-type="danger"
      confirm-text="清除"
      cancel-text="取消"
      :on-confirm="confirmClearIndex"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from "vue";
import { Toast , VButton, VDialog} from "@halo-dev/components";
import SectionCard from "../components/SectionCard.vue";
import RiFolderLine from "~icons/ri/folder-line";
import RiLightbulbLine from "~icons/ri/lightbulb-line";
import RiSearchLine from "~icons/ri/search-line";
import RiInboxLine from "~icons/ri/inbox-line";

const API_BASE = "/apis/console.api.ai-suite.halo.run/v1alpha1";

// ===== 概览统计 =====
const stats = ref({
  chunkCount: 0,
  indexedArticles: 0,
  failedArticles: 0,
  keywordChunks: 0,
  status: "idle",
});
const reindexing = ref(false);
let eventSource: EventSource | null = null;

interface ReindexProgress {
  phase: string;
  totalArticles: number;
  processedArticles: number;
  successArticles: number;
  failedArticles: number;
  currentArticleTitle: string;
  totalChunks: number;
  errorMessage: string | null;
  percentage: number;
  detail: string;
  truncatedKeywords: number;
  keywordsFailed: number;
}
const reindexProgress = ref<ReindexProgress | null>(null);
const tipsOpen = ref(false);

// ===== 文章列表 =====
interface Article {
  postName: string;
  title: string;
  status: "indexed" | "failed" | "not_indexed";
  chunkCount: number;
  keywordChunks: number;
  keywordStatus: string;
  keywordTruncated: number;
  createTime?: string | number;
  updateTime?: string | number;
}
const articles = ref<Article[]>([]);
const loadingArticles = ref(false);
const searchQuery = ref("");
const sortOrder = ref<"desc" | "asc">("desc");
const sortIcon = computed(() => sortOrder.value === "desc" ? "↓" : "↑");

// 状态筛选
const statusFilter = ref<"all" | "indexed" | "not_indexed" | "failed">("all");
const statusFilterOpen = ref(false);
const statusFilterRef = ref<HTMLElement | null>(null);
const statusDropdownPos = ref<Record<string, string>>({});
function toggleStatusFilter() {
  if (statusFilterOpen.value) {
    statusFilterOpen.value = false;
    return;
  }
  const el = statusFilterRef.value;
  if (el) {
    const r = el.getBoundingClientRect();
    statusDropdownPos.value = {
      position: "fixed",
      top: `${r.bottom + 4}px`,
      left: `${r.left + r.width / 2}px`,
      transform: "translateX(-50%)",
      zIndex: "100",
    };
  }
  statusFilterOpen.value = true;
}
function setStatusFilter(val: "all" | "indexed" | "not_indexed" | "failed") {
  statusFilter.value = val;
  statusFilterOpen.value = false;
  currentPage.value = 1;
}

// 关键词覆盖率筛选
const keywordFilter = ref<"all" | "incomplete" | "complete">("all");
const keywordFilterOpen = ref(false);
const keywordFilterRef = ref<HTMLElement | null>(null);
const keywordDropdownPos = ref<Record<string, string>>({});
function toggleKeywordFilter() {
  if (keywordFilterOpen.value) {
    keywordFilterOpen.value = false;
    return;
  }
  const el = keywordFilterRef.value;
  if (el) {
    const r = el.getBoundingClientRect();
    keywordDropdownPos.value = {
      position: "fixed",
      top: `${r.bottom + 4}px`,
      left: `${r.left + r.width / 2}px`,
      transform: "translateX(-50%)",
      zIndex: "100",
    };
  }
  keywordFilterOpen.value = true;
}
function setKeywordFilter(val: "all" | "incomplete" | "complete") {
  keywordFilter.value = val;
  keywordFilterOpen.value = false;
  currentPage.value = 1;
}

function toggleSort() {
  sortOrder.value = sortOrder.value === "desc" ? "asc" : "desc";
  currentPage.value = 1;
}

// ===== 索引管理 =====
const previewingPost = ref<string | null>(null);
const chunks = ref<{ id: string; postId: string; content: string; chunkIndex: number; keywords: string }[]>([]);
const loadingChunks = ref(false);
const clearingPost = ref<string | null>(null);

// 单篇重建索引进度（SSE，支持多篇并发）
const reindexingPosts = ref<Record<string, { stage: string; error: string; chunks: number; percent: number }>>({});
const postReindexESMap = new Map<string, EventSource>();

function getPostProgress(postName: string) {
  return reindexingPosts.value[postName];
}
function isPostReindexing(postName: string) {
  return postName in reindexingPosts.value;
}

// 清除索引确认弹窗状态
const showClearDialog = ref(false);
const clearTarget = ref<{ postName: string; title: string } | null>(null);
const clearDescription = computed(() =>
  clearTarget.value
    ? `确定要清除「${clearTarget.value.title}」的索引吗？清除后需重新索引才能恢复 AI 问答能力。`
    : ""
);

function openClearDialog(article: { postName: string; title: string }) {
  clearTarget.value = article;
  showClearDialog.value = true;
}

async function confirmClearIndex() {
  if (!clearTarget.value) return;
  const postName = clearTarget.value.postName;
  clearingPost.value = postName;
  showClearDialog.value = false;
  try {
    const resp = await fetch(`${API_BASE}/knowledge/clear-post/${postName}`, { method: "POST" });
    const data = await resp.json();
    if (data.success) {
      Toast.success("索引已清除");
      await Promise.all([loadStats(), loadArticles()]);
    } else {
      Toast.error(data.message || "清除失败");
    }
  } catch (e) {
    Toast.error("请求失败: " + (e as Error).message);
  } finally {
    clearingPost.value = null;
    clearTarget.value = null;
  }
}

// ===== 分页 =====
const currentPage = ref(1);
const pageSize = ref(10);
const pageSizeOptions = [10, 20, 50];

// ===== 计算属性 =====

const filteredArticles = computed(() => {
  const q = searchQuery.value.trim().toLowerCase();
  let list = articles.value;
  if (q) list = list.filter((a) => a.title.toLowerCase().includes(q));
  if (statusFilter.value !== "all") list = list.filter((a) => a.status === statusFilter.value);
  if (keywordFilter.value === "incomplete") {
    list = list.filter((a) => a.chunkCount > 0 && (a.keywordChunks || 0) < a.chunkCount);
  } else if (keywordFilter.value === "complete") {
    list = list.filter((a) => a.chunkCount > 0 && (a.keywordChunks || 0) >= a.chunkCount);
  }
  const dir = sortOrder.value === "desc" ? -1 : 1;
  return [...list].sort((a, b) => {
    const ta = a.createTime ? new Date(a.createTime).getTime() : 0;
    const tb = b.createTime ? new Date(b.createTime).getTime() : 0;
    return (ta - tb) * dir;
  });
});

const totalPages = computed(() => Math.max(1, Math.ceil(filteredArticles.value.length / pageSize.value)));

const pagedArticles = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value;
  return filteredArticles.value.slice(start, start + pageSize.value);
});

function onSearchChange() {
  currentPage.value = 1;
}

function goToPage(page: number) {
  currentPage.value = Math.max(1, Math.min(page, totalPages.value));
}

const indexStats = computed(() => {
  const list = articles.value;
  return {
    indexed: list.filter((a) => a.status === "indexed").length,
    failed: list.filter((a) => a.status === "failed").length,
    notIndexed: list.filter((a) => a.status === "not_indexed").length,
  };
});

// ===== 工具函数 =====

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

function formatDate(t: string | number | undefined) {
  if (!t) return "-";
  const d = typeof t === "number" ? new Date(t) : new Date(t);
  if (isNaN(d.getTime())) return "-";
  return d.toLocaleDateString("zh-CN", { year: "numeric", month: "2-digit", day: "2-digit" });
}

function keywordCoverage(article: Article): string {
  if (!article.chunkCount) return "-";
  const pct = Math.round((article.keywordChunks || 0) * 100 / article.chunkCount);
  return pct + "%";
}

function keywordCoverageClass(article: Article): string {
  if (!article.chunkCount) return "";
  const pct = Math.round((article.keywordChunks || 0) * 100 / article.chunkCount);
  if (pct === 100) return "kw-full";
  if (pct > 0) return "kw-partial";
  return "kw-none";
}

function truncatedTip(article: Article): string {
  const missing = article.keywordTruncated || 0;
  return `缺失 ${missing} 个切片关键词。建议降低关键词提取并发，或查看模型用量里的失败诊断`;
}

const truncatedSummary = computed(() => {
  const trList = articles.value.filter(a => a.keywordStatus === 'truncated');
  if (trList.length === 0) return '';
  const totalMissing = trList.reduce((s, a) => s + (a.keywordTruncated || 0), 0);
  return `${trList.length} 篇文章共缺失 ${totalMissing} 个切片关键词。建议先将关键词提取并发设为 1，并查看模型用量里的失败诊断`;
});

// ===== API 方法 =====

async function loadStats() {
  try {
    const resp = await fetch(`${API_BASE}/knowledge/stats`);
    if (resp.ok) {
      const data = await resp.json();
      stats.value = {
        chunkCount: data.chunkCount || 0,
        indexedArticles: data.indexedArticles || 0,
        failedArticles: data.failedArticles || 0,
        keywordChunks: data.keywordChunks || 0,
        status: data.status || "idle",
      };
    }
  } catch {}
}

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

async function refreshList() {
  await Promise.all([loadStats(), loadArticles()]);
  Toast.success("已刷新");
}

function phaseLabel(phase: string): string {
  return (
    {
      clearing: "正在清空索引...",
      listing: "正在获取文章列表...",
      processing: "正在索引文章...",
      retrying: "正在重试失败文章...",
      completed: "重建完成",
      error: "重建失败",
    }[phase] || phase
  );
}

function postStageLabel(stage: string): string {
  return (
    {
      starting: "准备中...",
      fetching_content: "获取文章内容",
      chunking: "正在切片",
      keywords: "提取关键词",
      embedding: "生成向量并写入索引",
      done: "完成",
      error: "失败",
    }[stage] || stage
  );
}

function connectProgressSSE() {
  eventSource = new EventSource(`${API_BASE}/knowledge/reindex/progress`);
  eventSource.addEventListener("progress", (event) => {
    try {
      reindexProgress.value = JSON.parse(event.data);
    } catch {}
  });
  eventSource.onerror = () => {
    // SSE 断连，不立刻清理，EventSource 会尝试自动重连
  };
}

async function triggerReindex() {
  if (reindexing.value) return;
  reindexing.value = true;
  reindexProgress.value = null;

  // 先开 SSE 连接（避免竞态），再触发重建
  connectProgressSSE();

  try {
    const resp = await fetch(`${API_BASE}/knowledge/reindex`, { method: "POST" });
    const data = await resp.json();
    if (data.status === "already_indexing") {
      Toast.info("索引正在重建中");
    } else if (data.status === "error" || data.success === false) {
      Toast.error(data.message || "启动失败");
      reindexing.value = false;
      eventSource?.close();
      eventSource = null;
    }
    // status === "started" → SSE 流接管进度展示
  } catch (e) {
    Toast.error("请求失败: " + (e as Error).message);
    reindexing.value = false;
    eventSource?.close();
    eventSource = null;
  }
}

// 监听完成/错误
watch(() => reindexProgress.value?.phase, (phase) => {
  if (phase === "completed" || phase === "error") {
    reindexing.value = false;
    eventSource?.close();
    eventSource = null;

    if (phase === "completed") {
      const p = reindexProgress.value!;
      let msg = p.failedArticles > 0
        ? `重建完成，${p.successArticles} 篇成功，${p.failedArticles} 篇失败`
        : `重建完成，共 ${p.totalChunks} 个切片`;
      if (p.truncatedKeywords > 0) {
        msg += `，${p.truncatedKeywords} 个切片关键词不完整`;
      }
      if (p.keywordsFailed > 0) {
        msg += `，${p.keywordsFailed} 篇文章关键词提取失败`;
      }
      Toast.success(msg);
    } else {
      Toast.error("重建失败: " + (reindexProgress.value?.errorMessage || "未知错误"));
    }
    Promise.all([loadStats(), loadArticles()]);
  }
});

onMounted(async () => {
  await loadStats();
  // 如果刷新页面时重建还在进行中，恢复 SSE 连接
  if (stats.value.status === "indexing") {
    reindexing.value = true;
    connectProgressSSE();
  }
  loadArticles();
  document.addEventListener("click", closeStatusFilter);
});

onUnmounted(() => {
  eventSource?.close();
  eventSource = null;
  document.removeEventListener("click", closeStatusFilter);
  postReindexESMap.forEach((es) => es.close());
  postReindexESMap.clear();
});

function closeStatusFilter() {
  statusFilterOpen.value = false;
  keywordFilterOpen.value = false;
}

async function retryPost(postName: string) {
  if (postName in reindexingPosts.value) return;
  reindexingPosts.value = { ...reindexingPosts.value, [postName]: { stage: "starting", error: "", chunks: 0, percent: 0 } };

  const es = new EventSource(`${API_BASE}/knowledge/reindex-post/${postName}/progress`);
  postReindexESMap.set(postName, es);
  let finished = false;

  es.addEventListener("progress", (event) => {
    if (finished) return;
    try {
      const data = JSON.parse(event.data);
      reindexingPosts.value = { ...reindexingPosts.value, [postName]: data };
      // 从 progress 事件判断完成，不依赖 done/onerror
      if (data.stage === "done" || data.stage === "error") {
        finished = true;
        es.close();
        finishPostReindex(postName, data.stage === "done");
      }
    } catch {}
  });
  es.onerror = () => {
    if (finished) return;
    // 只有 readyState === CONNECTING 才是真正的网络错误
    // CLOSED 说明流已结束，progress 事件应该已经处理了
    if (es.readyState === EventSource.CONNECTING) {
      finished = true;
      es.close();
      finishPostReindex(postName, false);
    }
  };
}

function finishPostReindex(postName: string, success: boolean) {
  postReindexESMap.get(postName)?.close();
  postReindexESMap.delete(postName);

  const progress = reindexingPosts.value[postName];
  const { [postName]: _, ...rest } = reindexingPosts.value;
  reindexingPosts.value = rest;

  if (success && progress) {
    Toast.success(`索引完成，${progress.chunks} 个切片`);
  } else if (!success) {
    Toast.error(progress?.error || "索引失败");
  }
  Promise.all([loadStats(), loadArticles()]);
}

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

</script>

<style scoped>
.knowledge-page {
  min-height: 100%;
  background: #f5f7fb;
}

/* ===== 顶部概览布局 ===== */
.ai-excerpt-cols {
  margin-bottom: 24px;
}

/* ===== 统计迷你卡片（横排） ===== */
.ai-stats-row {
  display: flex;
  gap: 12px;
  margin-bottom: 18px;
}

.ai-stat-mini {
  flex: 1;
  text-align: center;
  padding: 12px 8px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
}

.ai-stat-mini-warn {
  background: #fffbeb;
  border-color: #fde68a;
}

.ai-stat-mini-value {
  display: block;
  font-size: 22px;
  font-weight: 700;
  color: #111827;
  line-height: 1.2;
}

.ai-stat-green { color: #059669 !important; }
.ai-stat-blue { color: #2563eb !important; font-size: 16px !important; }

.ai-stat-mini-label {
  display: block;
  font-size: 12px;
  color: #8a94a6;
  margin-top: 4px;
}

/* ===== 重建操作栏 ===== */
.ai-reindex-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 14px 16px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  margin-bottom: 14px;
}

.ai-reindex-text {
  flex: 1;
  min-width: 0;
}

.ai-reindex-text strong {
  display: block;
  font-size: 14px;
  color: #111827;
  margin-bottom: 4px;
}

.ai-reindex-text span {
  font-size: 12px;
  color: #8a94a6;
  line-height: 1.5;
}

/* ===== 进度条 ===== */
.ai-reindex-progress {
  padding: 16px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  margin-bottom: 14px;
}

.ai-progress-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.ai-progress-phase {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
}

.ai-progress-pct {
  font-size: 14px;
  font-weight: 700;
  color: #2563eb;
}

.ai-progress-bar-track {
  height: 8px;
  background: #e5e7eb;
  border-radius: 4px;
  overflow: hidden;
  margin-bottom: 10px;
}

.ai-progress-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #3b82f6, #2563eb);
  border-radius: 4px;
  transition: width 0.3s ease;
}

.ai-progress-details {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: #6b7280;
}

.ai-progress-current {
  color: #374151;
  font-weight: 500;
  max-width: 60%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-progress-detail {
  display: inline-block;
  margin-left: 8px;
  padding: 1px 8px;
  background: #eff6ff;
  color: #2563eb;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}

.ai-progress-fail {
  color: #b91c1c;
  font-weight: 600;
}

/* ===== 提示条 ===== */
.ai-notice {
  padding: 12px 14px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.ai-notice-icon {
  font-size: 14px;
  line-height: 1.6;
  flex-shrink: 0;
}

.ai-notice-text {
  font-size: 12px;
  color: #4b5563;
  line-height: 1.7;
}

.ai-notice-text strong {
  color: #111827;
  font-weight: 600;
}

/* ===== 维护建议折叠区 ===== */
.ai-help-panel {
  margin-top: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fff;
  overflow: hidden;
}

.ai-help-toggle {
  width: 100%;
  border: 0;
  background: #fff;
  padding: 10px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: #374151;
  font-size: 13px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}

.ai-help-toggle:hover {
  background: #f9fafb;
}

.ai-help-chevron {
  color: #9ca3af;
  font-size: 12px;
  transition: transform 0.16s ease, color 0.16s ease;
}

.ai-help-chevron.open {
  color: #4b5563;
  transform: rotate(180deg);
}

.ai-tips-list {
  padding: 0 14px 12px;
  margin: 0;
  list-style: none;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 8px 18px;
  border-top: 1px solid #f3f4f6;
}

.ai-tips-list li {
  position: relative;
  font-size: 12px;
  color: #4b5563;
  line-height: 1.6;
  padding: 10px 0 0 12px;
}

.ai-tips-list li::before {
  content: "";
  position: absolute;
  left: 0;
  top: 18px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #9ca3af;
}

/* ===== 搜索 + 工具栏 ===== */
.ai-batch-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-bottom: 1px solid #e5e7eb;
  background: #f9fafb;
}

.ai-batch-actions {
  display: flex;
  gap: 6px;
  align-items: center;
}

.ai-search-box {
  height: 36px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  background: #fff;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  max-width: 320px;
}

.ai-search-box:focus-within {
  border-color: #111827;
  box-shadow: 0 0 0 3px rgba(17, 24, 39, 0.06);
}

.ai-search-icon {
  font-size: 13px;
  color: #9ca3af;
  flex-shrink: 0;
}

.ai-search-box input {
  flex: 1;
  height: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-size: 13px;
  color: #111827;
  font-family: inherit;
}

.ai-search-box input::placeholder {
  color: #9ca3af;
}

/* ===== 加载 & 空态 ===== */
.ai-loading,
.ai-loading-sm {
  padding: 24px 0;
  text-align: center;
  color: #8a94a6;
  font-size: 14px;
}

.ai-loading-sm { padding: 12px 0; }

.ai-empty {
  padding: 48px 20px;
  text-align: center;
}

.ai-empty-icon {
  font-size: 42px;
  margin-bottom: 14px;
  opacity: 0.7;
}

.ai-empty-title {
  font-size: 16px;
  font-weight: 700;
  color: #111827;
  margin-bottom: 6px;
}

.ai-empty-desc {
  font-size: 13px;
  color: #8a94a6;
}

.ai-empty-text {
  font-size: 13px;
  color: #8a94a6;
  padding: 12px 0;
}

/* ===== 表格 ===== */
.ai-table-wrap {
  overflow-x: auto;
  overflow-y: hidden;
  -webkit-overflow-scrolling: touch;
}

.ai-table {
  width: 100%;
  min-width: 980px;
  border-collapse: collapse;
  font-size: 14px;
}

.ai-table th {
  text-align: left;
  padding: 12px 16px;
  background: #f9fafb;
  color: #4b5563;
  font-weight: 600;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  border-bottom: 1px solid #e5e7eb;
}

.ai-table th.th-actions {
  text-align: center;
}

.ai-table th.th-center {
  text-align: center;
}

.ai-table td {
  padding: 14px 16px;
  border-bottom: 1px solid #f3f4f6;
  color: #111827;
  vertical-align: middle;
}

.ai-table tbody tr:last-child td { border-bottom: none; }
.ai-table tbody tr:hover { background: #fafbfc; }

.article-title-cell {
  font-weight: 600;
  color: #111827;
}

.ai-cell-center { text-align: center; }
.ai-cell-center .ai-status-badge { /* badge in center cell is fine */ }
.ai-cell-muted { color: #8a94a6; font-size: 13px; }

.ai-cell-date {
  font-size: 13px;
  color: #8a94a6;
  white-space: nowrap;
}

.ai-sort-link {
  font-size: 12px;
  color: #4b5563;
  cursor: pointer;
  text-decoration: none;
  user-select: none;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.ai-sort-link:hover { color: #111827; }

.actions-cell {
  white-space: nowrap;
  text-align: center;
  vertical-align: middle;
}
.actions-cell > * + * { margin-left: 8px; }

/* 状态徽章 */
.ai-status-badge {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.ai-status-badge.status-indexed { background: #ecfdf5; color: #047857; }
.ai-status-badge.status-failed { background: #fef2f2; color: #b91c1c; }
.ai-status-badge.status-not-indexed { background: #f3f4f6; color: #4b5563; }

/* 切片预览行 */
.preview-row td {
  padding: 0 !important;
  border-bottom: 1px solid #e5e7eb !important;
  background: #fafbfc;
}

.chunk-preview {
  max-height: 320px;
  overflow-y: auto;
  padding: 18px 22px;
}

.chunk-count-info {
  font-size: 12px;
  font-weight: 700;
  color: #6b7280;
  margin-bottom: 12px;
}

.chunk-item {
  display: flex;
  gap: 12px;
  padding: 12px 14px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  margin-bottom: 10px;
}

.chunk-body {
  flex: 1;
  min-width: 0;
}

.chunk-index {
  font-size: 11px;
  font-weight: 600;
  color: #6b7280;
  background: #f3f4f6;
  border-radius: 6px;
  padding: 2px 8px;
  height: fit-content;
  flex-shrink: 0;
}

.chunk-content {
  font-size: 13px;
  color: #374151;
  line-height: 1.7;
  word-break: break-all;
}

.chunk-keywords {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 8px;
}

.keyword-tag {
  display: inline-block;
  padding: 2px 8px;
  background: #eff6ff;
  color: #2563eb;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}

/* ===== 分页 ===== */
.ai-table-footer {
  padding: 14px 22px;
  border-top: 1px solid #f3f4f6;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.ai-pagination-controls { display: flex; align-items: center; gap: 8px; }

.ai-pagination-select {
  height: 30px;
  padding: 0 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  font-size: 12px;
  color: #4b5563;
  background: #fff;
  outline: none;
  cursor: pointer;
  font-family: inherit;
}

.ai-pagination-info {
  font-size: 13px;
  color: #6b7280;
  font-weight: 600;
  min-width: 60px;
  text-align: center;
}

.ai-filter-stat { font-size: 12px; color: #6b7280; font-weight: 600; }
.ai-filter-green { color: #047857; }
.ai-filter-red { color: #b91c1c; }

/* ===== 关键词覆盖率 ===== */
.ai-keyword-pct { font-weight: 600; font-size: 13px; }
.ai-keyword-pct.kw-full { color: #059669; }
.ai-keyword-pct.kw-partial { color: #ca8a04; }
.ai-keyword-pct.kw-none { color: #b91c1c; }

.kw-cell { white-space: nowrap; }

.kw-tag {
  display: inline-block;
  margin-left: 4px;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 10px;
  font-weight: 700;
  vertical-align: middle;
}

.kw-tag-truncated { background: #fef9c3; color: #a16207; }

.kw-tag-failed { background: #fee2e2; color: #b91c1c; }

/* ===== 关键词截断汇总 ===== */
.ai-kw-summary {
  padding: 10px 16px;
  background: #fef9c3;
  border: 1px solid #facc15;
  border-radius: 10px;
  font-size: 13px;
  color: #854d0e;
  line-height: 1.6;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 14px;
}

.ai-kw-summary-icon { flex-shrink: 0; }

/* ===== 状态筛选下拉 ===== */
.th-filter-wrap {
  position: relative;
  cursor: pointer;
  user-select: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.th-filter-icon {
  font-size: 10px;
  color: #9ca3af;
  transition: color 0.15s;
}
.th-filter-icon.active {
  color: #4f46e5;
}
.th-filter-dropdown {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.08);
  padding: 4px 0;
  min-width: 100px;
}
.th-filter-option {
  padding: 6px 14px;
  font-size: 13px;
  color: #374151;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.1s;
}
.th-filter-option:hover {
  background: #f3f4f6;
}
.th-filter-option.selected {
  color: #4f46e5;
  font-weight: 600;
  background: #eef2ff;
}

/* ===== 单篇重建进度条（操作列内联） ===== */
.post-reindex-inline {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 180px;
}
.post-reindex-stage {
  font-size: 12px;
  font-weight: 500;
  color: #4f46e5;
  white-space: nowrap;
}
.post-reindex-track {
  flex: 1;
  min-width: 60px;
  height: 4px;
  background: #e5e7eb;
  border-radius: 2px;
  overflow: hidden;
}
.post-reindex-fill {
  height: 100%;
  width: 0;
  background: linear-gradient(90deg, #818cf8, #4f46e5);
  border-radius: 2px;
  transition: width 0.4s ease;
}
.post-reindex-fill.is-done {
  background: #10b981;
}
.post-reindex-fill.is-error {
  background: #ef4444;
}
.post-reindex-pct {
  font-size: 11px;
  font-weight: 600;
  color: #4f46e5;
  white-space: nowrap;
  min-width: 30px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.post-reindex-ok {
  font-size: 12px;
  font-weight: 600;
  color: #10b981;
  white-space: nowrap;
}
.post-reindex-err {
  font-size: 12px;
  font-weight: 600;
  color: #ef4444;
  white-space: nowrap;
}

/* ===== 响应式 ===== */
@media (max-width: 760px) {
  .ai-batch-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .ai-search-box {
    width: 100%;
  }

  .ai-batch-actions {
    justify-content: flex-end;
  }

  .ai-table-wrap {
    overflow: visible;
  }

  .ai-table {
    display: block;
    min-width: 0;
    width: 100%;
  }

  .ai-table thead {
    display: none;
  }

  .ai-table tbody,
  .ai-table tr,
  .ai-table td {
    display: block;
    width: 100%;
  }

  .ai-table tbody {
    padding: 10px;
    background: #f8fafc;
  }

  .ai-table tbody tr {
    margin-bottom: 10px;
    border: 1px solid #e5e7eb;
    border-radius: 8px;
    background: #fff;
    overflow: hidden;
  }

  .ai-table tbody tr:hover {
    background: #fff;
  }

  .ai-table td {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 14px;
    min-height: 38px;
    padding: 10px 12px;
    border-bottom: 1px solid #f1f5f9;
    text-align: right;
  }

  .ai-table td::before {
    content: attr(data-label);
    flex: 0 0 72px;
    color: #64748b;
    font-size: 12px;
    font-weight: 650;
    text-align: left;
  }

  .ai-table tbody tr:last-child td,
  .ai-table td:last-child {
    border-bottom: 0;
  }

  .article-title-cell {
    display: block !important;
    padding: 13px 12px !important;
    text-align: left !important;
    line-height: 1.55;
    word-break: break-word;
  }

  .article-title-cell::before {
    display: none;
  }

  .ai-cell-center,
  .ai-cell-date,
  .kw-cell {
    text-align: right;
    white-space: normal;
  }

  .kw-cell {
    justify-content: space-between;
  }

  .actions-cell {
    display: flex !important;
    align-items: center !important;
    justify-content: flex-end !important;
    flex-wrap: wrap;
    white-space: normal;
  }

  .actions-cell::before {
    margin-right: auto;
  }

  .actions-cell > * + * {
    margin-left: 0;
  }

  .actions-cell > * {
    margin-left: 6px;
    margin-bottom: 6px;
  }

  .post-reindex-inline {
    min-width: 0;
    width: min(220px, 100%);
  }

  .preview-row {
    border: 0 !important;
    background: transparent !important;
  }

  .preview-row td {
    display: block !important;
    padding: 0 !important;
    border: 0 !important;
  }

  .preview-row td::before {
    display: none;
  }

  .chunk-preview {
    max-height: 360px;
    padding: 12px;
  }

  .chunk-item {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .ai-table-footer {
    align-items: stretch;
    flex-direction: column;
    padding: 12px;
  }

  .ai-pagination-controls {
    justify-content: flex-end;
    flex-wrap: wrap;
  }
}
</style>
