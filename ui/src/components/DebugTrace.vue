<template>
  <div class="debug-trace">
    <!-- 输入区 -->
    <div class="debug-input-area">
      <textarea
        ref="textareaRef"
        v-model="query"
        class="debug-textarea"
        rows="1"
        placeholder="输入调试问题... (Shift+Enter 换行)"
        @input="autoGrow"
        @keydown.enter.exact.prevent="sendDebug"
        :disabled="streaming"
      ></textarea>
      <button
        class="debug-icon-btn"
        @click="resetDebug"
        :disabled="!canReset"
        title="清空输入与追踪结果"
      >
        <RiRefreshLine />
      </button>
      <button class="debug-send-btn" @click="sendDebug" :disabled="!query.trim() || streaming">
        {{ streaming ? '追踪中...' : '发送' }}
      </button>
    </div>

    <!-- 空状态：未发送过任何问题时展示，引导用户 -->
    <div v-if="stages.length === 0 && !streaming && !aiResponse" class="debug-empty">
      <div class="debug-empty-header">
        <div class="debug-empty-title"><RiSearchLine /> 调试追踪</div>
        <div class="debug-empty-desc">输入问题后会依次展示管线各阶段的耗时、结果与降级原因，便于排查问答效果。</div>
      </div>

      <div class="debug-empty-section">
        <div class="debug-empty-section-title">试试这些问题（点击填入输入框）</div>
        <div class="debug-empty-chips">
          <button
            v-for="q in SAMPLE_QUERIES"
            :key="q"
            class="debug-empty-chip"
            @click="fillQuery(q)"
          >{{ q }}</button>
        </div>
      </div>

      <div class="debug-empty-section">
        <div class="debug-empty-section-title">管线 {{ STAGE_PREVIEW.length }} 个阶段（按顺序）</div>
        <ol class="debug-empty-stages">
          <li v-for="(s, i) in STAGE_PREVIEW" :key="s.name">
            <span class="debug-empty-stage-idx">{{ i + 1 }}</span>
            <span class="debug-empty-stage-name">{{ s.label }}</span>
            <span v-if="s.optional" class="debug-empty-stage-tag">可选</span>
          </li>
        </ol>
      </div>

      <div class="debug-empty-section">
        <div class="debug-empty-section-title">状态图例</div>
        <div class="debug-empty-legend">
          <span class="debug-empty-legend-item"><span class="debug-stage-dot ok"></span>完成</span>
          <span class="debug-empty-legend-item"><span class="debug-stage-dot fallback"></span>降级</span>
          <span class="debug-empty-legend-item"><span class="debug-stage-dot skipped"></span>跳过</span>
          <span class="debug-empty-legend-item"><span class="debug-stage-dot error"></span>出错</span>
        </div>
      </div>
    </div>

    <!-- 智能诊断：阶段跑完后展示，规则引擎产出 -->
    <div v-if="stages.length > 0 && !streaming" class="debug-diagnose">
      <div class="debug-diagnose-header">
        <span class="debug-diagnose-title"><RiSearchLine /> 智能诊断</span>
        <span class="debug-diagnose-sub">基于本次追踪数据</span>
      </div>
      <div
        v-for="finding in findings"
        :key="finding.title"
        class="debug-diagnose-card"
        :class="`debug-diagnose-${finding.level}`"
      >
        <div class="debug-diagnose-card-header">
          <span class="debug-diagnose-icon"><component
            :is="finding.level === 'ok' ? RiCheckLine
              : finding.level === 'info' ? RiInformationLine
              : finding.level === 'warning' ? RiAlertLine
              : RiCloseLine"
          /></span>
          <span class="debug-diagnose-card-title">{{ finding.title }}</span>
        </div>
        <div v-if="finding.causes.length" class="debug-diagnose-section">
          <div class="debug-diagnose-section-title">可能原因</div>
          <ul><li v-for="c in finding.causes" :key="c">{{ c }}</li></ul>
        </div>
        <div v-if="finding.suggestions.length" class="debug-diagnose-section">
          <div class="debug-diagnose-section-title">排查路径</div>
          <ol><li v-for="s in finding.suggestions" :key="s">{{ s }}</li></ol>
        </div>
      </div>
    </div>

    <!-- 管线追踪时间线 -->
    <div v-if="stages.length > 0" class="debug-timeline">
      <div v-for="(stage, i) in stages" :key="i" class="debug-stage">
        <div class="debug-stage-dot" :class="stage.status"></div>
        <div class="debug-stage-body">
          <!-- 点击 header 展开/收起 -->
          <div class="debug-stage-header" @click="toggleStage(i)">
            <span class="debug-expand-icon">{{ expandedStages[i] ? '▾' : '▸' }}</span>
            <span class="debug-stage-label">{{ stage.label }}</span>
            <span v-if="stage.durationMs > 0" class="debug-stage-dur">{{ stage.durationMs }}ms</span>
            <span class="debug-stage-status" :class="stage.status">{{ stage.statusLabel }}</span>
          </div>
          <div v-if="stage.detail" class="debug-stage-detail">{{ stage.detail }}</div>

          <!-- 展开详情 -->
          <div v-if="expandedStages[i]" class="debug-stage-expand">
            <!-- Rerank 精排：带阈值和通过状态 -->
            <template v-if="stage.name === 'rerank' && hasDocuments(stage)">
              <div class="debug-kv-list" style="margin-bottom:8px">
                <div class="debug-kv-row">
                  <span class="debug-kv-key">分数阈值</span>
                  <span class="debug-kv-val">{{ stage.data?.threshold ?? '-' }}</span>
                </div>
              </div>
              <table class="debug-doc-table">
                <thead>
                  <tr><th>#</th><th>文章标题</th><th>Chunk</th><th>Rerank 分数</th><th>状态</th></tr>
                </thead>
                <tbody>
                  <tr v-for="(doc, di) in getDocuments(stage)" :key="di"
                      :class="{ 'debug-row-filtered': doc.passed === false }">
                    <td class="debug-col-idx">{{ di + 1 }}</td>
                    <td class="debug-col-title">{{ doc.title || '未命名' }}</td>
                    <td class="debug-col-chunk"><a class="debug-chunk-link" @click.stop="openChunk(doc)" title="查看切片内容">#{{ doc.chunkIndex }}</a></td>
                    <td class="debug-col-score">{{ (doc.score as number).toFixed(4) }}</td>
                    <td class="debug-col-passed">
                      <span v-if="doc.passed" class="debug-passed-yes">通过</span>
                      <span v-else class="debug-passed-no">低于阈值</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </template>

            <!-- 通用文档类阶段：混合检索 / 原查询兜底 / 跨语言检索 -->
            <template v-else-if="hasDocuments(stage)">
              <table class="debug-doc-table">
                <thead>
                  <tr><th>#</th><th>文章标题</th><th>Chunk</th><th>分数</th></tr>
                </thead>
                <tbody>
                  <tr v-for="(doc, di) in getDocuments(stage)" :key="di">
                    <td class="debug-col-idx">{{ di + 1 }}</td>
                    <td class="debug-col-title">{{ doc.title || '未命名' }}</td>
                    <td class="debug-col-chunk"><a class="debug-chunk-link" @click.stop="openChunk(doc)" title="查看切片内容">#{{ doc.chunkIndex }}</a></td>
                    <td class="debug-col-score">{{ (doc.score as number).toFixed(4) }}</td>
                  </tr>
                </tbody>
              </table>
            </template>

            <!-- 意图识别 -->
            <template v-else-if="stage.name === 'chat_intent'">
              <div class="debug-kv-list">
                <div class="debug-kv-row">
                  <span class="debug-kv-key">识别结果</span>
                  <span class="debug-kv-val">{{ formatIntent(stage.detail) }}</span>
                </div>
                <div class="debug-kv-row">
                  <span class="debug-kv-key">原始值</span>
                  <span class="debug-kv-val muted">{{ stage.detail }}</span>
                </div>
              </div>
            </template>

            <!-- 查询改写 -->
            <template v-else-if="stage.name === 'query_rewrite'">
              <div class="debug-kv-list">
                <div class="debug-kv-row">
                  <span class="debug-kv-key">改写结果</span>
                  <span class="debug-kv-val">{{ stage.detail }}</span>
                </div>
              </div>
            </template>

            <!-- 向量编码 -->
            <template v-else-if="stage.name === 'embed'">
              <div class="debug-kv-list">
                <div class="debug-kv-row">
                  <span class="debug-kv-key">向量维度</span>
                  <span class="debug-kv-val">{{ stage.data?.dimensions || '-' }}</span>
                </div>
              </div>
            </template>

            <!-- 合并去重 -->
            <template v-else-if="stage.name === 'merge_dedup'">
              <div class="debug-kv-list">
                <div class="debug-kv-row">
                  <span class="debug-kv-key">去重前</span>
                  <span class="debug-kv-val">{{ stage.data?.before || 0 }} 条</span>
                </div>
                <div class="debug-kv-row">
                  <span class="debug-kv-key">去重后</span>
                  <span class="debug-kv-val">{{ stage.data?.after || 0 }} 条</span>
                </div>
              </div>
            </template>

            <!-- 构建上下文 -->
            <template v-else-if="stage.name === 'build_context'">
              <div class="debug-kv-list">
                <div class="debug-kv-row">
                  <span class="debug-kv-key">引用文档</span>
                  <span class="debug-kv-val">{{ stage.data?.docCount || 0 }} 篇</span>
                </div>
                <div class="debug-kv-row">
                  <span class="debug-kv-key">上下文长度</span>
                  <span class="debug-kv-val">{{ stage.data?.contextLength || 0 }} 字符</span>
                </div>
              </div>
            </template>

            <!-- 注入上下文到 LLM -->
            <template v-else-if="stage.name === 'inject_context'">
              <div class="debug-kv-list">
                <div class="debug-kv-row" v-if="stage.data?.ragDocCount != null">
                  <span class="debug-kv-key">RAG 文档数</span>
                  <span class="debug-kv-val">{{ stage.data.ragDocCount }} 篇</span>
                </div>
                <div class="debug-kv-row" v-if="stage.data?.contextLength != null">
                  <span class="debug-kv-key">注入字符数</span>
                  <span class="debug-kv-val">{{ stage.data.contextLength }} 字符</span>
                </div>
                <div class="debug-kv-row" v-if="stage.status === 'fallback' || stage.status === 'skipped'">
                  <span class="debug-kv-key">原因</span>
                  <span class="debug-kv-val muted">{{ stage.detail }}</span>
                </div>
              </div>
            </template>

            <!-- 跳过/降级阶段 -->
            <template v-else-if="stage.status === 'skipped' || stage.status === 'fallback'">
              <div class="debug-kv-list">
                <div class="debug-kv-row">
                  <span class="debug-kv-key">原因</span>
                  <span class="debug-kv-val muted">{{ stage.detail }}</span>
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- 汇总 -->
    <div v-if="summary" class="debug-summary">
      总耗时 {{ summary.totalMs }}ms · {{ summary.stageCount }} 个阶段 · 意图: {{ summary.intent }}
    </div>

    <!-- AI 回复 -->
    <div v-if="aiResponse || streaming" class="debug-response" ref="responseRef">
      <div class="debug-response-label">AI 回复</div>
      <div v-if="streaming && !aiResponse" class="debug-typing">
        <span></span><span></span><span></span>
      </div>
      <div v-else class="debug-response-text" v-html="renderedResponse"></div>
      <!-- 引用 -->
      <div v-if="citations.length > 0 && !streaming" class="debug-citations">
        <span class="debug-citations-label">参考文章：</span>
        <a v-for="(c, i) in citations" :key="i" :href="c.url" target="_blank" class="debug-citation-link">
          {{ c.title }}
        </a>
      </div>
    </div>

    <!-- 切片内容弹窗 -->
    <div v-if="chunkModal" class="debug-modal-overlay" @click.self="chunkModal = null">
      <div class="debug-modal">
        <div class="debug-modal-header">
          <span class="debug-modal-title">{{ chunkModal.title }} · #{{ chunkModal.chunkIndex }}</span>
          <button class="debug-modal-close" @click="chunkModal = null">&times;</button>
        </div>
        <div class="debug-modal-body">{{ chunkModal.content }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, watch, onMounted } from "vue";
