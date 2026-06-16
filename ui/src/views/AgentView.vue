<template>
  <div class="agent-page">
    <div class="ai-content">
      <div class="agent-hero">
        <div>
          <div class="agent-eyebrow"><RiRobot2Line /> 运营智能体</div>
          <h2>内容缺口分析 Agent</h2>
          <p>
            分析最近访客问答、低反馈回答和现有文章覆盖，生成可执行的选题、旧文更新与 FAQ 建议。
          </p>
        </div>
        <div class="agent-run-card">
          <label>
            分析周期
            <select v-model.number="form.days" :disabled="running">
              <option :value="7">最近 7 天</option>
              <option :value="30">最近 30 天</option>
              <option :value="90">最近 90 天</option>
            </select>
          </label>
          <label>
            问答样本
            <select v-model.number="form.maxLogs" :disabled="running">
              <option :value="30">最多 30 条</option>
              <option :value="60">最多 60 条</option>
              <option :value="100">最多 100 条</option>
            </select>
          </label>
          <label>
            输出上限
            <select v-model.number="form.maxTokens" :disabled="running">
              <option :value="3000">3000 tokens</option>
              <option :value="5000">5000 tokens</option>
              <option :value="8000">8000 tokens</option>
            </select>
          </label>
          <VButton type="primary" :loading="running" @click="runContentGap">
            {{ running ? "分析中..." : "运行分析" }}
          </VButton>
        </div>
      </div>

      <div class="agent-layout">
        <SectionCard title="任务过程" :icon-component="RiListCheck3" headerTitle="执行链路" headerDesc="第一版为同步任务，只读分析，不会自动修改文章">
          <div v-if="activeTaskId || running" class="agent-progress">
            <div class="agent-progress-head">
              <span>{{ activeCurrentStep || "等待任务" }}</span>
              <strong>{{ activeProgress }}%</strong>
            </div>
            <div class="agent-progress-track">
              <div class="agent-progress-bar" :style="{ width: activeProgress + '%' }"></div>
            </div>
          </div>
          <div class="agent-steps">
            <div
              v-for="(step, index) in visibleSteps"
              :key="index"
              class="agent-step"
              :class="'agent-step--' + step.status"
            >
              <span class="agent-step-dot">{{ index + 1 }}</span>
              <div>
                <div class="agent-step-name">{{ step.name }}</div>
                <div class="agent-step-detail">
                  {{ step.detail }}
                  <span v-if="step.percent !== undefined" class="agent-step-percent">{{ step.percent }}%</span>
                </div>
              </div>
            </div>
          </div>
        </SectionCard>

        <SectionCard title="数据概览" :icon-component="RiBarChart2Line" headerTitle="本次输入" headerDesc="当前版本使用问答日志和公开文章列表">
          <div class="agent-metrics">
            <div class="agent-metric">
              <span>问答样本</span>
              <strong>{{ metrics.sampledLogs ?? 0 }}</strong>
            </div>
            <div class="agent-metric">
              <span>公开文章</span>
              <strong>{{ metrics.publishedPosts ?? 0 }}</strong>
            </div>
            <div class="agent-metric">
              <span>点踩回答</span>
              <strong>{{ metrics.dislikedAnswers ?? 0 }}</strong>
            </div>
            <div class="agent-metric">
              <span>无引用回答</span>
              <strong>{{ metrics.noCitationAnswers ?? 0 }}</strong>
            </div>
          </div>
          <div class="agent-note">
            站内搜索词日志尚未接入，报告会明确标注这个数据限制。
          </div>
        </SectionCard>
      </div>

      <SectionCard title="任务记录" :icon-component="RiHistoryLine" headerTitle="最近运行" headerDesc="每次分析都会保存为历史报告，刷新页面后仍可查看">
        <div class="agent-history-toolbar">
          <span>共 {{ taskRecords.length }} 条</span>
          <VButton size="xs" type="default" :loading="historyLoading" @click="loadTaskRecords">刷新</VButton>
        </div>
        <div v-if="taskRecords.length === 0" class="agent-empty-line">暂无任务记录，运行一次分析后会出现在这里。</div>
        <div v-else class="agent-history-list">
          <div
            v-for="record in taskRecords"
            :key="record.taskId"
            class="agent-history-item"
            :class="{ 'agent-history-item--active': record.taskId === activeTaskId }"
          >
            <button class="agent-history-main" @click="openTaskRecord(record.taskId)">
              <strong>{{ record.summary || "内容缺口分析" }}</strong>
              <span>{{ formatTime(record.createdAt) }} · {{ formatDuration(record.durationMs) }}</span>
            </button>
            <div class="agent-history-actions">
              <em :class="'agent-status--' + record.status">{{ statusLabel(record.status) }}</em>
              <VButton size="xs" type="danger" :disabled="deletingTaskId === record.taskId" @click="askDeleteTaskRecord(record)">
                {{ deletingTaskId === record.taskId ? "删除中" : "删除" }}
              </VButton>
            </div>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="分析报告" :icon-component="RiFileList3Line" headerTitle="运营建议" headerDesc="高优先级内容缺口会优先展示">
        <div v-if="!result && !running" class="agent-empty">
          点击“运行分析”后，这里会生成内容缺口、选题大纲和旧文更新建议。
        </div>

        <div v-if="running" class="agent-loading">
          正在收集数据并生成报告，通常需要几十秒。
        </div>

        <div v-if="result" class="agent-report">
          <div v-if="!result.success" class="agent-error">
            {{ result.error || "分析未完成，请检查模型配置后重试。" }}
          </div>

          <p class="agent-summary">{{ report.summary }}</p>

          <div class="agent-section-title">内容缺口</div>
          <div v-if="contentGaps.length === 0" class="agent-empty-line">暂无明确内容缺口。样本较少时可以扩大周期后重试。</div>
          <div class="agent-gap-list">
            <article v-for="(gap, index) in contentGaps" :key="index" class="agent-gap">
              <div class="agent-gap-head">
                <h3>{{ gap.title }}</h3>
                <span class="agent-priority" :class="'agent-priority--' + gap.priority">{{ priorityLabel(gap.priority) }}</span>
              </div>
              <div class="agent-gap-meta">{{ typeLabel(gap.type) }}</div>
              <p>{{ gap.reason }}</p>
              <div class="agent-gap-action">{{ gap.suggestedAction }}</div>
              <ul v-if="gap.outline?.length" class="agent-outline">
                <li v-for="(item, i) in gap.outline" :key="i">{{ item }}</li>
              </ul>
              <div v-if="gap.evidence?.length" class="agent-evidence">
                <span>证据</span>
                <em v-for="(item, i) in gap.evidence" :key="i">{{ item }}</em>
              </div>
            </article>
          </div>

          <div class="agent-section-title">旧文更新建议</div>
          <div v-if="articleUpdates.length === 0" class="agent-empty-line">暂无旧文更新建议。</div>
          <div v-else class="agent-update-list">
            <div v-for="(item, index) in articleUpdates" :key="index" class="agent-update">
              <strong>{{ item.articleTitle }}</strong>
              <span>{{ item.reason }}</span>
              <p>{{ item.suggestion }}</p>
            </div>
          </div>

          <div class="agent-section-title">下一步</div>
          <ul class="agent-next">
            <li v-for="(item, index) in nextActions" :key="index">{{ item }}</li>
          </ul>

          <details v-if="limitations.length" class="agent-limitations">
            <summary>数据限制</summary>
            <ul>
              <li v-for="(item, index) in limitations" :key="index">{{ item }}</li>
            </ul>
          </details>
        </div>
      </SectionCard>
    </div>

    <VDialog
      v-model:visible="showDeleteDialog"
      type="warning"
      title="删除运行记录"
      :description="deleteDescription"
      confirm-type="danger"
      confirm-text="删除"
      cancel-text="取消"
      :on-confirm="confirmDeleteTaskRecord"
      :on-cancel="cancelDeleteTaskRecord"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { Toast, VButton, VDialog } from "@halo-dev/components";
