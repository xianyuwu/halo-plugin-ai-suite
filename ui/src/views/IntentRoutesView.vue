<template>
  <div class="ai-page">
    <!-- 列表态 -->
    <template v-if="mode === 'list'">
      <SectionCard
        title="意图路由"
        desc="配置自定义问答意图：命中触发关键词后跳过 RAG，改走可编排的处理器 pipeline（如「LLM 标题推理 → 标签过滤 → 时间排序」）。"
        :icon-component="RiRouteLine"
      >
        <template #header-extra>
          <VButton type="primary" size="sm" @click="startCreate">
            <template #icon>
              <RiAddLine />
            </template>
            新建意图
          </VButton>
        </template>

        <div v-if="loading" class="ai-empty">加载中…</div>
        <div v-else-if="!intents.length" class="ai-empty">
          还没有意图配置。点击「新建意图」创建第一个，或等待内置意图初始化。
        </div>
        <table v-else class="ai-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>触发词</th>
              <th>Pipeline</th>
              <th>优先级</th>
              <th>启用</th>
              <th>更新时间</th>
              <th class="ai-col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="it in intents" :key="it.id">
              <td>
                <div class="ai-title-cell">
                  <span class="ai-title-text">{{ it.displayName || it.id }}</span>
                  <span v-if="it.builtin" class="ai-tag ai-tag-builtin">内置</span>
                </div>
                <div v-if="it.description" class="ai-desc">{{ it.description }}</div>
              </td>
              <td>
                <div class="ai-trigger-chips">
                  <span v-for="p in it.triggerPatterns.slice(0, 4)" :key="p" class="ai-chip">{{ p }}</span>
                  <span v-if="it.triggerPatterns.length > 4" class="ai-chip ai-chip-more">
                    +{{ it.triggerPatterns.length - 4 }}
                  </span>
                  <span v-if="it.llmFallback" class="ai-chip ai-chip-llm">+ LLM 兜底</span>
                </div>
              </td>
              <td>
                <div class="ai-pipeline-summary">
                  <template v-if="it.pipeline && it.pipeline.length">
                    <span
                      v-for="(s, i) in it.pipeline"
                      :key="i"
                      class="ai-step-chip"
                    >
                      <span v-if="i > 0" class="ai-step-arrow">→</span>
                      {{ processorLabel(s.type) }}
                    </span>
                  </template>
                  <span v-else class="ai-empty-mini">未配置</span>
                </div>
              </td>
              <td>{{ it.priority }}</td>
              <td>
                <label class="ai-switch" @click.stop>
                  <input
                    type="checkbox"
                    :checked="it.enabled"
                    @change="toggleEnabled(it, ($event.target as HTMLInputElement).checked)"
                  />
                  <span class="ai-switch-slider"></span>
                </label>
              </td>
              <td class="ai-cell-muted">{{ formatDate(it.updatedAt) }}</td>
              <td class="ai-col-actions">
                <VSpace spacing="sm">
                  <VButton size="xs" @click="startEdit(it)">编辑</VButton>
                  <VButton
                    v-if="!it.builtin"
                    size="xs"
                    type="danger"
                    @click="confirmDelete(it)"
                  >删除</VButton>
                </VSpace>
              </td>
            </tr>
          </tbody>
        </table>
      </SectionCard>
    </template>

    <!-- 编辑/创建态 -->
    <template v-else>
      <SectionCard
        :title="form.id && isEditing ? `编辑意图：${form.displayName}` : '新建意图'"
        desc="配置触发条件和处理器 pipeline。处理器按顺序串联，上一步输出作为下一步输入。"
        :icon-component="RiRouteLine"
      >
        <template #header-extra>
          <VSpace>
            <VButton size="sm" @click="backToList">返回列表</VButton>
            <VButton type="primary" size="sm" :disabled="saving" @click="save">
              {{ saving ? "保存中…" : "保存" }}
            </VButton>
          </VSpace>
        </template>

        <div v-if="errorMessage" class="ai-error">{{ errorMessage }}</div>

        <!-- 基本信息区 -->
        <div class="ai-form-section">
          <div class="ai-section-title">基本信息</div>
          <div class="ai-form-grid">
            <div class="ai-form-field">
              <label class="ai-field-label">显示名称 *</label>
              <input
                v-model="form.displayName"
                class="ai-input"
                placeholder="如：最新文章"
              />
            </div>
            <div class="ai-form-field">
              <label class="ai-field-label">优先级</label>
              <input
                v-model.number="form.priority"
                type="number"
                class="ai-input"
                placeholder="数字越大越优先（默认 0）"
              />
            </div>
            <div class="ai-form-field ai-form-field-wide">
              <label class="ai-field-label">描述</label>
              <input
                v-model="form.description"
                class="ai-input"
                placeholder="可选，说明意图用途"
              />
            </div>
          </div>
        </div>

        <!-- 触发条件区 -->
        <div class="ai-form-section">
          <div class="ai-section-title">触发条件</div>
          <div class="ai-field-label">触发关键词正则（任一命中即触发，OR 关系）</div>
          <div class="ai-chip-input-list">
            <div
              v-for="(p, i) in form.triggerPatterns"
              :key="i"
              class="ai-chip-input-row"
            >
              <input
                v-model="form.triggerPatterns[i]"
                class="ai-input"
                placeholder="如：最新|最近"
              />
              <button class="ai-icon-btn" @click="form.triggerPatterns.splice(i, 1)">✕</button>
            </div>
            <VButton size="xs" @click="form.triggerPatterns.push('')">
              + 添加触发词
            </VButton>
          </div>

          <div class="ai-toggle-row">
            <label class="ai-switch">
              <input v-model="form.llmFallback" type="checkbox" />
              <span class="ai-switch-slider"></span>
            </label>
            <span>正则都没命中时，让 LLM 兜底分类（额外一次 LLM 调用，约 300-500ms）</span>
          </div>
          <div v-if="form.llmFallback" class="ai-form-field ai-form-field-wide">
            <label class="ai-field-label">LLM 兜底提示（可选）</label>
            <input
              v-model="form.llmFallbackHint"
              class="ai-input"
              placeholder="如：用户问「最新」类问题走此意图"
            />
          </div>
        </div>

        <!-- Pipeline 步骤区（节点图） -->
        <div class="ai-form-section">
          <div class="ai-section-title">
            Pipeline 处理器流程
            <span class="ai-section-hint">按顺序串联，上一步输出 → 下一步输入。可拖拽或用箭头重排</span>
          </div>

          <!-- 节点图画布 -->
          <div v-if="!form.pipeline.length" class="ai-empty-mini">
            还没有处理器。点击下方按钮添加。
          </div>
          <div v-else class="node-canvas" ref="canvasRef">
            <!-- 起点节点：全部文章（只读，试跑后显示总数） -->
            <div class="node-card node-card--source">
              <div class="node-header">
                <span class="node-index node-index--source">起</span>
                <span class="node-title">全部已发布文章</span>
              </div>
              <div v-if="previewStages.length" class="node-preview-badge">
                {{ getTotalPosts() }} 篇
              </div>
            </div>

            <!-- SVG 连线层（overlay） -->
            <svg class="node-connector" aria-hidden="true">
              <defs>
                <marker id="arrowhead" markerWidth="8" markerHeight="8" refX="4" refY="4" orient="auto">
                  <path d="M0,0 L8,4 L0,8 Z" fill="var(--ai-color-border-strong, #d1d5db)" />
                </marker>
              </defs>
              <line
                v-for="i in form.pipeline.length + 1"
                :key="'line-' + i"
                :x1="connectorX"
                :y1="connectorY(i - 1)"
                :x2="connectorX"
                :y2="connectorY(i) - 6"
                stroke="var(--ai-color-border-strong, #d1d5db)"
                stroke-width="2"
                marker-end="url(#arrowhead)"
              />
            </svg>

            <!-- 处理器节点列表 -->
            <div
              v-for="(step, i) in form.pipeline"
              :key="'node-' + i"
              class="node-card"
              :class="{
                'node-card--dragging': draggingIndex === i,
                'node-card--drop-target': dropTargetIndex === i,
                'node-card--preview-ok': getStage(i)?.status === 'ok',
                'node-card--preview-fallback': getStage(i)?.status === 'fallback',
              }"
              :draggable="true"
              @dragstart="onDragStart($event, i)"
              @dragover.prevent="onDragOver($event, i)"
              @dragleave="onDragLeave(i)"
              @drop.prevent="onDrop($event, i)"
              @dragend="onDragEnd"
            >
              <div class="node-header">
                <span class="node-index">{{ i + 1 }}</span>
                <select v-model="step.type" class="node-type-select">
                  <option value="">— 选择处理器 —</option>
                  <option v-for="t in processorTypes" :key="t.value" :value="t.value">
                    {{ t.label }}
                  </option>
                </select>
                <span v-if="isLlmStep(step.type)" class="node-llm-tag" title="此步会调用 LLM，消耗 token">LLM</span>
                <div class="node-actions">
                  <button class="node-icon-btn" :disabled="i === 0" @click.stop="moveStep(i, -1)" title="上移">↑</button>
                  <button class="node-icon-btn" :disabled="i === form.pipeline.length - 1" @click.stop="moveStep(i, 1)" title="下移">↓</button>
                  <button class="node-icon-btn node-icon-btn--danger" @click.stop="removeStep(i)" title="删除">✕</button>
                </div>
              </div>

              <!-- 参数区（可折叠） -->
              <div v-if="step.type && expandedSteps.has(i)" class="node-params">
                <div v-for="field in processorParamFields(step.type)" :key="field.key" class="node-param-field">
                  <label class="node-param-label">{{ field.label }}</label>
                  <input
                    v-model="step.params[field.key]"
                    class="ai-input"
                    :placeholder="field.placeholder || ''"
                  />
                </div>
              </div>
              <button v-if="step.type" class="node-toggle-params" @click.stop="toggleStepExpand(i)">
                {{ expandedSteps.has(i) ? '收起参数' : '展开参数' }}
              </button>

              <!-- 试跑结果（预览后显示） -->
              <div v-if="getStage(i)" class="node-preview">
                <span class="node-preview-badge" :class="'node-preview-badge--' + getStage(i)?.status">
                  {{ (getStage(i)?.data as any)?.in ?? '?' }} → {{ (getStage(i)?.data as any)?.out ?? '?' }} 篇
                </span>
                <span v-if="getStage(i)?.durationMs" class="node-preview-time">{{ getStage(i)?.durationMs }}ms</span>
                <button
                  v-if="(getStage(i)?.data as any)?.posts?.length"
                  class="node-preview-toggle"
                  @click.stop="togglePreviewExpand(i)"
                >
                  {{ previewExpanded.has(i) ? '隐藏文章' : `查看 ${((getStage(i)?.data as any)?.posts || []).length} 篇` }}
                </button>
                <div v-if="previewExpanded.has(i) && (getStage(i)?.data as any)?.posts?.length" class="node-preview-list">
                  <a
                    v-for="(post, pi) in (getStage(i)?.data as any)?.posts"
                    :key="pi"
                    :href="post.url"
                    target="_blank"
                    class="node-preview-post"
                  >
                    <span class="node-preview-post-title">{{ post.title }}</span>
                    <span v-if="post.publishTime" class="node-preview-post-date">{{ post.publishTime }}</span>
                  </a>
                </div>
              </div>
            </div>
          </div>

          <VButton size="xs" @click="addStep">+ 添加步骤</VButton>

          <!-- 试跑预览面板 -->
          <div class="preview-panel">
            <div class="preview-header">
              <span class="preview-title">试跑预览</span>
              <span v-if="!isEditing" class="preview-hint">需先保存意图才能试跑</span>
            </div>
            <div class="preview-input-row">
              <input
                v-model="previewQuery"
                class="ai-input preview-input"
                placeholder="输入示例问题，如：关于 AI 的最新文章"
                @keyup.enter="runPreview"
                :disabled="previewing || !isEditing"
              />
              <VButton
                type="primary"
                size="sm"
                :disabled="!isEditing || previewing || !form.pipeline.length"
                @click="runPreview"
              >
                {{ previewing ? "跑中…" : "试跑" }}
              </VButton>
            </div>
            <div v-if="hasLlmStep() && previewQuery" class="preview-warn">
              ⚠ 此意图含 LLM 步骤，试跑会真实消耗 token（用量页按 INTENT_PIPELINE 计费）
            </div>
            <div v-if="previewError" class="ai-error">{{ previewError }}</div>
            <div v-if="previewStages.length && !previewError" class="preview-summary">
              共 {{ previewStages.length }} 个阶段，耗时
              {{ previewStages.reduce((s, st) => s + (st.durationMs || 0), 0) }}ms
            </div>
          </div>
        </div>

        <!-- 输出模板区 -->
        <div class="ai-form-section">
          <div class="ai-section-title">输出模板（可选）</div>
          <div class="ai-field-label">
            指引 LLM 如何组织回答。留空则使用默认格式（编号 + 标题链接 + 发布日期 + 摘要）
          </div>
          <textarea
            v-model="form.outputTemplate"
            class="ai-input ai-textarea"
            rows="4"
            placeholder="如：按发布时间倒序列出文章，每篇用编号 + Markdown 链接格式"
          ></textarea>
        </div>
      </SectionCard>
    </template>

    <!-- 删除确认弹窗 -->
    <VDialog
      v-model:visible="showDeleteDialog"
      type="warning"
      title="删除意图"
      :description="`确定要删除意图「${deletingName}」吗？删除后不可恢复。`"
      confirm-type="danger"
      confirm-text="删除"
      cancel-text="取消"
      :on-confirm="doDelete"
    />
  </div>
