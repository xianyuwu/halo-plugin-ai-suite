<template>
  <div class="search-page">
    <div class="ai-content">
      <div class="search-workbench">
        <div class="search-config">
          <div class="ai-section-heading search-config-heading">
            <h2><RiSearchLine /> AI 搜索</h2>
          </div>
          <div class="search-config-scroll">
            <SectionCard title="功能接入" :icon-component="RiSearchLine" headerTitle="AI 搜索入口" headerDesc="接管主题搜索入口，并支持快捷键唤起">
              <div class="ai-card-body">
                <div class="ai-enhance-section">
                  <div class="ai-enhance-header">
                    <div>
                      <div class="ai-enhance-title">启用 AI 搜索</div>
                      <div class="ai-enhance-desc">接管主题搜索入口，并支持 Ctrl+K 和 / 快捷键唤起。</div>
                    </div>
                    <label class="ai-switch">
                      <input type="checkbox" v-model="form.enabled" />
                      <span class="ai-switch-slider" />
                    </label>
                  </div>
                </div>
                <div class="ai-card-actions">
                  <VButton type="default" @click="resetFields(['enabled'])">恢复默认</VButton>
                  <VButton type="primary" :disabled="saving || !themeColorValid" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
                </div>
              </div>
            </SectionCard>

            <SectionCard title="回答与结果" :icon-component="RiQuestionAnswerLine" headerTitle="搜索结果体验" headerDesc="控制 AI 综合回答、关键词结果数量和搜索场景提示词">
              <div class="ai-card-body">
                <div class="ai-disabled-note" v-if="!form.enabled">AI 搜索关闭后，主题搜索入口将保持原有行为。</div>

                <template v-else>
                  <div class="ai-enhance-section">
                    <div class="ai-enhance-header">
                      <div>
                        <div class="ai-enhance-title">AI 综合回答</div>
                        <div class="ai-enhance-desc">在搜索结果上方显示流式回答，并附带引用来源。</div>
                      </div>
                      <label class="ai-switch">
                        <input type="checkbox" v-model="form.showAiAnswer" />
                        <span class="ai-switch-slider" />
                      </label>
                    </div>
                  </div>

                  <div class="ai-form-grid-2">
                    <div class="ai-form-field">
                      <label class="ai-field-label">关键词结果数量</label>
                      <input class="ai-input" type="number" min="1" max="30" v-model.number="form.resultCount" />
                      <div class="ai-helper-text">搜索结果列表最多显示几篇文章，建议 5-15 篇。</div>
                    </div>
                    <div class="ai-form-field" v-show="form.showAiAnswer">
                      <label class="ai-field-label">AI 回答 Token 上限</label>
                      <input class="ai-input" type="number" min="128" max="2048" step="128" v-model.number="form.maxTokens" />
                      <div class="ai-helper-text">控制搜索综合回答长度，默认 512，不影响问答浮窗。</div>
                    </div>
                  </div>

                  <div class="ai-form-field ai-prompt-field" v-show="form.showAiAnswer">
                    <label class="ai-field-label">搜索专用提示词 <span class="ai-field-optional">可选</span></label>
                    <textarea
                      class="ai-input ai-textarea"
                      v-model="form.systemPrompt"
                      rows="4"
                      placeholder="留空则复用对话提示词。可输入搜索场景专用指令，如：请简洁回答，突出引用来源..."
                    ></textarea>
                    <div class="ai-helper-text">为空时使用「对话与外观」中的系统提示词。</div>
                  </div>
                </template>

                <div class="ai-card-actions">
                  <VButton type="default" @click="resetFields(['showAiAnswer', 'resultCount', 'maxTokens', 'systemPrompt'])">恢复默认</VButton>
                  <VButton type="primary" :disabled="saving || !themeColorValid" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
                </div>
              </div>
            </SectionCard>

            <SectionCard title="搜索外观" :icon-component="RiPaletteLine" headerTitle="弹框主题" headerDesc="单独配置 AI 搜索弹框的深浅色模式和主题色">
              <div class="ai-card-body">
                <div class="ai-form-grid-2">
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
                <div class="ai-card-actions">
                  <VButton type="default" @click="resetFields(['theme', 'themeColor'])">恢复默认</VButton>
                  <VButton type="primary" :disabled="saving || !themeColorValid" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
                </div>
              </div>
            </SectionCard>
          </div>
        </div>

        <div class="search-preview">
          <SectionCard title="实时预览" :icon-component="RiEyeLine" headerTitle="搜索弹框预览" headerDesc="预览主题色、AI 回答区和关键词结果样式">
            <div class="ai-card-body">
              <div class="search-preview-stage" :style="previewStyle">
                <div class="ai-search-overlay ai-search-preview-overlay" :data-theme="previewThemeValue">
                  <div class="ai-search-modal" :data-theme="previewThemeValue">
                    <div class="ai-search-input-wrap">
                      <RiSearchLine class="ai-search-icon" />
                      <input
                        v-model.trim="debugKeyword"
                        class="ai-search-input"
                        type="text"
                        placeholder="搜索文章..."
                        @input="handlePreviewInput"
                        @keydown.enter="runSearchDebug"
                      />
                      <button
                        v-if="debugKeyword"
                        class="ai-search-clear"
                        type="button"
                        title="清空"
                        @click="clearPreviewSearch"
                      >
                        &times;
                      </button>
                      <kbd class="ai-search-kbd">Esc</kbd>
                    </div>

                    <div class="ai-search-results">
                      <div class="ai-search-ai-section" v-show="form.showAiAnswer && (debugRan || debugKeyword)">
                        <div class="ai-search-ai-header">
                          <span class="ai-search-ai-title">✨ AI 综合回答</span>
                          <span class="ai-search-ai-badge">AI</span>
                        </div>
                        <div class="ai-search-ai-body" v-html="previewAnswerHtml"></div>
                        <div class="ai-search-ai-cites" v-if="previewCitations.length">
                          <a
                            v-for="(citation, index) in previewCitations"
                            :key="index"
                            class="ai-search-ai-cite"
                            :href="citation.url || '#'"
                            target="_blank"
                            rel="noopener noreferrer"
                            @click.prevent
                          >
                            [{{ index + 1 }}] {{ citation.title || citation.postId || '未知' }}
                          </a>
                        </div>
                      </div>

                      <div class="ai-search-kw-section" v-show="debugError || previewArticles.length || (debugRan && !debugResultsLoading)">
                        <div class="ai-search-kw-header">
                          📄 搜索结果 <span class="ai-search-kw-count">({{ previewArticles.length || normalizedResultCount }})</span>
                        </div>
                        <div v-if="debugError" class="ai-search-ai-error">{{ debugError }}</div>
                        <div v-else-if="debugRan && !debugResultsLoading && !previewArticles.length" class="ai-search-empty">没有找到匹配文章。</div>
                        <div v-else class="ai-search-kw-list">
                          <a
                            v-for="article in previewArticles"
                            :key="article.postId || article.title"
                            class="ai-search-kw-item"
                            :href="article.url || '#'"
                            @click.prevent
                          >
                            <div class="ai-search-kw-title">{{ article.title || '无标题' }}</div>
                            <div class="ai-search-kw-snippet" v-html="sanitizeSnippet(article.snippet || '')"></div>
                          </a>
                        </div>
                      </div>

                      <div
                        v-if="!debugError && !debugRan && !debugKeyword"
                        class="ai-search-empty"
                      >
                        输入关键词开始搜索
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <div class="search-preview-meta">
                <span>模式：{{ previewThemeLabel }}</span>
                <span>主题色：{{ effectiveThemeColor }}</span>
                <span v-if="debugRan">调试：{{ debugElapsed }}ms · {{ debugArticles.length }} 条结果</span>
                <span v-if="form.showAiAnswer">AI 回答会调用真实模型并消耗 token</span>
              </div>
            </div>
          </SectionCard>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount } from "vue";