import { marked } from "marked";
import RiRefreshLine from "~icons/ri/refresh-line";
import RiSearchLine from "~icons/ri/search-line";
import RiCheckLine from "~icons/ri/check-line";
import RiAlertLine from "~icons/ri/alert-line";
import RiCloseLine from "~icons/ri/close-line";
import RiInformationLine from "~icons/ri/information-line";
import { computeFindings } from "../composables/useTraceFindings";

// 外部传入的预填问题（如从问答记录一键追踪）
const props = defineProps<{ initialQuery?: string }>();

// 类型定义
interface TraceStage {
  name: string;
  label: string;
  startedAt: number;
  finishedAt: number;
  status: "ok" | "fallback" | "skipped" | "error";
  statusLabel: string;
  detail: string;
  data: any;
  durationMs: number;
}

interface TraceSummary {
  totalMs: number;
  stageCount: number;
  intent: string;
}

interface Citation {
  title: string;
  url?: string;
  postId?: string;
}

interface ChunkDetail {
  title: string;
  chunkIndex: number;
  content: string;
}

interface Finding {
  level: "ok" | "info" | "warning" | "error";
  title: string;
  causes: string[];
  suggestions: string[];
  relatedStages: string[];
}

// 状态
const query = ref("");
const streaming = ref(false);
const stages = ref<TraceStage[]>([]);
const summary = ref<TraceSummary | null>(null);
const aiResponse = ref("");
const citations = ref<Citation[]>([]);
const expandedStages = ref<Record<number, boolean>>({});
const chunkModal = ref<ChunkDetail | null>(null);
const textareaRef = ref<HTMLTextAreaElement | null>(null);
const responseRef = ref<HTMLElement | null>(null);

