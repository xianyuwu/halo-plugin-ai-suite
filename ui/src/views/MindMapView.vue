<template>
  <div class="mindmap-page">
    <div class="ai-content">
      <div class="mindmap-workbench">
        <div class="mindmap-config">
          <div class="ai-section-heading mindmap-config-heading">
            <h2><RiMindMap /> AI 脑图配置</h2>
            <div class="mindmap-heading-actions">
              <VButton type="default" :disabled="saving" @click="resetAllFields">恢复默认</VButton>
              <VButton type="primary" :disabled="saving || !themeColorValid" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
            </div>
          </div>

          <div class="mindmap-config-scroll">
            <div class="mindmap-settings-grid">
              <article class="ai-section-card mindmap-setting-card mindmap-setting-card--switch">
                <div class="mindmap-setting-head">
                  <span class="mindmap-setting-icon"><RiMindMap /></span>
                  <div>
                    <div class="mindmap-setting-title">功能接入</div>
                    <div class="mindmap-setting-desc">控制文章页正文顶部的 AI 脑图区块。</div>
                  </div>
                  <label class="ai-switch">
                    <input type="checkbox" v-model="form.enabled" />
                    <span class="ai-switch-slider" />
                  </label>
                </div>
                <div class="mindmap-setting-state" :class="{ enabled: form.enabled }">
                  {{ form.enabled ? '已启用' : '已关闭' }}
                </div>
              </article>

              <article class="ai-section-card mindmap-setting-card mindmap-theme-card">
                <div class="mindmap-setting-head">
                  <span class="mindmap-setting-icon"><RiPaletteLine /></span>
                  <div>
                    <div class="mindmap-setting-title">展示主题</div>
                    <div class="mindmap-setting-desc">单独控制脑图的深浅色和强调色。</div>
                  </div>
                </div>
                <div class="mindmap-theme-fields">
                  <div class="ai-form-field">
                    <label class="ai-field-label">深浅色模式</label>
                    <select class="ai-input ai-select" v-model="form.theme">
                      <option value="inherit">继承问答浮窗</option>
                      <option value="auto">自动适配博客</option>
                      <option value="system">跟随系统</option>
                      <option value="light">强制浅色</option>
                      <option value="dark">强制深色</option>
                    </select>
                    <div class="ai-helper-text">{{ themeModeHint }}</div>
                  </div>
                  <div class="ai-form-field">
                    <label class="ai-field-label">主题色</label>
                    <ThemeColorPicker
                      v-model="form.themeColor"
                      :effective-color="effectiveThemeColor"
                      :invalid="!themeColorValid"
                      allow-inherit
                    />
                    <div class="ai-helper-text" :class="{ error: !themeColorValid }">
                      {{ themeColorHint }}
                    </div>
                  </div>
                </div>
              </article>

              <article class="ai-section-card mindmap-setting-card mindmap-generation-card">
                <div class="mindmap-setting-head">
                  <span class="mindmap-setting-icon"><RiSettings3Line /></span>
                  <div>
                    <div class="mindmap-setting-title">生成参数</div>
                    <div class="mindmap-setting-desc">影响新生成和重生成的脑图内容。</div>
                  </div>
                </div>
                <div class="ai-disabled-note" v-if="!form.enabled">AI 脑图关闭后，文章页不会注入脑图区块；后台仍可管理已生成缓存。</div>
                <div class="ai-form-grid-2">
                  <div class="ai-form-field">
                    <label class="ai-field-label">生成温度</label>
                    <input class="ai-input" type="number" min="0" max="1" step="0.1" v-model.number="form.temperature" />
                    <div class="ai-helper-text">建议 0.2-0.4，数值越低越稳定。</div>
                  </div>
                  <div class="ai-form-field">
                    <label class="ai-field-label">最大输出 Token</label>
                    <input class="ai-input" type="number" min="512" max="8192" step="256" v-model.number="form.maxTokens" />
                    <div class="ai-helper-text">控制脑图 Markdown 的最大长度。</div>
                  </div>
                  <div class="ai-form-field">
                    <label class="ai-field-label">最大输入字符数</label>
                    <input class="ai-input" type="number" min="2000" max="50000" step="1000" v-model.number="form.maxInputLength" />
                    <div class="ai-helper-text">超长文章会按这个上限送入模型。</div>
                  </div>
                  <div class="ai-form-field">
                    <label class="ai-field-label">脑图层级</label>
                    <select class="ai-input ai-select" v-model.number="form.maxDepth">
                      <option :value="2">2 层：标题 / 章节</option>
                      <option :value="3">3 层：标题 / 章节 / 要点</option>
                      <option :value="4">4 层：标题 / 章节 / 要点 / 细节</option>
                    </select>
                    <div class="ai-helper-text">层级越深越细，也更容易拥挤。</div>
                  </div>

                  <div class="ai-form-field ai-prompt-field">
                    <label class="ai-field-label">自定义生成要求 <span class="ai-field-optional">可选</span></label>
                    <textarea
                      class="ai-input ai-textarea"
                      v-model="form.extraPrompt"
                      rows="4"
                      placeholder="例如：更偏向产品分析视角，突出问题、原因、方案和风险。"
                    ></textarea>
                    <div class="ai-helper-text">会追加到默认脑图提示词后。</div>
                  </div>
                </div>
              </article>
            </div>
          </div>
        </div>

      </div>

      <div class="ai-section-block mindmap-manager">
        <div class="ai-section-heading">
          <h2>文章脑图管理</h2>
          <span class="ai-section-tag">共 {{ articleTotal }} 篇</span>
        </div>
        <article class="ai-section-card">
          <div class="ai-card-body mindmap-table-card">
            <div class="mindmap-job-panel" :class="{ running: jobRunning, done: jobDone }">
              <div class="mindmap-job-top">
                <div>
                  <div class="mindmap-job-title">{{ jobRunning ? '正在生成 AI 脑图' : jobDone ? '脑图生成任务完成' : '一键生成脑图' }}</div>
                  <div class="mindmap-job-desc">
                    {{ jobRunning ? jobPhaseText : jobDone ? jobSummaryText : '适合首次初始化，或文章/生成配置变更后批量补齐。' }}
                  </div>
                </div>
                <div class="mindmap-job-controls">
                  <select class="mindmap-job-scope" v-model="jobScope" :disabled="jobRunning">
                    <option value="missing_stale">未生成 + 已过期</option>
                    <option value="missing">仅未生成</option>
                    <option value="all">全部重新生成</option>
                  </select>
                  <template v-if="!jobRunning">
                    <VButton size="xs" type="primary" @click="jobConfirming ? startGenerateAllJob() : requestGenerateAllJob()">
                      {{ jobConfirming ? '确认开始' : '开始生成' }}
                    </VButton>
                    <VButton v-if="jobConfirming" size="xs" type="default" @click="jobConfirming = false">取消</VButton>
                  </template>
                  <VButton v-else size="xs" type="default" @click="cancelGenerateJob">取消任务</VButton>
                </div>
              </div>

              <div v-if="jobConfirming && !jobRunning" class="mindmap-job-warning">
                将开始生成「{{ jobScopeLabel }}」范围内的文章脑图，可能调用模型并消耗 token。
              </div>

              <div v-if="activeJob" class="mindmap-job-progress-wrap">
                <div class="mindmap-job-progress-meta">
                  <span>{{ activeJob.percent }}%</span>
                  <span>{{ activeJob.completed }} / {{ activeJob.total }}</span>
                </div>
                <div class="mindmap-job-progress">
                  <span :style="{ width: `${activeJob.percent}%` }"></span>
                </div>
                <div class="mindmap-job-current">
                  <span>当前文章：{{ activeJob.currentTitle || '-' }}</span>
                  <span>当前阶段：{{ activeJob.phaseText }}</span>
                </div>
                <div class="mindmap-job-stats">
                  <span>成功 {{ activeJob.success }}</span>
                  <span>跳过 {{ activeJob.skipped }}</span>
                  <span :class="{ danger: activeJob.failed > 0 }">失败 {{ activeJob.failed }}</span>
                </div>
              </div>
            </div>

            <div class="mindmap-toolbar">
              <label class="mindmap-check-all">
                <input type="checkbox" :checked="allChecked" @change="toggleAll" />
                <span>{{ selectedCount > 0 ? `已选 ${selectedCount} 篇` : '全选' }}</span>
              </label>
              <div class="mindmap-search-bar">
                <RiSearchLine />
                <input v-model="searchQuery" type="text" placeholder="搜索文章标题..." />
              </div>
              <select class="mindmap-status-select" v-model="statusFilter" @change="loadArticles(1)">
                <option value="all">全部状态</option>
                <option value="generated">已生成</option>
                <option value="missing">未生成</option>
                <option value="stale">已过期</option>
              </select>
              <div class="mindmap-batch-actions">
                <VButton type="default" size="xs" :disabled="selectedCount === 0 || batchRunning || jobRunning" @click="batchGenerate">
                  {{ batchRunning ? '处理中...' : '批量生成' }}
                </VButton>
                <VButton type="default" size="xs" :disabled="selectedCount === 0 || batchRunning || jobRunning" @click="batchClear">批量清除</VButton>
              </div>
            </div>

            <div v-if="loadingArticles" class="mindmap-loading">加载中...</div>
            <div v-else class="mindmap-table-wrap">
              <table class="mindmap-table">
                <thead>
                  <tr>
                    <th style="width: 40px"></th>
                    <th>文章标题</th>
                    <th style="width: 105px"><a class="mindmap-sort-link" @click="toggleSort">发布时间 {{ sortIcon }}</a></th>
                    <th style="width: 86px">脑图状态</th>
                    <th style="width: 210px">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in articleList" :key="item.postName">
                    <td class="mindmap-cell-check">
                      <input type="checkbox" :value="item.postName" v-model="selected" />
                    </td>
                    <td>
                      <div class="mindmap-article-title">{{ item.title }}</div>
                      <div v-if="item.hasMindMap" class="mindmap-markdown-preview">{{ mindMapSummary(item) }}</div>
                    </td>
                    <td class="mindmap-cell-date">{{ formatDate(item.publishTime) }}</td>
                    <td>
                      <span class="mindmap-status-badge" :class="statusClass(item)">{{ statusLabel(item) }}</span>
                    </td>
                    <td>
                      <div class="mindmap-actions-cell">
                        <VButton type="primary" size="xs" :disabled="jobRunning" :loading="generating === item.postName" @click="generateOne(item.postName)">
                          {{ item.hasMindMap ? '重生成' : '生成' }}
                        </VButton>
                        <VButton v-if="item.hasMindMap" type="default" size="xs" @click="previewItem(item)">预览</VButton>
                        <VButton v-if="item.hasMindMap" type="default" size="xs" @click="clearOne(item.postName)">清除</VButton>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
              <div v-if="!articleList.length" class="mindmap-empty">暂无文章</div>
            </div>

            <div class="mindmap-table-footer">
              <span class="mindmap-footer-stat">
                <span v-if="selectedCount > 0">已选 {{ selectedCount }} 篇</span>
                <span class="mindmap-cost-tip">批量生成可能调用模型并消耗 token</span>
              </span>
              <div class="mindmap-pagination">
                <select v-model.number="pageSize" @change="loadArticles(1)">
                  <option v-for="s in pageSizeOptions" :key="s" :value="s">{{ s }} 条/页</option>
                </select>
                <VButton type="default" size="xs" :disabled="currentPage <= 1" @click="loadArticles(currentPage - 1)">‹</VButton>
                <span>{{ currentPage }} / {{ totalPages }}</span>
                <VButton type="default" size="xs" :disabled="currentPage >= totalPages" @click="loadArticles(currentPage + 1)">›</VButton>
              </div>
            </div>
          </div>
        </article>
      </div>

      <div v-if="previewOpen" class="mindmap-preview-dialog-backdrop" @click.self="previewOpen = false">
        <div class="mindmap-preview-dialog">
          <div class="mindmap-preview-dialog-head">
            <div>
              <strong>{{ previewTitle }}</strong>
              <span>访客端脑图预览</span>
            </div>
            <button type="button" class="mindmap-preview-fit" aria-label="适配视图" @click="fitPreviewMindMap"><RiFullscreenLine /></button>
            <button type="button" @click="previewOpen = false">×</button>
          </div>
          <div class="mindmap-article-preview" :class="previewThemeClass" :style="previewStyle">
            <article class="mindmap-article-shell">
              <h1>{{ previewTitle }}</h1>
              <div class="mindmap-article-meta">
                <span>{{ previewPublishTime }}</span>
                <span>{{ previewThemeLabel }}</span>
              </div>

              <section class="halo-mindmap-block console-mindmap-block" :data-theme="previewThemeClass" data-state="ready">
                <header class="halo-mindmap-head">
                  <div class="halo-mindmap-head-left">
                    <span class="halo-mindmap-head-icon"><RiMindMap /></span>
                    <span class="halo-mindmap-head-label">AI 思维导图</span>
                    <span class="halo-mindmap-head-pill" data-state="ready">{{ previewDepthLabel }}</span>
                  </div>
                </header>
                <div class="halo-mindmap-body">
                  <svg ref="previewSvg" class="halo-mindmap-svg" role="img" aria-label="AI 思维导图预览"></svg>
                  <div v-if="previewRendering" class="halo-mindmap-status">
                    <div class="halo-mindmap-loading">
                      <div class="halo-mindmap-loading-dots"><span></span><span></span><span></span></div>
                      <span>正在渲染思维导图...</span>
                    </div>
                  </div>
                </div>
                <footer class="halo-mindmap-foot">
                  <span class="halo-mindmap-foot-meta">文章结构总览</span>
                  <span class="halo-mindmap-foot-note">由 AI智能套件生成</span>
                </footer>
              </section>
            </article>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted, onBeforeUnmount, watch, nextTick } from "vue";
