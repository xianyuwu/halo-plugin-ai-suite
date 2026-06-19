<template>
  <div class="chat-logs-page">
    <div class="ai-content">
      <!-- 顶部 4 个指标卡 -->
      <div class="ai-metric-grid">
        <MetricCard
          color="blue"
          icon="message"
          label="总问答数"
          :value="stats.totalLogs"
          :value-fmt="formatNum"
          :delta-text="last7DaysLabel"
        />
        <MetricCard
          color="green"
          icon="pen"
          label="反馈覆盖率"
          :value="feedbackRate"
          :value-fmt="(n) => formatPct(n, 1)"
          :delta-text="(stats.likes + stats.dislikes) + ' 反馈 / ' + stats.totalLogs + ' 总'"
        />
        <MetricCard
          :color="stats.dislikeRate > 20 ? 'red' : 'green'"
          icon="alert"
          label="点踩率"
          :value="stats.dislikeRate"
          :value-fmt="(n) => formatPct(n, 1)"
          :value-class="stats.dislikeRate > 20 ? 'fail' : 'ok'"
          :delta-text="stats.dislikes + ' 点踩 / ' + (stats.likes + stats.dislikes) + ' 反馈'"
        />
        <MetricCard
          color="purple"
          icon="sparkles"
          label="今日新增"
          :value="stats.todayNew"
          :value-fmt="formatNum"
          :delta="deltaNewLogs.direction === 'flat' ? 'vs 昨日' : 'vs 昨日'"
          :delta-value="deltaNewLogs.value"
          delta-suffix="%"
          :delta-direction="deltaNewLogs.direction"
        />
      </div>

      <div class="ai-section-block">
        <div class="ai-section-heading">
          <h2>访客问答记录</h2>
          <span class="ai-section-tag">共 {{ total }} 条</span>
        </div>
        <article class="ai-section-card">
          <div class="ai-card-body" style="padding: 0;">
            <!-- 工具栏：筛选 + 操作按钮 -->
            <div class="ai-batch-toolbar">
              <div class="ai-filter-group">
                <select v-model="filters.range" @change="reload">
                  <option value="today">今天</option>
                  <option value="7d">7 天</option>
                  <option value="30d">30 天</option>
                  <option value="all">全部</option>
                </select>
                <select v-model="filters.model" @change="reload">
                  <option value="">全部模型</option>
                  <option v-for="m in models" :key="m" :value="m">{{ m }}</option>
                </select>
                <input v-model="filters.question" @input="debouncedReload" placeholder="搜索问题..." />
                <select v-model="filters.feedbackType" @change="reload">
                  <option value="all">全部反馈</option>
                  <option value="like">点赞</option>
                  <option value="dislike">点踩</option>
                  <option value="none">— 无反馈</option>
                </select>
              </div>
              <div class="ai-batch-actions">
                <VButton size="xs" type="default" @click="loadAll">刷新</VButton>
                <VButton size="xs" type="default" @click="exportCSV">导出 CSV</VButton>
                <VButton
                  size="xs"
                  :type="selectedIds.size > 0 ? 'danger' : 'default'"
                  :disabled="selectedIds.size === 0"
                  @click="askBatchDelete"
                >批量删除{{ selectedIds.size > 0 ? ` (${selectedIds.size})` : '' }}</VButton>
              </div>
            </div>

            <div class="ai-table-wrap">
              <table class="ai-table chat-logs-table">
                <thead>
                  <tr>
                    <th style="width: 40px;" class="text-center">
                      <input type="checkbox" :checked="allSelected" @change="toggleSelectAll" />
                    </th>
                    <th style="width: 15%; text-align: left;">时间</th>
                    <th style="width: 14%; text-align: left;">模型</th>
                    <th style="text-align: left;">问题</th>
                    <th style="width: 8%;" class="text-center">反馈</th>
                    <th style="width: 14%; text-align: left;">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="log in items" :key="log.id" @click="handleRowClick($event, log)" class="chat-logs-row" :class="{ 'row-selected': selectedIds.has(log.id) }">
                    <td class="text-center" @click.stop>
                      <input type="checkbox" :checked="selectedIds.has(log.id)" @change="toggleSelect(log.id)" />
                    </td>
                    <td class="ai-cell-date">{{ formatTime(log.timestamp) }}</td>
                    <td><code>{{ log.model }}</code></td>
                    <td class="chat-logs-q">{{ truncate(log.question, 50) }}</td>
                    <td class="ai-cell-center">
                      <span v-if="log.feedback" :class="{
                        'chat-logs-fb-like': log.feedback.type === 'like',
                        'chat-logs-fb-dislike': log.feedback.type === 'dislike'
                      }">
                        <template v-if="log.feedback.type === 'like'"><RiThumbUpLine /></template>
                        <template v-else><RiThumbDownLine /></template>
                      </span>
                      <span v-else class="chat-logs-fb-none">—</span>
                    </td>
                    <td class="actions-cell" style="text-align: left;">
                      <VSpace spacing="sm">
                        <VButton size="xs" type="default" @click="openDetail(log)">详情</VButton>
                        <VButton size="xs" type="danger" @click="askDelete(log)">删除</VButton>
                      </VSpace>
                    </td>
                  </tr>
                  <tr v-if="items.length === 0">
                    <td colspan="6" class="ai-empty">暂无记录 — 访客发问后会出现在这里</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div class="ai-table-footer">
              <span class="ai-filter-stat">
                共 {{ total }} 条
                <span v-if="selectedIds.size > 0" class="ai-selected-stat"> · 已选 {{ selectedIds.size }} 条</span>
              </span>
              <div class="ai-pagination-controls">
                <select class="ai-pagination-select" v-model.number="size" @change="onPageSizeChange">
                  <option :value="10">10 条/页</option>
                  <option :value="20">20 条/页</option>
                  <option :value="50">50 条/页</option>
                </select>
                <VButton size="xs" type="default" :disabled="page === 0" @click="changePage(page - 1)">‹</VButton>
                <span class="ai-pagination-info">{{ page + 1 }} / {{ totalPages }}</span>
                <VButton size="xs" type="default" :disabled="page >= totalPages - 1" @click="changePage(page + 1)">›</VButton>
              </div>
            </div>
          </div>
        </article>
      </div>
    </div>

    <!-- 详情弹窗 -->
    <div class="detail-modal-mask" v-if="detailLog" @click="detailLog = null"></div>
    <div class="detail-modal" v-if="detailLog" @click.self="detailLog = null">
      <div class="detail-modal-dialog">
        <div class="detail-modal-header">
          <h3>问答详情</h3>
          <button class="detail-modal-close" @click="detailLog = null">&times;</button>
        </div>
        <div class="detail-modal-body">
          <div class="detail-meta-row">
            <span class="detail-meta-item"><span class="detail-meta-label">时间</span>{{ formatTime(detailLog.timestamp) }}</span>
            <span class="detail-meta-item"><span class="detail-meta-label">模型</span><code>{{ detailLog.model }}</code></span>
            <span class="detail-meta-item"><span class="detail-meta-label">IP</span>{{ detailLog.ip }}</span>
          </div>
          <template v-if="detailLog.userAgent">
            <div class="detail-ua-tags">
              <span class="detail-ua-tag"><RiGlobalLine /> {{ parseUA(detailLog.userAgent).browser }}</span>
              <span class="detail-ua-tag"><RiComputerLine /> {{ parseUA(detailLog.userAgent).os }}</span>
              <span class="detail-ua-tag"><RiSmartphoneLine /> {{ parseUA(detailLog.userAgent).device }}</span>
            </div>
            <details class="detail-ua-raw">
              <summary>原始 UA</summary>
              <code>{{ detailLog.userAgent }}</code>
            </details>
          </template>
          <h4>问题</h4>
          <pre class="detail-pre">{{ detailLog.question }}</pre>
          <h4>回答</h4>
          <pre class="detail-pre detail-pre-answer">{{ detailLog.answer }}</pre>
          <template v-if="detailLog.citations && detailLog.citations.length > 0">
            <h4>引用文章 ({{ detailLog.citations.length }})</h4>
            <ul class="detail-citations">
              <li v-for="(c, i) in detailLog.citations" :key="i">
                <a v-if="c.url" :href="c.url" target="_blank">[{{ i + 1 }}] {{ c.title }}</a>
                <span v-else>[{{ i + 1 }}] {{ c.title }}</span>
              </li>
            </ul>
          </template>
          <template v-if="detailLog.feedback">
            <h4>反馈</h4>
            <div class="detail-feedback">
              <span :class="{
                'chat-logs-fb-like': detailLog.feedback.type === 'like',
                'chat-logs-fb-dislike': detailLog.feedback.type === 'dislike'
              }">
                <template v-if="detailLog.feedback.type === 'like'"><RiThumbUpLine /> 点赞</template>
                <template v-else><RiThumbDownLine /> 点踩</template>
              </span>
              <span class="detail-feedback-time">{{ formatTime(detailLog.feedback.timestamp) }}</span>
              <p v-if="detailLog.feedback.comment" class="detail-feedback-comment">
                {{ detailLog.feedback.comment }}
              </p>
            </div>
          </template>
          <!-- 管线追踪（点踩时自动记录） -->
          <template v-if="detailLog.traceStagesJson">
            <h4>
              管线追踪
              <span v-if="detailLog.traceIntent" class="trace-intent-tag">{{ formatIntent(detailLog.traceIntent) }}</span>
            </h4>

            <!-- 智能诊断：基于已存的 trace 数据即时计算 -->
            <div v-if="traceFindings.length > 0" class="trace-diagnose">
              <div class="trace-diagnose-header">
                <span class="trace-diagnose-title"><RiSearchLine /> 智能诊断</span>
                <span class="trace-diagnose-sub">基于本次管线数据</span>
              </div>
              <div
                v-for="(f, fi) in traceFindings"
                :key="fi"
                class="trace-diagnose-card"
                :class="`trace-diagnose-${f.level}`"
              >
                <div class="trace-diagnose-card-header">
                  <span class="trace-diagnose-icon"><component
                    :is="f.level === 'ok' ? RiCheckLine
                      : f.level === 'info' ? RiInformationLine
                      : f.level === 'warning' ? RiAlertLine
                      : RiCloseLine"
                  /></span>
                  <span class="trace-diagnose-card-title">{{ f.title }}</span>
                </div>
                <div v-if="f.causes.length" class="trace-diagnose-section">
                  <div class="trace-diagnose-section-title">可能原因</div>
                  <ul><li v-for="c in f.causes" :key="c">{{ c }}</li></ul>
                </div>
                <div v-if="f.suggestions.length" class="trace-diagnose-section">
                  <div class="trace-diagnose-section-title">排查路径</div>
                  <ol><li v-for="s in f.suggestions" :key="s">{{ s }}</li></ol>
                </div>
              </div>
            </div>

            <!-- 阶段时间线，点击 header 展开详情 -->
            <div class="trace-timeline">
              <div v-for="(stage, i) in parseTraceStages(detailLog.traceStagesJson)" :key="i" class="trace-stage-item">
                <div class="trace-stage-header" @click="toggleTraceStage(i)">
                  <span class="trace-expand-icon">{{ traceExpanded[i] ? '▾' : '▸' }}</span>
                  <span class="trace-dot" :class="stage.status"></span>
                  <span class="trace-stage-label">{{ stage.label }}</span>
                  <span class="trace-stage-dur">{{ stage.durationMs }}ms</span>
                  <span class="trace-stage-status" :class="stage.status">{{ stage.statusLabel }}</span>
                </div>
                <span v-if="stage.detail && !traceExpanded[i]" class="trace-stage-detail">{{ stage.detail }}</span>
                <div v-if="traceExpanded[i]" class="trace-stage-expand">
                  <span v-if="stage.detail" class="trace-stage-detail" style="margin-bottom: 6px;">{{ stage.detail }}</span>
                  <TraceStageDetail :stage="stage" />
                </div>
              </div>
            </div>
          </template>
        </div>
        <div class="detail-modal-footer">
          <button
            class="ai-btn ai-btn-xs detail-trace-btn"
            :class="{ 'detail-trace-btn-dislike': detailLog.feedback?.type === 'dislike' }"
            @click="openTrace(detailLog)"
          ><RiSearchLine /> 追踪调试</button>
          <span v-if="detailLog.feedback?.type === 'dislike'" class="detail-trace-hint">点踩记录 — 建议追踪排查原因</span>
        </div>
      </div>
    </div>

    <!-- 追踪调试弹窗 -->
    <div class="trace-modal-mask" v-if="showTrace" @click="showTrace = false"></div>
    <div class="trace-modal" v-if="showTrace" @click.self="showTrace = false">
      <div class="trace-modal-dialog">
        <div class="trace-modal-header">
          <h3><RiSearchLine /> 追踪调试</h3>
          <span class="trace-modal-question">{{ traceQuery }}</span>
          <button class="trace-modal-back" @click="backToDetail" title="返回问答详情">← 返回</button>
          <button class="detail-modal-close" @click="showTrace = false">&times;</button>
        </div>
        <div class="trace-modal-body">
          <DebugTrace :initial-query="traceQuery" />
        </div>
      </div>
    </div>

    <!-- 删除单条确认弹窗 (VDialog 标准结构: title + description + onConfirm) -->
    <VDialog
      v-model:visible="showDeleteDialog"
      type="warning"
      :title="'删除问答记录'"
      :description="deleteDescription"
      confirm-type="danger"
      :confirm-text="'删除'"
      :cancel-text="'取消'"
      :on-confirm="confirmDelete"
      :on-cancel="cancelDelete"
    />

    <!-- 批量删除确认弹窗 -->
    <VDialog
      v-model:visible="showBatchDeleteDialog"
      type="warning"
      :title="'批量删除问答记录'"
      :description="batchDeleteDescription"
      confirm-type="danger"
      :confirm-text="'删除 ' + selectedIds.size + ' 条'"
      :cancel-text="'取消'"
      :on-confirm="confirmBatchDelete"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { Toast, VDialog, VButton, VSpace } from "@halo-dev/components";