</template>

<script setup lang="ts">
import { VButton, VDialog, VSpace } from "@halo-dev/components";
import { nextTick, onMounted, ref } from "vue";
import RiRouteLine from "~icons/ri/route-line";
import RiAddLine from "~icons/ri/add-line";
import SectionCard from "../components/SectionCard.vue";

const API = "/apis/console.api.ai-suite.halo.run/v1alpha1/intent-routes";

// ===== 类型 =====
type PipelineStep = {
  type: string;
  params: Record<string, string>;
};

type IntentRouteDto = {
  id: string;
  displayName: string;
  description: string;
  enabled: boolean;
  priority: number;
  builtin: boolean;
  triggerPatterns: string[];
  llmFallback: boolean;
  llmFallbackHint: string;
  pipeline: PipelineStep[];
  outputTemplate: string;
  updatedAt: string;
};

// ===== 处理器元数据（前端展示 + 参数表单生成）=====
const processorTypes = [
  { value: "TOPIC_MATCH", label: "主题综合匹配（标签/分类 + LLM）" },
  { value: "LLM_TITLE_FILTER", label: "LLM 标题推理（语义判断主题相关）" },
  { value: "TAG_MATCH", label: "标签匹配（按 Post.spec.tags）" },
  { value: "KEYWORD_MATCH", label: "关键词匹配（标题/摘要包含）" },
  { value: "CATEGORY_MATCH", label: "分类匹配（按 Post.spec.categories）" },
  { value: "TIME_SORT", label: "时间排序（按 publishTime）" },
  { value: "VISIT_SORT", label: "浏览量排序（按 Counter）" },
] as const;