import { VButton, Toast } from "@halo-dev/components";
import RiFullscreenLine from "~icons/ri/fullscreen-line";
import RiMindMap from "~icons/ri/mind-map";
import RiPaletteLine from "~icons/ri/palette-line";
import RiSearchLine from "~icons/ri/search-line";
import RiSettings3Line from "~icons/ri/settings-3-line";
import ThemeColorPicker from "../components/ThemeColorPicker.vue";
import { saveGroup, loadGroup } from "../utils/config";

declare global {
  interface Window {
    markmap?: {
      Transformer: new () => { transform: (markdown: string) => { root: unknown } };
      Markmap: {
        create: (svg: SVGSVGElement, options: Record<string, unknown>, root: unknown) => { fit: () => void };
      };
    };
  }
}

const DEFAULTS = {
  enabled: true,
  temperature: 0.3,
  maxTokens: 2048,
  maxInputLength: 15000,
  maxDepth: 3,
  extraPrompt: "",
  theme: "inherit",
  themeColor: "",
};

const form = reactive({ ...DEFAULTS });
const chatConfig = reactive({
  widgetTheme: "auto",
  widgetThemeColor: "#4F46E5",
});
const saving = ref(false);
const saveMsg = ref("");
const saveOk = ref(false);
const MINDMAP_API = "/apis/console.api.ai-suite.halo.run/v1alpha1/mindmap";
const MARKMAP_BUNDLE = "/plugins/ai-suite/assets/res/js/markmap-bundle.min.js";

