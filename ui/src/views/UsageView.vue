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
                    <span class="usage-chart-tooltip-model">{{ displayModelName(m.model) }}</span>
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
                    <code class="usage-chart-legend-model">{{ displayModelName(line.model) }}</code>
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
            <table class="ai-table usage-summary-table">
              <thead>
                <tr>
                  <th>模型</th>
                  <th>{{ rangeLabelText }} Token</th>
                  <th>调用次数</th>
                  <th>失败数</th>
                  <th>失败率</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in rows" :key="row.model">
                  <td data-label="模型" class="usage-summary-model"><code :title="row.model">{{ displayModelName(row.model) }}</code></td>
                  <td data-label="Token">{{ row.tokens.toLocaleString() }}</td>
                  <td data-label="调用次数">{{ row.calls.toLocaleString() }}</td>
                  <td data-label="失败数">{{ row.failures.toLocaleString() }}</td>
                  <td data-label="失败率" :class="{ 'usage-fail': row.failureRate > 5 }">
                    {{ row.failureRate.toFixed(2) }}%
                  </td>
                  <td data-label="操作" class="usage-summary-actions">
                    <VSpace>
                      <VButton size="xs" type="default" @click="openCallDrawer(row.rawModels[0] || row.model)">详情</VButton>
                      <VButton size="xs" type="default" @click="openMergeDialog(row)">合并</VButton>
                      <VButton size="xs" type="default" @click="hideUsageRow(row)">隐藏</VButton>
                      <VButton size="xs" type="danger" @click="openDeleteDialog(row)">删除</VButton>
                    </VSpace>
                  </td>
                </tr>
                <tr v-if="rows.length === 0">
                  <td colspan="6" class="ai-empty">所选范围内暂无数据</td>
                </tr>
              </tbody>
            </table>
            <div v-if="hiddenModels.length > 0" class="usage-hidden-models">
              <span class="usage-hidden-title">已隐藏</span>
              <button
                v-for="model in hiddenModels"
                :key="model"
                type="button"
                class="usage-hidden-chip"
                :title="model"
                @click="restoreHiddenModel(model)"
              >
                {{ displayModelName(model) }} <span>恢复</span>
              </button>
              <VButton size="xs" type="default" @click="clearHiddenModels">全部恢复</VButton>
            </div>
          </div>
        </article>
      </div>

      <!-- 失败诊断 -->
      <div class="ai-subsection usage-diagnostics-section">
        <article class="ai-section-card usage-diagnostics-card">
          <button
            type="button"
            class="usage-diagnostics-toggle"
            @click="failureDiagnosticsExpanded = !failureDiagnosticsExpanded"
          >
            <span class="usage-diagnostics-toggle-main">
              <span class="usage-diagnostics-caret" :class="{ open: failureDiagnosticsExpanded }">▾</span>
              <span>
                <strong>失败诊断</strong>
                <em>{{ failureDiagnosticsSummary }}</em>
              </span>
            </span>
            <span class="usage-diagnostics-toggle-meta">范围: {{ rangeLabelText }}</span>
          </button>

          <div v-if="failureDiagnosticsExpanded" class="ai-card-body">
            <div v-if="failureDiagnosticsLoading" class="ai-empty">正在分析失败原因...</div>
            <div v-else-if="!failureDiagnostics || failureDiagnostics.total === 0" class="usage-diagnostics-ok">
              当前范围内没有失败调用。
            </div>
            <div v-else class="usage-diagnostics">
              <div class="usage-diagnostics-main">
                <div>
                  <div class="usage-diagnostics-label">失败调用</div>
                  <div class="usage-diagnostics-total">{{ failureDiagnostics.total.toLocaleString() }}</div>
                </div>
                <div v-if="primaryDiagnosis" class="usage-diagnostics-primary">
                  <div class="usage-diagnostics-label">主要原因</div>
                  <strong>{{ primaryDiagnosis.label }}</strong>
                  <p>{{ primaryDiagnosis.suggestion }}</p>
                  <code v-if="primaryDiagnosis.example">{{ primaryDiagnosis.example }}</code>
                </div>
              </div>

              <div class="usage-diagnostics-grid">
                <div class="usage-diagnostics-panel">
                  <h3>按接口类型</h3>
                  <div v-for="item in failureDiagnostics.byType" :key="item.key" class="usage-diagnostics-row">
                    <span>{{ item.label }}</span>
                    <strong>{{ item.count.toLocaleString() }}</strong>
                  </div>
                </div>
                <div class="usage-diagnostics-panel">
                  <h3>按调用场景</h3>
                  <div v-for="item in failureDiagnostics.byScenario.slice(0, 6)" :key="item.key" class="usage-diagnostics-row">
                    <span>{{ item.label }}</span>
                    <strong>{{ item.count.toLocaleString() }}</strong>
                  </div>
                </div>
              </div>

              <div class="usage-diagnostics-panel">
                <h3>最近失败</h3>
                <div v-for="item in failureDiagnostics.recent" :key="item.time + item.type + item.error" class="usage-diagnostics-recent">
                  <div class="usage-diagnostics-recent-head">
                    <span>{{ formatCallTime(item.time) }}</span>
                    <strong>{{ item.diagnosisLabel }}</strong>
                    <em>{{ item.typeLabel }} / {{ item.scenarioLabel }}</em>
                  </div>
                  <code :title="item.error">{{ item.error || "无错误摘要" }}</code>
                </div>
              </div>
            </div>
          </div>
        </article>
      </div>
    </div>

    <div class="usage-drawer-mask" v-if="mergeDialogOpen" @click="closeMergeDialog"></div>
    <div v-if="mergeDialogOpen" class="usage-cleanup-modal">
      <div class="usage-cleanup-modal-header">
        <h3>合并模型用量</h3>
        <button class="usage-drawer-close" @click="closeMergeDialog">×</button>
      </div>
      <div class="usage-cleanup-modal-body">
        <div class="ai-form-field">
          <label class="ai-field-label">来源模型</label>
          <input
            class="ai-input"
            :value="cleanupRow ? displayModelName(cleanupRow.model) : ''"
            disabled
            :title="cleanupRow?.rawModels.join('\n')"
          />
          <div class="ai-field-hint">
            会把当前范围内该模型的汇总与调用明细一起改到目标模型，适合清理切换 AI Foundation 后留下的旧模型名。
          </div>
        </div>
        <div class="ai-form-field">
          <label class="ai-field-label">目标模型</label>
          <select class="ai-input" v-model="mergeTargetModel">
            <option value="" disabled>选择目标模型</option>
            <option
              v-for="item in mergeTargetOptions"
              :key="item.model"
              :value="item.model"
            >
              {{ item.label }}
            </option>
          </select>
        </div>
        <div class="usage-cleanup-summary">
          范围: {{ rangeLabelText }}；预计处理来源原始记录:
          {{ cleanupRow?.rawModels.map(displayModelName).join("、") || "-" }}
        </div>
      </div>
      <div class="usage-cleanup-modal-footer">
        <VButton type="default" :disabled="cleanupBusy" @click="closeMergeDialog">取消</VButton>
        <VButton type="primary" :disabled="cleanupBusy || !mergeTargetModel" @click="confirmMergeUsage">
          {{ cleanupBusy ? "处理中..." : "确认合并" }}
        </VButton>
      </div>
    </div>

    <div class="usage-drawer-mask" v-if="deleteDialogOpen" @click="closeDeleteDialog"></div>
    <div v-if="deleteDialogOpen" class="usage-cleanup-modal usage-cleanup-modal-danger">
      <div class="usage-cleanup-modal-header">
        <h3>删除模型用量</h3>
        <button class="usage-drawer-close" @click="closeDeleteDialog">×</button>
      </div>
      <div class="usage-cleanup-modal-body">
        <p class="usage-cleanup-warning">
          将删除「{{ cleanupRow ? displayModelName(cleanupRow.model) : "" }}」在 {{ rangeLabelText }} 内的汇总和调用明细。这个操作不会影响模型配置，但历史统计数据会被移除。
        </p>
      </div>
      <div class="usage-cleanup-modal-footer">
        <VButton type="default" :disabled="cleanupBusy" @click="closeDeleteDialog">取消</VButton>
        <VButton type="danger" :disabled="cleanupBusy" @click="confirmDeleteUsage">
          {{ cleanupBusy ? "处理中..." : "确认删除" }}
        </VButton>
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
            启用模型 Token 限流
          </label>
          <div class="ai-field-hint">超出每日 token 上限后拒绝受限的对话类调用。访客次数限流是独立开关。</div>
        </div>
        <div class="ai-form-field">
          <label class="ai-field-label">对话模型每日 Token 上限</label>
          <div v-if="chatModelsForLimits.length === 0" class="ai-field-hint">
            请先到 <strong>模型配置 → 对话</strong> 页面设置对话模型。
          </div>
          <div v-for="(item, i) in chatModelsForLimits" :key="i" class="usage-permodel-row">
            <div class="usage-limit-field usage-limit-model">
              <span class="usage-limit-label">当前模型</span>
              <input class="ai-input" :value="displayModelName(item.model)" disabled :title="item.model" />
            </div>
            <div class="usage-limit-field usage-limit-number">
              <span class="usage-limit-label">每日上限</span>
              <input
                class="ai-input"
                type="number"
                min="0"
                placeholder="0 = 不限"
                v-model.number="item.limit"
              />
            </div>
            <div class="usage-limit-field usage-limit-progress">
              <span class="usage-limit-label">今日用量</span>
              <span class="ai-form-progress" :class="progressClass(item)">
                {{ formatTodayTokens(item.todayTokens) }} / {{ formatTodayTokens(item.limit || 0) }}
              </span>
            </div>
          </div>
          <div class="ai-field-hint">
            这里只限制当前 AI Foundation 对话模型；历史模型用量请在模型用量汇总里清理（0 = 不限）。
          </div>
          <div v-if="limitsForm.enabled" class="ai-field-warning">
            <span class="ai-field-warning-icon"><RiAlertLine /></span>
            <span>
              <strong>提示：</strong>Token 限流会作用于访客问答以及部分搜索增强、意图识别、测试连接等对话类调用。写作辅助、文章摘要、索引重建等内部任务不走此限流。
            </span>
          </div>
        </div>

        <div class="ai-form-divider" />

        <div class="ai-form-field">
          <label class="ai-field-label">
            <input type="checkbox" v-model="limitsForm.visitorEnabled" />
            启用访客限流（按客户端 IP 限流对话次数）
          </label>
          <div class="ai-field-hint">防单用户刷量，仅在能识别客户端 IP 的访客对话中生效；与模型 Token 限流相互独立。</div>
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

    <div class="usage-drawer-mask" v-if="callDrawerOpen" @click="callDrawerOpen = false"></div>
    <div class="usage-drawer usage-call-drawer" :class="{ open: callDrawerOpen }">
      <div class="usage-drawer-header">
        <div>
          <h3>{{ callDrawerModel ? displayModelName(callDrawerModel) : '模型' }} 调用明细</h3>
          <p>保留最近 {{ callRetentionDays }} 天，从本版本开始记录，当前范围：{{ rangeLabelText }}</p>
        </div>
        <button class="usage-drawer-close" @click="callDrawerOpen = false">×</button>
      </div>
      <div class="usage-drawer-body">
        <div v-if="callLoading" class="ai-empty">加载中...</div>
        <div v-else-if="callItems.length === 0" class="ai-empty">当前范围内暂无调用明细</div>
        <div v-else class="usage-call-table-wrap">
          <table class="ai-table usage-call-table">
            <thead>
              <tr>
                <th>
                  <button class="usage-sort-button" type="button" @click="toggleCallSort">
                    时间
                    <span>{{ callSort === "desc" ? "↓" : "↑" }}</span>
                  </button>
                </th>
                <th>
                  <select
                    v-if="showCallScenarioFilter"
                    class="usage-table-filter"
                    v-model="callScenario"
                    @change="loadCallDetails(1)"
                  >
                    <option value="all">调用场景</option>
                    <option v-for="scenario in callAvailableScenarios" :key="scenario" :value="scenario">
                      {{ scenarioLabel(scenario) }}
                    </option>
                  </select>
                  <span v-else>调用场景</span>
                </th>
                <th>接口类型</th>
                <th class="usage-num">总 Token</th>
                <th class="usage-num">输入</th>
                <th class="usage-num">输出</th>
                <th>
                  <select class="usage-table-filter" v-model="callStatus" @change="loadCallDetails(1)">
                    <option value="all">状态</option>
                    <option value="success">成功</option>
                    <option value="failed">失败</option>
                  </select>
                </th>
              </tr>
            </thead>
            <tbody>
              <template v-for="item in callItems" :key="item.id">
                <tr>
                  <td data-label="时间" class="usage-call-time">{{ formatCallTime(item.time) }}</td>
                  <td data-label="调用场景">{{ scenarioLabel(item.scenario) }}</td>
                  <td data-label="接口类型">{{ typeLabel(item.type) }}</td>
                  <td data-label="总 Token" class="usage-num">{{ item.totalTokens.toLocaleString() }}</td>
                  <td data-label="输入" class="usage-num">{{ item.promptTokens.toLocaleString() }}</td>
                  <td data-label="输出" class="usage-num">{{ item.completionTokens.toLocaleString() }}</td>
                  <td data-label="状态">
                    <span class="usage-call-status" :class="{ failed: item.failure }">
                      {{ item.failure ? '失败' : '成功' }}
                    </span>
                  </td>
                </tr>
                <tr v-if="item.error" class="usage-call-error-row">
                  <td colspan="7">
                    <span>错误信息：</span>{{ item.error }}
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>

        <div class="usage-call-footer">
          <span>共 {{ callTotal }} 条</span>
          <div class="usage-call-pages">
            <select class="usage-page-size" v-model.number="callPageSize" @change="loadCallDetails(1)">
              <option :value="10">10 条/页</option>
              <option :value="25">25 条/页</option>
              <option :value="50">50 条/页</option>
            </select>
            <VButton size="xs" type="default" :disabled="callLoading" @click="loadCallDetails(callPage)">刷新</VButton>
            <VButton size="xs" type="default" :disabled="callPage <= 1 || callLoading" @click="loadCallDetails(callPage - 1)">‹</VButton>
            <span>{{ callPage }} / {{ callTotalPages }}</span>
            <VButton size="xs" type="default" :disabled="callPage >= callTotalPages || callLoading" @click="loadCallDetails(callPage + 1)">›</VButton>
          </div>
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
  loadUsageCalls,
  loadUsageCleanup,
  saveHiddenUsageModels,
  mergeUsageModel,
  deleteUsageModel,
  loadUsageFailureDiagnostics,
  type DailyStatsEntry,
  type UsageCallLog,
  type UsageFailureDiagnostics,
} from "../utils/config";
import MetricCard from "../components/MetricCard.vue";
import UsageCalendar from "../components/UsageCalendar.vue";
import { formatNum, formatPct, computeDelta } from "../utils/format";
import { Toast, VButton, VSpace } from "@halo-dev/components";
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