import { Toast, VButton } from "@halo-dev/components";
import RiEyeLine from "~icons/ri/eye-line";
import RiPaletteLine from "~icons/ri/palette-line";
import RiQuestionAnswerLine from "~icons/ri/question-answer-line";
import RiSearchLine from "~icons/ri/search-line";
import SectionCard from "../components/SectionCard.vue";
import ThemeColorPicker from "../components/ThemeColorPicker.vue";
import { saveGroup, loadGroup } from "../utils/config";
import { sanitizeHtml } from "../utils/sanitize";

const DEFAULTS = {
  enabled: true,
  showAiAnswer: true,
  resultCount: 10,
  maxTokens: 512,
  systemPrompt: "",
  theme: "inherit",
  themeColor: "",
};

const form = ref({ ...DEFAULTS });
type SearchConfigKey = keyof typeof DEFAULTS;

const chatConfig = ref({
  widgetTheme: "auto",
  widgetThemeColor: "#4F46E5",
});

const saving = ref(false);
const saveMsg = ref("");
const saveOk = ref(false);
const debugKeyword = ref("");
const debugArticles = ref<SearchArticle[]>([]);
const debugCitations = ref<SearchCitation[]>([]);
const debugAnswer = ref("");
const debugError = ref("");
const debugElapsed = ref(0);
const debugRan = ref(false);
const debugResultsLoading = ref(false);
const debugAnswerLoading = ref(false);
let debugAbortController: AbortController | null = null;

