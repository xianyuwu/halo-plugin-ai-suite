<template>
  <div class="excerpt-page">
    <div class="ai-content">
      <div class="ai-excerpt-cols">
        <SectionCard title="自动摘要设置" :icon-component="RiFileTextLine" headerTitle="AI 自动摘要" headerDesc="启用后，在文章编辑页勾选「自动生成摘要」并保存时，将由 AI 自动生成摘要内容">
          <div class="ai-card-body">
            <div class="ai-toggle-row">
              <label class="ai-switch-label">
                <span class="ai-switch-track" :class="{ off: !excerptEnabled }" @click="toggleExcerpt"></span>
                <span>{{ excerptEnabled ? 'AI 自动摘要已启用' : '启用 AI 自动摘要' }}</span>
              </label>
              <span class="ai-toggle-desc">文章编辑页「自动生成摘要」勾选后，保存时 AI 自动生成并写入 excerpt 字段</span>
            </div>

            <div class="ai-config-grid">
              <div class="ai-config-item">
                <label class="ai-config-label">摘要最大长度（字）</label>
                <input
                  type="number"
                  class="ai-config-input"
                  v-model.number="excerptMaxLength"
                  min="50"
                  max="500"
                  step="10"
                  :disabled="savingConfig"
                  @change="saveExcerptConfig"
                />
                <span class="ai-config-hint">建议 120-200 字，传给 LLM 的目标长度约束</span>
              </div>
              <div class="ai-config-item">
                <label class="ai-config-label">输入内容最大字符数</label>
                <input
                  type="number"
                  class="ai-config-input"
                  v-model.number="excerptMaxInputLength"
                  min="500"
                  max="20000"
                  step="500"
                  :disabled="savingConfig"
                  @change="saveExcerptConfig"
                />
                <span class="ai-config-hint">超出部分会被截断，避免长文耗尽 LLM token</span>
              </div>
            </div>
          </div>
        </SectionCard>

        <SectionCard title="使用说明" :icon-component="RiLightbulbLine" headerTitle="如何使用" headerDesc="AI 摘要功能的使用方式和注意事项">
          <div class="ai-card-body">
            <ul class="ai-tips-list">
              <li>启用后前往任意文章编辑页，在右侧「摘要」区域勾选「自动生成摘要」</li>
              <li>保存文章时 AI 将自动生成摘要并写入 excerpt 字段，长度由「摘要最大长度」控制</li>
              <li>批量生成仅处理尚未生成摘要的已发布文章</li>
              <li>摘要可用于搜索引擎 SEO description 和社交媒体分享卡片</li>
            </ul>
          </div>
        </SectionCard>
      </div>

      <!-- 文章摘要管理列表 -->
      <div class="ai-section-block">
        <div class="ai-section-heading">
          <h2>文章摘要管理</h2>
          <span class="ai-section-tag">共 {{ articleTotal }} 篇</span>
        </div>
        <article class="ai-section-card">
          <div class="ai-card-body" style="padding: 0;">
            <div v-if="loadingArticles" class="ai-loading">加载中...</div>
            <div v-else class="ai-table-wrap">
              <div class="ai-batch-toolbar">
                <label class="ai-checkbox-all">
                  <input type="checkbox" :checked="allChecked" @change="toggleAll" />
                  <span>{{ selectedCount > 0 ? `已选 ${selectedCount} 篇` : '全选' }}</span>
                </label>
                <div class="ai-search-bar">
                  <span class="ai-search-icon"><RiSearchLine /></span>
                  <input
                    v-model="searchQuery"
                    class="ai-search-input"
                    type="text"
                    placeholder="搜索文章标题..."
                  />
                <span v-if="searchQuery" class="ai-search-count">
                  {{ articleTotal }} / {{ articleList.length }}
                </span>
                </div>
                <div class="ai-batch-actions">
                  <VButton type="default" size="xs" :disabled="selectedCount === 0 || batchRunning" @click="batchGenerate">{{ batchRunning ? '处理中...' : '批量生成' }}</VButton>
                  <VButton type="default" size="xs" :disabled="selectedCount === 0 || batchRunning" @click="batchClear">批量取消</VButton>
                </div>
              </div>
              <table class="ai-table">
                <thead>
                  <tr>
                    <th style="width: 40px"></th>
                    <th>文章标题</th>
                    <th style="width: 100px">
                      <a class="ai-sort-link" @click="toggleSort">发布时间 {{ sortIcon }}</a>
                    </th>
                    <th style="width: 70px;">状态</th>
                    <th style="width: 150px">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <template v-for="item in articleList" :key="item.postName">
                    <tr>
                      <td class="ai-cell-check" data-label="选择">
                        <input type="checkbox" :value="item.postName" v-model="selected" />
                      </td>
                      <td class="article-title-cell" data-label="文章标题">
                        <div class="article-title">{{ item.title }}</div>
                        <div v-if="item.hasExcerpt" class="article-excerpt-preview">{{ item.excerpt }}</div>
                      </td>
                      <td class="ai-cell-date" data-label="发布时间">{{ formatDate(item.publishTime) }}</td>
                      <td class="status-cell" data-label="状态">
                        <span class="ai-status-badge" :class="excerptStatusClass(item)">{{ excerptStatusLabel(item) }}</span>
                      </td>
                      <td class="actions-cell-td" data-label="操作">
                        <div class="actions-cell">
                        <VButton v-if="!item.hasExcerpt" type="primary" size="xs" :loading="generating === item.postName" @click="generateOne(item.postName)">
                          {{ generating === item.postName ? '生成中...' : '生成' }}
                        </VButton>
                        <VButton v-if="item.hasExcerpt" type="primary" size="xs" :loading="generating === item.postName" @click="generateOne(item.postName)">
                          {{ generating === item.postName ? '生成中...' : '重生成' }}
                        </VButton>
                        <VButton v-if="item.hasExcerpt" type="default" size="xs" @click="clearExcerpt(item.postName)">
                          清空
                        </VButton>
                      </div>
                      </td>
                    </tr>
                  </template>
                </tbody>
              </table>
            </div>
            <div class="ai-table-footer">
              <span class="ai-filter-stat">
                <span v-if="selectedCount > 0" style="color: #111827; margin-right: 12px;">已选 {{ selectedCount }} 篇</span>
                <a class="ai-footer-link" :class="{ disabled: batchRunning }" @click="generateAll">全部生成</a>
                <span style="color: #d1d5db; margin: 0 4px;">·</span>
                <a class="ai-footer-link" :class="{ disabled: batchRunning }" @click="clearAll">全部取消</a>
                <span style="margin-left: 12px;" class="ai-filter-green">{{ statsText }}</span>
              </span>
              <div class="ai-pagination-controls">
                <select class="ai-pagination-select" v-model.number="pageSize" @change="loadArticles(1)">
                  <option v-for="s in pageSizeOptions" :key="s" :value="s">{{ s }} 条/页</option>
                </select>
                <VButton type="default" size="xs" :disabled="currentPage <= 1" @click="loadArticles(currentPage - 1)">‹</VButton>
                <span class="ai-pagination-info">{{ currentPage }} / {{ Math.max(1, totalPages) }}</span>
                <VButton type="default" size="xs" :disabled="currentPage >= totalPages" @click="loadArticles(currentPage + 1)">›</VButton>
              </div>
            </div>
          </div>
        </article>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from "vue";