type AiFoundationModelType = "language" | "embedding" | "rerank";

interface AiFoundationModelOption {
  name: string;
  modelId?: string;
  displayName?: string;
  provider?: {
    displayName?: string;
    providerTypeDisplayName?: string;
  };
}

interface AiFoundationDefaultSlots {
  languageModelName?: string;
  embeddingModelName?: string;
  rerankModelName?: string;
}

const AI_FOUNDATION_API = "/apis/console.api.aifoundation.halo.run/v1alpha1";

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
const callDrawerOpen = ref(false);
const callDrawerModel = ref("");
const callType = ref("all");
const callScenario = ref("all");
const callStatus = ref("all");
const callSort = ref<"asc" | "desc">("desc");
const callLoading = ref(false);
const callItems = ref<UsageCallLog[]>([]);
const callTotal = ref(0);
const callPage = ref(1);
const callPageSize = ref(10);
const callRetentionDays = ref(30);
const callAvailableScenarios = ref<string[]>([]);
const failureDiagnostics = ref<UsageFailureDiagnostics | null>(null);
const failureDiagnosticsLoading = ref(false);
const failureDiagnosticsExpanded = ref(false);
const aiFoundationDefaultSlots = ref<AiFoundationDefaultSlots>({});
const aiFoundationModelLabels = ref<Record<string, string>>({});
const hiddenModels = ref<string[]>([]);
const cleanupRow = ref<Row | null>(null);
const mergeDialogOpen = ref(false);
const deleteDialogOpen = ref(false);
const mergeTargetModel = ref("");
const cleanupBusy = ref(false);

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