import MetricCard from "../components/MetricCard.vue";
import DebugTrace from "../components/DebugTrace.vue";
import TraceStageDetail from "../components/TraceStageDetail.vue";
import { computeFindings, type TraceStage, type Finding } from "../composables/useTraceFindings";
import { formatNum, formatPct, computeDelta } from "../utils/format";
import RiThumbUpLine from "~icons/ri/thumb-up-line";
import RiThumbDownLine from "~icons/ri/thumb-down-line";
import RiGlobalLine from "~icons/ri/global-line";
import RiComputerLine from "~icons/ri/computer-line";
import RiSmartphoneLine from "~icons/ri/smartphone-line";
import RiSearchLine from "~icons/ri/search-line";
import RiCheckLine from "~icons/ri/check-line";
import RiAlertLine from "~icons/ri/alert-line";
import RiCloseLine from "~icons/ri/close-line";
import RiInformationLine from "~icons/ri/information-line";

const API = "/apis/console.api.ai-suite.halo.run/v1alpha1";

interface Feedback { type: string; comment: string; timestamp: string; }
interface LogEntry {
  id: string;
  timestamp: string;
  ip: string;
  userAgent: string;
  question: string;
  answer: string;
  model: string;
  citations: Array<{ title: string; postId: string; url: string }>;
  feedback: Feedback | null;
  traceIntent: string | null;
  traceStagesJson: string | null;
}
interface PageResult { items: LogEntry[]; total: number; page: number; size: number; }
interface Stats { totalLogs: number; likes: number; dislikes: number; none: number; todayNew: number; last7Days: number; dislikeRate: number; }

