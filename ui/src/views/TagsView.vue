<template>
  <div class="ai-content tag-page">
    <div class="tag-top-grid">
      <SectionCard title="标签生成设置" :icon-component="RiPriceTag3Line" headerTitle="AI 标签生成" headerDesc="根据已保存的文章正文推荐标签，优先复用站内已有标签">
        <div class="ai-card-body tag-settings">
          <p class="tag-model-note">{{ tagModelDescription }}</p>
          <details class="tag-model-settings">
            <summary>模型设置（可选）</summary>
            <div class="tag-model-fields">
              <label for="tag-model">标签模型</label>
              <select id="tag-model" v-model="tagModelDraft" class="ai-input ai-select" :disabled="!optionsAvailable || savingModel">
                <option value="">复用内容创作默认模型</option>
                <option v-for="option in modelOptions" :key="option.name" :value="option.name">{{ displayName(option.name) }}</option>
                <option v-if="tagModelDraft && !modelOptions.some(option => option.name === tagModelDraft)" :value="tagModelDraft">当前配置：{{ tagModelDraft }}</option>
              </select>
              <span class="tag-muted">单独指定只影响 AI 标签生成。</span>
              <span v-if="!optionsAvailable" class="tag-muted">模型列表暂不可用，请刷新页面后重试。</span>
              <VButton type="primary" size="sm" :disabled="!optionsAvailable || savingModel" @click="saveTagModel">{{ savingModel ? '保存中...' : '保存模型' }}</VButton>
            </div>
          </details>
          <label class="tag-count">
            每篇推荐
            <select v-model.number="count" class="ai-input ai-select">
              <option v-for="n in [3, 4, 5, 6, 7, 8]" :key="n" :value="n">{{ n }} 个</option>
            </select>
          </label>
        </div>
      </SectionCard>

      <SectionCard class="ai-guide-card" title="使用说明" :icon-component="RiLightbulbLine" headerTitle="如何使用" headerDesc="生成候选标签并确认后应用到文章">
        <div class="ai-card-body">
          <ul class="ai-tips-list">
            <li>先保存文章正文；草稿和已发布文章都可以在下方列表生成标签。</li>
            <li>选择「每篇推荐」数量并点击「生成标签」。生成的候选标签优先复用站内已有标签，此时不会修改文章。</li>
            <li>勾选需要的候选标签，点击「应用到文章」后才会写入；文章现有标签会保留，站内没有的标签会新建。</li>
            <li>如需换一组建议，可点击「重新生成」。候选结果只在当前页面暂存，刷新或切换文章后需重新生成。</li>
            <li>默认使用「模型配置」中的内容创作默认模型；如需单独指定，在左侧「模型设置（可选）」中选择并保存。</li>
          </ul>
        </div>
      </SectionCard>
    </div>

    <div class="ai-section-block">
      <div class="ai-section-heading">
        <h2>文章标签管理</h2>
        <span class="ai-section-tag">共 {{ total }} 篇</span>
      </div>
      <div class="ai-section-card">
        <div class="tag-toolbar">
          <input v-model="keyword" class="ai-input" placeholder="搜索文章标题..." :disabled="loading || busy" @keydown.enter="searchArticles" />
          <VButton type="default" :disabled="loading || busy" @click="searchArticles">搜索</VButton>
        </div>
        <div v-if="loading" class="tag-state">正在加载文章...</div>
        <div v-else-if="articles.length === 0" class="tag-state">没有找到文章</div>
        <div v-else class="tag-table-wrap">
          <table class="tag-table">
            <thead>
              <tr>
                <th>文章标题</th>
                <th>当前标签</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="article in articles" :key="article.postName">
                <tr>
                  <td class="tag-title-cell">{{ article.title || '无标题文章' }}</td>
                  <td>
                    <div class="tag-existing">
                      <span v-if="article.tags.length === 0" class="tag-muted">暂无标签</span>
                      <span v-for="tag in article.tags" :key="tag" class="tag-chip">{{ tag }}</span>
                    </div>
                  </td>
                  <td><span class="tag-status" :class="article.published ? 'published' : 'draft'">{{ article.published ? '已发布' : '草稿' }}</span></td>
                  <td class="tag-action-cell">
                    <div class="tag-action-wrap">
                      <VButton class="tag-generate-button" type="primary" size="xs"
                        :disabled="busy" @click="generate(article)">
                        {{ activePost === article.postName ? '重新生成' : '生成标签' }}
                      </VButton>
                      <span class="tag-inline-status" role="status" aria-live="polite">{{ generating === article.postName ? '生成中…' : '' }}</span>
                    </div>
                  </td>
                </tr>
                <tr v-if="activePost === article.postName" class="tag-suggestion-row">
                  <td colspan="4">
                    <div class="tag-suggestions">
                      <div class="tag-suggestion-head">
                        <strong>候选标签</strong>
                        <span class="tag-muted">勾选要添加的标签，文章现有标签会保留</span>
                      </div>
                      <div v-if="suggestions.length === 0" class="tag-state">未生成可用标签，请调整文章内容后重试。</div>
                      <div v-else class="tag-options">
                        <label v-for="item in suggestions" :key="item.name" class="tag-option">
                          <input v-model="selected" type="checkbox" :value="item.name"
                            :disabled="busy || hasTag(article, item.name)" />
                          <span>{{ item.name }}</span>
                          <small :class="item.existing ? 'tag-reused' : 'tag-new'">
                            {{ hasTag(article, item.name) ? '已添加' : item.existing ? '已有' : '新建' }}
                          </small>
                        </label>
                      </div>
                      <div class="tag-actions">
                        <span class="tag-muted">已选 {{ selected.length }} 个</span>
                        <VButton type="default" size="sm" :disabled="busy" @click="closeSuggestions">取消</VButton>
                        <VButton class="tag-apply-button" type="primary" size="sm"
                          :disabled="selected.length === 0 || busy" @click="apply(article)">应用到文章</VButton>
                        <span class="tag-inline-status" role="status" aria-live="polite">{{ applying === article.postName ? '应用中…' : '' }}</span>
                      </div>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
        <div class="tag-table-footer">
          <span class="tag-footer-total">共 {{ total }} 篇文章</span>
          <div class="tag-pagination">
            <select v-model.number="pageSize" class="tag-pagination-select" aria-label="每页文章数" :disabled="loading || busy" @change="loadArticles(1)">
              <option v-for="size in [10, 20, 50]" :key="size" :value="size">{{ size }} 条/页</option>
            </select>
            <VButton type="default" size="xs" :disabled="page <= 1 || loading || busy" aria-label="上一页" @click="loadArticles(page - 1)">‹</VButton>
            <span class="tag-pagination-info">{{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }}</span>
            <VButton type="default" size="xs" :disabled="page * pageSize >= total || loading || busy" aria-label="下一页" @click="loadArticles(page + 1)">›</VButton>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Toast, VButton } from "@halo-dev/components";
