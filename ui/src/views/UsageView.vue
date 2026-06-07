<template>
  <div class="usage-page">
    <div class="ai-content">
      <!-- 顶部 4 个指标卡 -->
      <div class="ai-metric-grid">
        <MetricCard
          color="blue"
          icon="zap"
          label="今日总调用"
          :value="stats.calls"
          :value-fmt="formatNum"
          :delta="deltaCalls.direction === 'flat' ? 'vs 昨日' : 'vs 昨日'"
          :delta-value="deltaCalls.value"
          delta-suffix="%"
          :delta-direction="deltaCalls.direction"
        />
        <MetricCard
          color="purple"
          icon="database"
          label="今日总 Token"
          :value="stats.tokens"
          :value-fmt="formatNum"
          :delta="deltaTokens.direction === 'flat' ? 'vs 昨日' : 'vs 昨日'"
          :delta-value="deltaTokens.value"
          delta-suffix="%"
          :delta-direction="deltaTokens.direction"
        />
        <MetricCard
          :color="stats.failureRate > 5 ? 'red' : 'green'"
          icon="alert"
          label="今日失败率"
          :value="stats.failureRate"
          :value-fmt="(n) => formatPct(n, 2)"
          :value-class="stats.failureRate > 5 ? 'fail' : 'ok'"
          :delta-text="stats.calls + ' 调用 / ' + stats.failures + ' 失败'"
        />
        <MetricCard
          color="green"
          icon="puzzle"
          label="活跃模型"
          :value="modelCount"
          :delta-text="topModelLabel"
        />
      </div>

      <!-- 主标题 + 全局控件 -->
      <div class="ai-page-header">
        <h1 class="ai-page-title">用量统计</h1>
        <div class="usage-range-tabs">
          <!-- 日期范围 popover 触发按钮 -->
          <div class="usage-range-picker">
            <button
              ref="triggerRef"
              type="button"
              class="usage-range-trigger"
              @click="togglePicker"
            >
              <span class="usage-range-trigger-icon"><RiCalendarLine /></span>
              <span class="usage-range-trigger-text">{{ triggerText }}</span>
              <span class="usage-range-trigger-caret">▾</span>
            </button>
            <div
              v-if="pickerOpen"
              class="usage-range-popover"
              :style="popoverPos"
              @click.stop
            >
              <div class="usage-range-presets">
                <VButton
                  v-for="p in (['today', '1d', '7d', '14d', '30d'] as const)"
                  :key="p"
                  size="xs"
                  :type="tempPreset === p ? 'primary' : 'secondary'"
                  @click="applyPresetInPopover(p)"
                >
                  {{ presetLabel(p) }}
                </VButton>
              </div>
              <div class="usage-range-fields">
                <div
                  class="usage-range-field"
                  :class="{ active: pickingField === 'start' }"
                  @click="pickingField = 'start'"
                >
                  <div class="usage-range-field-label">开始</div>
                  <div class="usage-range-field-value">
                    {{ tempStart || "选择日期" }}
                  </div>
                </div>
                <div
                  class="usage-range-field"
                  :class="{ active: pickingField === 'end' }"
                  @click="pickingField = 'end'"
                >
                  <div class="usage-range-field-label">结束</div>
                  <div class="usage-range-field-value">
                    {{ tempEnd || "选择日期" }}
                  </div>
                </div>
              </div>
              <UsageCalendar
                :year="calYear"
                :month="calMonth"
                :start-date="tempStart"
                :end-date="tempEnd"
                :max-date="todayStr"
                @update:year="(y) => (calYear = y)"
                @update:month="(m) => (calMonth = m)"
                @select="onCalendarSelect"
              />
              <div v-if="pickerError" class="usage-range-error">{{ pickerError }}</div>
              <div class="usage-range-actions">
                <VButton type="default" @click="cancelPicker">取消</VButton>
                <VButton type="primary" @click="confirmPicker">确定</VButton>
              </div>
            </div>
          </div>
          <button class="ai-btn ai-btn-xs" @click="loadAll">刷新</button>
          <button class="ai-btn ai-btn-xs ai-btn-primary" @click="openDrawer">
            <RiSettings3Line /> 限流配置
          </button>
        </div>
      </div>

      <!-- 趋势子标题 + 折线图 -->
      <div class="ai-subsection">
        <h2 class="ai-subsection-heading">模型用量趋势</h2>
        <article class="ai-section-card">
          <div class="ai-card-body">
            <div v-if="xAxisLabels.length === 0" class="ai-empty">暂无历史数据</div>
            <div v-else class="usage-chart-wrap">
              <svg
                ref="chartSvg"
                :width="CHART_WIDTH"
                :height="CHART_HEIGHT"
                :viewBox="`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`"
                class="usage-chart"
                preserveAspectRatio="xMidYMid meet"
              >
                <!-- 水平网格线 + Y 轴刻度 -->
                <g>
                  <line
                    v-for="(t, i) in yAxisTicks"
                    :key="'gl' + i"
                    :x1="CHART_PAD.left"
                    :x2="CHART_WIDTH - CHART_PAD.right"
                    :y1="t.y"
                    :y2="t.y"
                    stroke="#e5e7eb"
                    stroke-width="1"
                    stroke-dasharray="2 3"
                  />
                  <text
                    v-for="(t, i) in yAxisTicks"
                    :key="'yl' + i"
                    :x="CHART_PAD.left - 6"
                    :y="t.y + 3"
                    text-anchor="end"
                    font-size="9"
                    fill="#9ca3af"
                  >
                    {{ t.label }}
                  </text>
                </g>

                <!-- 月初位置的浅色背景带 (横跨图表高度) -->
                <g>
                  <rect
                    v-for="(lbl, i) in xAxisLabels"
                    v-show="lbl.isMonthStart"
                    :key="'bg' + i"
                    :x="lbl.x - 3"
                    :y="CHART_PAD.top"
                    width="6"
                    :height="CHART_HEIGHT - CHART_PAD.top - CHART_PAD.bottom"
                    fill="#f9fafb"
                  />
                </g>

                <!-- X 轴日期 -->
                <g>
                  <text
                    v-for="(lbl, i) in xAxisLabels"
                    v-show="lbl.showLabel"
                    :key="'xl' + i"
                    :x="lbl.x"
                    :y="CHART_HEIGHT - 8"
                    text-anchor="middle"
                    font-size="8"
                    :font-weight="lbl.isHighlighted ? 500 : 400"
                    :textLength="lbl.textLength ?? undefined"
                    lengthAdjust="spacingAndGlyphs"
                    :fill="lbl.isHighlighted ? '#374151' : '#6b7280'"
                  >
                    {{ lbl.label }}
                  </text>
                </g>

                <!-- 模型折线: 面积先,描边后 -->
                <g v-for="(line, i) in chartLines" :key="'l' + i">
                  <path
                    v-if="line.isArea"
                    :d="line.areaD"
                    :fill="line.color"
                    fill-opacity="0.12"
                  />
                  <path
                    :d="line.pathD"
                    :stroke="line.color"
                    :stroke-width="line.isPrimary ? 2 : 1.2"
                    :stroke-dasharray="line.isDashed ? '5 4' : (line.isDisabled ? '3 3' : '')"
                    :opacity="line.isDisabled ? 0.4 : (line.isPrimary ? 1 : 0.7)"
                    fill="none"
                    stroke-linejoin="round"
                    stroke-linecap="round"
                  />
                  <!-- 不可见 hover 区域 (扩大命中范围到 12px 宽, 方便鼠标捕获) -->
                  <rect
                    v-for="(pt, j) in line.points"
                    v-show="line.isPrimary && pt.value > 0"
                    :key="'hz' + i + '-' + j"
                    :x="pt.x - 6"
                    :y="CHART_PAD.top"
                    width="12"
                    :height="CHART_INNER_H"
                    fill="transparent"
                    @mouseenter="onPointEnter($event, j, i)"
                    @mouseleave="onPointLeave"
                  />
                  <!-- 可见数据点 -->
                  <circle
                    v-for="(pt, j) in line.points"
                    v-show="line.isPrimary && pt.value > 0"
                    :key="'p' + i + '-' + j"
                    :cx="pt.x"
                    :cy="pt.y"
                    :r="2.5"
                    :fill="line.color"
                    :opacity="line.isDisabled ? 0.5 : 1"
                    @mouseenter="onPointEnter($event, j, i)"
                    @mouseleave="onPointLeave"
                  />
                </g>
              </svg>

              <!-- hover 数据点 tooltip (固定定位浮在数据点上方) -->
              <div
                v-if="tooltipData"
                class="usage-chart-tooltip"
                :style="{ left: tooltipData.screenX + 'px', top: tooltipData.screenY + 'px' }"
              >
                <div class="usage-chart-tooltip-date">{{ tooltipData.date }}</div>
                <div class="usage-chart-tooltip-models">
                  <div
                    v-for="m in tooltipData.allModels"
                    :key="m.model"
                    class="usage-chart-tooltip-row"
                    :class="{ 'is-hovered': m.model === tooltipData.hoveredModel }"
                  >
                    <span class="usage-chart-tooltip-dot" :style="{ background: m.color }"></span>
                    <span class="usage-chart-tooltip-model">{{ m.model }}</span>
                    <span class="usage-chart-tooltip-total">{{ m.total.toLocaleString() }}</span>
                  </div>
                </div>
              </div>

              <!-- 图例 -->
              <div class="usage-chart-legend">
                <div
                  v-for="(line, i) in chartLines"
                  :key="'lg' + i"
                  class="usage-chart-legend-item"
                >
                  <span
                    class="usage-chart-legend-dot"
                    :class="{
                      'usage-chart-legend-dashed': line.isDashed,
                      'usage-chart-legend-disabled': line.isDisabled,
                    }"
                    :style="{ background: line.color, borderColor: line.color }"
                  ></span>
                  <span class="usage-chart-legend-label">
                    <strong>{{ line.featureLabel }}</strong>
                    <code class="usage-chart-legend-model">{{ line.model }}</code>
                    <span v-if="line.isDisabled" class="usage-chart-legend-badge">未启用</span>
                  </span>
                </div>
                <div v-if="writingFoldsNote" class="usage-chart-legend-note">
                  <RiLightbulbLine /> {{ writingFoldsNote }}
                </div>
              </div>
            </div>
          </div>
        </article>
      </div>

      <!-- 聚合子标题 + 表格 -->
      <div class="ai-subsection">
        <div class="ai-subsection-header">
          <h2 class="ai-subsection-heading">模型用量汇总</h2>
          <span class="ai-subsection-meta">范围: {{ rangeLabelText }}</span>
        </div>
        <article class="ai-section-card">
          <div class="ai-card-body" style="padding: 0;">
            <table class="ai-table">
              <thead>
                <tr>
                  <th>模型</th>
                  <th>{{ rangeLabelText }} Token</th>
                  <th>调用次数</th>
                  <th>失败数</th>
                  <th>失败率</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in rows" :key="row.model">
                  <td><code>{{ row.model }}</code></td>
                  <td>{{ row.tokens.toLocaleString() }}</td>
                  <td>{{ row.calls.toLocaleString() }}</td>
                  <td>{{ row.failures.toLocaleString() }}</td>
                  <td :class="{ 'usage-fail': row.failureRate > 5 }">
                    {{ row.failureRate.toFixed(2) }}%
                  </td>
                </tr>
                <tr v-if="rows.length === 0">
                  <td colspan="5" class="ai-empty">所选范围内暂无数据</td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>
      </div>
    </div>

    <!-- 限流配置抽屉 -->
    <div class="usage-drawer-mask" v-if="drawerOpen" @click="drawerOpen = false"></div>
    <div class="usage-drawer" :class="{ open: drawerOpen }">
      <div class="usage-drawer-header">
        <h3>限流配置</h3>
        <button class="usage-drawer-close" @click="drawerOpen = false">×</button>
      </div>
      <div class="usage-drawer-body">
        <div class="ai-form-field">
          <label class="ai-field-label">
            <input type="checkbox" v-model="limitsForm.enabled" />
            启用对话限流（关闭后所有对话放行）
          </label>
          <div class="ai-field-hint">超出每日 token 上限自动拒绝对话。嵌入/重排序不参与限流（成本可忽略）。</div>
        </div>
        <div class="ai-form-field">
          <label class="ai-field-label">对话模型每日 Token 上限</label>
          <div v-if="chatModelsForLimits.length === 0" class="ai-field-hint">
            请先到 <strong>模型配置 → 对话</strong> 页面设置对话模型。
          </div>
          <div v-for="(item, i) in chatModelsForLimits" :key="i" class="usage-permodel-row">
            <input class="ai-input" :value="item.model" disabled title="模型名由「模型配置」统一管理" />
            <input
              class="ai-input"
              type="number"
              min="0"
              placeholder="上限 (0=不限)"
              v-model.number="item.limit"
            />
            <span class="ai-form-progress" :class="progressClass(item)">
              {{ formatTodayTokens(item.todayTokens) }} / {{ formatTodayTokens(item.limit || 0) }}
            </span>
          </div>
          <div class="ai-field-hint">
            模型名由「模型配置 → 对话」统一管理；此处仅设置每日 token 上限（0 = 不限）。
          </div>
          <div v-if="limitsForm.enabled" class="ai-field-warning">
            <span class="ai-field-warning-icon"><RiAlertLine /></span>
            <span>
              <strong>提示：</strong>限流仅对访客对话生效。写作辅助、文章摘要、索引重建等后台功能不受此限制，可正常运行。
            </span>
          </div>
        </div>

        <div class="ai-form-divider" />

        <div class="ai-form-field">
          <label class="ai-field-label">
            <input type="checkbox" v-model="limitsForm.visitorEnabled" />
            启用访客限流（按客户端 IP 限流对话次数）
          </label>
          <div class="ai-field-hint">防单用户刷量。嵌入/重排序不受访客限流影响（仍按模型 token 限流）。</div>
        </div>
        <div class="ai-form-field" v-if="limitsForm.visitorEnabled">
          <label class="ai-field-label">每 IP 每日对话次数上限（0=不限）</label>
          <input
            type="number" min="0"
            class="ai-input"
            v-model.number="limitsForm.visitorDailyLimit"
            placeholder="例: 50"
          />
        </div>
        <div class="ai-form-field" v-if="limitsForm.visitorEnabled">
          <label class="ai-field-label">每 IP 滑动 1 小时上限（0=不限）</label>
          <input
            type="number" min="0"
            class="ai-input"
            v-model.number="limitsForm.visitorHourlyLimit"
            placeholder="例: 10"
          />
        </div>
        <div class="ai-form-field" v-if="limitsForm.visitorEnabled">
          <label class="ai-field-label">IP 白名单（每行一个，精确匹配）</label>
          <textarea
            class="ai-input"
            rows="3"
            v-model="visitorWhitelistText"
            placeholder="192.168.1.10&#10;::1"
          ></textarea>
          <div class="ai-field-hint">白名单内 IP 不受限（建议把你自己的 IP 加进去）。</div>
        </div>
        <div v-if="saveMsg" class="ai-save-msg" :class="{ 'ai-save-ok': saveOk, 'ai-save-fail': !saveOk }">
          {{ saveMsg }}
        </div>
        <div class="ai-card-actions">
          <VButton @click="drawerOpen = false">取消</VButton>
          <VButton type="primary" :disabled="saving" @click="saveLimits">{{ saving ? '保存中...' : '保存' }}</VButton>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref, computed } from "vue";