const items = ref<LogEntry[]>([]);
const total = ref(0);
const page = ref(0);
const size = ref(10);
const stats = ref<Stats>({ totalLogs: 0, likes: 0, dislikes: 0, none: 0, todayNew: 0, last7Days: 0, dislikeRate: 0 });
const models = ref<string[]>([]);
const dislikeLogs = ref<LogEntry[]>([]);
const detailLog = ref<LogEntry | null>(null);
const showTrace = ref(false);
const traceQuery = ref("");
const traceExpanded = ref<Record<number, boolean>>({});
const traceFindings = computed<Finding[]>(() => {
  if (!detailLog.value?.traceStagesJson) return [];
  const stages = parseTraceStages(detailLog.value.traceStagesJson) as TraceStage[];
  // 没有 aiResponse 引用信息时不传 citations 参数，跳过"无引用"检查
  return computeFindings(stages, { totalMs: 0, stageCount: stages.length, intent: detailLog.value.traceIntent || "" }, null);
});

function toggleTraceStage(index: number) {
  traceExpanded.value = { ...traceExpanded.value, [index]: !traceExpanded.value[index] };
}

// 删除弹窗状态
const showDeleteDialog = ref(false);
const showBatchDeleteDialog = ref(false);
const pendingDelete = ref<LogEntry | null>(null);
const selectedIds = ref<Set<string>>(new Set());