type SearchArticle = {
  postId?: string;
  title: string;
  snippet: string;
  url?: string;
  score?: number;
};

type SearchCitation = {
  title?: string;
  postId?: string;
  url?: string;
};

const themeColorValid = computed(() => {
  const value = form.value.themeColor.trim();
  return !value || /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(value);
});

const effectiveThemeColor = computed(() => (
  themeColorValid.value && form.value.themeColor
    ? form.value.themeColor
    : chatConfig.value.widgetThemeColor || "#4F46E5"
));

const normalizedResultCount = computed(() => {
  const n = Number(form.value.resultCount) || 10;
  return Math.max(1, Math.min(30, n));
});

const themeModeHint = computed(() => {
  if (form.value.theme === "inherit") {
    return `当前继承「对话与外观」：${themeLabel(chatConfig.value.widgetTheme)}。`;
  }
  return "搜索弹框会使用这里单独设置的深浅色模式。";
});

const themeColorHint = computed(() => {
  if (!themeColorValid.value) return "请输入合法 HEX 色值，例如 #4F46E5。";
  if (!form.value.themeColor) return `当前继承问答主题色 ${chatConfig.value.widgetThemeColor || "#4F46E5"}。`;
  return "搜索弹框将使用这个独立主题色。";
});

const previewThemeLabel = computed(() => (
  form.value.theme === "inherit"
    ? `继承 ${themeLabel(chatConfig.value.widgetTheme)}`
    : themeLabel(form.value.theme)
));

const previewThemeValue = computed(() => {
  const mode = form.value.theme === "inherit" ? chatConfig.value.widgetTheme : form.value.theme;
  if (mode === "dark" || mode === "light" || mode === "auto") return mode;
  if (mode === "system") {
    return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }
  return "auto";
});

const previewStyle = computed(() => ({
  "--ai-chat-color": effectiveThemeColor.value,
  "--ai-search-color": effectiveThemeColor.value,
  "--ai-search-color-rgb": hexToRgbParts(effectiveThemeColor.value) || "79, 70, 229",
}));

const debugLoading = computed(() => debugResultsLoading.value || debugAnswerLoading.value);

const previewArticles = computed<SearchArticle[]>(() => {
  if (debugRan.value) return debugArticles.value;
  return [
    { postId: "demo-1", title: "如何构建博客知识库", snippet: "展示命中的摘要片段，关键词会被 <mark>高亮</mark> 标记。" },
    { postId: "demo-2", title: "AI 助手配置实践", snippet: "结果项保持紧凑，方便访客快速扫描。" },
  ];
});

const previewCitations = computed<SearchCitation[]>(() => {
  if (debugRan.value) return debugCitations.value;
  return [
    { title: "文章标题示例" },
    { title: "相关内容" },
  ];
});

const previewAnswerText = computed(() => {
  if (debugAnswerLoading.value && !debugAnswer.value) return "正在生成 AI 综合回答...";
  if (debugRan.value) return debugAnswer.value || "本次搜索没有生成 AI 综合回答。";
  return "根据已索引文章，优先给出简洁回答，并展示可追溯的引用来源。[1][2]";
});

const previewAnswerHtml = computed(() => formatAnswerHtml(previewAnswerText.value));

/**
 * AI 回答 → HTML: 先转义, 转换 [n] 引用标记与换行, 最后统一过 DOMPurify 净化.
 * 末尾 sanitizeHtml 是关键防线 —— 即便转义或转换引入意外标签也会被剥离.
 */