const processorLabelMap: Record<string, string> = Object.fromEntries(
  processorTypes.map((t) => [t.value, t.value]),
);

function processorLabel(type: string): string {
  const found = processorTypes.find((t) => t.value === type);
  return found ? found.label : type;
}

// 每个处理器的参数表单字段定义
function processorParamFields(type: string): Array<{
  key: string;
  label: string;
  placeholder?: string;
}> {
  switch (type) {
    case "TOPIC_MATCH":
      return [
        { key: "prompt", label: "判断指令", placeholder: "综合标题、摘要、标签和分类判断主题" },
        { key: "aliases", label: "主题别名", placeholder: "AI=人工智能;LLM=大语言模型|大模型" },
        { key: "candidateLimit", label: "候选上限", placeholder: "200（先按发布时间倒序）" },
        { key: "limit", label: "返回前 N 条", placeholder: "0 表示不限" },
        { key: "onFailure", label: "失败策略", placeholder: "empty（默认）或 keep" },
      ];
    case "LLM_TITLE_FILTER":
      return [
        { key: "prompt", label: "判断指令", placeholder: "判断标题是否与用户问题主题相关" },
        { key: "candidateLimit", label: "候选上限", placeholder: "200（先按发布时间倒序）" },
        { key: "limit", label: "返回前 N 条", placeholder: "0 表示不限" },
        { key: "onFailure", label: "失败策略", placeholder: "empty（默认）或 keep" },
      ];
    case "TAG_MATCH":
      return [
        { key: "mode", label: "模式", placeholder: "from_query 或 fixed" },
        { key: "tags", label: "标签列表（fixed 模式）", placeholder: "AI,LLM（逗号分隔）" },
        { key: "onFailure", label: "失败策略", placeholder: "empty（默认）或 keep" },
      ];
    case "KEYWORD_MATCH":
      return [
        { key: "mode", label: "模式", placeholder: "from_query 或 fixed" },
        { key: "fields", label: "字段范围", placeholder: "title 或 title,content" },
        { key: "keyword", label: "关键词（fixed 模式）", placeholder: "Docker,K8s" },
        { key: "onFailure", label: "失败策略", placeholder: "empty（默认）或 keep" },
      ];
    case "CATEGORY_MATCH":
      return [
        { key: "mode", label: "模式", placeholder: "from_query 或 fixed" },
        { key: "categories", label: "分类列表（fixed 模式）", placeholder: "技术,旅行（逗号分隔）" },
        { key: "onFailure", label: "失败策略", placeholder: "empty（默认）或 keep" },
      ];
    case "TIME_SORT":
      return [
        { key: "order", label: "排序方向", placeholder: "desc 或 asc" },
        { key: "limit", label: "返回前 N 条", placeholder: "10" },
        { key: "onFailure", label: "失败策略", placeholder: "empty（默认）或 keep" },
      ];
    case "VISIT_SORT":
      return [
        { key: "limit", label: "返回前 N 条", placeholder: "10" },
        { key: "onFailure", label: "失败策略", placeholder: "empty（默认）或 keep" },
      ];
    default:
      return [];
  }
}