import {
  loadUsageLimits,
  saveUsageLimits,
  loadUsageToday,
  loadUsageStats,
  type DailyStatsEntry,
} from "../utils/config";
import MetricCard from "../components/MetricCard.vue";
import UsageCalendar from "../components/UsageCalendar.vue";
import { formatNum, formatPct, computeDelta } from "../utils/format";
import { Toast , VButton, VSpace} from "@halo-dev/components";
import RiCalendarLine from "~icons/ri/calendar-line";
import RiSettings3Line from "~icons/ri/settings-3-line";
import RiLightbulbLine from "~icons/ri/lightbulb-line";
import RiAlertLine from "~icons/ri/alert-line";

interface ModelUsage {
  model: string;
  promptTokens: number;
  completionTokens: number;
  calls: number;
  failures: number;
  embeddingTokens: number;
}

// 日期范围 (YYYY-MM-DD, 替代原来的 today/7d/30d 字符串)
function toDateStr(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}
const _initToday = new Date();
const _initSevenDaysAgo = new Date(_initToday);
_initSevenDaysAgo.setDate(_initToday.getDate() - 6);
const startDate = ref<string>(toDateStr(_initSevenDaysAgo));
const endDate = ref<string>(toDateStr(_initToday));
// 当前选中的预设 (用于触发按钮显示); 用户在弹层里手选日期后变 null
const activePreset = ref<"today" | "1d" | "7d" | "14d" | "30d" | null>("7d");