import RiBarChart2Line from "~icons/ri/bar-chart-2-line";
import RiFileList3Line from "~icons/ri/file-list-3-line";
import RiHistoryLine from "~icons/ri/history-line";
import RiListCheck3 from "~icons/ri/list-check-3";
import RiRobot2Line from "~icons/ri/robot-2-line";
import SectionCard from "../components/SectionCard.vue";

const API = "/apis/console.api.ai-suite.halo.run/v1alpha1/agent/content-gap/run";
const TASKS_API = "/apis/console.api.ai-suite.halo.run/v1alpha1/agent/tasks";

type AgentStep = {
  name: string;
  status: "pending" | "running" | "done" | "failed";
  detail: string;
  percent?: number;
};

type ContentGap = {
  title: string;
  priority: "high" | "medium" | "low" | string;
  type: string;
  evidence?: string[];
  reason: string;
  suggestedAction: string;
  outline?: string[];
};

type ArticleUpdate = {
  articleTitle: string;
  reason: string;
  suggestion: string;
};

type AgentResult = {
  success: boolean;
  taskId?: string;
  status?: string;
  createdAt?: string;
  completedAt?: string;
  durationMs?: number;
  progress?: number;
  currentStep?: string;
  steps?: AgentStep[];
  error?: string;
  input?: { metrics?: Record<string, any> };
  report?: Record<string, any>;
};