// ===== 状态 =====
const mode = ref<"list" | "edit">("list");
const loading = ref(false);
const saving = ref(false);
const isEditing = ref(false);
const errorMessage = ref("");
const intents = ref<IntentRouteDto[]>([]);
const showDeleteDialog = ref(false);
const deletingId = ref("");
const deletingName = ref("");

// ===== 节点图状态 =====
type PreviewStage = {
  name: string;
  label: string;
  status: string;
  statusLabel: string;
  detail: string;
  durationMs?: number;
  data?: any;
};

const canvasRef = ref<HTMLElement | null>(null);
// 节点展开状态（参数区折叠）
const expandedSteps = ref<Set<number>>(new Set());
// 试跑结果展开状态（文章列表折叠）
const previewExpanded = ref<Set<number>>(new Set());
// 试跑状态
const previewQuery = ref("");
const previewing = ref(false);
const previewError = ref("");
const previewStages = ref<PreviewStage[]>([]);
// 拖拽状态
const draggingIndex = ref<number | null>(null);
const dropTargetIndex = ref<number | null>(null);
// 连线坐标参数（固定列布局：序号列居中 x + 节点高度 + 间距）
const connectorX = 28;
const NODE_HEIGHT = 64;        // 折叠态节点高度
const NODE_GAP = 16;           // 节点间距
// 用响应式高度数组，展开节点时连线跟随
const nodeHeights = ref<number[]>([]);

/** 计算第 i 个连线两端的 y 坐标（i=0 是起点→第1个节点，i=N 是最后节点→终点占位） */
function connectorY(i: number): number {
  let y = 40; // 起点节点中心偏下
  for (let k = 0; k < i; k++) {
    y += (nodeHeights.value[k] || NODE_HEIGHT) + NODE_GAP;
  }
  return y;
}

/** 重算节点高度（展开参数/预览后调用，让连线跟随） */
async function recalcHeights() {
  await nextTick();
  if (!canvasRef.value) return;
  const cards = canvasRef.value.querySelectorAll(".node-card:not(.node-card--source)");
  const heights: number[] = [];
  cards.forEach((el) => {
    heights.push((el as HTMLElement).offsetHeight);
  });
  nodeHeights.value = heights;
}