// 自动撑高：1 行起步，max-height 封顶后内部出现滚动条
const TEXTAREA_MAX_H = 96;
function autoGrow() {
  const ta = textareaRef.value;
  if (!ta) return;
  ta.style.height = "auto";
  ta.style.height = Math.min(ta.scrollHeight, TEXTAREA_MAX_H) + "px";
}

// 示例问题：覆盖三类意图，点 chip 填入输入框（不直接发送）
const SAMPLE_QUERIES = [
  "推荐几篇热门文章",
  "最新文章有哪些",
  "什么是 RAG？",
  "Embedding 是什么？",
];

// 管线阶段预览：与 RAGPipeline/ChatService 实际 addStage 顺序对齐
const STAGE_PREVIEW: { name: string; label: string; optional?: boolean }[] = [
  { name: "chat_intent", label: "意图识别" },
  { name: "query_rewrite", label: "查询改写", optional: true },
  { name: "embed", label: "向量编码" },
  { name: "original_query", label: "原查询兜底", optional: true },
  { name: "merge_dedup", label: "合并去重", optional: true },
  { name: "rerank", label: "Rerank 精排", optional: true },
  { name: "build_context", label: "构建上下文" },
  { name: "inject_context", label: "注入上下文到 LLM" },
];

// 智能诊断：基于公共 composable，按 stages/summary/citations 变化自动重算
const findings = computed<Finding[]>(() =>
  computeFindings(stages.value, summary.value, citations.value)
);