interface MindMapArticle {
  postName: string;
  title: string;
  publishTime: string;
  hasMindMap: boolean;
  stale: boolean;
  status: string;
  markdown: string;
}

interface MindMapJob {
  id: string;
  scope: "missing" | "missing_stale" | "all";
  status: "pending" | "running" | "completed" | "cancelled" | "failed";
  phase: string;
  phaseText: string;
  currentPostName: string;
  currentTitle: string;
  total: number;
  completed: number;
  success: number;
  skipped: number;
  failed: number;
  percent: number;
  results: Array<{ postName: string; title: string; status: string; message: string }>;
}

const articleList = ref<MindMapArticle[]>([]);
const articleTotal = ref(0);
const loadingArticles = ref(false);
const selected = ref<string[]>([]);
const batchRunning = ref(false);
const generating = ref("");
const searchQuery = ref("");
const statusFilter = ref<"all" | "generated" | "missing" | "stale">("all");
const currentPage = ref(1);
const pageSize = ref(10);
const pageSizeOptions = [10, 20, 50];
const sortOrder = ref<"desc" | "asc">("desc");
const previewOpen = ref(false);
const previewTitle = ref("");
const previewMarkdown = ref("");
const previewPublishTime = ref("");
const previewSvg = ref<SVGSVGElement | null>(null);
const previewRendering = ref(false);
const jobScope = ref<"missing" | "missing_stale" | "all">("missing_stale");
const activeJob = ref<MindMapJob | null>(null);
const jobConfirming = ref(false);
let markmapReady: Promise<void> | null = null;
let previewMarkmap: { fit: () => void } | null = null;
let searchTimer: ReturnType<typeof window.setTimeout> | undefined;
let jobTimer: ReturnType<typeof window.setInterval> | undefined;

const themeColorValid = computed(() => {
  const value = form.themeColor.trim();
  return !value || /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(value);
});

const effectiveThemeColor = computed(() => (
  themeColorValid.value && form.themeColor
    ? form.themeColor
    : chatConfig.widgetThemeColor || "#4F46E5"
));

const themeModeHint = computed(() => {
  if (form.theme === "inherit") {
    return `当前继承「对话与外观」：${themeLabel(chatConfig.widgetTheme)}。`;
  }
  return "AI 脑图区块会使用这里单独设置的深浅色模式。";
});

const themeColorHint = computed(() => {
  if (!themeColorValid.value) return "请输入合法 HEX 色值，例如 #4F46E5。";
  if (!form.themeColor) return `当前继承问答主题色 ${chatConfig.widgetThemeColor || "#4F46E5"}。`;
  return "AI 脑图区块将使用这个独立主题色。";
});

const previewThemeLabel = computed(() => (
  form.theme === "inherit"
    ? `继承 ${themeLabel(chatConfig.widgetTheme)}`
    : themeLabel(form.theme)
));

const previewThemeClass = computed(() => {
  const mode = form.theme === "inherit" ? chatConfig.widgetTheme : form.theme;
  return mode === "dark" ? "dark" : "light";
});

const previewStyle = computed(() => ({
  "--preview-accent": effectiveThemeColor.value,
}));

const selectedCount = computed(() => selected.value.length);
const totalPages = computed(() => Math.max(1, Math.ceil(articleTotal.value / pageSize.value)));
const allChecked = computed(() => articleList.value.length > 0
  && articleList.value.every((item) => selected.value.includes(item.postName)));