/** 起点节点的总数（试跑后从第一个 stage 的 in 推断） */
function getTotalPosts(): string {
  const firstPipelineStage = previewStages.value.find((s) => s.name?.startsWith("pipeline_step_") || s.name === "fetch_posts");
  if (firstPipelineStage?.name === "fetch_posts") {
    const total = (firstPipelineStage.data as any)?.totalPosts;
    if (total != null) return String(total);
  }
  const firstStep = previewStages.value.find((s) => s.name === "pipeline_step_0");
  return firstStep ? String((firstStep.data as any)?.in ?? "?") : "?";
}

/** 取第 i 个 pipeline step 对应的预览 stage */
function getStage(i: number): PreviewStage | undefined {
  return previewStages.value.find((s) => s.name === `pipeline_step_${i}`);
}

/** 判断某处理器类型是否会调 LLM */
function isLlmStep(type: string): boolean {
  return type === "TOPIC_MATCH" || type === "LLM_TITLE_FILTER";
}

/** 当前 form.pipeline 是否含 LLM 步骤 */
function hasLlmStep(): boolean {
  return form.value.pipeline.some((s) => isLlmStep(s.type));
}

function toggleStepExpand(i: number) {
  const set = new Set(expandedSteps.value);
  if (set.has(i)) set.delete(i); else set.add(i);
  expandedSteps.value = set;
  recalcHeights();
}

function togglePreviewExpand(i: number) {
  const set = new Set(previewExpanded.value);
  if (set.has(i)) set.delete(i); else set.add(i);
  previewExpanded.value = set;
  recalcHeights();
}

// ===== 拖拽重排 =====
function onDragStart(_e: DragEvent, i: number) {
  draggingIndex.value = i;
}
function onDragOver(_e: DragEvent, i: number) {
  if (draggingIndex.value === null || draggingIndex.value === i) return;
  dropTargetIndex.value = i;
}
function onDragLeave(i: number) {
  if (dropTargetIndex.value === i) dropTargetIndex.value = null;
}
function onDrop(_e: DragEvent, i: number) {
  if (draggingIndex.value === null || draggingIndex.value === i) {
    draggingIndex.value = null;
    dropTargetIndex.value = null;
    return;
  }
  const from = draggingIndex.value;
  const arr = form.value.pipeline;
  const [moved] = arr.splice(from, 1);
  arr.splice(i, 0, moved);
  // 重置预览（顺序变了，旧 trace 失效）
  previewStages.value = [];
  previewExpanded.value = new Set();
  expandedSteps.value = new Set();
  draggingIndex.value = null;
  dropTargetIndex.value = null;
  recalcHeights();
}
function onDragEnd() {
  draggingIndex.value = null;
  dropTargetIndex.value = null;
}

// ===== 试跑预览 =====
async function runPreview() {
  if (!isEditing.value || !form.value.id) {
    previewError.value = "请先保存意图再试跑";
    return;
  }
  if (!previewQuery.value.trim()) {
    previewError.value = "请输入示例问题";
    return;
  }
  previewing.value = true;
  previewError.value = "";
  previewStages.value = [];
  previewExpanded.value = new Set();
  try {
    const resp = await fetch(`${API}/${encodeURIComponent(form.value.id)}/preview`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ query: previewQuery.value }),
    });
    const data = await resp.json();
    if (!resp.ok) {
      throw new Error(data.error || `HTTP ${resp.status}`);
    }
    previewStages.value = (data.stages || []) as PreviewStage[];
    recalcHeights();
  } catch (e: any) {
    previewError.value = `试跑失败：${e.message}`;
  } finally {
    previewing.value = false;
  }
}

const emptyForm = (): IntentRouteDto => ({
  id: "",
  displayName: "",
  description: "",
  enabled: true,
  priority: 0,
  builtin: false,
  triggerPatterns: [""],
  llmFallback: false,
  llmFallbackHint: "",
  pipeline: [],
  outputTemplate: "",
  updatedAt: "",
});

const form = ref<IntentRouteDto>(emptyForm());

// ===== API =====
async function loadIntents() {
  loading.value = true;
  errorMessage.value = "";
  try {
    const resp = await fetch(`${API}`);
    if (!resp.ok) {
      const data = await resp.json().catch(() => ({}));
      throw new Error(data.error || `HTTP ${resp.status}`);
    }
    intents.value = (await resp.json()) as IntentRouteDto[];
  } catch (e: any) {
    errorMessage.value = `加载失败：${e.message}`;
  } finally {
    loading.value = false;
  }
}