// ===== 日期范围 popover 状态 =====
const pickerOpen = ref(false);
const triggerRef = ref<HTMLElement | null>(null);
// 位置用 Record<string, string> 而不是具体类型 — 避免 TS 类型窄化导致 left 被 Vue :style 过滤掉
const popoverPos = ref<Record<string, string>>({ top: "0px", left: "0px" });
const tempStart = ref<string>("");
const tempEnd = ref<string>("");
// 弹层里的临时预设 (确定时才提交到 activePreset)
const tempPreset = ref<typeof activePreset.value>(null);
// 当前正在填的字段 (点日历决定写到 start 还是 end)
const pickingField = ref<"start" | "end">("start");
// 日历显示的月份
const calYear = ref(_initToday.getFullYear());
const calMonth = ref(_initToday.getMonth());
// 弹层校验错误
const pickerError = ref<string>("");
const todayStr = toDateStr(new Date());

function presetLabel(p: "today" | "1d" | "7d" | "14d" | "30d"): string {
  if (p === "today") return "今天";
  if (p === "1d") return "1天";
  if (p === "7d") return "7天";
  if (p === "14d") return "14天";
  return "30天";
}

// 触发按钮显示文本
const triggerText = computed(() => {
  if (activePreset.value === "today") return "今日";
  if (activePreset.value === "1d") return "1天";
  if (activePreset.value === "7d") return "近7天";
  if (activePreset.value === "14d") return "近14天";
  if (activePreset.value === "30d") return "近30天";
  // 自定义
  if (startDate.value === endDate.value) return startDate.value;
  return `${startDate.value} ~ ${endDate.value}`;
});

function togglePicker() {
  if (pickerOpen.value) {
    pickerOpen.value = false;
    return;
  }
  // 打开: 用当前提交值初始化弹层
  tempStart.value = startDate.value;
  tempEnd.value = endDate.value;
  tempPreset.value = activePreset.value;
  pickingField.value = "start";
  pickerError.value = "";
  // 日历跳到 start 所在月
  const d = new Date(startDate.value + "T00:00:00");
  calYear.value = d.getFullYear();
  calMonth.value = d.getMonth();
  // 同步计算位置 (trigger 一直在 DOM 里, 不用 nextTick)
  // 左上角对齐 trigger 左下角: top = trigger.bottom + 6, left = trigger.left
  if (triggerRef.value) {
    const r = triggerRef.value.getBoundingClientRect();
    popoverPos.value = {
      top: `${r.bottom + 6}px`,
      left: `${r.left}px`,
    };
  }
  pickerOpen.value = true;
}
function cancelPicker() {
  pickerOpen.value = false;
  pickerError.value = "";
}
function confirmPicker() {
  pickerError.value = "";
  if (!tempStart.value || !tempEnd.value) {
    pickerError.value = "请选择完整的开始和结束日期";
    return;
  }
  if (new Date(tempStart.value) > new Date(tempEnd.value)) {
    pickerError.value = "开始日期不能晚于结束日期";
    return;
  }
  const span =
    Math.floor(
      (new Date(tempEnd.value).getTime() - new Date(tempStart.value).getTime()) /
        (1000 * 60 * 60 * 24)
    ) + 1;
  if (span > 30) {
    pickerError.value = `范围最大 30 天 (当前 ${span} 天)`;
    return;
  }
  // 提交
  startDate.value = tempStart.value;
  endDate.value = tempEnd.value;
  activePreset.value = tempPreset.value;
  pickerOpen.value = false;
  loadAll();
}

function applyPresetInPopover(p: "today" | "1d" | "7d" | "14d" | "30d") {
  tempPreset.value = p;
  pickerError.value = "";
  const today = new Date();
  tempEnd.value = toDateStr(today);
  let daysBack = 0;
  if (p === "1d") daysBack = 0;
  else if (p === "7d") daysBack = 6;
  else if (p === "14d") daysBack = 13;
  else if (p === "30d") daysBack = 29;
  if (daysBack > 0) {
    const d = new Date();
    d.setDate(today.getDate() - daysBack);
    tempStart.value = toDateStr(d);
  } else {
    tempStart.value = toDateStr(today);
  }
  // 日历跳到 start 所在月
  const d = new Date(tempStart.value + "T00:00:00");
  calYear.value = d.getFullYear();
  calMonth.value = d.getMonth();
  pickingField.value = "end";
}