type TaskRecord = {
  taskId: string;
  taskType: string;
  status: string;
  summary: string;
  error?: string;
  createdAt: string;
  completedAt: string;
  durationMs: number;
  progress?: number;
  currentStep?: string;
  metrics?: Record<string, any>;
};

const form = reactive({
  days: 30,
  maxLogs: 60,
  maxTokens: 5000,
});
const running = ref(false);
const historyLoading = ref(false);
const result = ref<AgentResult | null>(null);
const taskRecords = ref<TaskRecord[]>([]);
const activeTaskId = ref("");
const deletingTaskId = ref("");
const showDeleteDialog = ref(false);
const pendingDeleteRecord = ref<TaskRecord | null>(null);
const pollTimer = ref<ReturnType<typeof setTimeout> | null>(null);
const hiddenDeletingTaskIds = ref<Set<string>>(new Set());

const defaultSteps = computed<AgentStep[]>(() => [
  { name: "收集访客问答", status: running.value ? "running" : "pending", detail: "读取指定周期内的访客提问、回答、引用与反馈" },
  { name: "读取公开文章", status: "pending", detail: "收集当前博客公开文章标题，用于判断内容覆盖" },
  { name: "识别内容缺口", status: "pending", detail: "对比用户需求和已有内容，判断新增、更新或 FAQ 机会" },
  { name: "生成运营建议", status: "pending", detail: "输出选题、大纲、优先级和下一步动作" },
]);

const report = computed(() => result.value?.report || {});
const metrics = computed(() => result.value?.input?.metrics || {});
const activeProgress = computed(() => {
  if (typeof result.value?.progress === "number") return Math.max(0, Math.min(100, result.value.progress));
  const active = taskRecords.value.find((record) => record.taskId === activeTaskId.value);
  return Math.max(0, Math.min(100, Number(active?.progress || 0)));
});
const activeCurrentStep = computed(() => {
  return result.value?.currentStep || taskRecords.value.find((record) => record.taskId === activeTaskId.value)?.currentStep || "";
});
const visibleSteps = computed<AgentStep[]>(() => {
  const steps = Array.isArray(result.value?.steps) && result.value.steps.length > 0
    ? result.value.steps
    : report.value.steps;
  if (Array.isArray(steps) && steps.length > 0) {
    return steps.map((s: any) => ({
      name: s.name || "任务步骤",
      status: s.status || "done",
      detail: s.detail || "",
      percent: typeof s.percent === "number" ? s.percent : undefined,
    }));
  }
  return defaultSteps.value;
});
const contentGaps = computed<ContentGap[]>(() => Array.isArray(report.value.contentGaps) ? report.value.contentGaps : []);
const articleUpdates = computed<ArticleUpdate[]>(() => Array.isArray(report.value.articleUpdates) ? report.value.articleUpdates : []);
const nextActions = computed<string[]>(() => Array.isArray(report.value.nextActions) ? report.value.nextActions : []);
const limitations = computed<string[]>(() => Array.isArray(report.value.limitations) ? report.value.limitations : []);
const deleteDescription = computed(() => {
  const record = pendingDeleteRecord.value;
  const summary = record?.summary || "内容缺口分析";
  const time = formatTime(record?.createdAt);
  return `确定要删除这条运营智能体运行记录吗？删除后不可恢复。\n\n记录：${summary}\n时间：${time}`;
});