function fillQuery(text: string) {
  query.value = text;
  nextTick(() => {
    autoGrow();
    textareaRef.value?.focus();
  });
}

// 从问答记录一键追踪时，预填初始问题
onMounted(() => {
  if (props.initialQuery) {
    fillQuery(props.initialQuery);
  }
});

// 是否有可重置的内容（输入或结果）
const canReset = computed(() =>
  !streaming.value &&
  (query.value.trim() !== "" ||
    stages.value.length > 0 ||
    aiResponse.value !== "" ||
    summary.value !== null)
);

// 清空输入与所有追踪/结果状态，回到空状态
function resetDebug() {
  query.value = "";
  stages.value = [];
  summary.value = null;
  aiResponse.value = "";
  citations.value = [];
  expandedStages.value = {};
  chunkModal.value = null;
  nextTick(() => autoGrow());
}

// 智能自动滚动：流式时若用户已在底部附近，自动滚到底跟随新内容
// 用户主动上滑查看 timeline 时不打断
watch([aiResponse, streaming], () => {
  if (!streaming.value) return;
  nextTick(() => {
    const el = responseRef.value;
    if (!el) return;
    // 向上找最近的滚动容器
    let container: HTMLElement | null = el.parentElement;
    while (container && container !== document.body) {
      const style = getComputedStyle(container);
      if (style.overflowY === "auto" || style.overflowY === "scroll") break;
      container = container.parentElement;
    }
    if (!container) return;
    const distanceToBottom =
      container.scrollHeight - container.scrollTop - container.clientHeight;
    // 阈值 100px：用户基本在底部就跟随滚动
    if (distanceToBottom < 100) {
      container.scrollTo({ top: container.scrollHeight, behavior: "smooth" });
    }
  });
});

function openChunk(doc: any) {
  chunkModal.value = {
    title: doc.title || "未命名",
    chunkIndex: doc.chunkIndex,
    content: doc.content || "（无内容）",
  };
}

function toggleStage(index: number) {
  expandedStages.value[index] = !expandedStages.value[index];
}

// 将意图枚举值翻译为中文
function formatIntent(raw: string): string {
  const map: Record<string, string> = {
    NORMAL_CHAT: "普通对话（RAG 问答）",
    HOT_ARTICLES: "热门文章推荐",
    LATEST_ARTICLES: "最新文章",
  };
  return map[raw] || raw;
}

// 提取文档列表：data 可能是数组（混合检索等），也可能是 { documents: [...] }（rerank）
function getDocuments(stage: TraceStage): any[] {
  if (Array.isArray(stage.data) && stage.data.length > 0) return stage.data;
  if (Array.isArray(stage.data?.documents) && stage.data.documents.length > 0) return stage.data.documents;
  return [];
}