function formatAnswerHtml(value: string) {
  const escaped = (value || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
  const html = escaped
    .replace(/\[(\d{1,2})\]/g, '<sup class="ai-cite-inline" data-num="$1">$1</sup>')
    .replace(/\n{2,}/g, "</p><p>")
    .replace(/\n/g, "<br>")
    .replace(/^(.+)$/s, "<p>$1</p>");
  return sanitizeHtml(html);
}

/** snippet 净化: 后端已插入 <mark> 高亮标签, sanitizeHtml 放行 mark, 剥离其他注入 */
function sanitizeSnippet(value: string) {
  return sanitizeHtml(value || "");
}

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

function hexToRgbParts(hex: string) {
  let value = (hex || "").trim();
  if (!/^#([0-9a-f]{3}|[0-9a-f]{6})$/i.test(value)) return "";
  if (value.length === 4) {
    value = `#${value[1]}${value[1]}${value[2]}${value[2]}${value[3]}${value[3]}`;
  }
  const n = parseInt(value.slice(1), 16);
  return `${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}`;
}

function handlePreviewInput() {
  if (!debugKeyword.value) {
    clearPreviewSearch();
  }
}

function clearPreviewSearch() {
  debugAbortController?.abort();
  debugAbortController = null;
  debugKeyword.value = "";
  debugArticles.value = [];
  debugCitations.value = [];
  debugAnswer.value = "";
  debugError.value = "";
  debugElapsed.value = 0;
  debugRan.value = false;
  debugResultsLoading.value = false;
  debugAnswerLoading.value = false;
}

async function save() {
  if (!themeColorValid.value) {
    saveOk.value = false;
    saveMsg.value = "主题色格式不正确";
    Toast.error("主题色格式不正确");
    return;
  }
  form.value.resultCount = normalizedResultCount.value;
  form.value.maxTokens = Math.max(128, Math.min(2048, Number(form.value.maxTokens) || DEFAULTS.maxTokens));
  await saveGroup("search", form.value, saving, saveMsg, saveOk);
  if (saveOk.value) {
    Toast.success("AI 搜索配置已保存");
  } else if (saveMsg.value) {
    Toast.error(saveMsg.value);
  }
}

function resetFields(keys: SearchConfigKey[]) {
  keys.forEach((key) => {
    Object.assign(form.value, { [key]: DEFAULTS[key] });
  });
  saveMsg.value = "";
  Toast.success("已恢复默认");
}

async function runSearchDebug() {
  const keyword = debugKeyword.value.trim();
  if (!keyword) {
    Toast.error("请输入搜索关键词");
    return;
  }
  if (!form.value.enabled) {
    Toast.error("请先启用 AI 搜索");
    return;
  }

  debugAbortController?.abort();
  debugAbortController = new AbortController();
  const signal = debugAbortController.signal;
  const startedAt = performance.now();

  debugRan.value = true;
  debugError.value = "";
  debugArticles.value = [];
  debugCitations.value = [];
  debugAnswer.value = "";
  debugElapsed.value = 0;
  debugResultsLoading.value = true;
  debugAnswerLoading.value = !!form.value.showAiAnswer;

  try {
    const tasks = [loadDebugResults(keyword, signal)];
    if (form.value.showAiAnswer) {
      tasks.push(loadDebugAnswer(keyword, signal));
    }
    await Promise.all(tasks);
  } catch (e: any) {
    if (e?.name !== "AbortError") {
      debugError.value = e?.message || "搜索调试失败";
      Toast.error(debugError.value);
    }
  } finally {
    if (!signal.aborted) {
      debugResultsLoading.value = false;
      debugAnswerLoading.value = false;
      debugElapsed.value = Math.round(performance.now() - startedAt);
    }
  }
}

async function loadDebugResults(keyword: string, signal: AbortSignal) {
  try {
    const resp = await fetch(`/apis/api.ai-suite.halo.run/v1alpha1/search/halo-results?keyword=${encodeURIComponent(keyword)}`, { signal });
    if (!resp.ok) throw new Error("关键词搜索请求失败");
    const data = await resp.json();
    debugArticles.value = Array.isArray(data.articles) ? data.articles : [];
  } finally {
    if (!signal.aborted) debugResultsLoading.value = false;
  }
}

async function loadDebugAnswer(keyword: string, signal: AbortSignal) {
  try {
    const resp = await fetch(`/apis/api.ai-suite.halo.run/v1alpha1/search/answer`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
      },
      body: JSON.stringify({ keyword }),
      signal,
    });
    if (!resp.ok || !resp.body) throw new Error("AI 回答请求失败");
    await readAnswerStream(resp.body, signal);
  } finally {
    if (!signal.aborted) debugAnswerLoading.value = false;
  }
}

async function readAnswerStream(stream: ReadableStream<Uint8Array>, signal: AbortSignal) {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let currentEvent = "";

  while (true) {
    if (signal.aborted) {
      await reader.cancel();
      return;
    }
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() || "";

    for (const line of lines) {
      if (!line.trim()) {
        currentEvent = "";
        continue;
      }
      if (line.startsWith("event:")) {
        currentEvent = line.slice(6).trim();
        continue;
      }
      if (!line.startsWith("data:")) continue;

      const data = line.slice(5).trim();
      if (!data || data === "[DONE]") continue;
      if (currentEvent === "citations") {
        try {
          const parsed = JSON.parse(data);
          debugCitations.value = Array.isArray(parsed) ? parsed : [];
        } catch {}
        continue;
      }
      try {
        const parsed = JSON.parse(data);
        debugAnswer.value += parsed.content || "";
      } catch {
        debugAnswer.value += data;
      }
    }
  }
}