async function toggleEnabled(it: IntentRouteDto, enabled: boolean) {
  try {
    const body = toSaveRequest({ ...it, enabled });
    const resp = await fetch(`${API}/${encodeURIComponent(it.id)}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!resp.ok) {
      const data = await resp.json().catch(() => ({}));
      throw new Error(data.error || `HTTP ${resp.status}`);
    }
    it.enabled = enabled;
  } catch (e: any) {
    errorMessage.value = `切换启用失败：${e.message}`;
    // 失败时重载列表，保证 UI 与后端一致
    await loadIntents();
  }
}

function startCreate() {
  isEditing.value = false;
  form.value = emptyForm();
  errorMessage.value = "";
  resetPreviewState();
  mode.value = "edit";
  recalcHeights();
}

function startEdit(it: IntentRouteDto) {
  isEditing.value = true;
  // 深拷贝，避免直接改列表数据
  form.value = JSON.parse(JSON.stringify(it));
  errorMessage.value = "";
  resetPreviewState();
  mode.value = "edit";
  recalcHeights();
}

/** 重置所有预览/展开/拖拽状态（切换意图时调用） */
function resetPreviewState() {
  previewQuery.value = "";
  previewStages.value = [];
  previewError.value = "";
  expandedSteps.value = new Set();
  previewExpanded.value = new Set();
  draggingIndex.value = null;
  dropTargetIndex.value = null;
}

function backToList() {
  mode.value = "list";
  errorMessage.value = "";
}

async function save() {
  if (!form.value.displayName.trim()) {
    errorMessage.value = "请填写显示名称";
    return;
  }
  // 过滤空的触发词
  form.value.triggerPatterns = form.value.triggerPatterns.filter((p) => p.trim());
  // 过滤类型为空的 pipeline step
  form.value.pipeline = form.value.pipeline.filter((s) => s.type);

  saving.value = true;
  errorMessage.value = "";
  try {
    const body = toSaveRequest(form.value);
    const url = isEditing.value
      ? `${API}/${encodeURIComponent(form.value.id)}`
      : `${API}`;
    const method = isEditing.value ? "PUT" : "POST";
    const resp = await fetch(url, {
      method,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!resp.ok) {
      const data = await resp.json().catch(() => ({}));
      throw new Error(data.error || `HTTP ${resp.status}`);
    }
    await loadIntents();
    mode.value = "list";
  } catch (e: any) {
    errorMessage.value = `保存失败：${e.message}`;
  } finally {
    saving.value = false;
  }
}

function confirmDelete(it: IntentRouteDto) {
  deletingId.value = it.id;
  deletingName.value = it.displayName || it.id;
  showDeleteDialog.value = true;
}

async function doDelete() {
  try {
    const resp = await fetch(`${API}/${encodeURIComponent(deletingId.value)}`, {
      method: "DELETE",
    });
    if (!resp.ok && resp.status !== 204) {
      const data = await resp.json().catch(() => ({}));
      throw new Error(data.error || `HTTP ${resp.status}`);
    }
    showDeleteDialog.value = false;
    await loadIntents();
  } catch (e: any) {
    errorMessage.value = `删除失败：${e.message}`;
    showDeleteDialog.value = false;
  }
}

// ===== Pipeline step 操作 =====
function addStep() {
  form.value.pipeline.push({ type: "", params: {} });
  // 新节点默认展开参数
  expandedSteps.value = new Set([...expandedSteps.value, form.value.pipeline.length - 1]);
  recalcHeights();
}

function moveStep(index: number, delta: number) {
  const newIndex = index + delta;
  if (newIndex < 0 || newIndex >= form.value.pipeline.length) return;
  const arr = form.value.pipeline;
  [arr[index], arr[newIndex]] = [arr[newIndex], arr[index]];
  // 顺序变了，旧预览失效
  previewStages.value = [];
  previewExpanded.value = new Set();
  expandedSteps.value = new Set();
  recalcHeights();
}

function removeStep(index: number) {
  form.value.pipeline.splice(index, 1);
  previewStages.value = [];
  previewExpanded.value = new Set();
  expandedSteps.value = new Set();
  recalcHeights();
}

// ===== 工具 =====
function toSaveRequest(it: IntentRouteDto) {
  return {
    id: it.id,
    displayName: it.displayName,
    description: it.description,
    enabled: it.enabled,
    priority: it.priority,
    triggerPatterns: it.triggerPatterns,
    llmFallback: it.llmFallback,
    llmFallbackHint: it.llmFallbackHint,
    pipeline: it.pipeline,
    outputTemplate: it.outputTemplate,
  };
}

function formatDate(iso: string): string {
  if (!iso) return "—";
  try {
    const d = new Date(iso);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(
      d.getDate(),
    ).padStart(2, "0")} ${String(d.getHours()).padStart(2, "0")}:${String(
      d.getMinutes(),
    ).padStart(2, "0")}`;
  } catch {
    return iso;
  }
}

// 初始化：进入页面即加载
onMounted(() => {
  loadIntents();
});
</script>

<style scoped>
.ai-page {
  padding: 16px;
  max-width: 1280px;
  margin: 0 auto;
}

.ai-empty {
  text-align: center;
  padding: 40px 16px;
  color: var(--color-gray, #888);
}

.ai-empty-mini {
  color: var(--color-gray, #aaa);
  font-size: 13px;
}

.ai-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.ai-table th,
.ai-table td {
  text-align: left;
  padding: 12px 10px;
  border-bottom: 1px solid var(--color-border, #eee);
  vertical-align: top;
}

.ai-table th {
  font-weight: 600;
  color: var(--color-gray, #666);
  font-size: 13px;
  background: var(--color-bg-soft, #fafafa);
}

.ai-col-actions {
  white-space: nowrap;
}

.ai-title-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-title-text {
  font-weight: 600;
}

.ai-desc {
  font-size: 12px;
  color: var(--color-gray, #999);
  margin-top: 4px;
  max-width: 280px;
}

.ai-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
}

.ai-tag-builtin {
  background: var(--color-primary-soft, #e0e7ff);
  color: var(--color-primary, #4f46e5);
}

.ai-trigger-chips,
.ai-pipeline-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.ai-chip {
  display: inline-block;
  padding: 2px 8px;
  background: var(--color-bg-soft, #f0f0f0);
  border-radius: 4px;
  font-size: 12px;
  font-family: ui-monospace, monospace;
}

.ai-chip-more {
  color: var(--color-gray, #888);
}

.ai-chip-llm {
  background: var(--color-warning-soft, #fef3c7);
  color: var(--color-warning, #92400e);
}

.ai-step-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--color-text, #333);
}

.ai-step-arrow {
  color: var(--color-gray, #aaa);
}

.ai-cell-muted {
  color: var(--color-gray, #999);
  font-size: 13px;
}

/* 开关组件 */
.ai-switch {
  display: inline-block;
  position: relative;
  width: 36px;
  height: 20px;
  cursor: pointer;
  vertical-align: middle;
}

.ai-switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.ai-switch-slider {
  position: absolute;
  inset: 0;
  background: var(--color-border, #ccc);
  border-radius: 20px;
  transition: 0.2s;
}

.ai-switch-slider::before {
  content: "";
  position: absolute;
  height: 16px;
  width: 16px;
  left: 2px;
  top: 2px;
  background: white;
  border-radius: 50%;
  transition: 0.2s;
}

.ai-switch input:checked + .ai-switch-slider {
  background: var(--color-primary, #4f46e5);
}

.ai-switch input:checked + .ai-switch-slider::before {
  transform: translateX(16px);
}

/* 表单 */
.ai-form-section {
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--color-border, #f0f0f0);
}

.ai-form-section:last-child {
  border-bottom: none;
}

.ai-section-title {
  font-weight: 600;
  font-size: 15px;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-section-hint {
  font-weight: 400;
  font-size: 12px;
  color: var(--color-gray, #999);
}

.ai-form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.ai-form-field-wide {
  grid-column: 1 / -1;
}

.ai-form-field {
  margin-bottom: 8px;
}

.ai-field-label {
  display: block;
  font-size: 13px;
  color: var(--color-gray, #555);
  margin-bottom: 4px;
}

.ai-input {
  width: 100%;
  padding: 6px 10px;
  border: 1px solid var(--color-border, #ddd);
  border-radius: 4px;
  font-size: 14px;
  background: var(--color-bg, #fff);
  box-sizing: border-box;
}

.ai-input:focus {
  outline: none;
  border-color: var(--color-primary, #4f46e5);
}

.ai-textarea {
  font-family: inherit;
  resize: vertical;
  min-height: 80px;
}

.ai-chip-input-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 8px;
}

.ai-chip-input-row {
  display: flex;
  gap: 6px;
  align-items: center;
}

.ai-icon-btn {
  background: transparent;
  border: 1px solid var(--color-border, #ddd);
  border-radius: 4px;
  cursor: pointer;
  padding: 4px 8px;
  font-size: 14px;
  color: var(--color-gray, #666);
}

.ai-icon-btn:hover:not(:disabled) {
  background: var(--color-bg-soft, #f5f5f5);
}

.ai-icon-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.ai-icon-btn-danger:hover:not(:disabled) {
  color: var(--color-danger, #dc2626);
  border-color: var(--color-danger, #dc2626);
}

.ai-toggle-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0;
  font-size: 13px;
  color: var(--color-gray, #555);
}

/* Pipeline step 行 */
.ai-step-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  padding: 10px;
  background: var(--color-bg-soft, #fafafa);
  border-radius: 6px;
  margin-bottom: 8px;
}

.ai-step-index {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--color-primary, #4f46e5);
  color: white;
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.ai-step-body {
  flex: 1;
  min-width: 0;
}

.ai-select {
  margin-bottom: 8px;
}

.ai-step-params {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ai-param-field {
  flex: 1;
  min-width: 140px;
}

.ai-param-label {
  display: block;
  font-size: 12px;
  color: var(--color-gray, #888);
  margin-bottom: 2px;
}

.ai-param-input {
  padding: 4px 8px;
  font-size: 13px;
}

.ai-step-actions {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ai-error {
  background: var(--color-danger-soft, #fee2e2);
  color: var(--color-danger, #991b1b);
  padding: 10px 12px;
  border-radius: 4px;
  margin-bottom: 16px;
  font-size: 13px;
}

@media (max-width: 768px) {
  .ai-form-grid {
    grid-template-columns: 1fr;
  }
}

/* ===== 节点图（Pipeline 步骤区） ===== */
.node-canvas {
  position: relative;
  padding: 12px 0 12px 60px; /* 左侧留空给序号列 + 连线 */
}

.node-card {
  position: relative;
  background: var(--ai-color-bg-card, #fff);
  border: 1px solid var(--ai-color-border, #e5e7eb);
  border-radius: var(--ai-radius-lg, 10px);
  padding: 10px 12px;
  margin-bottom: 16px;
  box-shadow: var(--ai-shadow-card, 0 1px 2px rgba(0, 0, 0, 0.04));
  transition: border-color 0.15s, box-shadow 0.15s, opacity 0.15s;
}

.node-card:hover {
  border-color: var(--ai-color-border-strong, #d1d5db);
  box-shadow: var(--ai-shadow-hover, 0 2px 8px rgba(0, 0, 0, 0.06));
}

.node-card--source {
  background: var(--ai-color-bg-muted, #f9fafb);
  border-style: dashed;
}

.node-card--dragging {
  opacity: 0.4;
}

.node-card--drop-target {
  border-color: var(--ai-color-info, #2563eb);
  border-width: 2px;
}

.node-card--preview-ok {
  border-left: 3px solid var(--ai-color-success, #10b981);
}

.node-card--preview-fallback {
  border-left: 3px solid var(--ai-color-danger, #ef4444);
}

.node-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.node-index {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--ai-color-info, #2563eb);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.node-index--source {
  background: var(--ai-color-fg-muted, #6b7280);
}

.node-title {
  font-weight: 600;
  font-size: 14px;
  color: var(--ai-color-fg, #111827);
}

.node-type-select {
  flex: 1;
  min-width: 0;
  border: 1px solid var(--ai-color-border, #e5e7eb);
  border-radius: var(--ai-radius-sm, 4px);
  padding: 4px 8px;
  font-size: 13px;
  background: var(--ai-color-bg-card, #fff);
  color: var(--ai-color-fg, #111827);
}

.node-llm-tag {
  background: var(--ai-color-warning, #f59e0b);
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: var(--ai-radius-pill, 999px);
  flex-shrink: 0;
}

.node-actions {
  display: flex;
  gap: 2px;
  margin-left: auto;
}

.node-icon-btn {
  background: transparent;
  border: 1px solid var(--ai-color-border, #e5e7eb);
  border-radius: var(--ai-radius-sm, 4px);
  cursor: pointer;
  padding: 2px 8px;
  font-size: 13px;
  color: var(--ai-color-fg-muted, #6b7280);
  line-height: 1.4;
}

.node-icon-btn:hover:not(:disabled) {
  background: var(--ai-color-bg-muted, #f9fafb);
}

.node-icon-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.node-icon-btn--danger:hover:not(:disabled) {
  color: var(--ai-color-danger, #ef4444);
  border-color: var(--ai-color-danger, #ef4444);
}

.node-params {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 10px 0 6px 32px;
}

.node-param-field {
  flex: 1;
  min-width: 140px;
}

.node-param-label {
  display: block;
  font-size: 12px;
  color: var(--ai-color-fg-muted, #6b7280);
  margin-bottom: 2px;
}

.node-toggle-params {
  background: transparent;
  border: none;
  color: var(--ai-color-info, #2563eb);
  cursor: pointer;
  font-size: 12px;
  padding: 2px 0 0 32px;
}

.node-toggle-params:hover {
  text-decoration: underline;
}

/* 试跑结果区 */
.node-preview {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin: 8px 0 0 32px;
  padding-top: 8px;
  border-top: 1px dashed var(--ai-color-divider, #f1f5f9);
}

.node-preview-badge {
  background: var(--ai-color-bg-subtle, #f3f4f6);
  color: var(--ai-color-fg-secondary, #4b5563);
  font-size: 12px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: var(--ai-radius-pill, 999px);
}

.node-preview-badge--ok {
  background: var(--ai-color-success-bg, #ecfdf5);
  color: var(--ai-color-success, #10b981);
}

.node-preview-badge--fallback {
  background: var(--ai-color-danger-bg, #fee2e2);
  color: var(--ai-color-danger, #ef4444);
}

.node-preview-time {
  font-size: 11px;
  color: var(--ai-color-fg-subtle, #8a94a6);
}

.node-preview-toggle {
  background: transparent;
  border: none;
  color: var(--ai-color-info, #2563eb);
  cursor: pointer;
  font-size: 12px;
}

.node-preview-toggle:hover {
  text-decoration: underline;
}

.node-preview-list {
  flex-basis: 100%;
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 4px;
  max-height: 200px;
  overflow-y: auto;
}

.node-preview-post {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  background: var(--ai-color-bg-subtle, #f3f4f6);
  border-radius: var(--ai-radius-sm, 4px);
  text-decoration: none;
  color: var(--ai-color-fg-secondary, #4b5563);
  font-size: 12px;
}

.node-preview-post:hover {
  background: var(--ai-color-bg-muted, #f9fafb);
}

.node-preview-post-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-preview-post-date {
  color: var(--ai-color-fg-subtle, #8a94a6);
  font-size: 11px;
  flex-shrink: 0;
}

/* SVG 连线层 */
.node-connector {
  position: absolute;
  top: 0;
  left: 0;
  width: 60px; /* 序号列宽度 */
  height: 100%;
  pointer-events: none;
  overflow: visible;
}

/* 试跑预览面板 */
.preview-panel {
  margin-top: 16px;
  padding: 12px;
  background: var(--ai-color-bg-subtle, #f3f4f6);
  border-radius: var(--ai-radius-md, 8px);
}

.preview-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.preview-title {
  font-weight: 600;
  font-size: 13px;
  color: var(--ai-color-fg, #111827);
}

.preview-hint {
  font-size: 12px;
  color: var(--ai-color-fg-subtle, #8a94a6);
}

.preview-input-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.preview-input {
  flex: 1;
}

.preview-warn {
  margin-top: 8px;
  padding: 6px 10px;
  background: var(--ai-color-warning-bg, #fef3c7);
  color: #92400e;
  border-radius: var(--ai-radius-sm, 4px);
  font-size: 12px;
}

.preview-summary {
  margin-top: 8px;
  font-size: 12px;
  color: var(--ai-color-fg-muted, #6b7280);
}
</style>