import SectionCard from "../components/SectionCard.vue";
import RiLightbulbLine from "~icons/ri/lightbulb-line";
import RiPriceTag3Line from "~icons/ri/price-tag-3-line";
import { useContentModel } from "../composables/useContentModel";

const API_BASE = "/apis/console.api.ai-suite.halo.run/v1alpha1/tags";
const {
  load: loadContentModel, tagModel, tagModelDescription,
  options: modelOptions, optionsAvailable, displayName, saveOverride,
} = useContentModel();
const tagModelDraft = ref("");
const savingModel = ref(false);

async function saveTagModel() {
  savingModel.value = true;
  try {
    await saveOverride("tag", tagModelDraft.value);
    Toast.success("标签模型已保存");
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "保存失败");
  } finally {
    savingModel.value = false;
  }
}

async function loadTagModel() {
  await loadContentModel();
  tagModelDraft.value = tagModel.value;
}

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
const pageSize = ref(10);
const keyword = ref("");
const count = ref(5);
const loading = ref(false);
const generating = ref("");
const applying = ref("");
const busy = computed(() => Boolean(generating.value || applying.value));
const activePost = ref("");
const suggestions = ref<SuggestedTag[]>([]);
const selected = ref<string[]>([]);

async function readResponse(response: Response) {
  const contentType = response.headers.get("content-type") || "";
  if (!contentType.includes("application/json")) {
    throw new Error(response.redirected
      ? "登录已失效，请重新登录后重试"
      : "Halo 服务暂不可用，请刷新页面后重试");
  }
  let body;
  try {
    body = await response.json();
  } catch {
    throw new Error("Halo 返回的数据格式异常，请刷新页面后重试");
  }
  if (!response.ok) throw new Error(body.message || "请求失败");
  return body;
}

async function requestJson(url: string, init?: RequestInit) {
  try {
    return await readResponse(await fetch(url, init));
  } catch (error) {
    if (error instanceof TypeError) {
      throw new Error("无法连接 Halo，请确认服务正在运行后重试");
    }
    throw error;
  }
}

function hasTag(article: Article, name: string) {
  return article.tags.some(tag => tag.toLocaleLowerCase() === name.toLocaleLowerCase());
}