async function runContentGap() {
  running.value = true;
  result.value = null;
  activeTaskId.value = "";
  try {
    const resp = await fetch(API, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(form),
    });
    const data = await resp.json();
    result.value = data;
    activeTaskId.value = data.taskId || "";
    if (data.status === "running") {
      Toast.success("后台任务已启动，刷新页面不会中断");
      await loadTaskRecords();
      startPollingTask(data.taskId);
    } else if (data.success) {
      Toast.success("内容缺口分析已完成");
      running.value = false;
    } else {
      Toast.warning(data.error || "分析未完成");
      running.value = false;
    }
  } catch (e: any) {
    running.value = false;
    result.value = {
      success: false,
      error: e?.message || "请求失败",
      report: {
        summary: "分析请求失败。",
        contentGaps: [],
        articleUpdates: [],
        nextActions: ["检查服务状态后重试"],
        limitations: [],
      },
    };
    Toast.error("运行失败: " + (e?.message || "未知错误"));
  }
}

async function loadTaskRecords() {
  historyLoading.value = true;
  try {
    const resp = await fetch(TASKS_API);
    if (!resp.ok) return;
    const records = (await resp.json()) as TaskRecord[];
    taskRecords.value = records.filter((record) => !hiddenDeletingTaskIds.value.has(record.taskId));
    const activeExists = taskRecords.value.some((record) => record.taskId === activeTaskId.value);
    if (!activeExists && activeTaskId.value) {
      activeTaskId.value = "";
    }
  } catch {
    taskRecords.value = [];
  } finally {
    historyLoading.value = false;
  }
}

async function openTaskRecord(taskId: string) {
  if (!taskId) return;
  try {
    const resp = await fetch(`${TASKS_API}/${encodeURIComponent(taskId)}`);
    if (!resp.ok) {
      Toast.warning("任务记录不存在或已被删除");
      return;
    }
    result.value = await resp.json();
    activeTaskId.value = taskId;
    if (result.value?.status === "running") {
      running.value = true;
      startPollingTask(taskId);
      Toast.success("已载入运行中的任务");
    } else {
      running.value = false;
      Toast.success("已载入历史报告");
    }
  } catch (e: any) {
    Toast.error("载入失败: " + (e?.message || "未知错误"));
  }
}

function startPollingTask(taskId: string) {
  stopPollingTask();
  if (!taskId) return;
  const poll = async () => {
    try {
      const resp = await fetch(`${TASKS_API}/${encodeURIComponent(taskId)}`);
      if (!resp.ok) {
        running.value = false;
        stopPollingTask();
        await loadTaskRecords();
        return;
      }
      const data = await resp.json();
      result.value = data;
      activeTaskId.value = taskId;
      await loadTaskRecords();
      if (data.status === "running") {
        running.value = true;
        pollTimer.value = setTimeout(poll, 2000);
      } else {
        running.value = false;
        stopPollingTask();
        if (data.status === "success") {
          Toast.success("内容缺口分析已完成");
        } else if (data.status === "failed") {
          Toast.warning(data.error || "分析未完成");
        }
      }
    } catch {
      pollTimer.value = setTimeout(poll, 3000);
    }
  };
  pollTimer.value = setTimeout(poll, 1000);
}

function stopPollingTask() {
  if (pollTimer.value) {
    clearTimeout(pollTimer.value);
    pollTimer.value = null;
  }
}

function askDeleteTaskRecord(record: TaskRecord) {
  pendingDeleteRecord.value = record;
  showDeleteDialog.value = true;
}

function cancelDeleteTaskRecord() {
  pendingDeleteRecord.value = null;
}

