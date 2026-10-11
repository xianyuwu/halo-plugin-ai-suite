<template>
  <div class="ai-content tag-page">
    <div class="tag-intro ai-section-card">
      <div>
        <h2>AI 标签生成</h2>
        <p>根据已保存的文章正文推荐标签，优先复用站内已有标签。生成结果需确认后才会应用到文章。</p>
      </div>
      <label class="tag-count">
        每篇推荐
        <select v-model.number="count" class="ai-input ai-select">
          <option v-for="n in [3, 4, 5, 6, 7, 8]" :key="n" :value="n">{{ n }} 个</option>
        </select>
      </label>
    </div>

    <div class="ai-section-block">
      <div class="ai-section-heading">
        <h2>文章标签管理</h2>
        <span class="ai-section-tag">共 {{ total }} 篇</span>
      </div>
      <div class="ai-section-card">
        <div class="tag-toolbar">
          <input v-model="keyword" class="ai-input" placeholder="搜索文章标题..." @keydown.enter="loadArticles(1)" />
          <VButton type="default" :disabled="loading" @click="loadArticles(1)">搜索</VButton>
        </div>
        <div v-if="loading" class="tag-state">正在加载文章...</div>
        <div v-else-if="articles.length === 0" class="tag-state">没有找到文章</div>
        <div v-else class="tag-list">
          <div v-for="article in articles" :key="article.postName" class="tag-article">
            <div class="tag-article-main">
              <div class="tag-title-row">
                <strong>{{ article.title || '无标题文章' }}</strong>
                <span class="tag-status">{{ article.published ? '已发布' : '草稿' }}</span>
              </div>
              <div class="tag-existing">
                <span v-if="article.tags.length === 0" class="tag-muted">暂无标签</span>
                <span v-for="tag in article.tags" :key="tag" class="tag-chip">{{ tag }}</span>
              </div>
            </div>
            <VButton type="primary" size="sm" :loading="generating === article.postName"
              :disabled="!!generating || !!applying" @click="generate(article)">
              {{ activePost === article.postName ? '重新生成' : '生成标签' }}
            </VButton>

            <div v-if="activePost === article.postName" class="tag-suggestions">
              <div class="tag-suggestion-head">
                <strong>候选标签</strong>
                <span class="tag-muted">勾选要添加的标签，文章现有标签会保留</span>
              </div>
              <div v-if="suggestions.length === 0" class="tag-state">未生成可用标签，请调整文章内容后重试。</div>
              <div v-else class="tag-options">
                <label v-for="item in suggestions" :key="item.name" class="tag-option">
                  <input v-model="selected" type="checkbox" :value="item.name" />
                  <span>{{ item.name }}</span>
                  <small :class="item.existing ? 'tag-reused' : 'tag-new'">{{ item.existing ? '已有' : '新建' }}</small>
                </label>
              </div>
              <div class="tag-actions">
                <span class="tag-muted">已选 {{ selected.length }} 个</span>
                <VButton type="default" size="sm" @click="closeSuggestions">取消</VButton>
                <VButton type="primary" size="sm" :loading="applying === article.postName"
                  :disabled="selected.length === 0 || !!applying" @click="apply(article)">应用到文章</VButton>
              </div>
            </div>
          </div>
        </div>
        <div class="tag-pagination">
          <span>第 {{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }} 页</span>
          <VButton type="default" size="xs" :disabled="page <= 1 || loading" @click="loadArticles(page - 1)">上一页</VButton>
          <VButton type="default" size="xs" :disabled="page * pageSize >= total || loading" @click="loadArticles(page + 1)">下一页</VButton>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Toast, VButton } from "@halo-dev/components";

const API_BASE = "/apis/console.api.ai-suite.halo.run/v1alpha1/tags";

interface Article {
  postName: string;
  title: string;
  tags: string[];
  published: boolean;
}

interface SuggestedTag {
  name: string;
  existing: boolean;
}