async function loadArticles(targetPage = page.value) {
  if (busy.value) return;
  loading.value = true;
  try {
    const params = new URLSearchParams({
      page: String(targetPage), size: String(pageSize.value), keyword: keyword.value.trim(),
    });
    const body = await requestJson(`${API_BASE}/articles?${params}`);
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

async function searchArticles() {
  if (busy.value) return;
  await loadArticles(1);
}

async function generate(article: Article) {
  if (busy.value) return;
  const previousPost = activePost.value;
  const previousSuggestions = suggestions.value;
  const previousSelection = [...selected.value];
  generating.value = article.postName;
  activePost.value = "";
  suggestions.value = [];
  selected.value = [];
  try {
    const body = await requestJson(`${API_BASE}/generate`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ postName: article.postName, count: count.value }),
    });
    article.tags = body.currentTags || article.tags;
    suggestions.value = body.tags || [];
    selected.value = suggestions.value
      .filter(item => !hasTag(article, item.name))
      .map(item => item.name);
    activePost.value = article.postName;
    if (suggestions.value.length === 0) Toast.warning("未生成可用标签");
  } catch (error) {
    activePost.value = previousPost;
    suggestions.value = previousSuggestions;
    selected.value = previousSelection;
    Toast.error(error instanceof Error ? error.message : "生成标签失败");
  } finally {
    generating.value = "";
  }
}

async function apply(article: Article) {
  if (busy.value || selected.value.length === 0) return;
  applying.value = article.postName;
  try {
    const body = await requestJson(`${API_BASE}/apply`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ postName: article.postName, tags: selected.value }),
    });
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

onMounted(async () => {
  await loadArticles(1);
  loadTagModel();
});
</script>

<style scoped>
.tag-page { display: flex; flex-direction: column; gap: 24px; }
.tag-top-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; align-items: stretch; }
.tag-settings { display: flex; flex-direction: column; gap: 14px; }
.tag-model-note { margin: 0; color: #6b7280; font-size: 13px; }
.tag-model-settings { margin-top: 12px; border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px 12px; }
.tag-model-settings summary { cursor: pointer; font-size: 13px; font-weight: 600; }
.tag-model-fields { display: grid; gap: 8px; margin-top: 12px; }
.tag-model-fields select { width: 100%; }
.tag-model-fields button { justify-self: end; }
.tag-count { display: flex; justify-content: flex-end; align-items: center; gap: 8px; white-space: nowrap; font-size: 13px; }
.tag-count select { min-width: 90px; }
.tag-toolbar { display: flex; gap: 8px; padding: 10px 16px; border-bottom: 1px solid #e5e7eb; background: #f9fafb; }
.tag-toolbar input { max-width: 320px; }
.tag-table-wrap { overflow-x: auto; -webkit-overflow-scrolling: touch; }
.tag-table { width: 100%; min-width: 650px; border-collapse: collapse; font-size: 14px; }
.tag-table th { padding: 12px 16px; text-align: left; background: #f9fafb; color: #4b5563; font-size: 12px; font-weight: 600; border-bottom: 1px solid #e5e7eb; }
.tag-table td { padding: 14px 16px; color: #111827; border-bottom: 1px solid #f3f4f6; vertical-align: middle; }
.tag-table tbody tr:not(.tag-suggestion-row):hover { background: #fafbfc; }
.tag-table tbody tr:last-child td { border-bottom: 0; }
.tag-title-cell { font-weight: 600; min-width: 180px; }
.tag-action-cell { width: 180px; white-space: nowrap; }
.tag-action-wrap { display: flex; align-items: center; gap: 8px; }
.tag-generate-button { width: 88px; flex: none; justify-content: center; }
.tag-apply-button { width: 104px; flex: none; justify-content: center; }
.tag-inline-status { display: inline-block; width: 60px; flex: none; color: #6b7280; font-size: 12px; }
.tag-existing, .tag-options, .tag-actions, .tag-suggestion-head { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.tag-status { display: inline-flex; padding: 4px 10px; border-radius: 999px; font-size: 12px; font-weight: 700; white-space: nowrap; }
.tag-status.published { background: #ecfdf5; color: #047857; }
.tag-status.draft { background: #f3f4f6; color: #4b5563; }
.tag-muted { color: #6b7280; font-size: 12px; }
.tag-chip { padding: 3px 8px; background: #f3f4f6; color: #4b5563; border-radius: 12px; font-size: 12px; }
.tag-suggestion-row td { padding: 0 16px 16px; background: #fff; }
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
.tag-table-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 22px; border-top: 1px solid #f3f4f6; }
.tag-footer-total { color: #6b7280; font-size: 12px; }
.tag-pagination { display: flex; align-items: center; gap: 8px; }
.tag-pagination-select { height: 30px; padding: 0 8px; border: 1px solid #e5e7eb; border-radius: 8px; color: #4b5563; background: #fff; font: inherit; font-size: 12px; }
.tag-pagination-info { min-width: 60px; text-align: center; color: #6b7280; font-size: 13px; font-weight: 600; }
@media (max-width: 1024px) { .tag-top-grid { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .tag-toolbar input { width: 100%; } .tag-table-footer { flex-wrap: wrap; } .tag-pagination { margin-left: auto; } }
</style>