const sortIcon = computed(() => (sortOrder.value === "desc" ? "↓" : "↑"));
const jobRunning = computed(() => activeJob.value?.status === "pending" || activeJob.value?.status === "running");
const jobDone = computed(() => !!activeJob.value && !jobRunning.value);
const jobPhaseText = computed(() => activeJob.value?.phaseText || "准备生成");
const jobSummaryText = computed(() => {
  if (!activeJob.value) return "";
  if (activeJob.value.status === "cancelled") return "任务已取消";
  if (activeJob.value.status === "failed") return activeJob.value.phaseText || "任务失败";
  return `成功 ${activeJob.value.success}，跳过 ${activeJob.value.skipped}，失败 ${activeJob.value.failed}`;
});
const jobScopeLabel = computed(() => ({
  missing: "仅未生成",
  missing_stale: "未生成 + 已过期",
  all: "全部重新生成",
}[jobScope.value]));

function themeLabel(theme: string) {
  const map: Record<string, string> = {
    inherit: "继承问答浮窗",
    auto: "自动适配博客",
    system: "跟随系统",
    light: "强制浅色",
    dark: "强制深色",
  };
  return map[theme] || theme || "自动适配博客";
}

function resetAllFields() {
  Object.assign(form, { ...DEFAULTS });
  saveMsg.value = "";
  Toast.success("已恢复默认");
}

async function save() {
  if (!themeColorValid.value) {
    saveOk.value = false;
    saveMsg.value = "主题色格式不正确";
    Toast.error("主题色格式不正确");
    return;
  }
  form.maxDepth = Math.max(2, Math.min(4, Number(form.maxDepth) || DEFAULTS.maxDepth));
  form.temperature = Math.max(0, Math.min(1, Number(form.temperature) || DEFAULTS.temperature));
  form.maxTokens = Math.max(512, Math.min(8192, Number(form.maxTokens) || DEFAULTS.maxTokens));
  form.maxInputLength = Math.max(2000, Math.min(50000, Number(form.maxInputLength) || DEFAULTS.maxInputLength));
  await saveGroup("mindmap", form, saving, saveMsg, saveOk);
  if (saveOk.value) {
    Toast.success(saveMsg.value || "保存成功");
  } else {
    Toast.error(saveMsg.value || "保存失败");
  }
}

function toggleAll(event: Event) {
  const checked = (event.target as HTMLInputElement).checked;
  selected.value = checked ? articleList.value.map((item) => item.postName) : [];
}

function toggleSort() {
  sortOrder.value = sortOrder.value === "desc" ? "asc" : "desc";
  loadArticles(1);
}

function formatDate(value: string) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value.slice(0, 10) || "-";
  }
  return date.toLocaleDateString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
}

function statusLabel(item: MindMapArticle) {
  if (!item.hasMindMap) return "未生成";
  if (item.stale) return "已过期";
  return "已生成";
}

function statusClass(item: MindMapArticle) {
  if (!item.hasMindMap) return "status-missing";
  if (item.stale) return "status-stale";
  return "status-generated";
}