const allSelected = computed(() =>
  items.value.length > 0 && items.value.every((log) => selectedIds.value.has(log.id))
);

function toggleSelect(id: string) {
  const next = new Set(selectedIds.value);
  if (next.has(id)) next.delete(id); else next.add(id);
  selectedIds.value = next;
}

function toggleSelectAll() {
  if (allSelected.value) {
    selectedIds.value = new Set();
  } else {
    selectedIds.value = new Set(items.value.map((log) => log.id));
  }
}

const deleteDescription = computed(() => {
  const q = pendingDelete.value?.question || "";
  const truncated = q.length > 80 ? q.substring(0, 80) + "..." : q;
  return `确定要删除这条问答记录吗？删除后不可恢复。\n\n问题：${truncated}`;
});

const batchDeleteDescription = computed(() =>
  `确定要删除选中的 ${selectedIds.value.size} 条问答记录吗？删除后不可恢复。`
);

// 顶部指标卡衍生数据
const feedbackRate = computed(() => {
  if (stats.value.totalLogs === 0) return 0;
  return ((stats.value.likes + stats.value.dislikes) * 100) / stats.value.totalLogs;
});
const last7DaysLabel = computed(() => {
  return `7 天共 ${stats.value.last7Days} 条 · 日均 ${formatNum(
    stats.value.last7Days > 0 ? stats.value.last7Days / 7 : 0
  )}`;
});
// 后端 yesterday.newLogs 提供基线
const deltaNewLogs = computed(() =>
  computeDelta(stats.value.todayNew, (stats.value as any).yesterday?.newLogs ?? 0)
);

const filters = reactive({
  range: "7d" as "today" | "7d" | "30d" | "all",
  model: "",
  feedbackType: "all" as "all" | "like" | "dislike" | "none",
  question: "",
});

// debounce: 文本输入停顿 300ms 后自动搜索
let debounceTimer: ReturnType<typeof setTimeout> | null = null;
function debouncedReload() {
  if (debounceTimer) clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => { reload(); }, 300);
}

// 卸载时清理防抖 timer, 否则用户输入后立即切走页面, 300ms 后仍会 fetch 并写已卸载组件状态
onUnmounted(() => {
  if (debounceTimer) clearTimeout(debounceTimer);
});

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)));

function rangeToFromTo(range: string): { from?: string; to?: string } {
  const now = new Date();
  const to = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 0);
  if (range === "all") return {};
  const days = range === "today" ? 0 : range === "7d" ? 6 : 29;
  const from = new Date(now.getFullYear(), now.getMonth(), now.getDate() - days, 0, 0, 0);
  return { from: from.toISOString(), to: to.toISOString() };
}