import { Toast , VButton} from "@halo-dev/components";
import SectionCard from "../components/SectionCard.vue";
import RiFileTextLine from "~icons/ri/file-text-line";
import RiLightbulbLine from "~icons/ri/lightbulb-line";
import RiSearchLine from "~icons/ri/search-line";

const API_BASE = "/apis/console.api.ai-suite.halo.run/v1alpha1";
const CONFIG_API = `${API_BASE}/config`;

const excerptEnabled = ref(false);
const excerptMaxLength = ref(160);
const excerptMaxInputLength = ref(3000);
const savingConfig = ref(false);
const generating = ref<string | null>(null);
const loadingArticles = ref(false);
const selected = ref<string[]>([]);
const batchRunning = ref(false);

interface ArticleItem {
  postName: string;
  title: string;
  excerpt: string;
  hasExcerpt: boolean;
  autoGenerate: boolean;
  publishTime: string;
}
const articleList = ref<ArticleItem[]>([]);
const articleTotal = ref(0);
const searchQuery = ref("");
const currentPage = ref(1);
const pageSize = ref(10);
const pageSizeOptions = [10, 20, 50];

const totalPages = computed(() => Math.max(1, Math.ceil(articleTotal.value / pageSize.value)));


function excerptStatusClass(item: ArticleItem) {
  return item.hasExcerpt ? "status-indexed" : "status-not-indexed";
}