function mindMapSummary(item: MindMapArticle) {
  const firstLine = (item.markdown || "").split(/\r?\n/).find((line) => line.trim());
  const text = firstLine?.replace(/^#+\s*/, "").trim();
  if (!text || text === item.title) {
    return item.stale ? "文章或生成配置已变化，建议重新生成" : "已缓存 Markdown 脑图";
  }
  return text;
}

function markdownMaxDepth(markdown: string) {
  const depths = (markdown || "")
    .split(/\r?\n/)
    .map((line) => line.match(/^(#{1,6})\s+/)?.[1].length || 0)
    .filter(Boolean);
  return depths.length ? Math.max(...depths) : form.maxDepth;
}

const previewDepthLabel = computed(() => {
  const depth = markdownMaxDepth(previewMarkdown.value);
  return `${depth} 层结构`;
});

async function loadArticles(page = currentPage.value) {
  currentPage.value = Math.max(1, page);
  loadingArticles.value = true;
  try {
    const params = new URLSearchParams({
      page: String(currentPage.value),
      size: String(pageSize.value),
      sort: sortOrder.value,
      status: statusFilter.value,
      keyword: searchQuery.value.trim(),
    });
    const response = await fetch(`${MINDMAP_API}/articles?${params.toString()}`);
    if (!response.ok) {
      throw new Error(`请求失败：${response.status}`);
    }
    const data = await response.json();
    articleList.value = Array.isArray(data.items) ? data.items : [];
    articleTotal.value = Number(data.total) || 0;
    const currentNames = new Set(articleList.value.map((item) => item.postName));
    selected.value = selected.value.filter((postName) => currentNames.has(postName));
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "文章列表加载失败");
  } finally {
    loadingArticles.value = false;
  }
}

async function postJson(path: string, body: Record<string, unknown>) {
  const response = await fetch(`${MINDMAP_API}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`请求失败：${response.status}`);
  }
  const data = await response.json();
  if (data?.success === false) {
    throw new Error(data.message || "操作失败");
  }
  return data;
}

async function getJson(path: string) {
  const response = await fetch(`${MINDMAP_API}${path}`);
  if (!response.ok) {
    throw new Error(`请求失败：${response.status}`);
  }
  const data = await response.json();
  if (data?.success === false) {
    throw new Error(data.message || "请求失败");
  }
  return data;
}

function stopJobPolling() {
  if (jobTimer) {
    window.clearInterval(jobTimer);
    jobTimer = undefined;
  }
}

function startJobPolling(jobId: string) {
  stopJobPolling();
  jobTimer = window.setInterval(() => {
    refreshJob(jobId);
  }, 1500);
}

async function refreshJob(jobId: string) {
  try {
    const data = await getJson(`/jobs/${jobId}`);
    activeJob.value = data.job;
    if (!jobRunning.value) {
      stopJobPolling();
      await loadArticles(currentPage.value);
    }
  } catch (error) {
    stopJobPolling();
    Toast.error(error instanceof Error ? error.message : "任务进度查询失败");
  }
}

function requestGenerateAllJob() {
  jobConfirming.value = true;
}

async function startGenerateAllJob() {
  try {
    const data = await postJson("/jobs/generate-all", { scope: jobScope.value });
    jobConfirming.value = false;
    activeJob.value = data.job;
    startJobPolling(data.jobId);
    Toast.success("脑图生成任务已启动");
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "任务启动失败");
  }
}

async function cancelGenerateJob() {
  if (!activeJob.value?.id) return;
  try {
    const data = await postJson(`/jobs/${activeJob.value.id}/cancel`, {});
    activeJob.value = data.job;
    stopJobPolling();
    await loadArticles(currentPage.value);
    Toast.success("任务已取消");
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "取消失败");
  }
}

async function generateOne(postName: string) {
  const article = articleList.value.find((item) => item.postName === postName);
  generating.value = postName;
  try {
    const path = article?.hasMindMap ? "/regenerate" : "/generate";
    const data = await postJson(path, { postName });
    Toast.success(data.cached ? "已读取缓存脑图" : "脑图已生成");
    await loadArticles(currentPage.value);
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "生成失败");
  } finally {
    generating.value = "";
  }
}

async function clearOne(postName: string) {
  try {
    await postJson("/clear", { postName });
    Toast.success("脑图缓存已清除");
    await loadArticles(currentPage.value);
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "清除失败");
  }
}

async function batchGenerate() {
  if (selected.value.length === 0) return;
  batchRunning.value = true;
  try {
    const data = await postJson("/batch-generate", { postNames: selected.value });
    Toast.success(`已处理 ${data.count || 0}/${data.total || selected.value.length} 篇文章`);
    selected.value = [];
    await loadArticles(currentPage.value);
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "批量生成失败");
  } finally {
    batchRunning.value = false;
  }
}

async function batchClear() {
  if (selected.value.length === 0) return;
  batchRunning.value = true;
  try {
    const data = await postJson("/batch-clear", { postNames: selected.value });
    Toast.success(`已清除 ${data.count || 0}/${data.total || selected.value.length} 篇文章`);
    selected.value = [];
    await loadArticles(currentPage.value);
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "批量清除失败");
  } finally {
    batchRunning.value = false;
  }
}

function previewItem(item: MindMapArticle) {
  previewTitle.value = item.title || item.postName;
  previewMarkdown.value = item.markdown || "暂无脑图 Markdown";
  previewPublishTime.value = formatDate(item.publishTime);
  previewOpen.value = true;
  renderPreviewMindMap();
}

function getMarkmapColor(theme: string) {
  return (node: { depth?: number }) => {
    const depth = node.depth || 0;
    if (depth === 0) return effectiveThemeColor.value;
    if (depth === 1) return theme === "dark" ? "#A7F3D0" : "#059669";
    if (depth === 2) return theme === "dark" ? "#FDE68A" : "#D97706";
    return theme === "dark" ? "#94A3B8" : "#64748B";
  };
}

function ensureMarkmap() {
  if (window.markmap?.Markmap && window.markmap?.Transformer) {
    return Promise.resolve();
  }
  if (markmapReady) return markmapReady;
  markmapReady = new Promise((resolve, reject) => {
    const existed = document.querySelector<HTMLScriptElement>(`script[src="${MARKMAP_BUNDLE}"]`);
    if (existed) {
      existed.addEventListener("load", () => resolve(), { once: true });
      existed.addEventListener("error", () => reject(new Error("markmap 加载失败")), { once: true });
      return;
    }
    const script = document.createElement("script");
    script.src = MARKMAP_BUNDLE;
    script.defer = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error("markmap 加载失败"));
    document.head.appendChild(script);
  }).then(() => {
    if (!window.markmap?.Markmap || !window.markmap?.Transformer) {
      throw new Error("markmap 全局对象不可用");
    }
  });
  return markmapReady;
}

function fitPreviewMindMap() {
  if (!previewMarkmap || !previewSvg.value) return;
  try {
    previewSvg.value.setAttribute("preserveAspectRatio", "xMidYMid meet");
    previewMarkmap.fit();
  } catch (error) {
    console.warn("[MindMap] 预览适配失败", error);
  }
}

async function renderPreviewMindMap() {
  previewRendering.value = true;
  previewMarkmap = null;
  await nextTick();
  const svg = previewSvg.value;
  if (!svg) {
    previewRendering.value = false;
    return;
  }
  svg.innerHTML = "";
  try {
    await ensureMarkmap();
    const transformer = new window.markmap!.Transformer();
    const { root } = transformer.transform(previewMarkdown.value || "# 暂无脑图");
    previewMarkmap = window.markmap!.Markmap.create(svg, {
      autoFit: true,
      duration: 250,
      initialExpandLevel: 4,
      maxWidth: 320,
      spacingHorizontal: 82,
      spacingVertical: 9,
      color: getMarkmapColor(previewThemeClass.value),
    }, root);
    window.setTimeout(() => {
      fitPreviewMindMap();
      previewRendering.value = false;
    }, 120);
  } catch (error) {
    previewRendering.value = false;
    Toast.error(error instanceof Error ? error.message : "脑图预览渲染失败");
  }
}

watch(searchQuery, () => {
  if (searchTimer) window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => {
    loadArticles(1);
  }, 300);
});

watch(previewOpen, (open) => {
  if (!open) {
    previewMarkmap = null;
  }
});

watch([effectiveThemeColor, previewThemeClass], () => {
  if (previewOpen.value) {
    renderPreviewMindMap();
  }
});

onMounted(async () => {
  await Promise.all([
    loadGroup("mindmap", form),
    loadGroup("chat", chatConfig),
  ]);
  await loadArticles();
});

onBeforeUnmount(() => {
  if (searchTimer) window.clearTimeout(searchTimer);
  stopJobPolling();
});
</script>

<style scoped>
.mindmap-page {
  height: calc(100vh - 64px);
  overflow: hidden;
  background: #f5f7fb;
}
.mindmap-page .ai-content {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 22px;
  padding: 0 24px;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: thin;
  scrollbar-color: #cbd5e1 transparent;
}
.mindmap-page .ai-content::-webkit-scrollbar { width: 6px; }
.mindmap-page .ai-content::-webkit-scrollbar-track { background: transparent; }
.mindmap-page .ai-content::-webkit-scrollbar-thumb {
  background: #cbd5e1;
  border-radius: 999px;
}
.mindmap-workbench {
  display: block;
  flex: 0 0 auto;
  min-height: 0;
  min-width: 0;
}
.mindmap-config {
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 20px 4px 0;
}
.mindmap-config-heading {
  flex: 0 0 auto;
  align-items: center;
}
.mindmap-heading-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
}
.mindmap-config-scroll {
  flex: 1;
  min-height: 0;
  overflow: visible;
  padding-right: 0;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.mindmap-settings-grid {
  display: grid;
  grid-template-columns: minmax(280px, 0.68fr) minmax(0, 1.32fr);
  grid-template-areas:
    "switch generation"
    "theme generation";
  gap: 18px;
  align-items: stretch;
}
.mindmap-setting-card {
  min-width: 0;
  padding: 18px;
}
.mindmap-setting-card--switch {
  grid-area: switch;
}
.mindmap-theme-card {
  grid-area: theme;
}
.mindmap-generation-card {
  grid-area: generation;
}
.mindmap-setting-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}
.mindmap-setting-head > div {
  min-width: 0;
  flex: 1;
}
.mindmap-setting-icon {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: 8px;
  background: #f1f5f9;
  color: #334155;
}
.mindmap-setting-icon svg {
  width: 16px;
  height: 16px;
}
.mindmap-setting-title {
  color: #111827;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.35;
}
.mindmap-setting-desc {
  margin-top: 3px;
  color: #8a94a6;
  font-size: 12px;
  line-height: 1.5;
}
.mindmap-setting-card .ai-switch {
  margin-left: auto;
}
.mindmap-setting-state {
  display: inline-flex;
  align-items: center;
  width: fit-content;
  margin-top: 18px;
  padding: 4px 9px;
  border-radius: 999px;
  background: #f1f5f9;
  color: #64748b;
  font-size: 12px;
  font-weight: 500;
}
.mindmap-setting-state.enabled {
  background: #ecfdf5;
  color: #047857;
}
.mindmap-theme-fields {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-top: 18px;
}
.mindmap-generation-card .ai-form-grid-2 {
  margin-top: 18px;
}
.ai-disabled-note {
  padding: 14px 16px;
  border-radius: 8px;
  background: #f8fafc;
  color: #64748b;
  font-size: 13px;
  border: 1px dashed #cbd5e1;
  margin-top: 18px;
}

.ai-switch {
  position: relative;
  width: 46px;
  height: 26px;
  display: inline-block;
  flex-shrink: 0;
}
.ai-switch input { display: none; }
.ai-switch-slider {
  position: absolute;
  cursor: pointer;
  inset: 0;
  background: #d1d5db;
  border-radius: 999px;
  transition: 0.2s;
}
.ai-switch-slider::before {
  content: "";
  position: absolute;
  width: 20px;
  height: 20px;
  left: 3px;
  top: 3px;
  background: #fff;
  border-radius: 999px;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.22);
  transition: 0.2s;
}
.ai-switch input:checked + .ai-switch-slider { background: #111827; }
.ai-switch input:checked + .ai-switch-slider::before { transform: translateX(20px); }

.ai-form-grid-2 { display: grid; grid-template-columns: repeat(2, 1fr); gap: 18px; }
.ai-prompt-field { grid-column: 1 / -1; }
.ai-color-row { display: flex; align-items: center; gap: 10px; }
.ai-color-preview {
  width: 42px;
  height: 42px;
  border-radius: 8px;
  flex-shrink: 0;
  box-shadow: inset 0 0 0 4px #fff, 0 0 0 1px #e5e7eb;
  border: 1px solid #e5e7eb;
}
.ai-color-row .ai-input { flex: 1; }
.ai-input.invalid {
  border-color: #ef4444 !important;
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.12);
}
.ai-inherit-btn {
  height: 42px;
  padding: 0 12px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  background: #fff;
  color: #374151;
  font-size: 13px;
  cursor: pointer;
}
.ai-inherit-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.ai-field-optional {
  font-size: 12px;
  font-weight: 400;
  color: var(--ai-color-fg-muted);
  margin-left: 6px;
}
.ai-helper-text.error,
.ai-save-msg.error { color: #dc2626; }

.mindmap-article-preview {
  --mm-bg: #ffffff;
  --mm-bg-elev: #f8fafc;
  --mm-bg-canvas: #fbfdff;
  --mm-text: #172033;
  --mm-text-muted: #667085;
  --mm-border: #d8e2ea;
  --mm-accent: var(--preview-accent);
  --mm-accent-soft: color-mix(in srgb, var(--preview-accent) 10%, transparent);
  --mm-btn-bg: color-mix(in srgb, var(--preview-accent) 10%, transparent);
  --mm-btn-hover: color-mix(in srgb, var(--preview-accent) 16%, transparent);
  --mm-accent-2: #059669;
  --mm-warm: #d97706;
  --mm-green-soft: rgba(5, 150, 105, 0.10);
  --mm-shadow: 0 12px 32px rgba(15, 23, 42, 0.07);
  --mm-grid: rgba(100, 116, 139, 0.14);
}
.mindmap-article-preview.dark {
  --mm-bg: #111827;
  --mm-bg-elev: #172033;
  --mm-bg-canvas: #0f172a;
  --mm-text: #e5eef6;
  --mm-text-muted: #9aa9b8;
  --mm-border: #26374a;
  --mm-accent-soft: color-mix(in srgb, var(--preview-accent) 13%, transparent);
  --mm-btn-bg: color-mix(in srgb, var(--preview-accent) 12%, transparent);
  --mm-btn-hover: color-mix(in srgb, var(--preview-accent) 18%, transparent);
  --mm-accent-2: #a7f3d0;
  --mm-warm: #fde68a;
  --mm-green-soft: rgba(167, 243, 208, 0.10);
  --mm-shadow: 0 16px 40px rgba(0, 0, 0, 0.24);
  --mm-grid: rgba(148, 163, 184, 0.11);
}
.mindmap-article-shell {
  height: min(680px, calc(100vh - 170px));
  min-height: 500px;
  padding: 24px 24px 28px;
  border: 1px solid rgba(148, 163, 184, 0.28);
  border-radius: 8px;
  background: var(--mm-bg);
  color: var(--mm-text);
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.08);
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--preview-accent) 34%, #cbd5e1) transparent;
}
.mindmap-article-shell::-webkit-scrollbar { width: 6px; }
.mindmap-article-shell::-webkit-scrollbar-track { background: transparent; }
.mindmap-article-shell::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--preview-accent) 30%, #cbd5e1);
  border-radius: 999px;
}
.mindmap-article-shell h1 {
  margin: 0;
  font-size: 22px;
  line-height: 1.3;
  font-weight: 700;
  color: var(--mm-text);
}
.mindmap-article-meta {
  display: flex;
  gap: 12px;
  margin-top: 9px;
  font-size: 12px;
  color: var(--mm-text-muted);
}
.mindmap-article-shell p {
  margin: 16px 0 0;
  font-size: 13px;
  line-height: 1.8;
  color: var(--mm-text);
  opacity: 0.78;
}
.console-mindmap-block {
  margin: 24px 0 20px;
  background: var(--mm-bg);
  border: 1px solid var(--mm-border);
  border-radius: 8px;
  box-shadow: var(--mm-shadow);
  overflow: hidden;
}
.halo-mindmap-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--mm-border);
  background: linear-gradient(180deg, var(--mm-bg-elev), var(--mm-bg));
  user-select: none;
}
.halo-mindmap-head-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.halo-mindmap-head-icon {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: var(--mm-accent);
  background: linear-gradient(135deg, var(--mm-accent-soft), rgba(5, 150, 105, 0.10));
  flex-shrink: 0;
}
.halo-mindmap-head-icon svg {
  width: 16px;
  height: 16px;
}
.halo-mindmap-head-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--mm-text);
  white-space: nowrap;
}
.halo-mindmap-head-pill {
  font-size: 10px;
  padding: 3px 8px;
  border-radius: 999px;
  background: var(--mm-green-soft);
  color: var(--mm-accent-2);
  font-weight: 500;
  white-space: nowrap;
}
.halo-mindmap-body {
  position: relative;
  width: 100%;
  height: 56vh;
  min-height: 380px;
  max-height: 560px;
  overflow: hidden;
  background:
    linear-gradient(var(--mm-grid) 1px, transparent 1px),
    linear-gradient(90deg, var(--mm-grid) 1px, transparent 1px),
    linear-gradient(180deg, var(--mm-bg-canvas), var(--mm-bg));
  background-size: 28px 28px, 28px 28px, 100% 100%;
}
.halo-mindmap-svg {
  width: 90%;
  height: 90%;
  display: block;
  margin: 5% auto;
  color: var(--mm-text);
}
.halo-mindmap-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 18px;
  border-top: 1px solid var(--mm-border);
  background: var(--mm-bg);
  font-size: 12px;
  color: var(--mm-text-muted);
}
.halo-mindmap-foot-meta::before {
  content: "";
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 7px;
  border-radius: 999px;
  background: var(--mm-accent-2);
  vertical-align: middle;
}
.halo-mindmap-foot-note {
  font-size: 11px;
  opacity: 0.7;
}
.halo-mindmap-status {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.halo-mindmap-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--mm-text-muted);
  font-size: 14px;
  text-align: center;
  padding: 20px;
}
.halo-mindmap-loading-dots {
  display: flex;
  gap: 6px;
}
.halo-mindmap-loading-dots span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--mm-accent);
  animation: mm-bounce 1.2s ease-in-out infinite;
}
.halo-mindmap-loading-dots span:nth-child(2) { animation-delay: 0.15s; }
.halo-mindmap-loading-dots span:nth-child(3) { animation-delay: 0.3s; }
@keyframes mm-bounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}
.halo-mindmap-body :deep(svg circle) {
  fill: var(--mm-bg) !important;
  stroke-width: 2 !important;
  filter: drop-shadow(0 2px 3px rgba(15, 23, 42, 0.12));
}
.halo-mindmap-body :deep(svg path) {
  fill: none !important;
  stroke: #94a3b8 !important;
  stroke-width: 1.2 !important;
  stroke-opacity: 0.5 !important;
  stroke-linecap: round !important;
  stroke-linejoin: round !important;
}
.mindmap-article-preview.dark .halo-mindmap-body :deep(svg path) {
  stroke: #64748b !important;
  stroke-opacity: 0.58 !important;
}
.halo-mindmap-body :deep(foreignObject),
.halo-mindmap-body :deep(.markmap-node) {
  font-family: inherit;
}
.halo-mindmap-body :deep(foreignObject > div),
.halo-mindmap-body :deep(.markmap-foreign) {
  color: var(--mm-text) !important;
  font-weight: 500 !important;
  line-height: 1.4 !important;
  text-shadow: 0 1px 0 var(--mm-bg);
  background: transparent !important;
}
.halo-mindmap-body :deep(.markmap-node-level-0) { font-size: 16px !important; font-weight: 700 !important; }
.halo-mindmap-body :deep(.markmap-node-level-1) { font-size: 14px !important; font-weight: 600 !important; }
.halo-mindmap-body :deep(.markmap-node-level-2) { font-size: 13px !important; font-weight: 500 !important; }
.halo-mindmap-body :deep(.markmap-node-level-3) { font-size: 12px !important; font-weight: 400 !important; }
.mindmap-preview-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 14px;
  color: #64748b;
  font-size: 12px;
}

.mindmap-manager {
  flex: 0 0 auto;
  padding: 0 4px 36px;
}
.mindmap-table-card {
  padding: 0 !important;
}
.mindmap-job-panel {
  margin: 14px 18px 0;
  padding: 14px 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fbfdff;
}
.mindmap-job-panel.running {
  border-color: color-mix(in srgb, var(--preview-accent, #4f46e5) 24%, #e5e7eb);
  background: #fff;
}
.mindmap-job-panel.done {
  background: #f8fafc;
}
.mindmap-job-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.mindmap-job-title {
  color: #111827;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.4;
}
.mindmap-job-desc {
  margin-top: 3px;
  color: #8a94a6;
  font-size: 12px;
  line-height: 1.5;
}
.mindmap-job-controls {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.mindmap-job-scope {
  height: 32px;
  min-width: 152px;
  border: 1px solid #d8dee8;
  border-radius: 8px;
  background: #fff;
  color: #374151;
  font-size: 12px;
  padding: 0 10px;
  outline: none;
}
.mindmap-job-scope:disabled {
  color: #94a3b8;
  background: #f8fafc;
}
.mindmap-job-warning {
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid #fed7aa;
  border-radius: 8px;
  background: #fff7ed;
  color: #9a3412;
  font-size: 12px;
  line-height: 1.5;
}
.mindmap-job-progress-wrap {
  margin-top: 12px;
}
.mindmap-job-progress-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: #64748b;
  font-size: 12px;
  margin-bottom: 7px;
}
.mindmap-job-progress {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: #e5e7eb;
}
.mindmap-job-progress span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--preview-accent, #4f46e5);
  transition: width 0.25s ease;
}
.mindmap-job-current {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(180px, auto);
  gap: 10px;
  margin-top: 10px;
  color: #64748b;
  font-size: 12px;
}
.mindmap-job-current span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mindmap-job-stats {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  color: #64748b;
  font-size: 12px;
}
.mindmap-job-stats .danger {
  color: #dc2626;
}
.mindmap-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px solid #e5e7eb;
  background: #fff;
}
.mindmap-check-all {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  color: #374151;
  font-size: 13px;
  cursor: pointer;
}
.mindmap-check-all input,
.mindmap-cell-check input {
  width: 14px;
  height: 14px;
  accent-color: #111827;
}
.mindmap-search-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  width: min(340px, 36vw);
  height: 34px;
  padding: 0 10px;
  border: 1px solid #d8dee8;
  border-radius: 8px;
  background: #f9fafb;
  color: #8a94a6;
}
.mindmap-search-bar svg {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
}
.mindmap-search-bar input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  color: #111827;
  font-size: 13px;
}
.mindmap-status-select,
.mindmap-pagination select {
  height: 34px;
  border: 1px solid #d8dee8;
  border-radius: 8px;
  background: #fff;
  color: #374151;
  font-size: 13px;
  padding: 0 10px;
  outline: none;
}
.mindmap-batch-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.mindmap-loading,
.mindmap-empty {
  padding: 34px 18px;
  text-align: center;
  color: #8a94a6;
  font-size: 13px;
}
.mindmap-table-wrap {
  overflow-x: auto;
}
.mindmap-table {
  width: 100%;
  min-width: 820px;
  border-collapse: collapse;
  font-size: 13px;
}
.mindmap-table th {
  padding: 11px 12px;
  background: #f8fafc;
  color: #64748b;
  font-weight: 600;
  text-align: left;
  border-bottom: 1px solid #e5e7eb;
}
.mindmap-table td {
  padding: 13px 12px;
  color: #111827;
  border-bottom: 1px solid #edf1f6;
  vertical-align: middle;
}
.mindmap-table tbody tr:hover {
  background: #fafafa;
}
.mindmap-cell-check,
.mindmap-cell-date {
  white-space: nowrap;
}
.mindmap-cell-date {
  color: #64748b !important;
}
.mindmap-sort-link {
  color: #64748b;
  cursor: pointer;
  text-decoration: none;
  user-select: none;
}
.mindmap-sort-link:hover {
  color: #111827;
}
.mindmap-article-title {
  max-width: 520px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
  color: #111827;
}
.mindmap-markdown-preview {
  max-width: 520px;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #8a94a6;
  font-size: 12px;
}
.mindmap-status-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 58px;
  height: 24px;
  padding: 0 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}
.mindmap-status-badge.status-generated {
  background: #ecfdf5;
  color: #047857;
}
.mindmap-status-badge.status-missing {
  background: #f1f5f9;
  color: #64748b;
}
.mindmap-status-badge.status-stale {
  background: #fff7ed;
  color: #c2410c;
}
.mindmap-actions-cell {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
}
.mindmap-table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 18px;
  border-top: 1px solid #edf1f6;
  background: #fff;
}
.mindmap-footer-stat {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  color: #64748b;
  font-size: 12px;
}
.mindmap-cost-tip {
  color: #8a94a6;
}
.mindmap-pagination {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #64748b;
  font-size: 13px;
}
.mindmap-preview-dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(15, 23, 42, 0.42);
}
.mindmap-preview-dialog {
  width: min(1040px, 94vw);
  height: min(780px, 90vh);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 22px 60px rgba(15, 23, 42, 0.24);
}
.mindmap-preview-dialog-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-bottom: 1px solid #e5e7eb;
  flex: 0 0 auto;
}
.mindmap-preview-dialog-head > div {
  min-width: 0;
  flex: 1;
}
.mindmap-preview-dialog-head strong {
  display: block;
  max-width: 760px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #111827;
  font-size: 14px;
}
.mindmap-preview-dialog-head span {
  display: block;
  margin-top: 3px;
  color: #8a94a6;
  font-size: 12px;
}
.mindmap-preview-dialog-head button {
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #64748b;
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
}
.mindmap-preview-dialog-head .mindmap-preview-fit {
  display: grid;
  place-items: center;
  font-size: 0;
}
.mindmap-preview-dialog-head .mindmap-preview-fit svg {
  width: 16px;
  height: 16px;
}
.mindmap-preview-dialog-head button:hover {
  background: #f1f5f9;
  color: #111827;
}
.mindmap-preview-dialog > .mindmap-article-preview {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  padding: 14px;
  background: #f8fafc;
}
.mindmap-preview-dialog .mindmap-article-shell {
  height: 100%;
  min-height: 0;
  max-height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: none;
}
.mindmap-preview-dialog .mindmap-article-shell h1 {
  flex: 0 0 auto;
  font-size: 18px;
  line-height: 1.35;
}
.mindmap-preview-dialog .mindmap-article-meta {
  flex: 0 0 auto;
}
.mindmap-preview-dialog .console-mindmap-block {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  margin: 16px 0 0;
}
.mindmap-preview-dialog .halo-mindmap-body {
  flex: 1;
  min-height: 0;
  height: auto;
  max-height: none;
}

@media (max-width: 1320px) {
  .mindmap-article-shell {
    height: min(640px, calc(100vh - 190px));
    min-height: 480px;
  }
}
@media (max-width: 1180px) {
  .mindmap-page {
    height: auto;
    overflow: visible;
  }
  .mindmap-page .ai-content {
    display: block;
    height: auto;
    padding: 28px 32px 52px;
    overflow: visible;
  }
  .mindmap-workbench {
    display: flex;
    flex-direction: column;
    min-height: 0;
  }
  .mindmap-config {
    padding: 0;
  }
  .mindmap-config-scroll {
    overflow: visible;
    padding-right: 0;
  }
  .mindmap-manager {
    padding: 22px 0 0;
  }
  .mindmap-article-shell {
    height: auto;
    max-height: none;
    overflow: visible;
  }
}
@media (max-width: 900px) {
  .ai-form-grid-2 { grid-template-columns: 1fr; }
  .mindmap-toolbar,
  .mindmap-table-footer {
    align-items: stretch;
    flex-direction: column;
  }
  .mindmap-job-top,
  .mindmap-job-controls {
    align-items: stretch;
    flex-direction: column;
  }
  .mindmap-job-scope {
    width: 100%;
  }
  .mindmap-job-current {
    grid-template-columns: 1fr;
  }
  .mindmap-search-bar {
    width: 100%;
  }
  .mindmap-batch-actions {
    margin-left: 0;
  }
  .mindmap-footer-stat,
  .mindmap-pagination {
    width: 100%;
  }
  .mindmap-pagination {
    justify-content: flex-end;
  }
  .mindmap-preview-dialog {
    width: 96vw;
    max-height: 92vh;
  }
  .mindmap-preview-dialog > .mindmap-article-preview {
    padding: 12px;
  }
  .mindmap-preview-dialog .halo-mindmap-body {
    height: 54vh;
    min-height: 320px;
  }
}
</style>