function aiFoundationOptionLabel(option: AiFoundationModelOption) {
  const modelName = option.displayName || option.modelId || option.name;
  const provider = option.provider?.displayName || option.provider?.providerTypeDisplayName;
  return provider ? `${modelName} · ${provider}` : modelName;
}

function normalizeUsageModelName(model: string) {
  if (model === "ai-foundation-language") {
    return aiFoundationDefaultSlots.value.languageModelName || model;
  }
  if (model === "ai-foundation-embedding") {
    return aiFoundationDefaultSlots.value.embeddingModelName || model;
  }
  if (model === "ai-foundation-rerank") {
    return aiFoundationDefaultSlots.value.rerankModelName || model;
  }
  return model;
}

function displayModelName(model: string) {
  const normalized = normalizeUsageModelName(model);
  return aiFoundationModelLabels.value[normalized] || normalized || model;
}

const hiddenModelSet = computed(() => {
  return new Set(hiddenModels.value.flatMap((model) => {
    const normalized = normalizeUsageModelName(model);
    return normalized === model ? [model] : [model, normalized];
  }));
});

function isModelHidden(model: string) {
  if (!model) return false;
  return hiddenModelSet.value.has(model) || hiddenModelSet.value.has(normalizeUsageModelName(model));
}

async function loadAiFoundationModelLabels() {
  try {
    const [defaults, language, embedding, rerank] = await Promise.all([
      fetch(`${AI_FOUNDATION_API}/default-model-slots`).then((resp) => resp.ok ? resp.json() : {}),
      fetchAiFoundationModelOptions("language"),
      fetchAiFoundationModelOptions("embedding"),
      fetchAiFoundationModelOptions("rerank"),
    ]);
    aiFoundationDefaultSlots.value = defaults || {};
    const labels: Record<string, string> = {};
    for (const option of [...language, ...embedding, ...rerank]) {
      if (option.name) labels[option.name] = aiFoundationOptionLabel(option);
    }
    aiFoundationModelLabels.value = labels;
  } catch {
    // AI Foundation 只影响展示名，失败时继续显示原始模型名。
  }
}