function hasDocuments(stage: TraceStage): boolean {
  return getDocuments(stage).length > 0;
}

// 用 marked 渲染 AI 回复（GFM + 换行转 <br>），覆盖标题/列表/代码块/引用/表格等
const renderedResponse = computed(() =>
  sanitizeHtml(marked.parse(aiResponse.value, { breaks: true, gfm: true, async: false }) as string)
);

const ALLOWED_TAGS = new Set([
  "A", "B", "BLOCKQUOTE", "BR", "CODE", "DEL", "DIV", "EM", "H1", "H2", "H3",
  "H4", "H5", "H6", "HR", "I", "LI", "OL", "P", "PRE", "S", "SPAN", "STRONG",
  "TABLE", "TBODY", "TD", "TH", "THEAD", "TR", "UL"
]);
const ALLOWED_ATTRS: Record<string, Set<string>> = {
  A: new Set(["href", "title", "target", "rel"]),
  CODE: new Set(["class"]),
  PRE: new Set(["class"]),
  SPAN: new Set(["class"]),
  DIV: new Set(["class"]),
  TH: new Set(["align"]),
  TD: new Set(["align"]),
};

function sanitizeHtml(html: string): string {
  const template = document.createElement("template");
  template.innerHTML = html || "";
  cleanNode(template.content);
  return template.innerHTML;
}

function cleanNode(parent: ParentNode) {
  for (const child of Array.from(parent.childNodes)) {
    if (child.nodeType === Node.TEXT_NODE) continue;
    if (child.nodeType !== Node.ELEMENT_NODE) {
      child.parentNode?.removeChild(child);
      continue;
    }
    const el = child as HTMLElement;
    if (!ALLOWED_TAGS.has(el.tagName)) {
      el.replaceWith(document.createTextNode(el.textContent || ""));
      continue;
    }
    const allowedAttrs = ALLOWED_ATTRS[el.tagName] || new Set<string>();
    for (const attr of Array.from(el.attributes)) {
      if (!allowedAttrs.has(attr.name)) {
        el.removeAttribute(attr.name);
      }
    }
    if (el.tagName === "A") {
      const href = el.getAttribute("href") || "";
      if (!isSafeHref(href)) {
        el.removeAttribute("href");
      } else {
        el.setAttribute("target", "_blank");
        el.setAttribute("rel", "noopener noreferrer");
      }
    }
    cleanNode(el);
  }
}

function isSafeHref(href: string): boolean {
  return href.startsWith("/")
    || href.startsWith("#")
    || /^https?:\/\//i.test(href);
}

// SSE 解析辅助
function parseSSEBlock(block: string): { eventType: string; eventData: string } {
  let eventType = "";
  let eventData = "";
  for (const line of block.split("\n")) {
    if (line.startsWith("event:")) {
      eventType = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      eventData = line.slice(5).trim();
    }
  }
  return { eventType, eventData };
}

async function sendDebug() {
  const q = query.value.trim();
  if (!q || streaming.value) return;

  // 重置状态
  stages.value = [];
  summary.value = null;
  aiResponse.value = "";
  citations.value = [];
  streaming.value = true;

  try {
    const res = await fetch(
      "/apis/console.api.ai-suite.halo.run/v1alpha1/chat/debug/stream",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: q, history: [] }),
      }
    );

    if (!res.ok || !res.body) {
      aiResponse.value = "请求失败: " + res.status;
      streaming.value = false;
      return;
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split("\n\n");
      buffer = parts.pop() || "";

      for (const block of parts) {
        if (!block.trim()) continue;
        const { eventType, eventData } = parseSSEBlock(block);

        if (eventType === "trace_stage") {
          try {
            const stage = JSON.parse(eventData);
            // 计算前端用的 durationMs（后端可能已返回或需要计算）
            if (!stage.durationMs && stage.startedAt && stage.finishedAt) {
              stage.durationMs = stage.finishedAt - stage.startedAt;
            }
            stages.value.push(stage);
          } catch {}
        } else if (eventType === "trace_summary") {
          try { summary.value = JSON.parse(eventData); } catch {}
        } else if (eventType === "citations") {
          try { citations.value = JSON.parse(eventData); } catch {}
        } else if (eventData === "[DONE]") {
          // 结束
        } else {
          // token 流
          try {
            const p = JSON.parse(eventData);
            if (p.content) aiResponse.value += p.content;
          } catch {
            // 兜底
            if (eventData) aiResponse.value += eventData;
          }
        }
      }
    }
  } catch (e: any) {
    aiResponse.value = "连接失败: " + (e.message || "未知错误");
  } finally {
    streaming.value = false;
  }
}
</script>