function excerptStatusLabel(item: ArticleItem) {
  return item.hasExcerpt ? "已生成" : "未生成";
}

function formatDate(t: string) {
  if (!t) return "-";
  const d = new Date(t);
  if (isNaN(d.getTime())) return t.substring(0, 10);
  return d.toLocaleDateString("zh-CN", { year: "numeric", month: "2-digit", day: "2-digit" });
}

// 排序
const sortOrder = ref<"desc" | "asc">("desc");
const sortIcon = computed(() => sortOrder.value === "desc" ? "↓" : "↑");

function toggleSort() {
  sortOrder.value = sortOrder.value === "desc" ? "asc" : "desc";
  loadArticles(1);
}

async function loadArticles(page?: number) {
  if (page) currentPage.value = page;
  loadingArticles.value = true;
  try {
    const kw = encodeURIComponent(searchQuery.value.trim());
    const resp = await fetch(`${API_BASE}/knowledge/excerpts/all?page=${currentPage.value}&size=${pageSize.value}&sort=${sortOrder.value}&keyword=${kw}`);
    if (resp.ok) {
      const data = await resp.json();
      articleList.value = data.items || [];
      articleTotal.value = data.total || 0;
    }
  } catch {} finally {
    loadingArticles.value = false;
  }
}

// 搜索框输入防抖 300ms — 避免每次按键都打后端
let searchTimer: number | undefined;
watch(searchQuery, () => {
  if (searchTimer) window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => loadArticles(1), 300);
});

// 卸载时清理防抖 timer, 否则用户输入后立即切走页面, 300ms 后仍会 fetch 并写已卸载组件状态
onUnmounted(() => {
  if (searchTimer) window.clearTimeout(searchTimer);
});

async function loadExcerptConfig() {
  try {
    const resp = await fetch(CONFIG_API);
    if (resp.ok) {
      const data = await resp.json();
      const ex = data.excerpt || {};
      excerptEnabled.value = !!ex.enabled;
      if (Number.isFinite(ex.maxLength) && ex.maxLength > 0) {
        excerptMaxLength.value = ex.maxLength;
      }
      if (Number.isFinite(ex.maxInputLength) && ex.maxInputLength > 0) {
        excerptMaxInputLength.value = ex.maxInputLength;
      }
    }
  } catch {}
}

function clampInt(val: number, min: number, max: number, fallback: number) {
  if (!Number.isFinite(val)) return fallback;
  const v = Math.round(val);
  if (v < min) return min;
  if (v > max) return max;
  return v;
}

async function persistExcerptConfig(): Promise<boolean> {
  savingConfig.value = true;
  const payload = {
    enabled: excerptEnabled.value,
    maxLength: clampInt(excerptMaxLength.value, 50, 500, 160),
    maxInputLength: clampInt(excerptMaxInputLength.value, 500, 20000, 3000),
  };
  // 把限幅后的值同步回输入框（避免用户输入超界值后保存被偷偷改）
  excerptMaxLength.value = payload.maxLength;
  excerptMaxInputLength.value = payload.maxInputLength;
  try {
    const resp = await fetch(`${CONFIG_API}/save`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ excerpt: payload }),
    });
    return resp.ok;
  } catch {
    return false;
  } finally {
    savingConfig.value = false;
  }
}