onMounted(async () => {
  await Promise.all([
    loadGroup("search", form.value),
    loadGroup("chat", chatConfig.value),
  ]);
});

onBeforeUnmount(() => {
  debugAbortController?.abort();
});
</script>

<style scoped>
.search-page {
  height: calc(100vh - 64px);
  overflow: hidden;
  background: #f5f7fb;
}
.search-page .ai-content {
  display: flex;
  height: 100%;
  gap: 22px;
  padding: 0 24px;
}
.search-workbench {
  display: flex;
  flex: 1;
  min-width: 0;
  gap: 22px;
}
.search-config,
.search-preview {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.search-config {
  flex: 1;
  padding: 20px 0;
}
.search-config-heading {
  flex: 0 0 auto;
}
.search-config-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 8px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  scrollbar-width: thin;
  scrollbar-color: #cbd5e1 transparent;
}
.search-config-scroll::-webkit-scrollbar { width: 6px; }
.search-config-scroll::-webkit-scrollbar-track { background: transparent; }
.search-config-scroll::-webkit-scrollbar-thumb {
  background: #cbd5e1;
  border-radius: 999px;
}
.search-preview {
  flex: 0 0 520px;
  padding: 20px 28px;
  overflow: hidden;
}

.ai-enhance-section {
  padding: 18px 20px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  margin-bottom: 16px;
}
.ai-enhance-section:last-child { margin-bottom: 0; }
.ai-enhance-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}
.ai-enhance-title {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
  margin-bottom: 3px;
}
.ai-enhance-desc {
  font-size: 12px;
  color: #8a94a6;
  line-height: 1.5;
}
.ai-disabled-note {
  padding: 14px 16px;
  border-radius: 8px;
  background: #f8fafc;
  color: #64748b;
  font-size: 13px;
  border: 1px dashed #cbd5e1;
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
.ai-prompt-field { margin-top: 18px; }
.ai-color-swatches {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
.ai-color-swatch {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px #d8dee8, 0 2px 6px rgba(15, 23, 42, 0.12);
  cursor: pointer;
  padding: 0;
  position: relative;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.ai-color-swatch:hover {
  transform: translateY(-1px);
  box-shadow: 0 0 0 1px #94a3b8, 0 4px 10px rgba(15, 23, 42, 0.16);
}
.ai-color-swatch.active {
  box-shadow: 0 0 0 2px #111827, 0 5px 14px rgba(15, 23, 42, 0.22);
}
.ai-color-swatch-check {
  opacity: 0;
  color: #fff;
  font-size: 14px;
  font-weight: 800;
  line-height: 1;
  text-shadow: 0 1px 3px rgba(15, 23, 42, 0.55);
}
.ai-color-swatch.active .ai-color-swatch-check {
  opacity: 1;
}
.ai-color-row { display: flex; align-items: center; gap: 10px; }
.ai-color-preview {
  width: 42px;
  height: 42px;
  border-radius: 8px;
  flex-shrink: 0;
  box-shadow: inset 0 0 0 4px #fff, 0 0 0 1px #e5e7eb;
  border: 1px solid #e5e7eb;
  position: relative;
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

.search-preview-stage {
  width: 100%;
  height: min(680px, calc(100vh - 190px));
  min-height: 520px;
  border-radius: 8px;
  overflow: hidden;
  position: relative;
  background:
    linear-gradient(135deg, rgba(15, 23, 42, 0.08), rgba(148, 163, 184, 0.12)),
    radial-gradient(circle at 20% 20%, rgba(var(--ai-search-color-rgb), 0.16), transparent 34%),
    #eef2f7;
}
.ai-search-preview-overlay {
  position: absolute;
  inset: 0;
  background: rgba(15, 18, 30, 0.28);
  backdrop-filter: blur(6px) saturate(120%);
  -webkit-backdrop-filter: blur(6px) saturate(120%);
  z-index: 1;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 58px;
}
.ai-search-modal {
  width: 580px;
  max-width: calc(100% - 36px);
  max-height: calc(100% - 92px);
  background: var(--ai-chat-bg-window, #fff);
  border-radius: 20px;
  border: 1px solid rgba(255, 255, 255, 0.55);
  box-shadow:
    0 32px 100px rgba(0, 0, 0, 0.22),
    0 12px 32px rgba(0, 0, 0, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.ai-search-input-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 48px;
  padding: 0 14px;
  border-bottom: 1px solid var(--ai-chat-border, rgba(0, 0, 0, 0.06));
  box-shadow: none;
  transition: border-color 0.2s ease, box-shadow 0.25s ease;
}
.ai-search-input-wrap:focus-within {
  border-bottom-color: transparent;
  box-shadow:
    inset 0 -1.5px 0 0 var(--ai-chat-color, #7C3BED),
    0 6px 20px -8px var(--ai-chat-color, #7C3BED);
}
.ai-search-icon {
  width: 24px;
  height: 24px;
  color: var(--ai-chat-color, #7C3BED);
  flex-shrink: 0;
}
.ai-search-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  font-size: 15px;
  color: var(--ai-chat-text, #1a1a1a);
  line-height: 1.5;
  min-width: 0;
}
.ai-search-input::placeholder {
  color: var(--ai-chat-text-placeholder, #aaa);
}
.ai-search-clear {
  border: none;
  background: transparent;
  color: var(--ai-chat-text-muted, #999);
  font-size: 16px;
  cursor: pointer;
  width: 24px;
  height: 24px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: color 0.12s;
}
.ai-search-clear:hover { color: var(--ai-chat-text, #333); }
.ai-search-kbd {
  font-size: 12px;
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid var(--ai-chat-border, #d1d5db);
  background: var(--ai-chat-bg-window, #fff);
  color: var(--ai-chat-text-muted, #6b7280);
  font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
  box-shadow: 0 1px 0 rgba(0,0,0,0.06);
  flex-shrink: 0;
  line-height: 1.4;
}
.ai-search-results {
  flex: 1;
  overflow-y: auto;
  padding: 0;
}
.ai-search-empty {
  padding: 48px 20px;
  text-align: center;
  color: var(--ai-chat-text-muted, #999);
  font-size: 14px;
}
.ai-search-ai-section {
  padding: 18px 20px 20px;
  border-bottom: 1px solid var(--ai-chat-border, rgba(0, 0, 0, 0.06));
  position: relative;
  background: linear-gradient(180deg,
    rgba(var(--ai-search-color-rgb, 124, 59, 237), 0.08) 0%,
    rgba(var(--ai-search-color-rgb, 124, 59, 237), 0.03) 50%,
    transparent 100%);
}
.ai-search-ai-section::before {
  content: "";
  position: absolute;
  top: 0;
  left: 16px;
  right: 16px;
  height: 1px;
  background: linear-gradient(90deg,
    transparent 0%,
    rgba(var(--ai-search-color-rgb, 124, 59, 237), 0.5) 50%,
    transparent 100%);
}
.ai-search-ai-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.ai-search-ai-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ai-chat-text, #1a1a1a);
}
.ai-search-ai-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  background: linear-gradient(135deg,
    var(--ai-search-color, var(--ai-chat-color, #7C3BED)),
    color-mix(in srgb, var(--ai-search-color, var(--ai-chat-color, #7C3BED)) 72%, white));
  color: #fff;
  font-weight: 600;
  letter-spacing: 0.5px;
}
.ai-search-ai-body {
  font-size: 14px;
  line-height: 1.7;
  color: var(--ai-chat-text, #1a1a1a);
  word-break: break-word;
}
.ai-search-ai-body :deep(p) { margin: 6px 0; }
.ai-search-ai-body :deep(p:first-child) { margin-top: 0; }
.ai-search-ai-body :deep(p:last-child) { margin-bottom: 0; }
.ai-search-ai-body :deep(.ai-cite-inline) {
  font-size: 10px;
  background: var(--ai-chat-color, #7C3BED);
  color: #fff;
  padding: 0 4px;
  border-radius: 3px;
  cursor: default;
  vertical-align: super;
  font-weight: 600;
}
.ai-search-ai-loading {
  color: var(--ai-chat-text-muted, #999);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.ai-search-ai-loading::before {
  content: "";
  width: 14px;
  height: 14px;
  border: 2px solid var(--ai-chat-border, #ddd);
  border-top-color: var(--ai-chat-color, #7C3BED);
  border-radius: 50%;
  animation: ai-spin 0.8s linear infinite;
}
@keyframes ai-spin {
  to { transform: rotate(360deg); }
}
.ai-search-ai-error {
  color: #e74c3c;
  font-size: 13px;
}
.ai-search-ai-cites {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--ai-chat-border, #eee);
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.ai-search-ai-cite {
  font-size: 12px;
  color: var(--ai-chat-text-accent, #7C3BED);
  background: var(--ai-chat-bg-bubble-ai, #f5f2ff);
  padding: 3px 8px;
  border-radius: 6px;
  text-decoration: none;
  transition: background 0.15s;
}
.ai-search-ai-cite:hover {
  background: var(--ai-chat-border, #e0d6ff);
}
.ai-search-kw-section {
  padding: 14px 18px 16px;
}
.ai-search-kw-header {
  font-size: 11px;
  font-weight: 700;
  color: var(--ai-chat-text-muted, #999);
  margin-bottom: 12px;
  letter-spacing: 1px;
  text-transform: uppercase;
  display: flex;
  align-items: center;
  gap: 8px;
}
.ai-search-kw-count {
  font-weight: 500;
  color: var(--ai-chat-text-muted, #999);
  letter-spacing: 0.5px;
}
.ai-search-kw-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.ai-search-kw-item {
  display: block;
  padding: 12px 14px;
  border-radius: 10px;
  text-decoration: none;
  color: inherit;
  position: relative;
  border-left: 2px solid transparent;
  transition: background 0.15s, transform 0.15s, border-color 0.15s, padding-left 0.15s;
}
.ai-search-kw-item:hover {
  background: var(--ai-chat-bg-bubble-ai, rgba(124, 59, 237, 0.08));
  border-left-color: var(--ai-chat-color, #7C3BED);
  padding-left: 16px;
}
.ai-search-kw-item:hover .ai-search-kw-title {
  color: var(--ai-chat-color, #7C3BED);
}
.ai-search-kw-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ai-chat-text-accent, #7C3BED);
  margin-bottom: 6px;
  line-height: 1.4;
  transition: color 0.15s;
}
.ai-search-kw-snippet {
  font-size: 13px;
  color: var(--ai-chat-text-muted, #666);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.ai-search-kw-snippet :deep(mark) {
  background: rgba(var(--ai-search-color-rgb, 124, 59, 237), 0.08);
  color: var(--ai-chat-text-accent, #7C3BED);
  padding: 0 2px;
  border-radius: 2px;
  font-weight: 600;
  border-bottom: 1.5px solid rgba(var(--ai-search-color-rgb, 124, 59, 237), 0.45);
}
.ai-search-preview-overlay[data-theme="dark"] {
  background: rgba(8, 10, 18, 0.45);
}
.ai-search-modal[data-theme="dark"],
.ai-search-preview-overlay[data-theme="dark"] .ai-search-modal {
  --ai-chat-bg-window: rgba(30, 32, 48, 0.55);
  --ai-chat-bg-bubble-ai: rgba(53, 53, 72, 0.7);
  --ai-chat-bg-input: rgba(53, 53, 72, 0.55);
  --ai-chat-text: #f0f0f5;
  --ai-chat-text-muted: #b8b8ce;
  --ai-chat-text-placeholder: #8a8aa8;
  --ai-chat-text-accent: #c4b5fd;
  --ai-chat-border: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.14);
  box-shadow:
    0 32px 100px rgba(0, 0, 0, 0.6),
    0 12px 32px rgba(0, 0, 0, 0.4),
    inset 0 1px 0 rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(32px) saturate(180%);
  -webkit-backdrop-filter: blur(32px) saturate(180%);
  background: var(--ai-chat-bg-window);
}

.search-preview-shell {
  width: 100%;
  height: min(680px, calc(100vh - 190px));
  min-height: 520px;
  border-radius: 8px;
  border: 1px solid rgba(148, 163, 184, 0.24);
  overflow-y: auto;
  overflow-x: hidden;
  background: #fff;
  box-shadow: 0 18px 46px rgba(15, 23, 42, 0.12);
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--preview-accent) 34%, #cbd5e1) transparent;
}
.search-preview-shell::-webkit-scrollbar { width: 6px; }
.search-preview-shell::-webkit-scrollbar-track { background: transparent; }
.search-preview-shell::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--preview-accent) 30%, #cbd5e1);
  border-radius: 999px;
}
.search-preview-shell.dark {
  background: #1f2433;
  color: #f8fafc;
  border-color: rgba(255,255,255,0.12);
}
.search-preview-input {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.24);
  position: sticky;
  top: 0;
  z-index: 2;
  background: inherit;
}
.search-preview-input svg {
  color: var(--preview-accent);
  font-size: 20px;
}
.search-preview-input span {
  flex: 1;
  color: #94a3b8;
  font-size: 15px;
}
.search-preview-input input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  color: inherit;
  font-size: 14px;
  font-family: inherit;
}
.search-preview-input input::placeholder {
  color: #94a3b8;
}
.search-preview-shell.dark .search-preview-input input {
  color: #f8fafc;
}
.search-preview-input kbd {
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid rgba(148, 163, 184, 0.36);
  color: #64748b;
}
.search-preview-ai {
  padding: 22px;
  background: color-mix(in srgb, var(--preview-accent) 9%, transparent);
  border-bottom: 1px solid rgba(148, 163, 184, 0.20);
}
.search-preview-ai-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 700;
}
.search-preview-ai-head b {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--preview-accent);
  color: #fff;
}
.search-preview-ai p {
  margin: 10px 0 0;
  font-size: 14px;
  line-height: 1.7;
  color: inherit;
  opacity: 0.82;
}
.search-preview-ai-scroll {
  max-height: 180px;
  overflow-y: auto;
  margin-top: 10px;
  padding-right: 6px;
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--preview-accent) 36%, #cbd5e1) transparent;
}
.search-preview-ai-scroll::-webkit-scrollbar { width: 6px; }
.search-preview-ai-scroll::-webkit-scrollbar-track { background: transparent; }
.search-preview-ai-scroll::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--preview-accent) 32%, #cbd5e1);
  border-radius: 999px;
}
.search-preview-ai-body {
  font-size: 14px;
  line-height: 1.7;
  color: inherit;
  opacity: 0.86;
  word-break: break-word;
}
.search-preview-ai-body :deep(p) {
  margin: 6px 0;
}
.search-preview-ai-body :deep(p:first-child) { margin-top: 0; }
.search-preview-ai-body :deep(p:last-child) { margin-bottom: 0; }
.search-preview-ai-body :deep(.ai-cite-inline) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  margin: 0 1px;
  border-radius: 4px;
  background: var(--preview-accent);
  color: #fff;
  font-size: 10px;
  line-height: 1;
  font-weight: 700;
  vertical-align: super;
  transform: translateY(-1px);
}
.search-preview-cites {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}
.search-preview-cites span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 6px;
  color: var(--preview-accent);
  background: color-mix(in srgb, var(--preview-accent) 12%, white);
}
.search-preview-cites span b {
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: var(--preview-accent);
  color: #fff;
  font-size: 10px;
  line-height: 1;
  flex-shrink: 0;
}
.search-preview-shell.dark .search-preview-cites span {
  background: rgba(255,255,255,0.08);
}
.search-preview-results {
  padding: 18px 22px 22px;
}
.search-preview-results-title {
  font-size: 11px;
  font-weight: 700;
  color: #64748b;
  margin-bottom: 10px;
}
.search-preview-item {
  padding: 14px 0 14px 14px;
  border-left: 2px solid transparent;
  border-radius: 6px;
}
.search-preview-item + .search-preview-item { margin-top: 2px; }
.search-preview-item:hover,
.search-preview-item:first-of-type {
  border-left-color: var(--preview-accent);
  background: color-mix(in srgb, var(--preview-accent) 7%, transparent);
}
.search-preview-item strong {
  display: block;
  font-size: 14px;
  color: var(--preview-accent);
  margin-bottom: 4px;
}
.search-preview-item p {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: #64748b;
}
.search-preview-item :deep(mark) {
  padding: 0 2px;
  border-radius: 3px;
  background: color-mix(in srgb, var(--preview-accent) 18%, white);
  color: inherit;
}
.search-preview-empty,
.search-preview-error {
  padding: 16px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.5;
}
.search-preview-empty {
  color: #64748b;
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
}
.search-preview-error {
  color: #b91c1c;
  background: #fef2f2;
  border: 1px solid #fecaca;
}
.search-preview-shell.dark .search-preview-item p,
.search-preview-shell.dark .search-preview-results-title,
.search-preview-shell.dark .search-preview-input kbd { color: #aeb8c8; }
.search-preview-shell.dark .search-preview-empty {
  color: #aeb8c8;
  background: rgba(255,255,255,0.05);
  border-color: rgba(255,255,255,0.12);
}
.search-preview-shell.dark .search-preview-error {
  color: #fecaca;
  background: rgba(127, 29, 29, 0.26);
  border-color: rgba(248, 113, 113, 0.28);
}
.search-preview-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 14px;
  font-size: 12px;
  color: #64748b;
}

@media (max-width: 1320px) {
  .search-preview {
    flex-basis: 460px;
    padding-right: 24px;
  }
  .search-preview-shell {
    height: min(640px, calc(100vh - 190px));
    min-height: 480px;
  }
}
@media (max-width: 1180px) {
  .search-page {
    height: auto;
    overflow: visible;
  }
  .search-page .ai-content {
    display: block;
    height: auto;
    padding: 28px 32px 52px;
  }
  .search-workbench {
    display: flex;
    flex-direction: column;
  }
  .search-config {
    padding: 0;
  }
  .search-config-scroll {
    overflow: visible;
    padding-right: 0;
  }
  .search-preview {
    flex: none;
    padding: 0;
  }
  .search-preview-shell {
    height: auto;
    max-height: none;
    overflow: visible;
  }
}
@media (max-width: 900px) {
  .ai-form-grid-2 { grid-template-columns: 1fr; }
}
</style>