// 日历点击: 根据 pickingField 决定写到 start 还是 end
function onCalendarSelect(date: string) {
  pickerError.value = "";
  if (pickingField.value === "end" && tempStart.value) {
    // 用户在填 end
    if (date < tempStart.value) {
      // 选了比 start 早的日期 → 交换
      tempEnd.value = tempStart.value;
      tempStart.value = date;
      pickingField.value = "end";
    } else {
      tempEnd.value = date;
      pickingField.value = "start"; // 下一轮回 start
    }
  } else {
    // 默认 / 填 start
    tempStart.value = date;
    tempEnd.value = "";
    pickingField.value = "end";
  }
}

// 点击 popover 外部关闭
function onDocClick(e: MouseEvent) {
  if (!pickerOpen.value) return;
  const target = e.target as HTMLElement;
  if (!target.closest(".usage-range-picker")) {
    pickerOpen.value = false;
  }
}

const todayData = ref<{ date: string; models: ModelUsage[] }>({ date: "", models: [] });
const statsData = ref<{ range: string; days: number; daily: DailyStatsEntry[]; totals: any; yesterday?: any; modelsInRange: string[]; start: string; end: string } | null>(null);
const drawerOpen = ref(false);
const saving = ref(false);
const saveMsg = ref("");
const saveOk = ref(false);

// 当前 5 个主模型配置 (从 /usage/limits 拉, 供多折线图用)
const chartModelConfig = ref({
  chatModel: "",
  embeddingModel: "",
  rerankEnabled: false,
  rerankModel: "",
  queryRewriteEnabled: false,
  queryRewriteModel: "",
  writingModel: "",
});

// SVG 元素引用 — 用于 hover 时把 viewBox 坐标转屏幕坐标
const chartSvg = ref<SVGSVGElement | null>(null);

// 当前 hover 的数据点 (lineIdx + pointIdx, pointIdx 是 chartLines.points 的索引)
const hoveredPoint = ref<{ lineIdx: number; pointIdx: number; screenX: number; screenY: number } | null>(null);

function onPointEnter(_event: MouseEvent, pointIdx: number, lineIdx: number) {
  const svg = chartSvg.value;
  const line = chartLines.value[lineIdx];
  const pt = line?.points[pointIdx];
  if (!svg || !pt) {
    hoveredPoint.value = null;
    return;
  }
  // 把 viewBox 坐标 (pt.x, pt.y) 转屏幕坐标, 供 position: fixed 的 tooltip 用
  const sp = svg.createSVGPoint();
  sp.x = pt.x;
  sp.y = pt.y;
  const ctm = svg.getScreenCTM();
  if (!ctm) {
    hoveredPoint.value = null;
    return;
  }
  const screen = sp.matrixTransform(ctm);
  hoveredPoint.value = {
    lineIdx,
    pointIdx,
    screenX: screen.x,
    screenY: screen.y,
  };
}

function onPointLeave() {
  hoveredPoint.value = null;
}

// 限流配置 — 对话模型 + 访客双重维度
// chatModelLimits: [{ model: string, limit: number }] — 模型名只读(从主配置 ModelConfig 拉), 数字可编辑
// currentChatModel: 主配置里当前启用的对话模型 (string, 用于空状态提示)
// visitorEnabled/visitorDailyLimit/visitorHourlyLimit/visitorWhitelist: 访客 IP 限流
const limitsForm = reactive({
  enabled: false,
  currentChatModel: "",
  chatModelLimits: [] as Array<{ model: string; limit: number }>,
  visitorEnabled: false,
  visitorDailyLimit: 0,
  visitorHourlyLimit: 0,
  visitorWhitelist: [] as string[],
});

const stats = computed(() => {
  const models = todayData.value.models;
  const calls = models.reduce((s, m) => s + m.calls, 0);
  const failures = models.reduce((s, m) => s + m.failures, 0);
  const tokens = models.reduce(
    (s, m) => s + m.promptTokens + m.completionTokens + m.embeddingTokens,
    0
  );
  return {
    calls,
    failures,
    tokens,
    failureRate: calls === 0 ? 0 : (failures * 100) / calls,
  };
});

const modelCount = computed(() => todayData.value.models.length);

const topModelLabel = computed(() => {
  const list = todayData.value.models;
  if (list.length === 0) return "暂无模型";
  // 按 token 用量降序取第 1
  const sorted = [...list].sort(
    (a, b) =>
      b.promptTokens + b.completionTokens + b.embeddingTokens -
      (a.promptTokens + a.completionTokens + a.embeddingTokens)
  );
  const top = sorted[0];
  const tokens = top.promptTokens + top.completionTokens + top.embeddingTokens;
  return `${top.model} (${formatNum(tokens)})`;
});

// 今日 vs 昨日 — 后端 statsData.yesterday 提供基线
const deltaCalls = computed(() => {
  const y = statsData.value?.yesterday;
  return computeDelta(stats.value.calls, y?.calls ?? 0);
});
const deltaTokens = computed(() => {
  const y = statsData.value?.yesterday;
  return computeDelta(
    stats.value.tokens,
    (y?.promptTokens ?? 0) + (y?.completionTokens ?? 0) + (y?.embeddingTokens ?? 0)
  );
});

interface Row {
  model: string;
  tokens: number;
  calls: number;
  failures: number;
  failureRate: number;
}

/** 表格列头显示的 range 文本 (例 "近 7 天" / "12-01 ~ 12-30") */
const rangeLabelText = computed(() => {
  if (!startDate.value || !endDate.value) return "—";
  const start = startDate.value.substring(5); // MM-DD
  const end = endDate.value.substring(5);
  if (start === end) {
    return endDate.value;  // 同一天, 显示完整日期 YYYY-MM-DD
  }
  return `${start} ~ ${end}`;
});

/** 聚合 statsData.daily (range 内) by model — 替代之前 todayData.models (单日) */
const rows = computed<Row[]>(() => {
  if (!statsData.value?.daily?.length) return [];
  // 累加每个模型跨 range 的 p/c/e/calls/failures
  const agg = new Map<string, { p: number; c: number; e: number; calls: number; failures: number }>();
  for (const d of statsData.value.daily) {
    for (const [model, bm] of Object.entries(d.byModel || {})) {
      const cur = agg.get(model) || { p: 0, c: 0, e: 0, calls: 0, failures: 0 };
      cur.p += bm.p;
      cur.c += bm.c;
      cur.e += bm.e;
      cur.calls += bm.calls;
      cur.failures += bm.failures ?? 0;
      agg.set(model, cur);
    }
  }
  return Array.from(agg.entries())
    .map(([model, a]) => ({
      model,
      tokens: a.p + a.c + a.e,
      calls: a.calls,
      failures: a.failures,
      failureRate: a.calls === 0 ? 0 : (a.failures * 100) / a.calls,
    }))
    .sort((x, y) => y.tokens - x.tokens);
});

// ===== 多折线图 =====