async function toggleExcerpt() {
  const previous = excerptEnabled.value;
  excerptEnabled.value = !previous;
  const ok = await persistExcerptConfig();
  if (ok) {
    Toast.success(excerptEnabled.value ? "AI 自动摘要已启用" : "AI 自动摘要已关闭");
  } else {
    excerptEnabled.value = previous;
    Toast.error("设置失败");
  }
}

async function saveExcerptConfig() {
  const ok = await persistExcerptConfig();
  if (ok) {
    Toast.success("摘要配置已保存");
  } else {
    Toast.error("保存失败");
  }
}

async function generateOne(postName: string) {
  generating.value = postName;
  try {
    const resp = await fetch(`${API_BASE}/knowledge/excerpts/generate`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ postName }),
    });
    const data = await resp.json();
    if (data.success) {
      Toast.success("摘要已生成并保存");
      loadArticles(currentPage.value);
    } else { Toast.error(data.message || "生成失败"); }
  } catch (e) { Toast.error("请求失败: " + (e as Error).message); }
  finally { generating.value = null; }
}


async function clearExcerpt(postName: string) {
  try {
    const resp = await fetch(`${API_BASE}/knowledge/excerpts/clear`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ postName }),
    });
    const data = await resp.json();
    if (data.success) {
      Toast.success("已关闭摘要");
      loadArticles(currentPage.value);
    } else { Toast.error(data.message || "操作失败"); }
  } catch (e) { Toast.error("请求失败: " + (e as Error).message); }
}

// ===== 批量操作 =====

async function batchGenerate() {
  if (selected.value.length === 0) return;
  batchRunning.value = true;
  try {
    const resp = await fetch(`${API_BASE}/knowledge/excerpts/batch-generate`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ postNames: selected.value }),
    });
    const data = await resp.json();
    if (data.success) { Toast.success(`已为 ${data.count} 篇文章生成摘要`); selected.value = []; loadArticles(currentPage.value); }
    else { Toast.error(data.message || "操作失败"); }
  } catch (e) { Toast.error("请求失败: " + (e as Error).message); }
  finally { batchRunning.value = false; }
}

async function batchClear() {
  if (selected.value.length === 0) return;
  batchRunning.value = true;
  try {
    const resp = await fetch(`${API_BASE}/knowledge/excerpts/batch-clear`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ postNames: selected.value }),
    });
    const data = await resp.json();
    if (data.success) { Toast.success(`已清空 ${data.count} 篇文章摘要`); selected.value = []; loadArticles(currentPage.value); }
    else { Toast.error(data.message || "操作失败"); }
  } catch (e) { Toast.error("请求失败: " + (e as Error).message); }
  finally { batchRunning.value = false; }
}

async function generateAll() {
  batchRunning.value = true;
  try {
    const resp = await fetch(`${API_BASE}/knowledge/summarize-all`, { method: "POST" });
    const data = await resp.json();
    if (data.success) { Toast.success(`已为 ${data.count} 篇文章生成摘要`); loadArticles(1); }
    else { Toast.error(data.message || "批量生成失败"); }
  } catch (e) { Toast.error("请求失败: " + (e as Error).message); }
  finally { batchRunning.value = false; }
}

async function clearAll() {
  batchRunning.value = true;
  try {
    const resp = await fetch(`${API_BASE}/knowledge/excerpts/clear-all`, { method: "POST" });
    const data = await resp.json();
    if (data.success) { Toast.success(`已清空 ${data.count} 篇文章摘要`); loadArticles(1); }
    else { Toast.error(data.message || "操作失败"); }
  } catch (e) { Toast.error("请求失败: " + (e as Error).message); }
  finally { batchRunning.value = false; }
}