const articles = ref<Article[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = 10;
const keyword = ref("");
const count = ref(5);
const loading = ref(false);
const generating = ref("");
const applying = ref("");
const activePost = ref("");
const suggestions = ref<SuggestedTag[]>([]);
const selected = ref<string[]>([]);

async function readResponse(response: Response) {
  const body = await response.json();
  if (!response.ok) throw new Error(body.message || "请求失败");
  return body;
}

async function loadArticles(targetPage = page.value) {
  loading.value = true;
  try {
    const params = new URLSearchParams({
      page: String(targetPage), size: String(pageSize), keyword: keyword.value.trim(),
    });
    const body = await readResponse(await fetch(`${API_BASE}/articles?${params}`));
    articles.value = body.items || [];
    total.value = body.total || 0;
    page.value = targetPage;
    closeSuggestions();
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "加载文章失败");
  } finally {
    loading.value = false;
  }
}

async function generate(article: Article) {
  generating.value = article.postName;
  activePost.value = "";
  suggestions.value = [];
  selected.value = [];
  try {
    const body = await readResponse(await fetch(`${API_BASE}/generate`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ postName: article.postName, count: count.value }),
    }));
    suggestions.value = body.tags || [];
    selected.value = suggestions.value
      .filter(item => !article.tags.some(tag => tag.toLowerCase() === item.name.toLowerCase()))
      .map(item => item.name);
    activePost.value = article.postName;
    if (suggestions.value.length === 0) Toast.warning("未生成可用标签");
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "生成标签失败");
  } finally {
    generating.value = "";
  }
}

async function apply(article: Article) {
  applying.value = article.postName;
  try {
    const body = await readResponse(await fetch(`${API_BASE}/apply`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ postName: article.postName, tags: selected.value }),
    }));
    article.tags = body.tags || article.tags;
    Toast.success(`已为文章添加 ${body.added || 0} 个标签`);
    closeSuggestions();
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "应用标签失败");
  } finally {
    applying.value = "";
  }
}

function closeSuggestions() {
  activePost.value = "";
  suggestions.value = [];
  selected.value = [];
}

onMounted(() => loadArticles(1));
</script>

<style scoped>
.tag-page { display: flex; flex-direction: column; gap: 24px; }
.tag-intro { display: flex; justify-content: space-between; align-items: center; gap: 24px; padding: 24px; }
.tag-intro h2 { margin: 0 0 8px; font-size: 18px; font-weight: 600; }
.tag-intro p { margin: 0; color: #6b7280; font-size: 13px; }
.tag-count { display: flex; align-items: center; gap: 8px; white-space: nowrap; font-size: 13px; }
.tag-count select { min-width: 90px; }
.tag-toolbar { display: flex; gap: 8px; padding: 16px; border-bottom: 1px solid #e5e7eb; }
.tag-toolbar input { max-width: 320px; }
.tag-list { padding: 0 16px; }
.tag-article { display: flex; align-items: flex-start; gap: 16px; flex-wrap: wrap; padding: 18px 0; border-bottom: 1px solid #e5e7eb; }
.tag-article:last-child { border-bottom: 0; }
.tag-article-main { flex: 1; min-width: 200px; }
.tag-title-row, .tag-existing, .tag-options, .tag-actions, .tag-suggestion-head { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.tag-title-row strong { font-size: 14px; }
.tag-status, .tag-muted { color: #6b7280; font-size: 12px; }
.tag-existing { margin-top: 9px; }
.tag-chip { padding: 3px 8px; background: #f3f4f6; color: #4b5563; border-radius: 12px; font-size: 12px; }
.tag-suggestions { width: 100%; padding: 16px; border: 1px solid #ddd6fe; background: #faf9ff; border-radius: 8px; }
.tag-suggestion-head { margin-bottom: 12px; }
.tag-options { gap: 10px; }
.tag-option { display: inline-flex; align-items: center; gap: 7px; padding: 7px 10px; border: 1px solid #e5e7eb; background: white; border-radius: 7px; cursor: pointer; font-size: 13px; }
.tag-option input { accent-color: #6d28d9; }
.tag-option small { font-size: 11px; }
.tag-reused { color: #059669; }
.tag-new { color: #d97706; }
.tag-actions { justify-content: flex-end; margin-top: 16px; }
.tag-actions .tag-muted { margin-right: auto; }
.tag-state { padding: 28px; color: #6b7280; font-size: 13px; text-align: center; }
.tag-pagination { display: flex; justify-content: flex-end; align-items: center; gap: 8px; padding: 14px 16px; border-top: 1px solid #e5e7eb; color: #6b7280; font-size: 12px; }
@media (max-width: 640px) { .tag-intro { align-items: stretch; flex-direction: column; } .tag-toolbar input { width: 100%; } }
</style>