const CHART_WIDTH = 700;
const CHART_HEIGHT = 200;
const CHART_PAD = { top: 16, right: 16, bottom: 28, left: 50 };
const CHART_INNER_W = CHART_WIDTH - CHART_PAD.left - CHART_PAD.right;
const CHART_INNER_H = CHART_HEIGHT - CHART_PAD.top - CHART_PAD.bottom;

// 5 主功能位的固定颜色 (与图例颜色一致)
const FEATURE_COLORS: Record<string, string> = {
  chat: "#8B5CF6",         // 紫 — 对话
  embed: "#3B82F6",        // 蓝 — 嵌入
  rerank: "#10B981",       // 绿 — 重排序
  queryRewrite: "#F59E0B", // 橙 — 查询改写
  writing: "#EF4444",      // 红 — 写作
};

type LineFeature = "chat" | "embed" | "rerank" | "queryRewrite" | "writing" | "history";

interface ChartPoint {
  x: number;
  y: number;
  value: number;
  dateLabel: string;
}

interface ChartLine {
  model: string;        // 模型名(原样)
  feature: LineFeature; // 功能位
  featureLabel: string; // '对话'/'嵌入'/'重排序'/'查询改写'/'写作'/'历史'
  isPrimary: boolean;   // 5 主线 vs 历史补线
  isDashed: boolean;    // 写作虚线
  isArea: boolean;      // 对话面积填充
  isDisabled: boolean;  // 未启用 (rerank/queryRewrite)
  isFolded: boolean;    // 写作折叠到对话
  color: string;
  points: ChartPoint[];
  pathD: string;        // 折线 SVG d
  areaD: string;        // 面积闭合 d (对话用)
}

/** 向上取整到"漂亮数字": 1/2/5 × 10^k */
function niceCeil(n: number): number {
  if (n <= 1) return 1;
  const exp = Math.floor(Math.log10(n));
  const base = Math.pow(10, exp);
  const ratio = n / base;
  let nice;
  if (ratio <= 1) nice = 1;
  else if (ratio <= 2) nice = 2;
  else if (ratio <= 5) nice = 5;
  else nice = 10;
  return nice * base;
}

/** 由模型名 hash 到柔和的灰色色相 — 历史补线用 */
function hashColor(s: string): string {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  const hue = Math.abs(h) % 360;
  return `hsl(${hue}, 25%, 60%)`;
}

/**
 * Catmull-Rom → Cubic Bezier 平滑曲线.
 * 把数据点串成一条经过每个点的平滑曲线, 替代默认的 L 直线段.
 * tension=0.5 是标准 Catmull-Rom, 越小越接近直线, 越大越弯曲.
 */
function catmullRomPath(points: ChartPoint[]): string {
  if (points.length === 0) return "";
  if (points.length === 1) {
    return `M ${points[0].x.toFixed(1)},${points[0].y.toFixed(1)}`;
  }
  if (points.length === 2) {
    return `M ${points[0].x.toFixed(1)},${points[0].y.toFixed(1)} L ${points[1].x.toFixed(1)},${points[1].y.toFixed(1)}`;
  }
  const t = 0.5 / 3;  // tension / 3, Catmull-Rom 转 Cubic Bezier 系数
  let d = `M ${points[0].x.toFixed(1)},${points[0].y.toFixed(1)}`;
  for (let i = 0; i < points.length - 1; i++) {
    const p0 = i > 0 ? points[i - 1] : points[i];
    const p1 = points[i];
    const p2 = points[i + 1];
    const p3 = i < points.length - 2 ? points[i + 2] : points[i + 1];
    const cp1x = p1.x + (p2.x - p0.x) * t;
    const cp1y = p1.y + (p2.y - p0.y) * t;
    const cp2x = p2.x - (p3.x - p1.x) * t;
    const cp2y = p2.y - (p3.y - p1.y) * t;
    d += ` C ${cp1x.toFixed(1)},${cp1y.toFixed(1)} ${cp2x.toFixed(1)},${cp2y.toFixed(1)} ${p2.x.toFixed(1)},${p2.y.toFixed(1)}`;
  }
  return d;
}

/** 平滑曲线的面积路径: 基线 → 首点 → 沿平滑曲线 → 末点 → 基线 闭合 */
function catmullRomAreaPath(points: ChartPoint[], baseline: number): string {
  if (points.length < 2) return "";
  const first = points[0];
  const last = points[points.length - 1];
  let d = `M ${first.x.toFixed(1)},${baseline} L ${first.x.toFixed(1)},${first.y.toFixed(1)}`;
  const t = 0.5 / 3;
  for (let i = 0; i < points.length - 1; i++) {
    const p0 = i > 0 ? points[i - 1] : points[i];
    const p1 = points[i];
    const p2 = points[i + 1];
    const p3 = i < points.length - 2 ? points[i + 2] : points[i + 1];
    const cp1x = p1.x + (p2.x - p0.x) * t;
    const cp1y = p1.y + (p2.y - p0.y) * t;
    const cp2x = p2.x - (p3.x - p1.x) * t;
    const cp2y = p2.y - (p3.y - p1.y) * t;
    d += ` C ${cp1x.toFixed(1)},${cp1y.toFixed(1)} ${cp2x.toFixed(1)},${cp2y.toFixed(1)} ${p2.x.toFixed(1)},${p2.y.toFixed(1)}`;
  }
  d += ` L ${last.x.toFixed(1)},${baseline} Z`;
  return d;
}

/** Y 轴最大值 — 跨所有天×所有模型的 token 总量 */
const chartNiceMax = computed(() => {
  if (!statsData.value?.daily?.length) return 1;
  let m = 0;
  statsData.value.daily.forEach((d) => {
    Object.values(d.byModel || {}).forEach((bm) => {
      const t = bm.p + bm.c + bm.e;
      if (t > m) m = t;
    });
  });
  return niceCeil(Math.max(1, m));
});