onMounted(() => { loadArticles(); loadExcerptConfig(); });
</script>

<style scoped>
/* 搜索框 — 表格上方（嵌在批量工具栏内，工具栏"全选"右面）*/
.ai-batch-toolbar {
  /* 已在 pages.css 定义 justify-content: space-between — 3 子元素时
     搜索框被推到中间。这里改 flex-start 让 3 个元素紧贴左边排，
     批量按钮靠 margin-left: auto 推到最右 */
  justify-content: flex-start;
  gap: 12px;
}
.ai-batch-actions {
  margin-left: auto;
}
.ai-search-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  max-width: 320px;
  padding: 4px 10px;
  background: var(--ai-color-bg-page);
  border: 1px solid var(--ai-color-border);
  border-radius: var(--ai-radius-sm);
}
.ai-search-icon {
  display: inline-flex;
  align-items: center;
  color: var(--ai-color-fg-subtle);
  flex-shrink: 0;
}
.ai-search-input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  font-size: 13px;
  color: var(--ai-color-fg);
  font-family: inherit;
}
.ai-search-input::placeholder { color: var(--ai-color-fg-disabled); }
.ai-search-count {
  font-size: 12px;
  color: var(--ai-color-fg-muted);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

.excerpt-page {
  min-height: 100%;
  background: #f5f7fb;
}

.ai-excerpt-cols {
  display: flex;
  gap: 24px;
  margin-bottom: 24px;
  align-items: stretch;
}

.ai-excerpt-cols > :deep(.ai-section-block) {
  flex: 1;
  min-width: 0;
}

/* 使用说明卡片——纯提示性质，底色区别于功能卡片 */
.ai-excerpt-cols > :deep(.ai-section-block:last-child) .ai-section-card {
  background: #f8fafc;
  border-color: #e2e8f0;
  box-shadow: none;
}

.ai-toggle-row {
  padding: 14px 16px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  margin-bottom: 14px;
}

.ai-switch-label {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  font-weight: 600;
  color: #111827;
  cursor: pointer;
  user-select: none;
}

.ai-toggle-desc {
  display: block;
  margin-top: 8px;
  font-size: 12px;
  color: #8a94a6;
  line-height: 1.5;
}

.ai-config-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin-top: 4px;
}

.ai-config-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
}

.ai-config-label {
  font-size: 12px;
  font-weight: 600;
  color: #374151;
}

.ai-config-input {
  height: 34px;
  padding: 0 10px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 14px;
  color: #111827;
  background: #fff;
  outline: none;
  font-family: inherit;
  transition: border-color 0.15s;
}

.ai-config-input:focus {
  border-color: #4f46e5;
  box-shadow: 0 0 0 2px rgba(79, 70, 229, 0.1);
}

.ai-config-input:disabled {
  background: #f3f4f6;
  color: #9ca3af;
  cursor: not-allowed;
}

.ai-config-hint {
  font-size: 11px;
  color: #8a94a6;
  line-height: 1.4;
}

@media (max-width: 640px) {
  .ai-config-grid { grid-template-columns: 1fr; }
}

.ai-switch-track {
  width: 38px;
  height: 22px;
  border-radius: 999px;
  background: #111827;
  position: relative;
  flex-shrink: 0;
  transition: background 0.2s;
}

.ai-switch-track::after {
  content: "";
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  position: absolute;
  top: 3px;
  right: 3px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.18);
  transition: transform 0.2s;
}

.ai-switch-track.off {
  background: #d1d5db;
}

.ai-switch-track.off::after {
  right: auto;
  left: 3px;
}

/* 文章管理表格 */
.ai-table-wrap {
  overflow-x: auto;
  overflow-y: hidden;
  -webkit-overflow-scrolling: touch;
}

.ai-batch-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-bottom: 1px solid #e5e7eb;
  background: #f9fafb;
}