async function loadAll() {
  await Promise.all([loadList(), loadStats(), loadDislikeLogs()]);
}

async function loadStats() {
  try {
    const { from, to } = rangeToFromTo(filters.range);
    const params = new URLSearchParams();
    if (from) params.set("from", from);
    if (to) params.set("to", to);
    const resp = await fetch(API + "/chat-logs/stats?" + params.toString());
    if (!resp.ok) return;
    const data = await resp.json();
    stats.value = {
      totalLogs: data.totalLogs || 0,
      likes: data.feedbackDistribution?.like || 0,
      dislikes: data.feedbackDistribution?.dislike || 0,
      none: data.feedbackDistribution?.none || 0,
      todayNew: data.todayNew || 0,
      last7Days: data.last7Days || 0,
      dislikeRate: data.dislikeRate || 0,
    };
    // 提取所有用过的模型名，按使用量降序排列
    const byModel: Record<string, number> = data.totalByModel || {};
    models.value = Object.entries(byModel)
      .sort((a, b) => b[1] - a[1])
      .map(([name]) => name);
  } catch {}
}

async function loadDislikeLogs() {
  try {
    const resp = await fetch(API + "/chat-logs?page=0&size=200&feedbackType=dislike");
    if (!resp.ok) return;
    const data = await resp.json();
    dislikeLogs.value = data.items || [];
  } catch {}
}

function exportCSV() {
  const header = ["时间", "IP", "问题", "回答", "模型", "反馈类型", "反馈评论"];
  const rows = dislikeLogs.value.map(l => [
    l.timestamp, l.ip, l.question, l.answer, l.model,
    l.feedback ? l.feedback.type : "",
    l.feedback ? l.feedback.comment : ""
  ]);
  const csv = "﻿" + header.map(h => `"${String(h).replace(/"/g, '""')}"`).join(",") + "\n" +
    rows.map(r => r.map(c => `"${String(c == null ? "" : c).replace(/"/g, '""')}"`).join(",")).join("\n");
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "chat-feedback-dislike-" + new Date().toISOString().slice(0, 10) + ".csv";
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(url); a.remove(); }, 100);
  Toast.success("已导出 CSV");
}

async function loadList() {
  const { from, to } = rangeToFromTo(filters.range);
  const params = new URLSearchParams();
  params.set("page", String(page.value));
  params.set("size", String(size.value));
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  if (filters.model) params.set("model", filters.model);
  if (filters.question) params.set("question", filters.question);
  params.set("feedbackType", filters.feedbackType);

  try {
    const resp = await fetch(API + "/chat-logs?" + params.toString());
    if (!resp.ok) return;
    const data: PageResult = await resp.json();
    items.value = data.items || [];
    total.value = data.total || 0;
  } catch {}
}

function reload() {
  page.value = 0;
  selectedIds.value = new Set();
  loadList();
  loadStats();
}

function changePage(p: number) {
  if (p < 0 || p >= totalPages.value) return;
  page.value = p;
  selectedIds.value = new Set();
  loadList();
}

function onPageSizeChange() {
  page.value = 0;
  selectedIds.value = new Set();
  loadList();
}

function handleRowClick(event: MouseEvent, log: LogEntry) {
  const target = event.target as HTMLElement | null;
  if (target?.closest(".actions-cell, button, input, select, textarea, a, label")) {
    return;
  }
  openDetail(log);
}

function openDetail(log: LogEntry) {
  detailLog.value = log;
}

function openTrace(log: LogEntry) {
  detailLog.value = null;          // 关闭详情弹窗
  traceQuery.value = log.question; // 预填原始问题
  showTrace.value = true;          // 打开追踪弹窗
}

function backToDetail() {
  showTrace.value = false;         // 关闭追踪弹窗
  // detailLog 之前在 openTrace 里被置空，重新从当前列表中找回
  if (traceQuery.value) {
    const found = items.value.find(i => i.question === traceQuery.value);
    if (found) detailLog.value = found;
  }
}

function truncate(s: string, n: number): string {
  if (!s) return "";
  return s.length > n ? s.substring(0, n) + "..." : s;
}

function formatTime(iso: string): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  const y = d.getFullYear();
  const mo = String(d.getMonth() + 1).padStart(2, "0");
  const da = String(d.getDate()).padStart(2, "0");
  const h = String(d.getHours()).padStart(2, "0");
  const mi = String(d.getMinutes()).padStart(2, "0");
  return `${y}-${mo}-${da} ${h}:${mi}`;
}

// 解析 traceStagesJson 字符串为数组
function parseTraceStages(json: string): Array<{ name: string; label: string; durationMs: number; status: string; statusLabel: string; detail: string }> {
  try { return JSON.parse(json) || []; } catch { return []; }
}