/** 5 主线 + 历史补线 (历史补线 = modelsInRange 减去 5 主模型的并集) */
const chartLines = computed<ChartLine[]>(() => {
  if (!statsData.value?.daily?.length) return [];
  const daily = statsData.value.daily; // 后端已按 start..end 升序返回
  const days = daily.length;
  const xStep = days > 1 ? CHART_INNER_W / (days - 1) : 0;
  const niceMax = chartNiceMax.value;
  const cfg = chartModelConfig.value;
  const writingFolds = !cfg.writingModel || cfg.writingModel === cfg.chatModel;

  const primaryList: Array<{
    feature: Exclude<LineFeature, "history">;
    label: string;
    model: string;
    enabled: boolean;
    dashed: boolean;
    area: boolean;
  }> = [
    { feature: "chat",         label: "对话",     model: cfg.chatModel,         enabled: true,                    dashed: false, area: true  },
    { feature: "embed",        label: "嵌入",     model: cfg.embeddingModel,    enabled: true,                    dashed: false, area: false },
    { feature: "rerank",       label: "重排序",   model: cfg.rerankModel,       enabled: !!cfg.rerankEnabled,     dashed: false, area: false },
    { feature: "queryRewrite", label: "查询改写", model: cfg.queryRewriteModel, enabled: !!cfg.queryRewriteEnabled, dashed: false, area: false },
  ];
  if (!writingFolds) {
    primaryList.push({
      feature: "writing", label: "写作", model: cfg.writingModel,
      enabled: true, dashed: true, area: false,
    });
  }

  const primarySet = new Set(primaryList.map((p) => p.model).filter(Boolean));
  const historyExtras = (statsData.value.modelsInRange || []).filter(
    (m) => m && !primarySet.has(m)
  );

  // 给定 model 名 → 折线 + 面积 d 字符串 (用 Catmull-Rom 平滑曲线)
  const buildLineFor = (model: string): { points: ChartPoint[]; pathD: string; areaD: string } => {
    const points: ChartPoint[] = daily.map((d, i) => {
      const m = d.byModel?.[model];
      const total = m ? m.p + m.c + m.e : 0;
      const x = days > 1
        ? CHART_PAD.left + i * xStep
        : CHART_PAD.left + CHART_INNER_W / 2;
      const y = CHART_PAD.top + CHART_INNER_H - (total / niceMax) * CHART_INNER_H;
      return { x, y, value: total, dateLabel: d.date.substring(5) };
    });
    const pathD = catmullRomPath(points);
    return { points, pathD, areaD: "" };
  };

  const lines: ChartLine[] = [];

  // 去重 + 合并: 多个功能位共用同一 model 时合并成一条线, featureLabel 用 '+' 拼接
  // 视觉属性 (color/area/dashed) 走 "代表性 feature" — 优先 chat(紫+面积) > embed > writing > 其他
  // 避免 "对话" 和 "查询改写" 共用同一模型时画两条完全重叠的线
  const FEATURE_PREFERENCE = ["chat", "embed", "writing", "queryRewrite", "rerank"] as const;
  const grouped = new Map<string, typeof primaryList>();
  for (const p of primaryList) {
    if (!p.model) continue;
    if (!grouped.has(p.model)) grouped.set(p.model, []);
    grouped.get(p.model)!.push(p);
  }
  for (const [model, group] of grouped) {
    const rep =
      (FEATURE_PREFERENCE.map((f) => group.find((g) => g.feature === f)).find(
        Boolean
      ) as (typeof primaryList)[number] | undefined) || group[0];
    const mergedLabel = group.length > 1 ? group.map((g) => g.label).join("+") : rep.label;
    const { points, pathD } = buildLineFor(model);
    const areaD = rep.area ? catmullRomAreaPath(points, CHART_PAD.top + CHART_INNER_H) : "";
    // isDisabled: 仅当组内所有 feature 都未启用; chat/embed 始终启用, 含 chat 必亮
    const allDisabled = group.every((g) => !g.enabled);
    lines.push({
      model,
      feature: rep.feature,
      featureLabel: mergedLabel,
      isPrimary: true,
      isDashed: rep.dashed,
      isArea: rep.area,
      isDisabled: allDisabled,
      isFolded: false,
      color: FEATURE_COLORS[rep.feature],
      points,
      pathD,
      areaD,
    });
  }

  historyExtras.forEach((model) => {
    const { points, pathD } = buildLineFor(model);
    lines.push({
      model,
      feature: "history",
      featureLabel: model,
      isPrimary: false,
      isDashed: false,
      isArea: false,
      isDisabled: false,
      isFolded: false,
      color: hashColor(model),
      points,
      pathD,
      areaD: "",
    });
  });

  return lines;
});

/** 写作辅助图例提示文案（抽常量集中管理，避免散落） */
const WRITING_FOLDS_NOTE = {
  noModel: (chatModel: string) =>
    `写作辅助未配置独立模型，自动复用「${chatModel}」`,
  sameAsChat: (writingModel: string) =>
    `写作辅助「${writingModel}」与对话模型相同，未单独画线`,
};

/** 写作是否折叠到对话 (供模板展示提示用) */
const writingFoldsNote = computed(() => {
  const cfg = chartModelConfig.value;
  if (!cfg.chatModel) return null;
  if (!cfg.writingModel) {
    return WRITING_FOLDS_NOTE.noModel(cfg.chatModel);
  }
  if (cfg.writingModel === cfg.chatModel) {
    return WRITING_FOLDS_NOTE.sameAsChat(cfg.writingModel);
  }
  return null;
});

/** 模型名 → 折线颜色, tooltip 里的"其他模型"行用 */
const modelColorMap = computed(() => {
  const m = new Map<string, string>();
  for (const line of chartLines.value) {
    m.set(line.model, line.color);
  }
  return m;
});

/** 当前 hover 的数据点 → 该天所有模型的总 Token 列表 */
const tooltipData = computed(() => {
  if (!hoveredPoint.value) return null;
  const { lineIdx, pointIdx, screenX, screenY } = hoveredPoint.value;
  const line = chartLines.value[lineIdx];
  if (!line) return null;
  const pt = line.points[pointIdx];
  if (!pt) return null;
  const daily = statsData.value?.daily;
  if (!daily) return null;
  const originalIdx = daily.length - 1 - pointIdx;
  const dayData = daily[originalIdx];
  if (!dayData) return null;
  // 所有有数据的模型, 按总 Token 降序
  const allModels = Object.entries(dayData.byModel || {})
    .map(([m, bm]) => ({
      model: m,
      total: bm.p + bm.c + bm.e,
      color: modelColorMap.value.get(m) || "#9ca3af",
    }))
    .filter((m) => m.total > 0)
    .sort((a, b) => b.total - a.total);
  return {
    date: dayData.date,
    hoveredModel: line.model,
    allModels,
    screenX,
    screenY,
  };
});

/** Y 轴 5 个刻度 (0, 0.25, 0.5, 0.75, 1.0 × niceMax) */
const yAxisTicks = computed(() => {
  const max = chartNiceMax.value;
  return [0, 0.25, 0.5, 0.75, 1.0].map((f) => ({
    value: max * f,
    y: CHART_PAD.top + CHART_INNER_H - f * CHART_INNER_H,
    label: formatTokens(max * f),
  }));
});

/** X 轴日期标签 (MM-DD) */
const xAxisLabels = computed(() => {
  if (!statsData.value?.daily?.length) return [];
  const daily = statsData.value.daily;
  const days = daily.length;
  const xStep = days > 1 ? CHART_INNER_W / (days - 1) : 0;
  // 30d 槽位 (~23 viewBox 单位) 比 "MM-DD" 自然宽度 (~25) 还窄,
  // 用 SVG textLength 强制压到槽位宽度. 7d/今日槽位宽, 不启用
  const textLength = xStep > 0 && xStep < 25 ? Math.max(16, xStep - 2) : null;
  return daily.map((d, i) => {
    // 月初 (-01) 或首/末日期 → 标签加粗加深; 月初位置额外画背景带
    const isMonthStart = d.date.endsWith("-01");
    const isAnchor = i === 0 || i === days - 1;
    // 7d/今日全显; 8+ 天每隔一天显示, 强制首/末/月初
    const showLabel = days <= 7 || i % 2 === 0 || isAnchor || isMonthStart;
    return {
      x: days > 1
        ? CHART_PAD.left + i * xStep
        : CHART_PAD.left + CHART_INNER_W / 2,
      label: d.date.substring(5),
      showLabel,
      textLength,
      isHighlighted: isMonthStart || isAnchor,
      isMonthStart,
    };
  });
});

function formatTokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + "M";
  if (n >= 1_000) return (n / 1_000).toFixed(1) + "k";
  return String(n);
}

async function loadAll() {
  // 日期校验在 confirmPicker / applyPresetInPopover 已做, 这里不再重复
  todayData.value = await loadUsageToday();
  statsData.value = await loadUsageStats(startDate.value, endDate.value);

  // 拉后端 limits + 当前 chatModel (主配置)
  const temp = {
    enabled: false,
    chatModelLimits: {} as Record<string, number>,
    chatModel: "",
    visitorEnabled: false,
    visitorDailyLimit: 0,
    visitorHourlyLimit: 0,
    visitorWhitelist: [] as string[],
  };
  await loadUsageLimits(temp);
  limitsForm.enabled = temp.enabled;
  limitsForm.currentChatModel = temp.chatModel;
  limitsForm.visitorEnabled = temp.visitorEnabled;
  limitsForm.visitorDailyLimit = temp.visitorDailyLimit;
  limitsForm.visitorHourlyLimit = temp.visitorHourlyLimit;
  limitsForm.visitorWhitelist = temp.visitorWhitelist;

  // 5 个主模型配置 — 供多折线图用
  chartModelConfig.value = {
    chatModel: temp.chatModel,
    embeddingModel: temp.embeddingModel,
    rerankEnabled: temp.rerankEnabled,
    rerankModel: temp.rerankModel,
    queryRewriteEnabled: temp.queryRewriteEnabled,
    queryRewriteModel: temp.queryRewriteModel,
    writingModel: temp.writingModel,
  };

  // 模型列表 = 来自主配置 + 已配置限流的并集 (去重)
  // 主配置是单一数据源 — drawer 不再加/删模型
  const allModels = new Set<string>();
  if (temp.chatModel) allModels.add(temp.chatModel);
  for (const m of Object.keys(temp.chatModelLimits)) allModels.add(m);
  limitsForm.chatModelLimits = Array.from(allModels).map((model) => ({
    model,
    limit: temp.chatModelLimits[model] ?? 0,
  }));

  saveMsg.value = "";
  saveOk.value = false;
}

/** 抽屉里展示的限流模型行: 模型名 + 上限 + 今日已用 (供进度条用) */
const chatModelsForLimits = computed(() => {
  return limitsForm.chatModelLimits.map((item) => {
    const today = todayData.value.models.find((m) => m.model === item.model);
    const todayTokens = today
      ? today.promptTokens + today.completionTokens + today.embeddingTokens
      : 0;
    return { ...item, todayTokens };
  });
});

function progressClass(item: { limit: number; todayTokens: number }): string {
  if (!item.limit || item.limit <= 0) return "ai-form-progress-none";
  const ratio = item.todayTokens / item.limit;
  if (ratio >= 1) return "ai-form-progress-over";
  if (ratio >= 0.8) return "ai-form-progress-warn";
  return "ai-form-progress-ok";
}

function formatTodayTokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + "M";
  if (n >= 1_000) return (n / 1_000).toFixed(0) + "k";
  return String(n);
}

/** IP 白名单: 数组 ↔ 多行字符串 (textarea) 双向绑定 */
const visitorWhitelistText = computed({
  get: () => limitsForm.visitorWhitelist.join("\n"),
  set: (v) => {
    limitsForm.visitorWhitelist = (v || "")
      .split(/\r?\n/)
      .map((s) => s.trim())
      .filter(Boolean);
  },
});

async function saveLimits() {
  saving.value = true;
  // 必须传 ref 本身 (而非 { value: x } 字面量),否则 saveUsageLimits 内部的
  // saving.value / saveMsg.value = ... 写的是临时对象,不会回写到本页 ref,
  // 导致后面 saveMsg.value 永远空、走 Toast.error("") 分支,误显示"保存失败"
  await saveUsageLimits(limitsForm, saving, saveMsg);
  // 必须在 loadAll() 之前缓存结果 — loadAll() 末尾会清空 saveMsg / saveOk,
  // 不缓存的话后面检查 saveMsg.value==="保存成功" 永远 false → 误报保存失败
  const result = saveMsg.value;
  // saveUsageLimits 直接写 saveMsg.value 进去,但因为 .value 是 ref 引用,值已更新
  // 简化:重新调 loadUsageLimits 拿一遍
  await loadAll();
  saving.value = false;
  if (result === "保存成功") {
    saveOk.value = true;
    Toast.success("限流配置已保存");
  } else {
    saveOk.value = false;
    Toast.error(result || "保存失败");
  }
}

function openDrawer() {
  drawerOpen.value = true;
  // 拉最新 limits，避免抽屉显示陈旧数据（用户可能切过其他页面改了配置）
  loadAll();
}

onMounted(() => {
  loadAll();
  document.addEventListener("click", onDocClick);
});

onUnmounted(() => {
  document.removeEventListener("click", onDocClick);
});
</script>

<style scoped>
.usage-page {
  position: relative;
}

/* ===== 页面主标题 + 全局控件 ===== */
.ai-page-header {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 12px;
  margin: 4px 0 20px;
}
.ai-page-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: #111827;
  position: relative;
  padding-left: 12px;
}
.ai-page-title::before {
  content: "";
  position: absolute;
  left: 0;
  top: 4px;
  bottom: 4px;
  width: 4px;
  border-radius: 2px;
  background: linear-gradient(180deg, #6366f1, #8b5cf6);
}

/* ===== 子标题 (页面内的段落标识, 弱化) ===== */
.ai-subsection {
  margin-top: 28px;
}
.ai-subsection:first-of-type {
  margin-top: 24px;
}
.ai-subsection-heading {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #4b5563;
  letter-spacing: 0.01em;
  position: relative;
  padding-left: 10px;
  margin-bottom: 12px;
}
.ai-subsection-heading::before {
  content: "";
  position: absolute;
  left: 0;
  top: 3px;
  bottom: 3px;
  width: 3px;
  border-radius: 1.5px;
  background: #d1d5db;
}
.ai-subsection-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
}
.ai-subsection-header .ai-subsection-heading {
  margin-bottom: 0;
}
.ai-subsection-meta {
  font-size: 11.5px;
  color: #9ca3af;
}
.usage-range-tabs {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}