<style scoped>
.debug-trace {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 2px;
  font-size: 13px;
}

/* 输入区 */
.debug-input-area {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.debug-textarea {
  flex: 1;
  padding: 10px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  font-size: 13px;
  resize: none;
  outline: none;
  font-family: inherit;
  line-height: 1.5;
  background: #fff;
  transition: border-color 0.15s;
  /* 1 行起步，autoGrow 由 JS 撑高，最多 ~3 行后内部出滚动条 */
  min-height: 40px;
  max-height: 96px;
  overflow-y: auto;
}
.debug-textarea:focus {
  border-color: #6366f1;
}
.debug-textarea:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.debug-send-btn {
  padding: 10px 18px;
  border: none;
  border-radius: 10px;
  background: #4f46e5;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s;
}
.debug-send-btn:hover:not(:disabled) {
  background: #4338ca;
}
.debug-send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.debug-icon-btn {
  width: 40px;
  height: 40px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #fff;
  color: #64748b;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.15s;
}
.debug-icon-btn:hover:not(:disabled) {
  border-color: #cbd5e1;
  color: #4f46e5;
  background: #f8fafc;
}
.debug-icon-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.debug-icon-btn svg {
  width: 18px;
  height: 18px;
}

/* 空状态：未发送过任何问题时展示 */
.debug-empty {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 4px 2px 0;
}
.debug-empty-header {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.debug-empty-title {
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
}
.debug-empty-desc {
  font-size: 12px;
  color: #64748b;
  line-height: 1.65;
}
.debug-empty-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.debug-empty-section-title {
  font-size: 11px;
  font-weight: 600;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.debug-empty-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.debug-empty-chip {
  background: #fff;
  border: 1px solid #e2e8f0;
  color: #334155;
  border-radius: 999px;
  padding: 5px 12px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
  font-family: inherit;
}
.debug-empty-chip:hover {
  border-color: #6366f1;
  color: #4f46e5;
  background: #eef2ff;
}
.debug-empty-stages {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: #334155;
}
.debug-empty-stages li {
  display: flex;
  align-items: center;
  gap: 8px;
}
.debug-empty-stage-idx {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #f1f5f9;
  color: #64748b;
  font-size: 11px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.debug-empty-stage-name {
  flex: 1;
}
.debug-empty-stage-tag {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 999px;
  background: #f3f4f6;
  color: #94a3b8;
  font-weight: 500;
}
.debug-empty-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 12px;
  color: #64748b;
}
.debug-empty-legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
/* 复用 .debug-stage-dot 的状态色，但去掉 timeline 的定位和环 */
.debug-empty-legend-item .debug-stage-dot {
  position: static;
  width: 8px;
  height: 8px;
  border: none;
  box-shadow: none;
}

/* 智能诊断面板 */
.debug-diagnose {
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 12px;
}
.debug-diagnose-header {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.debug-diagnose-title {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}
.debug-diagnose-sub {
  font-size: 11px;
  color: #94a3b8;
}
.debug-diagnose-card {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-left: 3px solid #cbd5e1;
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.debug-diagnose-card.debug-diagnose-ok      { border-left-color: #10b981; }
.debug-diagnose-card.debug-diagnose-info    { border-left-color: #3b82f6; }
.debug-diagnose-card.debug-diagnose-warning { border-left-color: #f59e0b; }
.debug-diagnose-card.debug-diagnose-error   { border-left-color: #ef4444; }
.debug-diagnose-card-header {
  display: flex;
  align-items: center;
  gap: 6px;
}
.debug-diagnose-icon {
  font-size: 14px;
  font-weight: 700;
  width: 18px;
  text-align: center;
  flex-shrink: 0;
}
.debug-diagnose-ok .debug-diagnose-icon      { color: #10b981; }
.debug-diagnose-info .debug-diagnose-icon    { color: #3b82f6; }
.debug-diagnose-warning .debug-diagnose-icon { color: #f59e0b; }
.debug-diagnose-error .debug-diagnose-icon   { color: #ef4444; }
.debug-diagnose-card-title {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}
.debug-diagnose-section-title {
  font-size: 10px;
  font-weight: 600;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 4px;
}
.debug-diagnose-section ul,
.debug-diagnose-section ol {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: #475569;
  line-height: 1.7;
}
.debug-diagnose-section li + li {
  margin-top: 2px;
}

/* 时间线 */
.debug-timeline {
  position: relative;
  padding-left: 22px;
  margin: 4px 0;
}
.debug-timeline::before {
  content: "";
  position: absolute;
  left: 6px;
  top: 6px;
  bottom: 6px;
  width: 2px;
  background: #e5e7eb;
  border-radius: 1px;
}
.debug-stage {
  position: relative;
  margin-bottom: 12px;
}
.debug-stage:last-child {
  margin-bottom: 0;
}
.debug-stage-dot {
  position: absolute;
  left: -20px;
  top: 5px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #10b981;
  border: 2px solid #fff;
  box-shadow: 0 0 0 2px #10b981;
}
.debug-stage-dot.ok {
  background: #10b981;
  box-shadow: 0 0 0 2px #10b981;
}
.debug-stage-dot.fallback {
  background: #f59e0b;
  box-shadow: 0 0 0 2px #f59e0b;
}
.debug-stage-dot.skipped {
  background: #9ca3af;
  box-shadow: 0 0 0 2px #9ca3af;
}
.debug-stage-dot.error {
  background: #ef4444;
  box-shadow: 0 0 0 2px #ef4444;
}
.debug-stage-body {
  background: #f8fafc;
  border-radius: 8px;
  padding: 8px 12px;
  border: 1px solid #f1f5f9;
}
.debug-stage-header {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  cursor: pointer;
  user-select: none;
}
.debug-stage-header:hover .debug-stage-label {
  color: #4f46e5;
}
.debug-expand-icon {
  font-size: 11px;
  color: #94a3b8;
  width: 12px;
  flex-shrink: 0;
}
.debug-stage-label {
  font-weight: 600;
  color: #1e293b;
  font-size: 13px;
}
.debug-stage-dur {
  font-size: 11px;
  color: #64748b;
  font-variant-numeric: tabular-nums;
  background: #f1f5f9;
  padding: 1px 6px;
  border-radius: 4px;
}
.debug-stage-status {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
  margin-left: auto;
}
.debug-stage-status.ok {
  color: #059669;
  background: #ecfdf5;
}
.debug-stage-status.fallback {
  color: #d97706;
  background: #fffbeb;
}
.debug-stage-status.skipped {
  color: #6b7280;
  background: #f3f4f6;
}
.debug-stage-status.error {
  color: #dc2626;
  background: #fef2f2;
}
.debug-stage-detail {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
  word-break: break-all;
  line-height: 1.5;
}

/* 展开详情区域 */
.debug-stage-expand {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #e2e8f0;
}

/* 文档表格 */
.debug-doc-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.debug-doc-table th {
  text-align: left;
  font-weight: 600;
  color: #64748b;
  padding: 4px 6px;
  border-bottom: 1px solid #e2e8f0;
  font-size: 11px;
}
.debug-doc-table td {
  padding: 4px 6px;
  border-bottom: 1px solid #f1f5f9;
  color: #334155;
}
.debug-col-idx {
  width: 24px;
  text-align: center;
  font-weight: 700;
  color: #4f46e5;
}
.debug-col-title {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.debug-col-chunk {
  color: #94a3b8;
  font-variant-numeric: tabular-nums;
}
.debug-col-score {
  font-variant-numeric: tabular-nums;
  color: #64748b;
  text-align: right;
}

/* 键值对列表 */
.debug-kv-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.debug-kv-row {
  display: flex;
  gap: 8px;
  font-size: 12px;
}
.debug-kv-key {
  color: #64748b;
  flex-shrink: 0;
  min-width: 60px;
}
.debug-kv-val {
  color: #334155;
  word-break: break-all;
}
.debug-kv-val.muted {
  color: #94a3b8;
  font-style: italic;
}

/* 汇总 */
.debug-summary {
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 12px;
  color: #166534;
  font-weight: 500;
}

/* AI 回复 */
.debug-response {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 12px;
}
.debug-response-label {
  font-size: 11px;
  font-weight: 600;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 8px;
}
.debug-response-text {
  font-size: 13px;
  line-height: 1.7;
  color: #1e293b;
  word-break: break-word;
}
.debug-response-text :deep(p) { margin: 6px 0; }
.debug-response-text :deep(p:first-child) { margin-top: 0; }
.debug-response-text :deep(p:last-child) { margin-bottom: 0; }
.debug-response-text :deep(h1),
.debug-response-text :deep(h2),
.debug-response-text :deep(h3),
.debug-response-text :deep(h4) {
  font-weight: 700;
  color: #0f172a;
  margin: 14px 0 6px;
  line-height: 1.3;
}
.debug-response-text :deep(h1) { font-size: 17px; }
.debug-response-text :deep(h2) { font-size: 15px; }
.debug-response-text :deep(h3) { font-size: 14px; }
.debug-response-text :deep(h4) { font-size: 13px; }
.debug-response-text :deep(strong) { font-weight: 600; }
.debug-response-text :deep(em) { font-style: italic; }
.debug-response-text :deep(del) { text-decoration: line-through; color: #94a3b8; }
.debug-response-text :deep(ul),
.debug-response-text :deep(ol) {
  margin: 6px 0;
  padding-left: 22px;
}
.debug-response-text :deep(li) {
  margin: 3px 0;
  line-height: 1.65;
}
.debug-response-text :deep(li > p) { margin: 2px 0; }
.debug-response-text :deep(code) {
  background: #f1f5f9;
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: #334155;
}
.debug-response-text :deep(pre) {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 10px 12px;
  overflow-x: auto;
  margin: 8px 0;
  font-size: 12px;
  line-height: 1.6;
}
.debug-response-text :deep(pre code) {
  background: transparent;
  padding: 0;
  font-size: 12px;
  color: #1e293b;
  white-space: pre;
}
.debug-response-text :deep(blockquote) {
  border-left: 3px solid #cbd5e1;
  padding: 2px 0 2px 12px;
  color: #64748b;
  margin: 8px 0;
  background: #f8fafc;
  border-radius: 0 4px 4px 0;
}
.debug-response-text :deep(blockquote > p) { margin: 4px 0; }
.debug-response-text :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  width: 100%;
  font-size: 12px;
}
.debug-response-text :deep(th),
.debug-response-text :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 5px 8px;
  text-align: left;
}
.debug-response-text :deep(th) {
  background: #f8fafc;
  font-weight: 600;
  color: #334155;
}
.debug-response-text :deep(hr) {
  border: none;
  border-top: 1px solid #e5e7eb;
  margin: 12px 0;
}
.debug-response-text :deep(a) {
  color: #4f46e5;
  text-decoration: none;
}
.debug-response-text :deep(a:hover) {
  text-decoration: underline;
}

/* 打字动画 */
.debug-typing {
  display: flex;
  gap: 4px;
  padding: 4px 0;
}
.debug-typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #94a3b8;
  animation: debug-bounce 1.2s infinite;
}
.debug-typing span:nth-child(2) {
  animation-delay: 0.2s;
}
.debug-typing span:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes debug-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
  30% { transform: translateY(-6px); opacity: 1; }
}

/* 引用 */
.debug-citations {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid #f1f5f9;
}
.debug-citations-label {
  font-size: 11px;
  color: #94a3b8;
  font-weight: 500;
}
.debug-citation-link {
  display: inline-block;
  font-size: 12px;
  color: #4f46e5;
  text-decoration: none;
  margin-right: 10px;
}
.debug-citation-link:hover {
  text-decoration: underline;
}

/* Chunk 可点击链接 */
.debug-chunk-link {
  color: #4f46e5;
  cursor: pointer;
  text-decoration: none;
  border-bottom: 1px dashed #a5b4fc;
  transition: color 0.15s, border-color 0.15s;
}
.debug-chunk-link:hover {
  color: #3730a3;
  border-bottom-style: solid;
}

/* Rerank 通过/过滤状态 */
.debug-row-filtered {
  opacity: 0.45;
}
.debug-col-passed {
  text-align: center;
}
.debug-passed-yes {
  font-size: 11px;
  font-weight: 600;
  color: #059669;
  background: #ecfdf5;
  padding: 1px 6px;
  border-radius: 4px;
}
.debug-passed-no {
  font-size: 11px;
  font-weight: 600;
  color: #dc2626;
  background: #fef2f2;
  padding: 1px 6px;
  border-radius: 4px;
}

/* 弹窗 */
.debug-modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  animation: debug-fade-in 0.15s ease;
}
@keyframes debug-fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}
.debug-modal {
  background: #fff;
  border-radius: 12px;
  width: 560px;
  max-width: 90vw;
  max-height: 70vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}
.debug-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid #e5e7eb;
}
.debug-modal-title {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
}
.debug-modal-close {
  border: none;
  background: transparent;
  font-size: 20px;
  color: #94a3b8;
  cursor: pointer;
  padding: 0 4px;
  line-height: 1;
  transition: color 0.15s;
}
.debug-modal-close:hover {
  color: #475569;
}
.debug-modal-body {
  padding: 16px 18px;
  overflow-y: auto;
  font-size: 13px;
  line-height: 1.7;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-word;
  flex: 1;
}
</style>