.ai-checkbox-all {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #4b5563;
  cursor: pointer;
  user-select: none;
}

.ai-checkbox-all input {
  cursor: pointer;
}

.ai-batch-actions {
  display: flex;
  gap: 6px;
  align-items: center;
}

.ai-cell-check {
  text-align: center;
  vertical-align: middle !important;
}

.ai-cell-check input {
  cursor: pointer;
}

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

.ai-table {
  width: 100%;
  min-width: 760px;
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

.ai-table td {
  padding: 12px 16px;
  border-bottom: 1px solid #f3f4f6;
  color: #111827;
  vertical-align: middle;
}

.ai-table td.article-title-cell {
  vertical-align: top;
  padding-top: 14px;
  padding-bottom: 14px;
}

.ai-table td.actions-cell-td {
  vertical-align: middle;
}

.ai-table tbody tr:last-child td { border-bottom: none; }
.ai-table tbody tr:hover { background: #fafbfc; }

.article-title-cell { font-weight: 600; color: #111827; }

.article-title {
  font-weight: 600;
  color: #111827;
  margin-bottom: 2px;
}

.article-excerpt-preview {
  margin-top: 6px;
  font-size: 12px;
  color: #8a94a6;
  line-height: 1.6;
  max-width: 600px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.actions-cell {
  display: flex;
  gap: 6px;
  align-items: center;
  white-space: nowrap;
}

.ai-status-badge {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.ai-status-badge.status-indexed { background: #ecfdf5; color: #047857; }
.ai-status-badge.status-not-indexed { background: #f3f4f6; color: #4b5563; }



.ai-loading,
.ai-loading-sm { padding: 24px 0; text-align: center; color: #8a94a6; font-size: 14px; }

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

.ai-footer-link {
  font-size: 12px;
  color: #6b7280;
  cursor: pointer;
  text-decoration: none;
}

.ai-footer-link:hover { color: #111827; }
.ai-footer-link.disabled { color: #d1d5db; cursor: not-allowed; pointer-events: none; }

.ai-tips-list {
  padding: 0;
  margin: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ai-tips-list li {
  font-size: 13px;
  color: #4b5563;
  line-height: 1.7;
  padding: 0;
}

@media (max-width: 1024px) {
  .ai-excerpt-cols {
    flex-direction: column;
  }
}

@media (max-width: 760px) {
  .ai-batch-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .ai-search-bar {
    max-width: none;
    width: 100%;
  }

  .ai-batch-actions {
    margin-left: 0;
    justify-content: flex-end;
    flex-wrap: wrap;
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
    align-items: center;
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

  .ai-table td:last-child {
    border-bottom: 0;
  }

  .article-title-cell {
    display: block !important;
    padding: 13px 12px !important;
    text-align: left !important;
  }

  .article-title-cell::before {
    display: none;
  }

  .article-title,
  .article-excerpt-preview {
    max-width: none;
    line-height: 1.55;
  }

  .article-excerpt-preview {
    -webkit-line-clamp: 3;
  }

  .ai-cell-check {
    justify-content: flex-start !important;
  }

  .ai-cell-check::before {
    flex: 0 0 auto !important;
    margin-right: 12px;
  }

  .status-cell,
  .ai-cell-date {
    text-align: right;
  }

  .actions-cell-td {
    align-items: flex-start !important;
  }

  .actions-cell {
    justify-content: flex-end;
    flex-wrap: wrap;
    white-space: normal;
  }

  .ai-table-footer {
    align-items: stretch;
    flex-direction: column;
    padding: 12px;
  }

  .ai-filter-stat {
    line-height: 1.8;
  }

  .ai-pagination-controls {
    justify-content: flex-end;
    flex-wrap: wrap;
  }
}

@media (max-width: 640px) {
  .ai-config-grid {
    grid-template-columns: 1fr;
  }

  .ai-toggle-row {
    padding: 12px;
  }
}
</style>