async function confirmDeleteTaskRecord() {
  const record = pendingDeleteRecord.value;
  if (!record?.taskId || deletingTaskId.value) return;
  deletingTaskId.value = record.taskId;
  const previousRecords = taskRecords.value;
  hiddenDeletingTaskIds.value = new Set([...hiddenDeletingTaskIds.value, record.taskId]);
  taskRecords.value = taskRecords.value.filter((item) => item.taskId !== record.taskId);
  if (activeTaskId.value === record.taskId) {
    activeTaskId.value = "";
    result.value = null;
    running.value = false;
    stopPollingTask();
  }
  try {
    const resp = await fetch(`${TASKS_API}/${encodeURIComponent(record.taskId)}`, {
      method: "DELETE",
    });
    if (!resp.ok) {
      Toast.error("删除失败，请稍后重试");
      hiddenDeletingTaskIds.value.delete(record.taskId);
      taskRecords.value = previousRecords;
      return;
    }
    Toast.success("任务记录已删除");
    await loadTaskRecords();
  } catch (e: any) {
    Toast.error("删除失败: " + (e?.message || "未知错误"));
    hiddenDeletingTaskIds.value.delete(record.taskId);
    taskRecords.value = previousRecords;
  } finally {
    deletingTaskId.value = "";
    pendingDeleteRecord.value = null;
    showDeleteDialog.value = false;
  }
}


function priorityLabel(priority: string) {
  if (priority === "high") return "高优先级";
  if (priority === "medium") return "中优先级";
  if (priority === "low") return "低优先级";
  return priority || "未分级";
}

function typeLabel(type: string) {
  const map: Record<string, string> = {
    new_article: "新增文章",
    update_article: "更新旧文",
    faq: "补充 FAQ",
    internal_link: "增加内链",
  };
  return map[type] || type || "运营建议";
}

function statusLabel(status: string) {
  if (status === "success") return "成功";
  if (status === "failed") return "失败";
  if (status === "running") return "运行中";
  return status || "未知";
}

function formatDuration(ms?: number) {
  const value = Number(ms || 0);
  if (value <= 0) return "0s";
  if (value < 1000) return value + "ms";
  return (value / 1000).toFixed(1) + "s";
}

function formatTime(value?: string) {
  if (!value) return "";
  try {
    return new Date(value).toLocaleString();
  } catch {
    return value;
  }
}

onMounted(() => {
  loadTaskRecords().then(() => {
    const runningRecord = taskRecords.value.find((record) => record.status === "running");
    if (runningRecord) {
      openTaskRecord(runningRecord.taskId);
    }
  });
});

onUnmounted(() => {
  stopPollingTask();
});
</script>

<style scoped>
.agent-page {
  padding: 0;
}

.agent-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 18px;
  margin-bottom: 18px;
  padding: 22px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
}

.agent-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #2563eb;
}

.agent-hero h2 {
  margin: 10px 0 8px;
  font-size: 24px;
  line-height: 1.2;
  font-weight: 700;
  color: #111827;
}

.agent-hero p {
  margin: 0;
  max-width: 760px;
  font-size: 14px;
  line-height: 1.7;
  color: #4b5563;
}

.agent-run-card {
  display: grid;
  gap: 12px;
}

.agent-run-card label {
  display: grid;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: #4b5563;
}

.agent-run-card select {
  height: 34px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  padding: 0 10px;
  background: #ffffff;
  color: #111827;
}

.agent-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(320px, 0.9fr);
  gap: 18px;
  margin-bottom: 18px;
}

.agent-steps {
  display: grid;
  gap: 12px;
}

.agent-progress {
  margin-bottom: 14px;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
}

.agent-progress-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  font-size: 13px;
  color: #4b5563;
}

.agent-progress-head strong {
  color: #111827;
}

.agent-progress-track {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: #e5e7eb;
}

.agent-progress-bar {
  height: 100%;
  border-radius: inherit;
  background: #2563eb;
  transition: width 0.25s ease;
}

.agent-step {
  display: flex;
  gap: 12px;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
}

.agent-step-dot {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 26px;
  height: 26px;
  border-radius: 999px;
  background: #f3f4f6;
  color: #4b5563;
  font-size: 12px;
  font-weight: 700;
}

.agent-step--done .agent-step-dot {
  background: #dcfce7;
  color: #166534;
}

.agent-step--running .agent-step-dot {
  background: #dbeafe;
  color: #1d4ed8;
}

.agent-step--failed .agent-step-dot {
  background: #fee2e2;
  color: #b91c1c;
}

.agent-step-name {
  font-size: 14px;
  font-weight: 650;
  color: #111827;
}

.agent-step-detail {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.5;
  color: #6b7280;
}

.agent-step-percent {
  margin-left: 8px;
  color: #2563eb;
  font-weight: 600;
}