/* ===== 日期范围 popover ===== */
.usage-range-picker {
  position: relative;
}
.usage-range-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #fff;
  font-size: 12.5px;
  color: #111827;
  cursor: pointer;
  transition: all 0.15s;
}
.usage-range-trigger:hover {
  border-color: #4f46e5;
  background: #f9fafb;
}
.usage-range-trigger-icon {
  font-size: 13px;
}
.usage-range-trigger-text {
  font-variant-numeric: tabular-nums;
}
.usage-range-trigger-caret {
  color: #9ca3af;
  font-size: 9px;
  margin-left: 2px;
}
.usage-range-popover {
  position: fixed;
  /* top / right 由 inline style 从 trigger 屏幕坐标计算 */
  z-index: 200;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.08), 0 2px 6px rgba(0, 0, 0, 0.04);
  padding: 14px;
  width: 280px;
}
.usage-range-presets {
  display: flex;
  gap: 4px;
  margin-bottom: 10px;
}
.usage-range-presets .ai-btn {
  flex: 1;
  text-align: center;
}
.usage-range-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  margin-bottom: 10px;
}
.usage-range-field {
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 6px 8px;
  cursor: pointer;
  transition: all 0.15s;
}
.usage-range-field:hover {
  border-color: #4f46e5;
}
.usage-range-field.active {
  border-color: #4f46e5;
  background: #eef2ff;
  box-shadow: 0 0 0 1px #4f46e5 inset;
}
.usage-range-field-label {
  font-size: 11px;
  color: #9ca3af;
  margin-bottom: 2px;
}
.usage-range-field-value {
  font-size: 12.5px;
  color: #111827;
  font-variant-numeric: tabular-nums;
}
.usage-range-field.active .usage-range-field-value {
  color: #4f46e5;
  font-weight: 500;
}
.usage-range-error {
  margin: 8px 0 0;
  padding: 6px 8px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 4px;
  font-size: 11.5px;
  color: #b91c1c;
}
.usage-range-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #f3f4f6;
}
.usage-range-actions .ai-btn {
  min-width: 60px;
}
.ai-input-xs {
  font-size: 12px;
  padding: 3px 8px;
  height: auto;
  min-width: 120px;
}
.usage-chart-wrap {
  width: 100%;
  overflow-x: auto;
}
.usage-chart {
  width: 100%;
  max-width: 100%;
  height: auto;
  display: block;
}
.usage-chart-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 16px;
  margin-top: 8px;
  padding: 8px 4px 0;
  font-size: 12px;
  color: #374151;
}
.usage-chart-legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.usage-chart-legend-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
  flex: 0 0 auto;
  border: 1.5px solid transparent;
}
.usage-chart-legend-dot.usage-chart-legend-dashed {
  background: transparent !important;
  border-style: dashed;
  border-width: 2px;
  height: 0;
  width: 14px;
  align-self: center;
}
.usage-chart-legend-dot.usage-chart-legend-disabled {
  opacity: 0.4;
}
.usage-chart-legend-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}
.usage-chart-legend-model {
  font-size: 10.5px;
  color: #6b7280;
  background: #f3f4f6;
  padding: 0 5px;
  border-radius: 3px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.usage-chart-legend-badge {
  font-size: 10px;
  color: #9ca3af;
  background: #f3f4f6;
  padding: 0 5px;
  border-radius: 3px;
  margin-left: 2px;
}
.usage-chart-legend-note {
  flex-basis: 100%;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: #6b7280;
  margin-top: 2px;
}

/* hover tooltip */
.usage-chart-tooltip {
  position: fixed;
  z-index: 100;
  transform: translate(-50%, calc(-100% - 12px));
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.08), 0 2px 4px rgba(0, 0, 0, 0.04);
  padding: 8px 10px;
  min-width: 200px;
  max-width: 280px;
  pointer-events: none;
  font-size: 11px;
  color: #374151;
}
.usage-chart-tooltip::after {
  /* 小三角指向数据点 */
  content: "";
  position: absolute;
  left: 50%;
  bottom: -5px;
  transform: translateX(-50%) rotate(45deg);
  width: 8px;
  height: 8px;
  background: #fff;
  border-right: 1px solid #e5e7eb;
  border-bottom: 1px solid #e5e7eb;
}
.usage-chart-tooltip-date {
  font-weight: 600;
  color: #111827;
  font-size: 12px;
  margin-bottom: 6px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.usage-chart-tooltip-models {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.usage-chart-tooltip-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 6px;
  margin: 0 -6px;
  border-radius: 4px;
  font-size: 11px;
  color: #6b7280;
}
.usage-chart-tooltip-row.is-hovered {
  background: #f3f4f6;
  color: #111827;
}
.usage-chart-tooltip-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: 0 0 auto;
}
.usage-chart-tooltip-model {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 10.5px;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.usage-chart-tooltip-total {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  font-weight: 500;
}
.usage-fail {
  color: #ef4444;
  font-weight: 600;
}
.usage-progress {
  width: 140px;
  height: 8px;
  background: #E5E7EB;
  border-radius: 4px;
  overflow: hidden;
  margin-bottom: 2px;
}
.usage-progress-fill {
  height: 100%;
  background: #4F46E5;
  transition: width 0.2s;
}
.usage-progress-over .usage-progress-fill {
  background: #ef4444;
}
.usage-progress-text {
  font-size: 11px;
  color: #6b7280;
}
.usage-permodel-row {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 6px;
}
.usage-permodel-row .ai-input { flex: 1; }

/* 进度文本: 显示 "已用 / 上限" + 阈值颜色 */
.ai-form-progress {
  font-size: 11px;
  color: #9ca3af;
  font-variant-numeric: tabular-nums;
  min-width: 80px;
  text-align: right;
  flex-shrink: 0;
}
.ai-form-progress-ok   { color: #6b7280; }
.ai-form-progress-warn { color: #f59e0b; font-weight: 600; }
.ai-form-progress-over { color: #ef4444; font-weight: 600; }
.ai-form-progress-none { color: #cbd5e1; }
.ai-field-hint {
  font-size: 12px;
  color: #9ca3af;
  margin: 4px 0 8px;
  line-height: 1.5;
}
.ai-field-warning {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin: 6px 0 0;
  padding: 8px 10px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 6px;
  font-size: 12px;
  color: #92400e;
  line-height: 1.6;
}
.ai-field-warning strong { color: #78350f; }
.ai-field-warning code {
  background: #fef3c7;
  padding: 0 4px;
  border-radius: 3px;
  font-size: 11px;
  color: #78350f;
}
.ai-field-warning-icon {
  flex: 0 0 auto;
  line-height: 1.6;
}
.ai-form-divider {
  height: 1px;
  background: #e5e7eb;
  margin: 18px 0 14px;
}

/* 抽屉 */
.usage-drawer-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  z-index: 1000;
}
.usage-drawer {
  position: fixed;
  top: 0;
  right: 0;
  width: 500px;
  height: 100vh;
  background: var(--ai-bg, #fff);
  border-left: 1px solid #e5e7eb;
  z-index: 1001;
  transform: translateX(100%);
  transition: transform 0.25s;
  overflow-y: auto;
  box-shadow: -4px 0 16px rgba(0, 0, 0, 0.08);
}
.usage-drawer.open { transform: translateX(0); }
.usage-drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #e5e7eb;
}
.usage-drawer-header h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.usage-drawer-close {
  background: none;
  border: 0;
  font-size: 22px;
  color: #6b7280;
  cursor: pointer;
  padding: 0;
  line-height: 1;
}
.usage-drawer-body {
  padding: 16px 20px;
}
.ai-save-msg {
  font-size: 12px;
  margin: 8px 0;
}
.ai-save-ok { color: #10b981; }
.ai-save-fail { color: #ef4444; }
</style>