// 意图枚举 → 中文
function formatIntent(raw: string): string {
  const map: Record<string, string> = {
    NORMAL_CHAT: "普通对话 (RAG)",
    HOT_ARTICLES: "热门文章",
    LATEST_ARTICLES: "最新文章",
    "builtin-hot-articles": "热门文章",
    "builtin-latest-posts": "最新文章",
    "builtin-by-tag": "按标签查询",
    "builtin-by-category": "按分类查询",
  };
  return map[raw] || raw;
}

// 解析 User-Agent 字符串，提取浏览器、操作系统、设备类型
function parseUA(ua: string): { browser: string; os: string; device: string } {
  if (!ua) return { browser: "未知", os: "未知", device: "未知" };

  // 浏览器（先匹配细分品牌，再兜底）
  let browser = "未知浏览器";
  if (/Edg\/(\d+)/.test(ua)) browser = "Edge " + RegExp.$1;
  else if (/OPR\/(\d+)/.test(ua)) browser = "Opera " + RegExp.$1;
  else if (/Chrome\/(\d+)/.test(ua)) browser = "Chrome " + RegExp.$1;
  else if (/Firefox\/(\d+)/.test(ua)) browser = "Firefox " + RegExp.$1;
  else if (/Version\/(\d+).*Safari/.test(ua)) browser = "Safari " + RegExp.$1;

  // 操作系统
  let os = "未知系统";
  const winMap: Record<string, string> = {
    "10.0": "10", "6.3": "8.1", "6.2": "8", "6.1": "7", "6.0": "Vista",
  };
  const winMatch = ua.match(/Windows NT (\d+\.\d+)/);
  if (winMatch) {
    os = "Windows " + (winMap[winMatch[1]] || winMatch[1]);
  } else if (/Mac OS X ([\d_]+)/.test(ua)) {
    os = "macOS " + RegExp.$1.replace(/_/g, ".");
  } else if (/Android (\d+)/.test(ua)) {
    os = "Android " + RegExp.$1;
  } else if (/iPhone OS (\d+)/.test(ua) || /iPad.*OS (\d+)/.test(ua)) {
    os = "iOS " + RegExp.$1;
  } else if (/Linux/.test(ua)) {
    os = "Linux";
  }

  // 设备类型
  let device = "桌面端";
  if (/iPad/.test(ua)) device = "平板";
  else if (/Mobile|Android.*Mobile|iPhone/.test(ua)) device = "移动端";

  return { browser, os, device };
}

function askDelete(log: LogEntry) {
  pendingDelete.value = log;
  showDeleteDialog.value = true;
}

async function confirmDelete() {
  const log = pendingDelete.value;
  if (!log) return;
  // 乐观更新：立刻从列表中移除行 + 减少总数, UI 瞬间响应
  const idx = items.value.findIndex((x) => x.id === log.id);
  if (idx >= 0) items.value.splice(idx, 1);
  total.value = Math.max(0, total.value - 1);
  stats.value = {
    ...stats.value,
    totalLogs: Math.max(0, stats.value.totalLogs - 1),
  };
  if (log.feedback) {
    if (log.feedback.type === "like") {
      stats.value = { ...stats.value, likes: Math.max(0, stats.value.likes - 1) };
    } else if (log.feedback.type === "dislike") {
      stats.value = { ...stats.value, dislikes: Math.max(0, stats.value.dislikes - 1) };
    }
  } else {
    stats.value = { ...stats.value, none: Math.max(0, stats.value.none - 1) };
  }
  // 背景同步到后端
  try {
    const resp = await fetch(API + "/chat-logs/" + log.id, { method: "DELETE" });
    const data = await resp.json();
    if (!data.deleted) {
      // 后端失败: 重新拉一次
      Toast.error("删除失败: " + (data.error || ""));
      await loadAll();
    } else {
      Toast.success("已删除");
      // 同步后端统计，避免乐观更新与实际不一致
      await loadStats();
    }
  } catch (e: any) {
    Toast.error("删除失败: " + e.message);
    await loadAll();
  }
}

function cancelDelete() {
  pendingDelete.value = null;
}

async function askBatchDelete() {
  if (selectedIds.value.size === 0) return;
  showBatchDeleteDialog.value = true;
}

async function confirmBatchDelete() {
  const ids = [...selectedIds.value];
  // 乐观更新：立刻从列表中移除选中行
  items.value = items.value.filter((x) => !selectedIds.value.has(x.id));
  total.value = Math.max(0, total.value - ids.length);
  stats.value = { ...stats.value, totalLogs: Math.max(0, stats.value.totalLogs - ids.length) };
  selectedIds.value = new Set();

  // 并行调用单条 DELETE 接口
  const results = await Promise.allSettled(
    ids.map((id) => fetch(API + "/chat-logs/" + id, { method: "DELETE" }).then((r) => r.json()))
  );
  const failed = results.filter((r) => r.status === "rejected" || (r.status === "fulfilled" && !r.value.deleted));
  if (failed.length > 0) {
    Toast.error(`${failed.length} 条删除失败，已刷新列表`);
    await loadAll();
  } else {
    Toast.success(`已删除 ${ids.length} 条`);
    // 刷新统计数据
    await loadStats();
  }
}