.agent-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.agent-metric {
  padding: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
}

.agent-metric span {
  display: block;
  font-size: 12px;
  color: #6b7280;
}

.agent-metric strong {
  display: block;
  margin-top: 8px;
  font-size: 24px;
  line-height: 1;
  color: #111827;
}

.agent-note,
.agent-empty,
.agent-loading,
.agent-empty-line {
  margin-top: 14px;
  font-size: 13px;
  line-height: 1.6;
  color: #6b7280;
}

.agent-history-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  color: #6b7280;
  font-size: 13px;
}

.agent-history-list {
  display: grid;
  gap: 10px;
}

.agent-history-item {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
  text-align: left;
}

.agent-history-item:hover,
.agent-history-item--active {
  border-color: #2563eb;
  background: #eff6ff;
}

.agent-history-main {
  min-width: 0;
  flex: 1;
  border: 0;
  background: transparent;
  padding: 0;
  text-align: left;
  cursor: pointer;
}

.agent-history-main strong {
  display: block;
  color: #111827;
  font-size: 14px;
  line-height: 1.45;
}

.agent-history-main span {
  display: block;
  margin-top: 4px;
  color: #6b7280;
  font-size: 12px;
}

.agent-history-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}

.agent-history-actions em {
  flex: 0 0 auto;
  padding: 4px 8px;
  border-radius: 999px;
  font-style: normal;
  font-size: 12px;
  font-weight: 600;
}

.agent-status--success {
  background: #dcfce7;
  color: #166534;
}

.agent-status--failed {
  background: #fee2e2;
  color: #b91c1c;
}

.agent-status--running {
  background: #dbeafe;
  color: #1d4ed8;
}

.agent-empty,
.agent-loading {
  display: grid;
  place-items: center;
  min-height: 180px;
  border: 1px dashed #d1d5db;
  border-radius: 8px;
  background: #f9fafb;
  text-align: center;
  padding: 24px;
}

.agent-error {
  margin-bottom: 14px;
  padding: 12px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 13px;
}

.agent-summary {
  margin: 0 0 18px;
  padding: 14px 16px;
  border-radius: 8px;
  background: #eff6ff;
  color: #1e3a8a;
  line-height: 1.7;
}

.agent-section-title {
  margin: 22px 0 12px;
  font-size: 15px;
  font-weight: 700;
  color: #111827;
}

.agent-gap-list,
.agent-update-list {
  display: grid;
  gap: 12px;
}

.agent-gap,
.agent-update {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 16px;
  background: #ffffff;
}

.agent-gap-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.agent-gap h3 {
  margin: 0;
  font-size: 16px;
  color: #111827;
}

.agent-priority {
  flex: 0 0 auto;
  padding: 3px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  background: #f3f4f6;
  color: #4b5563;
}

.agent-priority--high {
  background: #fee2e2;
  color: #b91c1c;
}

.agent-priority--medium {
  background: #fef3c7;
  color: #92400e;
}

.agent-priority--low {
  background: #dcfce7;
  color: #166534;
}

.agent-gap-meta {
  margin-top: 6px;
  font-size: 12px;
  color: #2563eb;
  font-weight: 600;
}

.agent-gap p,
.agent-update p {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: #4b5563;
}

.agent-gap-action {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #f9fafb;
  color: #111827;
  font-size: 13px;
}

.agent-outline,
.agent-next,
.agent-limitations ul {
  margin: 10px 0 0;
  padding-left: 20px;
  color: #4b5563;
  font-size: 13px;
  line-height: 1.7;
}

.agent-evidence {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.agent-evidence span,
.agent-evidence em {
  padding: 4px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-style: normal;
}

.agent-evidence span {
  background: #eef2ff;
  color: #3730a3;
  font-weight: 600;
}

.agent-evidence em {
  background: #f3f4f6;
  color: #4b5563;
}

.agent-update strong {
  display: block;
  color: #111827;
}

.agent-update span {
  display: block;
  margin-top: 6px;
  color: #6b7280;
  font-size: 13px;
}

.agent-limitations {
  margin-top: 16px;
  color: #6b7280;
  font-size: 13px;
}

@media (max-width: 960px) {
  .agent-hero,
  .agent-layout {
    grid-template-columns: 1fr;
  }
}
</style>