async function fetchAiFoundationModelOptions(type: AiFoundationModelType) {
  const params = new URLSearchParams({
    modelType: type,
    available: "true",
    enabled: "true",
  });
  const resp = await fetch(`${AI_FOUNDATION_API}/model-options?${params.toString()}`);
  if (!resp.ok) return [];
  return (await resp.json()) as AiFoundationModelOption[];
}

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
// chatModelLimits: [{ model: string, limit: number }] — 仅当前对话模型，数字可编辑
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

const visibleTodayModels = computed(() => {
  return todayData.value.models.filter((model) => !isModelHidden(model.model));
});

const stats = computed(() => {
  const models = visibleTodayModels.value;
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

const modelCount = computed(() => visibleTodayModels.value.length);

const topModelLabel = computed(() => {
  const list = visibleTodayModels.value;
  if (list.length === 0) return "暂无模型";
  // 按 token 用量降序取第 1
  const sorted = [...list].sort(
    (a, b) =>
      b.promptTokens + b.completionTokens + b.embeddingTokens -
      (a.promptTokens + a.completionTokens + a.embeddingTokens)
  );
  const top = sorted[0];
  const tokens = top.promptTokens + top.completionTokens + top.embeddingTokens;
  return `${displayModelName(top.model)} (${formatNum(tokens)})`;
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
  rawModels: string[];
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
  const agg = new Map<string, { p: number; c: number; e: number; calls: number; failures: number; rawModels: Set<string> }>();
  for (const d of statsData.value.daily) {
    for (const [model, bm] of Object.entries(d.byModel || {})) {
      const normalizedModel = normalizeUsageModelName(model);
      if (isModelHidden(model) || isModelHidden(normalizedModel)) continue;
      const cur = agg.get(normalizedModel) || { p: 0, c: 0, e: 0, calls: 0, failures: 0, rawModels: new Set<string>() };
      cur.p += bm.p;
      cur.c += bm.c;
      cur.e += bm.e;
      cur.calls += bm.calls;
      cur.failures += bm.failures ?? 0;
      cur.rawModels.add(model);
      agg.set(normalizedModel, cur);
    }
  }
  return Array.from(agg.entries())
    .map(([model, a]) => ({
      model,
      rawModels: Array.from(a.rawModels),
      tokens: a.p + a.c + a.e,
      calls: a.calls,
      failures: a.failures,
      failureRate: a.calls === 0 ? 0 : (a.failures * 100) / a.calls,
    }))
    .sort((x, y) => y.tokens - x.tokens);
});
const callTotalPages = computed(() => Math.max(1, Math.ceil(callTotal.value / callPageSize.value)));
const showCallScenarioFilter = computed(() => callAvailableScenarios.value.length > 1);
const primaryDiagnosis = computed(() => failureDiagnostics.value?.byDiagnosis?.[0] || null);
const failureDiagnosticsSummary = computed(() => {
  if (failureDiagnosticsLoading.value) return "正在分析失败原因";
  if (!failureDiagnostics.value || failureDiagnostics.value.total === 0) {
    return "当前范围内没有失败调用";
  }
  const primary = primaryDiagnosis.value;
  return primary
    ? `${failureDiagnostics.value.total.toLocaleString()} 次失败，主要原因：${primary.label}`
    : `${failureDiagnostics.value.total.toLocaleString()} 次失败`;
});
const mergeTargetOptions = computed(() => {
  const selected = cleanupRow.value;
  const options = new Map<string, string>();
  for (const row of rows.value) {
    if (selected && row.model === selected.model) continue;
    options.set(row.model, displayModelName(row.model));
  }
  for (const model of statsData.value?.modelsInRange || []) {
    const normalized = normalizeUsageModelName(model);
    if (selected && (normalized === selected.model || selected.rawModels.includes(model))) continue;
    if (isModelHidden(model) || isModelHidden(normalized)) continue;
    options.set(normalized, displayModelName(normalized));
  }
  const cfg = chartModelConfig.value;
  [cfg.chatModel, cfg.embeddingModel, cfg.rerankModel, cfg.queryRewriteModel, cfg.writingModel]
    .filter(Boolean)
    .forEach((model) => {
      if (!selected || model !== selected.model) {
        options.set(model, displayModelName(model));
      }
    });
  return Array.from(options.entries()).map(([model, label]) => ({ model, label }));
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
    Object.entries(d.byModel || {}).forEach(([model, bm]) => {
      if (isModelHidden(model) || isModelHidden(normalizeUsageModelName(model))) return;
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
  const historyExtras = (statsData.value.modelsInRange || []).filter((m) => {
    const normalized = normalizeUsageModelName(m);
    return m && !primarySet.has(m) && !primarySet.has(normalized) && !isModelHidden(m) && !isModelHidden(normalized);
  });

  // 给定 model 名 → 折线 + 面积 d 字符串 (用 Catmull-Rom 平滑曲线)
  const buildLineFor = (model: string): { points: ChartPoint[]; pathD: string; areaD: string } => {
    const points: ChartPoint[] = daily.map((d, i) => {
      const m = d.byModel?.[model] || Object.entries(d.byModel || {}).find(
        ([raw]) => normalizeUsageModelName(raw) === model
      )?.[1];
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
    if (isModelHidden(p.model)) continue;
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
      featureLabel: displayModelName(model),
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
    `写作辅助未配置独立模型，自动复用「${displayModelName(chatModel)}」`,
  sameAsChat: (writingModel: string) =>
    `写作辅助「${displayModelName(writingModel)}」与对话模型相同，未单独画线`,
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
  const dayData = daily[pointIdx];
  if (!dayData) return null;
  // 所有有数据的模型, 按总 Token 降序
  const allModels = Object.entries(dayData.byModel || {})
    .filter(([m]) => !isModelHidden(m) && !isModelHidden(normalizeUsageModelName(m)))
    .map(([m, bm]) => ({
      model: normalizeUsageModelName(m),
      total: bm.p + bm.c + bm.e,
      color: modelColorMap.value.get(normalizeUsageModelName(m)) || modelColorMap.value.get(m) || "#9ca3af",
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
  await loadAiFoundationModelLabels();
  const cleanup = await loadUsageCleanup();
  hiddenModels.value = cleanup.hiddenModels || [];
  todayData.value = await loadUsageToday();
  statsData.value = await loadUsageStats(startDate.value, endDate.value);
  loadFailureDiagnostics();

  // 拉后端 limits + 当前 chatModel (主配置)
  const temp = {
    enabled: false,
    chatModelLimits: {} as Record<string, number>,
    chatModel: "",
    embeddingModel: "",
    rerankEnabled: false,
    rerankModel: "",
    queryRewriteEnabled: false,
    queryRewriteModel: "",
    writingModel: "",
    visitorEnabled: false,
    visitorDailyLimit: 0,
    visitorHourlyLimit: 0,
    visitorWhitelist: [] as string[],
  };
  await loadUsageLimits(temp);
  const defaultChatModel = aiFoundationDefaultSlots.value.languageModelName || "";
  const defaultEmbeddingModel = aiFoundationDefaultSlots.value.embeddingModelName || "";
  const defaultRerankModel = aiFoundationDefaultSlots.value.rerankModelName || "";
  const effectiveChatModel = temp.chatModel || defaultChatModel;
  const effectiveEmbeddingModel = temp.embeddingModel || defaultEmbeddingModel;
  const effectiveRerankModel = temp.rerankModel || defaultRerankModel;
  const effectiveQueryRewriteModel = temp.queryRewriteModel || effectiveChatModel;
  const effectiveWritingModel = temp.writingModel || effectiveChatModel;
  limitsForm.enabled = temp.enabled;
  limitsForm.currentChatModel = effectiveChatModel;
  limitsForm.visitorEnabled = temp.visitorEnabled;
  limitsForm.visitorDailyLimit = temp.visitorDailyLimit;
  limitsForm.visitorHourlyLimit = temp.visitorHourlyLimit;
  limitsForm.visitorWhitelist = temp.visitorWhitelist;

  // 5 个主模型配置 — 供多折线图用
  chartModelConfig.value = {
    chatModel: effectiveChatModel,
    embeddingModel: effectiveEmbeddingModel,
    rerankEnabled: temp.rerankEnabled,
    rerankModel: effectiveRerankModel,
    queryRewriteEnabled: temp.queryRewriteEnabled,
    queryRewriteModel: effectiveQueryRewriteModel,
    writingModel: effectiveWritingModel,
  };

  const normalizedChatModel = normalizeUsageModelName(effectiveChatModel);
  const currentLimit = normalizedChatModel
    ? resolveCurrentChatLimit(temp.chatModelLimits, normalizedChatModel)
    : 0;
  limitsForm.chatModelLimits = normalizedChatModel
    ? [{ model: normalizedChatModel, limit: currentLimit }]
    : [];

  saveMsg.value = "";
  saveOk.value = false;
}

function resolveCurrentChatLimit(limits: Record<string, number>, currentModel: string) {
  const exact = limits[currentModel];
  if (exact !== undefined) return exact;
  const currentLabel = displayModelName(currentModel);
  for (const [model, limit] of Object.entries(limits)) {
    const normalizedModel = normalizeUsageModelName(model);
    if (normalizedModel === currentModel) return limit;
    if (model && currentLabel && (currentLabel === model || currentLabel.startsWith(`${model} ·`))) {
      return limit;
    }
  }
  return 0;
}

async function loadFailureDiagnostics() {
  failureDiagnosticsLoading.value = true;
  try {
    failureDiagnostics.value = await loadUsageFailureDiagnostics({
      start: startDate.value,
      end: endDate.value,
    });
  } catch (e: any) {
    failureDiagnostics.value = null;
    Toast.error(e?.message || "失败诊断加载失败");
  } finally {
    failureDiagnosticsLoading.value = false;
  }
}

async function persistHiddenModels(models: string[]) {
  const unique = Array.from(new Set(models.map((model) => model.trim()).filter(Boolean)));
  await saveHiddenUsageModels(unique);
  hiddenModels.value = unique;
}

async function hideUsageRow(row: Row) {
  try {
    await persistHiddenModels([...hiddenModels.value, row.model, ...row.rawModels]);
    Toast.success("已隐藏该模型用量，可在表格下方恢复");
  } catch (e: any) {
    Toast.error(e?.message || "隐藏失败");
  }
}

async function restoreHiddenModel(model: string) {
  try {
    const normalized = normalizeUsageModelName(model);
    await persistHiddenModels(hiddenModels.value.filter((item) => {
      return item !== model && normalizeUsageModelName(item) !== normalized;
    }));
    Toast.success("已恢复显示");
  } catch (e: any) {
    Toast.error(e?.message || "恢复失败");
  }
}

async function clearHiddenModels() {
  try {
    await persistHiddenModels([]);
    Toast.success("已恢复全部隐藏模型");
  } catch (e: any) {
    Toast.error(e?.message || "恢复失败");
  }
}

function openMergeDialog(row: Row) {
  cleanupRow.value = row;
  mergeTargetModel.value = mergeTargetOptions.value[0]?.model || "";
  mergeDialogOpen.value = true;
}

function closeMergeDialog() {
  if (cleanupBusy.value) return;
  mergeDialogOpen.value = false;
  mergeTargetModel.value = "";
  cleanupRow.value = null;
}

function openDeleteDialog(row: Row) {
  cleanupRow.value = row;
  deleteDialogOpen.value = true;
}

function closeDeleteDialog() {
  if (cleanupBusy.value) return;
  deleteDialogOpen.value = false;
  cleanupRow.value = null;
}

async function confirmMergeUsage() {
  const row = cleanupRow.value;
  const targetModel = mergeTargetModel.value;
  if (!row || !targetModel) return;
  cleanupBusy.value = true;
  try {
    let affectedCalls = 0;
    let affectedLogs = 0;
    const sources = row.rawModels.filter((model) => model !== targetModel);
    for (const sourceModel of sources) {
      const result = await mergeUsageModel({
        sourceModel,
        targetModel,
        start: startDate.value,
        end: endDate.value,
      });
      affectedCalls += result.affectedCalls || 0;
      affectedLogs += result.affectedLogs || 0;
    }
    await persistHiddenModels(hiddenModels.value.filter((model) => {
      return !sources.includes(model) && normalizeUsageModelName(model) !== row.model;
    }));
    mergeDialogOpen.value = false;
    mergeTargetModel.value = "";
    cleanupRow.value = null;
    await loadAll();
    Toast.success(`合并完成，影响 ${affectedCalls} 次调用、${affectedLogs} 条明细`);
  } catch (e: any) {
    Toast.error(e?.message || "合并失败");
  } finally {
    cleanupBusy.value = false;
  }
}

async function confirmDeleteUsage() {
  const row = cleanupRow.value;
  if (!row) return;
  cleanupBusy.value = true;
  try {
    let affectedCalls = 0;
    let affectedLogs = 0;
    for (const model of row.rawModels) {
      const result = await deleteUsageModel({
        model,
        start: startDate.value,
        end: endDate.value,
      });
      affectedCalls += result.affectedCalls || 0;
      affectedLogs += result.affectedLogs || 0;
    }
    await persistHiddenModels(hiddenModels.value.filter((model) => {
      return !row.rawModels.includes(model) && normalizeUsageModelName(model) !== row.model;
    }));
    deleteDialogOpen.value = false;
    cleanupRow.value = null;
    await loadAll();
    Toast.success(`删除完成，移除 ${affectedCalls} 次调用、${affectedLogs} 条明细`);
  } catch (e: any) {
    Toast.error(e?.message || "删除失败");
  } finally {
    cleanupBusy.value = false;
  }
}

async function openCallDrawer(model: string) {
  callDrawerModel.value = model;
  callType.value = "all";
  callScenario.value = "all";
  callStatus.value = "all";
  callSort.value = "desc";
  callDrawerOpen.value = true;
  await loadCallDetails(1);
}

async function loadCallDetails(page = callPage.value) {
  if (!callDrawerModel.value) return;
  callLoading.value = true;
  callPage.value = Math.max(1, page);
  try {
    const data = await loadUsageCalls({
      model: callDrawerModel.value,
      start: startDate.value,
      end: endDate.value,
      type: callType.value,
      scenario: callScenario.value,
      status: callStatus.value,
      sort: callSort.value,
      page: callPage.value,
      size: callPageSize.value,
    });
    callItems.value = data.items || [];
    callTotal.value = data.total || 0;
    callRetentionDays.value = data.retentionDays || 30;
    callAvailableScenarios.value = data.scenarios || [];
    if (!showCallScenarioFilter.value) {
      callScenario.value = "all";
    }
    callPage.value = data.page || callPage.value;
  } catch (e: any) {
    Toast.error(e?.message || "调用明细加载失败");
  } finally {
    callLoading.value = false;
  }
}

function toggleCallSort() {
  callSort.value = callSort.value === "desc" ? "asc" : "desc";
  loadCallDetails(1);
}

function formatCallTime(value: string) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

function typeLabel(type: string) {
  const map: Record<string, string> = {
    chat: "Chat",
    embed: "Embedding",
    rerank: "Rerank",
  };
  return map[type] || type || "未知";
}

function scenarioLabel(scenario: string) {
  const map: Record<string, string> = {
    unknown: "未标记",
    model_test: "模型连通性测试",
    visitor_qa: "访客问答",
    hot_articles: "热门文章推荐",
    search_answer: "搜索综合回答",
    search_query_rewrite: "搜索查询改写",
    search_hyde: "HyDE 检索生成",
    search_cross_language: "跨语言检索翻译",
    search_embedding: "搜索向量化",
    search_rerank: "搜索重排序",
    index_embedding: "文章索引向量化",
    keyword_extract: "生成关键词",
    mindmap_generate: "AI 脑图生成",
    summary_generate: "AI 摘要生成",
    writing_assist: "写作辅助",
    evaluation_answer: "效果评测 · 生成回答",
    evaluation_judge: "效果评测 · AI 评分",
    agent_content_gap: "运营智能体 · 内容缺口分析",
  };
  return map[scenario] || scenario || "未标记";
}

/** 抽屉里展示的限流模型行: 模型名 + 上限 + 今日已用 (供进度条用) */
const chatModelsForLimits = computed(() => {
  return limitsForm.chatModelLimits.map((item) => {
    const normalizedModel = normalizeUsageModelName(item.model);
    const todayTokens = todayData.value.models
      .filter((m) => normalizeUsageModelName(m.model) === normalizedModel)
      .reduce((sum, m) => sum + m.promptTokens + m.completionTokens + m.embeddingTokens, 0);
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
  -webkit-overflow-scrolling: touch;
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
.usage-summary-table {
  min-width: 720px;
}
.usage-summary-model code {
  display: inline-block;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: middle;
  white-space: nowrap;
}
.usage-summary-actions {
  white-space: nowrap;
}
.usage-diagnostics-ok {
  padding: 18px;
  border: 1px solid #bbf7d0;
  border-radius: 8px;
  background: #f0fdf4;
  color: #047857;
  font-size: 13px;
  font-weight: 600;
}
.usage-diagnostics-section {
  margin-top: 34px;
}
.usage-diagnostics-card {
  overflow: hidden;
}
.usage-diagnostics-toggle {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 14px;
  border: 0;
  border-bottom: 1px solid transparent;
  background: #f8fafc;
  color: #334155;
  cursor: pointer;
  text-align: left;
}
.usage-diagnostics-card .ai-card-body {
  border-top: 1px solid #e5e7eb;
}
.usage-diagnostics-toggle:hover {
  background: #f1f5f9;
}
.usage-diagnostics-toggle-main {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.usage-diagnostics-toggle-main strong {
  display: block;
  color: #111827;
  font-size: 13px;
  font-weight: 650;
}
.usage-diagnostics-toggle-main em {
  display: block;
  margin-top: 2px;
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  font-style: normal;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.usage-diagnostics-toggle-meta {
  color: #94a3b8;
  font-size: 12px;
  white-space: nowrap;
}
.usage-diagnostics-caret {
  display: inline-flex;
  width: 22px;
  height: 22px;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  background: #fff;
  color: #64748b;
  font-size: 12px;
  transform: rotate(-90deg);
  transition: transform 0.15s ease;
}
.usage-diagnostics-caret.open {
  transform: rotate(0deg);
}
.usage-diagnostics {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.usage-diagnostics-main {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;
}
.usage-diagnostics-main > div {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  padding: 12px 14px;
}
.usage-diagnostics-label {
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
}
.usage-diagnostics-total {
  margin-top: 6px;
  color: #dc2626;
  font-size: 30px;
  font-weight: 750;
  line-height: 1;
}
.usage-diagnostics-primary strong {
  display: block;
  margin-top: 4px;
  color: #111827;
  font-size: 15px;
}
.usage-diagnostics-primary p {
  margin: 6px 0 0;
  color: #475569;
  font-size: 12px;
  line-height: 1.6;
}
.usage-diagnostics-primary code,
.usage-diagnostics-recent code {
  display: block;
  margin-top: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  border-radius: 6px;
  background: #f8fafc;
  color: #64748b;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  padding: 6px 8px;
}
.usage-diagnostics-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}
.usage-diagnostics-panel {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  padding: 12px 14px;
}
.usage-diagnostics-panel h3 {
  margin: 0 0 10px;
  color: #334155;
  font-size: 13px;
  font-weight: 650;
}
.usage-diagnostics-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
  border-top: 1px solid #f1f5f9;
  color: #475569;
  font-size: 12px;
}
.usage-diagnostics-row:first-of-type {
  border-top: 0;
  padding-top: 0;
}
.usage-diagnostics-row strong {
  color: #111827;
  font-variant-numeric: tabular-nums;
}
.usage-diagnostics-recent {
  padding: 10px 0;
  border-top: 1px solid #f1f5f9;
}
.usage-diagnostics-recent:first-of-type {
  border-top: 0;
  padding-top: 0;
}
.usage-diagnostics-recent-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  color: #64748b;
  font-size: 12px;
}
.usage-diagnostics-recent-head strong {
  color: #b91c1c;
}
.usage-diagnostics-recent-head em {
  color: #94a3b8;
  font-style: normal;
}
.usage-hidden-models {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid #eef2f7;
  background: #f8fafc;
}
.usage-hidden-title {
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
}
.usage-hidden-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 260px;
  height: 26px;
  padding: 0 8px;
  border: 1px solid #dbe3ef;
  border-radius: 6px;
  background: #fff;
  color: #334155;
  font-size: 12px;
  cursor: pointer;
}
.usage-hidden-chip:hover {
  border-color: #4f46e5;
  color: #3730a3;
}
.usage-hidden-chip span {
  color: #94a3b8;
  font-size: 11px;
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
  display: grid;
  grid-template-columns: minmax(0, 1fr) 124px 104px;
  gap: 8px;
  align-items: end;
  margin-bottom: 8px;
}
.usage-limit-field {
  min-width: 0;
}
.usage-limit-label {
  display: block;
  margin-bottom: 4px;
  color: #64748b;
  font-size: 11px;
  font-weight: 600;
}
.usage-limit-model .ai-input {
  overflow: hidden;
  text-overflow: ellipsis;
  color: #475569;
}
.usage-limit-progress {
  min-height: 42px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

/* 进度文本: 显示 "已用 / 上限" + 阈值颜色 */
.ai-form-progress {
  font-size: 11px;
  color: #9ca3af;
  font-variant-numeric: tabular-nums;
  min-width: 0;
  text-align: left;
  flex-shrink: 0;
  white-space: nowrap;
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
.usage-drawer-header p {
  margin: 4px 0 0;
  color: #8a94a6;
  font-size: 12px;
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
.usage-cleanup-modal {
  position: fixed;
  left: 50%;
  top: 50%;
  z-index: 1002;
  width: min(520px, calc(100vw - 32px));
  transform: translate(-50%, -50%);
  overflow: hidden;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.18);
}
.usage-cleanup-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid #e5e7eb;
}
.usage-cleanup-modal-header h3 {
  margin: 0;
  color: #111827;
  font-size: 15px;
  font-weight: 600;
}
.usage-cleanup-modal-body {
  padding: 16px 18px;
}
.usage-cleanup-modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 18px 16px;
  border-top: 1px solid #f1f5f9;
}
.usage-cleanup-summary {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  background: #f8fafc;
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}
.usage-cleanup-warning {
  margin: 0;
  color: #7f1d1d;
  font-size: 13px;
  line-height: 1.7;
}
.usage-cleanup-modal-danger .usage-cleanup-modal-header {
  background: #fef2f2;
}
.usage-call-drawer {
  width: min(860px, 94vw);
}
.usage-call-table-wrap {
  overflow: auto;
  -webkit-overflow-scrolling: touch;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}
.usage-call-table {
  min-width: 760px;
  border: 0;
}
.usage-call-table th,
.usage-call-table td {
  white-space: nowrap;
}
.usage-call-table th {
  background: #f8fafc;
}
.usage-table-filter {
  max-width: 132px;
  border: 0;
  background: transparent;
  color: #374151;
  font-size: 12px;
  font-weight: 600;
  outline: none;
  cursor: pointer;
}
.usage-table-filter:focus {
  color: #2563eb;
}
.usage-sort-button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 0;
  background: transparent;
  color: #374151;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  padding: 0;
}
.usage-sort-button:hover {
  color: #2563eb;
}
.usage-call-table tbody tr:hover td {
  background: #f9fafb;
}
.usage-call-table .usage-call-time {
  color: #111827;
  font-size: 12px;
  font-weight: 500;
}
.usage-num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.usage-call-status {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  background: #ecfdf5;
  color: #047857;
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
  flex-shrink: 0;
}
.usage-call-status.failed {
  background: #fef2f2;
  color: #dc2626;
}
.usage-call-error-row td {
  background: #fef2f2;
  color: #b91c1c;
  font-size: 12px;
  line-height: 1.5;
  white-space: normal;
}
.usage-call-error-row span {
  font-weight: 600;
}
.usage-call-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 14px;
  color: #64748b;
  font-size: 12px;
}
.usage-call-pages {
  display: flex;
  align-items: center;
  gap: 8px;
}
.usage-page-size {
  height: 28px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #fff;
  color: #374151;
  font-size: 12px;
  outline: none;
}
.usage-page-size:focus {
  border-color: #2563eb;
}
.ai-save-msg {
  font-size: 12px;
  margin: 8px 0;
}
.ai-save-ok { color: #10b981; }
.ai-save-fail { color: #ef4444; }

@media (max-width: 720px) {
  .ai-page-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .usage-range-popover {
    width: min(280px, calc(100vw - 24px));
  }

  .usage-chart {
    min-width: 620px;
  }

  .usage-chart-legend {
    gap: 8px;
  }

  .usage-chart-legend-label {
    min-width: 0;
    white-space: normal;
  }

  .usage-chart-legend-model {
    max-width: 180px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .usage-diagnostics-main,
  .usage-diagnostics-grid {
    grid-template-columns: 1fr;
  }

  .usage-diagnostics-toggle {
    align-items: flex-start;
    flex-direction: column;
  }

  .usage-diagnostics-toggle-meta {
    margin-left: 32px;
  }

  .usage-summary-table,
  .usage-call-table {
    display: block;
    min-width: 0;
    width: 100%;
    overflow: visible;
  }

  .usage-summary-table thead,
  .usage-call-table thead {
    display: none;
  }

  .usage-summary-table tbody,
  .usage-summary-table tr,
  .usage-summary-table td,
  .usage-call-table tbody,
  .usage-call-table tr,
  .usage-call-table td {
    display: block;
    width: 100%;
  }

  .usage-summary-table tbody,
  .usage-call-table tbody {
    padding: 10px;
    background: #f8fafc;
  }

  .usage-summary-table tbody tr,
  .usage-call-table tbody tr {
    margin-bottom: 10px;
    border: 1px solid #e5e7eb;
    border-radius: 8px;
    background: #fff;
    overflow: hidden;
  }

  .usage-summary-table tbody tr:hover td,
  .usage-call-table tbody tr:hover td {
    background: #fff;
  }

  .usage-summary-table td,
  .usage-call-table td {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 14px;
    min-height: 38px;
    padding: 10px 12px;
    border-bottom: 1px solid #f1f5f9;
    text-align: right !important;
    white-space: normal;
  }

  .usage-summary-table td::before,
  .usage-call-table td::before {
    content: attr(data-label);
    flex: 0 0 78px;
    color: #64748b;
    font-size: 12px;
    font-weight: 650;
    text-align: left;
  }

  .usage-summary-table td:last-child,
  .usage-call-table td:last-child {
    border-bottom: 0;
  }

  .usage-summary-model {
    display: block !important;
    padding: 13px 12px !important;
    text-align: left !important;
  }

  .usage-summary-model::before {
    display: none;
  }

  .usage-summary-model code {
    max-width: 100%;
    white-space: normal;
    word-break: break-word;
  }

  .usage-summary-actions {
    align-items: flex-start !important;
  }

  .usage-call-table-wrap {
    overflow: visible;
    border: 0;
    border-radius: 0;
    background: transparent;
  }

  .usage-call-error-row {
    margin-top: -10px;
  }

  .usage-call-error-row td {
    display: block !important;
    text-align: left !important;
  }

  .usage-call-error-row td::before {
    display: none;
  }

  .usage-call-footer {
    align-items: stretch;
    flex-direction: column;
  }

  .usage-call-pages {
    justify-content: flex-end;
    flex-wrap: wrap;
  }

  .usage-drawer,
  .usage-call-drawer {
    width: min(100vw, 520px);
  }

  .usage-drawer-body {
    padding: 14px 12px;
  }

  .usage-permodel-row {
    grid-template-columns: 1fr;
  }

  .ai-form-progress {
    min-width: 0;
    text-align: left;
  }

  .usage-cleanup-modal {
    width: calc(100vw - 20px);
  }

  .usage-cleanup-modal-footer {
    flex-wrap: wrap;
  }
}
</style>