onMounted(() => {
  loadAll();
});
</script>

<style scoped>
.chat-logs-row { cursor: pointer; }
.row-selected td { background: #eff6ff !important; }
.chat-logs-q { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.th-center { text-align: center; }
.ai-cell-center { text-align: center; }
.chat-logs-fb-like { color: #10b981; font-weight: 600; }
.chat-logs-fb-dislike { color: #ef4444; font-weight: 600; }
.chat-logs-fb-none { color: #9ca3af; }

/* ===== 详情弹窗 ===== */
.detail-modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 1000;
  animation: fadeIn 0.15s ease;
}
.detail-modal {
  position: fixed;
  inset: 0;
  z-index: 1001;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.detail-modal-dialog {
  width: 100%;
  max-width: 780px;
  max-height: 80vh;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  animation: modalSlideUp 0.2s ease;
}
.detail-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #e5e7eb;
  flex-shrink: 0;
}
.detail-modal-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #111827;
}
.detail-modal-close {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  font-size: 20px;
  color: #6b7280;
  cursor: pointer;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.1s;
}
.detail-modal-close:hover {
  background: #f3f4f6;
  color: #111827;
}
.detail-modal-body {
  padding: 20px;
  overflow-y: auto;
  flex: 1;
}
.detail-meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  padding: 8px 0;
  font-size: 12px;
  color: #6b7280;
  border-bottom: 1px solid #f3f4f6;
  margin-bottom: 12px;
}
.detail-meta-label {
  color: #9ca3af;
  margin-right: 4px;
}
.detail-ua-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid #f3f4f6;
  margin-bottom: 12px;
}
.detail-ua-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  background: #f0f9ff;
  border: 1px solid #bae6fd;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  color: #0369a1;
}
.detail-ua-raw {
  font-size: 11px;
  color: #9ca3af;
  margin-bottom: 12px;
}
.detail-ua-raw summary {
  cursor: pointer;
  user-select: none;
}
.detail-ua-raw summary:hover { color: #6b7280; }
.detail-ua-raw code {
  display: block;
  margin-top: 4px;
  padding: 8px 10px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  font-size: 11px;
  word-break: break-all;
  color: #6b7280;
}
.detail-modal-body h4 {
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin: 14px 0 6px;
}
.detail-pre {
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px 14px;
  font-size: 13px;
  white-space: pre-wrap;
  word-wrap: break-word;
  max-height: 180px;
  overflow-y: auto;
  margin: 0;
  line-height: 1.7;
}
.detail-pre-answer {
  max-height: 240px;
  background: #f5f3ff;
  border-color: #c4b5fd;
}
.detail-citations {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.8;
}
.detail-feedback {
  background: #fef3c7;
  border: 1px solid #fde68a;
  border-radius: 8px;
  padding: 10px 14px;
}
.detail-feedback-time { color: #6b7280; font-size: 11px; margin-left: 8px; }
.detail-feedback-comment { margin: 6px 0 0; font-size: 13px; }
.detail-modal-footer {
  padding: 12px 20px;
  border-top: 1px solid #e5e7eb;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.detail-trace-btn {
  font-weight: 600;
}
.detail-trace-btn-dislike {
  color: #dc2626;
  border-color: #fca5a5;
  background: #fef2f2;
}
.detail-trace-btn-dislike:hover {
  background: #fee2e2;
}
.detail-trace-hint {
  font-size: 11px;
  color: #dc2626;
}

/* ===== 管线追踪时间线（只读） ===== */
.trace-intent-tag {
  font-size: 11px;
  font-weight: 500;
  color: #4f46e5;
  background: #eef2ff;
  padding: 1px 8px;
  border-radius: 4px;
  margin-left: 6px;
}
.trace-timeline {
  position: relative;
  padding-left: 20px;
  margin: 4px 0 8px;
}
.trace-timeline::before {
  content: "";
  position: absolute;
  left: 5px;
  top: 6px;
  bottom: 6px;
  width: 2px;
  background: #e5e7eb;
  border-radius: 1px;
}
.trace-stage-item {
  position: relative;
  padding: 5px 0 5px 0;
  font-size: 12px;
}
.trace-stage-header {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  cursor: pointer;
  user-select: none;
}
.trace-stage-header:hover .trace-stage-label { color: #4f46e5; }
.trace-expand-icon {
  font-size: 10px;
  color: #9ca3af;
  width: 10px;
  flex-shrink: 0;
}
.trace-stage-expand {
  margin-top: 6px;
  padding: 8px 10px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
}
.trace-dot {
  position: absolute;
  left: -19px;
  top: 9px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.trace-dot.ok { background: #10b981; }
.trace-dot.fallback { background: #f59e0b; }
.trace-dot.skipped { background: #9ca3af; }
.trace-dot.error { background: #ef4444; }
.trace-stage-label {
  font-weight: 600;
  color: #1e293b;
}
.trace-stage-dur {
  font-size: 11px;
  color: #6b7280;
  font-variant-numeric: tabular-nums;
  background: #f3f4f6;
  padding: 0 5px;
  border-radius: 3px;
}
.trace-stage-status {
  font-size: 11px;
  font-weight: 600;
  padding: 0 5px;
  border-radius: 3px;
  margin-left: auto;
}
.trace-stage-status.ok { color: #059669; background: #ecfdf5; }
.trace-stage-status.fallback { color: #d97706; background: #fffbeb; }
.trace-stage-status.skipped { color: #6b7280; background: #f3f4f6; }
.trace-stage-status.error { color: #dc2626; background: #fef2f2; }
.trace-stage-detail {
  width: 100%;
  font-size: 11px;
  color: #6b7280;
  line-height: 1.5;
  margin-top: 2px;
}

/* ===== 智能诊断面板 ===== */
.trace-diagnose {
  display: flex;
  flex-direction: column;
  gap: 6px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px 12px;
  margin: 8px 0 12px;
}
.trace-diagnose-header {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.trace-diagnose-title {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}
.trace-diagnose-sub {
  font-size: 11px;
  color: #94a3b8;
}
.trace-diagnose-card {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-left: 3px solid #cbd5e1;
  border-radius: 6px;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.trace-diagnose-card.trace-diagnose-ok      { border-left-color: #10b981; }
.trace-diagnose-card.trace-diagnose-info    { border-left-color: #3b82f6; }
.trace-diagnose-card.trace-diagnose-warning { border-left-color: #f59e0b; }
.trace-diagnose-card.trace-diagnose-error   { border-left-color: #ef4444; }
.trace-diagnose-card-header {
  display: flex;
  align-items: center;
  gap: 6px;
}
.trace-diagnose-icon {
  font-size: 13px;
  font-weight: 700;
  width: 16px;
  text-align: center;
  flex-shrink: 0;
}
.trace-diagnose-ok      .trace-diagnose-icon { color: #10b981; }
.trace-diagnose-info    .trace-diagnose-icon { color: #3b82f6; }
.trace-diagnose-warning .trace-diagnose-icon { color: #f59e0b; }
.trace-diagnose-error   .trace-diagnose-icon { color: #ef4444; }
.trace-diagnose-card-title {
  font-size: 12.5px;
  font-weight: 600;
  color: #1e293b;
}
.trace-diagnose-section-title {
  font-size: 10px;
  font-weight: 600;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 3px;
}
.trace-diagnose-section ul,
.trace-diagnose-section ol {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: #475569;
  line-height: 1.7;
}
.trace-diagnose-section li + li { margin-top: 2px; }

/* ===== 追踪调试弹窗 ===== */
.trace-modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 1100;
  animation: fadeIn 0.15s ease;
}
.trace-modal {
  position: fixed;
  inset: 0;
  z-index: 1101;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}
.trace-modal-dialog {
  width: 100%;
  max-width: 780px;
  max-height: 80vh;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.18);
  display: flex;
  flex-direction: column;
  animation: modalSlideUp 0.2s ease;
}
.trace-modal-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 20px;
  border-bottom: 1px solid #e5e7eb;
  flex-shrink: 0;
}
.trace-modal-header h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #111827;
  white-space: nowrap;
}
.trace-modal-question {
  flex: 1;
  font-size: 13px;
  color: #6b7280;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.trace-modal-back {
  padding: 4px 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #fff;
  color: #374151;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.15s;
}
.trace-modal-back:hover {
  border-color: #4f46e5;
  color: #4f46e5;
  background: #eef2ff;
}
.trace-modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
}

@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes modalSlideUp { from { opacity: 0; transform: translateY(16px); } to { opacity: 1; transform: translateY(0); } }

/* ===== 工具栏（筛选 + 操作按钮） ===== */
.ai-batch-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-bottom: 1px solid #e5e7eb;
  background: #f9fafb;
  gap: 12px;
}

.ai-filter-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.ai-filter-group select,
.ai-filter-group input {
  height: 32px;
  padding: 0 10px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 12px;
  color: #374151;
  background: #fff;
  outline: none;
  transition: border-color 0.15s;
  box-sizing: border-box;
}

.ai-filter-group select { min-width: 90px; }
.ai-filter-group input { flex: 1; min-width: 100px; max-width: 200px; }

.ai-filter-group select:focus,
.ai-filter-group input:focus {
  border-color: #111827;
}

.ai-filter-group input::placeholder {
  color: #9ca3af;
}

.ai-batch-actions {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-shrink: 0;
}

.ai-table-wrap { overflow: hidden; }

/* ===== 表格底部：状态 + 分页 ===== */
.ai-table-footer {
  padding: 12px 16px;
  border-top: 1px solid #f3f4f6;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.ai-filter-stat { font-size: 12px; color: #6b7280; font-weight: 600; }
.ai-selected-stat { color: #2563eb; font-weight: 700; }

.ai-pagination-controls { display: flex; align-items: center; gap: 8px; }

.ai-pagination-select {
  height: 30px;
  padding: 0 8px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
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
</style>
