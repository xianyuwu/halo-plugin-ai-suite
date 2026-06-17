<template>
  <div class="evaluation-page">
    <div class="ai-content">
      <!-- 顶部指标卡 -->
      <div class="ai-metric-grid">
        <MetricCard
          color="blue"
          icon="sparkles"
          label="综合得分"
          :value="currentSummary?.avgScore ?? 0"
          :value-fmt="scoreFmt"
          :delta-text="currentSummary ? `${currentSummary.totalCases} 个用例 · ${currentDurationText}` : '尚未运行'"
        />
        <MetricCard
          color="green"
          icon="database"
          label="检索命中率"
          :value="currentSummary?.retrievalHitRate ?? 0"
          :value-fmt="pctFmt"
          :delta-text="currentSummary ? '期望来源出现在召回结果中' : '快速检查检索是否正常'"
        />
        <MetricCard
          color="purple"
          icon="circle"
          label="忠实度"
          :value="currentSummary?.faithfulness ?? 0"
          :value-fmt="scoreFmt"
          :delta-text="currentSummary ? '回答是否被上下文支撑' : 'Faithfulness'"
        />
        <MetricCard
          :color="(currentSummary?.hallucinationHighRiskRate ?? 0) > 10 ? 'red' : 'green'"
          icon="alert"
          label="高幻觉风险"
          :value="currentSummary?.hallucinationHighRiskRate ?? 0"
          :value-fmt="pctFmt"
          :delta-text="currentSummary ? `${currentSummary.failedCases} 个运行失败` : '目标 ≤ 5%'"
        />
      </div>

      <!-- Tab 导航 -->
      <div class="eval-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          :class="['ai-tab-btn', { active: activeTab === tab.key }]"
          @click="switchTab(tab.key)"
        >{{ tab.label }}</button>
      </div>

      <!-- ===== Tab: 运行评测 ===== -->
      <div v-if="activeTab === 'run'" class="eval-workbench">
        <SectionCard
          title=""
          :icon-component="RiFlaskLine"
          headerTitle="评测运行工作台"
          headerDesc="选择评测集、控制运行范围，并在同一屏查看进度与问题分组"
        >
          <div class="ai-card-body">
            <div v-if="errorMessage" class="eval-error-panel">{{ errorMessage }}</div>
            <div v-if="datasetMessage" class="eval-dataset-msg">{{ datasetMessage }}</div>

            <div class="eval-run-grid">
              <div class="eval-run-panel">
                <div class="eval-panel-title">运行配置</div>
                <label class="eval-field">
                  <span>评测集</span>
                  <select v-model="selectedDatasetId" class="eval-select">
                    <option value="">请选择评测集</option>
                    <option v-for="ds in datasets" :key="ds.id" :value="ds.id">
                      {{ ds.name || ds.id }}
                    </option>
                  </select>
                </label>
                <div v-if="selectedDataset" class="eval-dataset-summary">
                  <strong>{{ selectedDataset.name }}</strong>
                  <p>{{ selectedDataset.description || "暂无描述" }}</p>
                  <div class="eval-chip-row">
                    <span class="eval-chip">{{ selectedDataset.cases.length }} 条用例</span>
                    <span class="eval-chip">{{ selectedDataset.defaultDataset ? "内置模板" : "自定义" }}</span>
                    <span class="eval-chip">更新 {{ formatUpdatedAt(selectedDataset.updatedAt) }}</span>
                  </div>
                </div>
                <label class="eval-field">
                  <span>运行范围</span>
                  <select v-model.number="runCaseLimit" class="eval-select">
                    <option :value="0">全部有效用例</option>
                    <option :value="5">前 5 条快速检查</option>
                    <option :value="10">前 10 条</option>
                    <option :value="20">前 20 条</option>
                  </select>
                </label>
                <div class="eval-run-actions">
                  <VButton size="sm" type="primary" :loading="running" :disabled="runCases.length === 0" @click="runSelectedDataset">
                    {{ running ? "评测中..." : "运行评测" }}
                  </VButton>
                  <VButton size="sm" type="default" :disabled="!selectedDataset" @click="editSelectedDataset">编辑评测集</VButton>
                </div>
                <div v-if="runProgress" class="eval-progress-panel eval-run-progress">
                  <div class="eval-progress-head">
                    <strong>正在评测</strong>
                    <span>{{ progressPct }}%</span>
                  </div>
                  <div class="eval-progress-bar">
                    <div class="eval-progress-fill" :style="{ width: progressPct + '%' }"></div>
                  </div>
                  <span class="eval-progress-text">
                    {{ runProgress.completed }} / {{ runProgress.total }} 用例完成
                    <template v-if="runProgress.current"> · {{ truncateStr(runProgress.current, 36) }}</template>
                  </span>
                </div>
              </div>

              <div class="eval-run-panel">
                <div class="eval-panel-title">当前报告</div>
                <div v-if="report" class="eval-report-summary">
                  <div>
                    <span>综合得分</span>
                    <strong :class="scoreClass(report.summary.avgScore)">{{ report.summary.avgScore.toFixed(2) }}</strong>
                  </div>
                  <div>
                    <span>检索命中</span>
                    <strong>{{ report.summary.retrievalHitRate.toFixed(0) }}%</strong>
                  </div>
                  <div>
                    <span>高风险</span>
                    <strong :class="report.summary.hallucinationHighRiskRate > 10 ? 'eval-risk-high' : 'eval-risk-low'">
                      {{ report.summary.hallucinationHighRiskRate.toFixed(0) }}%
                    </strong>
                  </div>
                </div>
                <div v-if="report" class="eval-issue-groups">
                  <div v-for="group in issueGroups" :key="group.key" class="eval-issue-group">
                    <div class="eval-issue-head">
                      <strong>{{ group.title }}</strong>
                      <span>{{ group.items.length }}</span>
                    </div>
                    <article v-for="item in group.items.slice(0, 3)" :key="`${group.key}-${item.id}`" class="eval-issue-card">
                      <strong>{{ item.question }}</strong>
                      <p>{{ item.judge.reason || item.error || item.judge.error || "暂无原因说明" }}</p>
                      <div class="eval-result-meta">
                        {{ item.id }} · 得分 {{ item.judge.totalScore.toFixed(2) }} · 检索{{ item.retrieval.hit ? "命中" : "未命中" }}
                      </div>
                    </article>
                    <div v-if="group.items.length === 0" class="eval-issue-empty">暂无</div>
                  </div>
                </div>
                <div v-else class="ai-empty-panel">
                  <div>
                    <div class="ai-empty-title">还没有当前报告</div>
                    <div class="ai-empty-desc">运行一次评测后，这里会展示得分、风险分组和最近问题。</div>
                  </div>
                </div>
              </div>
            </div>

            <div class="eval-recent-block">
              <div class="eval-panel-title">最近实验</div>
              <table class="eval-recent-table">
                <thead>
                  <tr>
                    <th style="text-align: left;">运行名称</th>
                    <th>综合分</th>
                    <th>检索命中</th>
                    <th>时间</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in runRecords.slice(0, 5)" :key="item.runId">
                    <td style="text-align: left;">{{ item.name || item.runId }}</td>
                    <td><span :class="scoreClass(item.summary?.avgScore ?? 0)">{{ (item.summary?.avgScore ?? 0).toFixed(2) }}</span></td>
                    <td>{{ (item.summary?.retrievalHitRate ?? 0).toFixed(0) }}%</td>
                    <td>{{ formatTime(item.startedAt) }}</td>
                    <td><VButton size="xs" type="default" @click="selectRun(item.runId)">查看</VButton></td>
                  </tr>
                  <tr v-if="runRecords.length === 0">
                    <td colspan="5" class="eval-empty-cell">暂无实验记录</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </SectionCard>
      </div>

      <!-- ===== Tab: 评测集 ===== -->
      <div v-if="activeTab === 'datasets'">
        <!-- 评测集列表 -->
        <SectionCard
          v-if="!editingDataset"
          title=""
          :icon-component="RiFlaskLine"
          headerTitle="评测集管理"
          headerDesc="管理评测用例集合，每个评测集包含一组问答用例"
        >
          <div class="ai-card-body">
            <div class="eval-toolbar">
              <VButton size="sm" type="primary" @click="newDatasetDraft">+ 新建评测集</VButton>
              <VButton size="sm" type="default" @click="loadTemplate">恢复内置模板</VButton>
            </div>
            <div v-if="errorMessage" class="eval-error-panel">{{ errorMessage }}</div>
            <div v-if="datasetMessage" class="eval-dataset-msg">{{ datasetMessage }}</div>
            <table class="eval-dataset-table">
              <thead>
                <tr>
                  <th style="text-align: left;">名称</th>
                  <th style="width: 80px;">用例数</th>
                  <th style="width: 80px;">类型</th>
                  <th style="width: 140px;">最后更新</th>
                  <th style="width: 140px;">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="ds in datasets" :key="ds.id" class="eval-dataset-row" @click="editDataset(ds)">
                  <td style="text-align: left; font-weight: 600;">
                    <span class="eval-dataset-name-link">{{ ds.name || ds.id }}</span>
                  </td>
                  <td class="text-center">{{ ds.cases.length }}</td>
                  <td class="text-center">
                    <span v-if="ds.defaultDataset" class="eval-badge-default">默认</span>
                    <span v-else class="eval-badge-custom">自定义</span>
                  </td>
                  <td class="text-center" style="font-size: 12px; color: #6b7280;">{{ formatUpdatedAt(ds.updatedAt) }}</td>
                  <td @click.stop>
                    <VSpace :spacing="4">
                      <VButton size="xs" type="default" @click="copyDataset(ds)">复制</VButton>
                      <VButton
                        size="xs"
                        type="danger"
                        :disabled="ds.defaultDataset"
                        @click="askDeleteDataset(ds)"
                      >删除</VButton>
                    </VSpace>
                  </td>
                </tr>
                <tr v-if="datasets.length === 0">
                  <td colspan="5" class="eval-empty-cell">暂无评测集，点击「新建评测集」或「恢复内置模板」开始</td>
                </tr>
              </tbody>
            </table>
          </div>
        </SectionCard>

        <!-- 评测集编辑 -->
        <SectionCard
          v-else
          :title="`编辑: ${editingDataset.name}`"
          :icon-component="RiFlaskLine"
          :headerTitle="editingDataset.name"
          headerDesc="编辑评测集名称和用例"
        >
          <div class="ai-card-body">
            <div class="eval-edit-header">
              <VButton size="xs" type="default" @click="backToList">&#8592; 返回列表</VButton>
              <div class="eval-edit-name-row">
                <label class="eval-name-label">名称</label>
                <input v-model="editingDataset.name" class="eval-name-input" placeholder="评测集名称" />
              </div>
              <div class="eval-edit-actions">
                <VButton size="sm" type="default" :loading="savingDataset" @click="saveDataset">保存</VButton>
                <VButton size="sm" type="primary" :loading="running" :disabled="validCases.length === 0" @click="runFromEdit">
                  {{ running ? (runProgress ? `${runProgress.completed}/${runProgress.total} 评测中...` : "提交中...") : "运行评测" }}
                </VButton>
              </div>
            </div>

            <div v-if="errorMessage" class="eval-error-panel">{{ errorMessage }}</div>
            <div v-if="datasetMessage" class="eval-dataset-msg">{{ datasetMessage }}</div>
            <div v-if="runProgress" class="eval-progress-panel">
              <div class="eval-progress-bar">
                <div class="eval-progress-fill" :style="{ width: progressPct + '%' }"></div>
              </div>
              <span class="eval-progress-text">
                {{ runProgress.completed }} / {{ runProgress.total }} 用例完成
                <template v-if="runProgress.current"> · {{ truncateStr(runProgress.current, 30) }}</template>
              </span>
            </div>

            <div class="eval-case-toolbar">
              <span class="eval-case-count">{{ editingCases.length }} 条用例 · {{ validCases.length }} 条有效</span>
              <div v-if="editingCases.length > 0" class="eval-pagination-controls">
                <select v-model.number="pageSize" class="eval-page-size" @change="changePageSize">
                  <option :value="10">10 条/页</option>
                  <option :value="20">20 条/页</option>
                  <option :value="30">30 条/页</option>
                </select>
                <button type="button" class="eval-page-btn" :disabled="currentPage <= 1" @click="currentPage--">&#8249;</button>
                <span class="eval-page-info">{{ currentPage }} / {{ totalPages }}</span>
                <button type="button" class="eval-page-btn" :disabled="currentPage >= totalPages" @click="currentPage++">&#8250;</button>
              </div>
              <VButton size="xs" type="default" @click="addCase">新增用例</VButton>
            </div>

            <table class="eval-case-table">
              <thead>
                <tr>
                  <th style="width: 40px;">#</th>
                  <th style="text-align: left;">问题</th>
                  <th style="width: 120px;">标签</th>
                  <th style="width: 60px;">来源</th>
                  <th style="width: 120px;">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(item, index) in pagedCases" :key="item.localId">
                  <td class="text-center" style="color: #9ca3af;">{{ pageStart + index + 1 }}</td>
                  <td style="text-align: left;" class="eval-case-td-question">
                    <span :class="{ 'eval-case-empty': !item.question.trim() }">
                      {{ item.question.trim() || "未填写问题" }}
                    </span>
                  </td>
                  <td>
                    <div class="eval-case-tags" v-if="splitList(item.tagsText).length">
                      <span v-for="tag in splitList(item.tagsText).slice(0, 3)" :key="tag" class="eval-tag">{{ tag }}</span>
                    </div>
                    <span v-else style="color: #9ca3af;">—</span>
                  </td>
                  <td class="text-center">{{ splitList(item.expectedSourcesText).length }}</td>
                  <td>
                    <VSpace :spacing="4">
                      <VButton size="xs" type="default" @click="openCaseEditor(pageStart + index)">编辑</VButton>
                      <VButton size="xs" type="danger" @click="removeCase(pageStart + index)">删除</VButton>
                    </VSpace>
                  </td>
                </tr>
                <tr v-if="editingCases.length === 0">
                  <td colspan="5" class="eval-empty-cell">还没有用例，点击「新增用例」开始</td>
                </tr>
              </tbody>
            </table>
          </div>
        </SectionCard>
      </div>

      <!-- 用例编辑弹窗 -->
      <div class="eval-modal-mask" v-if="editingCaseIndex >= 0" @click="closeCaseEditor"></div>
      <div class="eval-modal" v-if="editingCaseIndex >= 0" @click.self="closeCaseEditor">
        <div class="eval-modal-dialog">
          <div class="eval-modal-header">
            <h3>编辑用例 {{ editingCaseIndex + 1 }}</h3>
            <button class="eval-modal-close" @click="closeCaseEditor">&times;</button>
          </div>
          <div class="eval-modal-body" v-if="editingCaseDraft">
            <label>
              <span class="eval-modal-label">问题</span>
              <textarea v-model="editingCaseDraft.question" rows="3" placeholder="输入用户会问的问题"></textarea>
            </label>
            <label>
              <span class="eval-modal-label">参考答案</span>
              <textarea v-model="editingCaseDraft.referenceAnswer" rows="4" placeholder="写出理想回答应覆盖的要点"></textarea>
            </label>
            <label>
              <span class="eval-modal-label">期望来源</span>
              <input v-model="editingCaseDraft.expectedSourcesText" placeholder="文章标题或 postId，多个用英文逗号分隔" />
            </label>
            <label>
              <span class="eval-modal-label">标签</span>
              <input v-model="editingCaseDraft.tagsText" placeholder="快速评测, RAG, 无答案" />
            </label>
          </div>
          <div class="eval-modal-footer">
            <VButton size="sm" type="default" @click="closeCaseEditor">取消</VButton>
            <VButton size="sm" type="primary" @click="saveCaseEdit">保存</VButton>
          </div>
        </div>
      </div>

      <!-- ===== Tab: 实验记录 ===== -->
      <div v-if="activeTab === 'experiments'">
        <SectionCard
          title=""
          :icon-component="RiBarChartLine"
          headerTitle="实验历史"
          headerDesc="每次评测运行的完整报告，支持查看详情和跨次对比"
        >
          <div class="ai-card-body">
            <div class="eval-toolbar">
              <span class="eval-case-count">共 {{ runRecords.length }} 次实验</span>
              <div class="eval-toolbar-right">
                <VButton
                  size="xs"
                  type="default"
                  :disabled="selectedRuns.size < 2"
                  @click="openCompare"
                >对比 ({{ selectedRuns.size }})</VButton>
                <VButton size="xs" type="default" :loading="loadingRuns" @click="loadRuns">刷新</VButton>
              </div>
            </div>

            <table class="eval-experiment-table">
              <thead>
                <tr>
                  <th style="width: 36px;" class="text-center">
                    <input type="checkbox" :checked="allRunsSelected" @change="toggleSelectAllRuns" />
                  </th>
                  <th style="text-align: left;">运行名称</th>
                  <th style="text-align: left;">评测集</th>
                  <th style="width: 70px;">综合分</th>
                  <th style="width: 70px;">检索命中</th>
                  <th style="width: 70px;">忠实度</th>
                  <th style="width: 70px;">幻觉风险</th>
                  <th style="width: 50px;">用例</th>
                  <th style="width: 60px;">耗时</th>
                  <th style="width: 130px;">时间</th>
                  <th style="width: 100px;">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="item in runRecords"
                  :key="item.runId"
                  :class="{ 'eval-row-active': report?.runId === item.runId }"
                >
                  <td class="text-center" @click.stop>
                    <input type="checkbox" :checked="selectedRuns.has(item.runId)" @change="toggleRunSelect(item.runId)" />
                  </td>
                  <td style="text-align: left; font-weight: 600; cursor: pointer;" @click="selectRun(item.runId)">
                    {{ item.name || item.runId }}
                  </td>
                  <td style="text-align: left; font-size: 12px; color: #6b7280;">{{ item.datasetId || "—" }}</td>
                  <td class="text-center">
                    <span :class="scoreClass(item.summary?.avgScore)">{{ (item.summary?.avgScore ?? 0).toFixed(2) }}</span>
                  </td>
                  <td class="text-center">{{ (item.summary?.retrievalHitRate ?? 0).toFixed(0) }}%</td>
                  <td class="text-center">{{ (item.summary?.faithfulness ?? 0).toFixed(2) }}</td>
                  <td class="text-center">
                    <span :class="(item.summary?.hallucinationHighRiskRate ?? 0) > 10 ? 'eval-risk-high' : 'eval-risk-low'">
                      {{ (item.summary?.hallucinationHighRiskRate ?? 0).toFixed(0) }}%
                    </span>
                  </td>
                  <td class="text-center">{{ item.summary?.totalCases ?? 0 }}</td>
                  <td class="text-center" style="font-size: 12px;">{{ formatDuration(item.durationMs) }}</td>
                  <td class="text-center" style="font-size: 12px; color: #6b7280;">{{ formatTime(item.startedAt) }}</td>
                  <td>
                    <VSpace :spacing="4">
                      <VButton size="xs" type="default" @click="selectRun(item.runId)">查看</VButton>
                      <VButton size="xs" type="danger" @click="askDeleteRun(item)">删除</VButton>
                    </VSpace>
                  </td>
                </tr>
                <tr v-if="runRecords.length === 0">
                  <td colspan="11" class="eval-empty-cell">暂无实验记录，运行一次评测后会自动保存到这里</td>
                </tr>
              </tbody>
            </table>
          </div>
        </SectionCard>

        <!-- 实验详情（展开在下方） -->
        <SectionCard
          v-if="report && !showCompare"
          title=""
          :icon-component="RiBarChartLine"
          headerTitle="实验详情"
          :headerDesc="`${report.name} · ${report.startedAt}`"
        >
          <div class="ai-card-body">
            <div class="eval-score-grid">
              <div class="eval-score-item">
                <span>相关性</span><strong>{{ report.summary.relevance.toFixed(2) }}</strong>
              </div>
              <div class="eval-score-item">
                <span>正确性</span><strong>{{ report.summary.correctness.toFixed(2) }}</strong>
              </div>
              <div class="eval-score-item">
                <span>完整性</span><strong>{{ report.summary.completeness.toFixed(2) }}</strong>
              </div>
              <div class="eval-score-item">
                <span>引用准确性</span><strong>{{ report.summary.citationAccuracy.toFixed(2) }}</strong>
              </div>
            </div>
            <div class="eval-result-list">
              <article
                v-for="result in report.results"
                :key="result.id"
                class="eval-result"
                :class="{ weak: result.judge.totalScore < 3.5 || result.judge.hallucinationRisk === 'high' }"
              >
                <div class="eval-result-head">
                  <div>
                    <strong>{{ result.question }}</strong>
                    <div class="eval-result-meta">
                      {{ result.id }} · {{ result.latencyMs }}ms · 检索{{ result.retrieval.hit ? "命中" : "未命中" }}
                    </div>
                  </div>
                  <div class="eval-result-score">
                    {{ result.judge.totalScore.toFixed(2) }}
                    <span>/ 5</span>
                  </div>
                </div>
                <div class="eval-badges">
                  <span>相关 {{ result.judge.relevance }}</span>
                  <span>正确 {{ result.judge.correctness }}</span>
                  <span>忠实 {{ result.judge.faithfulness }}</span>
                  <span>完整 {{ result.judge.completeness }}</span>
                  <span :class="{ danger: result.judge.hallucinationRisk === 'high' }">
                    幻觉风险 {{ riskLabel(result.judge.hallucinationRisk) }}
                  </span>
                </div>
                <p v-if="result.judge.reason" class="eval-reason">{{ result.judge.reason }}</p>
                <p v-if="result.error || result.judge.error" class="eval-error">
                  {{ result.error || result.judge.error }}
                </p>
                <details>
                  <summary>查看回答与检索结果</summary>
                  <h4>AI 回答</h4>
                  <pre>{{ result.answer || "无回答" }}</pre>
                  <h4>参考答案</h4>
                  <pre>{{ result.referenceAnswer || "未填写" }}</pre>
                  <h4>Top 来源</h4>
                  <ul>
                    <li v-for="doc in result.retrievedDocuments" :key="`${doc.postId}-${doc.chunkIndex}`">
                      <strong>{{ doc.title || doc.postId }}</strong>
                      <span> chunk {{ doc.chunkIndex }} · score {{ Number(doc.score).toFixed(3) }}</span>
                      <p>{{ doc.content }}</p>
                    </li>
                  </ul>
                </details>
              </article>
            </div>
          </div>
        </SectionCard>

        <!-- 对比视图 -->
        <SectionCard
          v-if="showCompare"
          title=""
          :icon-component="RiBarChartLine"
          headerTitle="实验对比"
          :headerDesc="`${compareReportsLoaded.length} 次实验并排对比`"
        >
          <div class="ai-card-body">
            <div class="eval-compare-header">
              <VButton size="xs" type="default" @click="showCompare = false">关闭对比</VButton>
            </div>
            <div class="eval-compare-summary">
              <div v-for="r in compareReportsLoaded" :key="r.runId" class="eval-compare-col">
                <strong>{{ r.name || r.runId }}</strong>
                <div class="eval-compare-metrics">
                  <div><span>综合</span><strong :class="scoreClass(r.summary.avgScore)">{{ r.summary.avgScore.toFixed(2) }}</strong></div>
                  <div><span>相关性</span>{{ r.summary.relevance.toFixed(2) }}</div>
                  <div><span>正确性</span>{{ r.summary.correctness.toFixed(2) }}</div>
                  <div><span>忠实度</span>{{ r.summary.faithfulness.toFixed(2) }}</div>
                  <div><span>完整性</span>{{ r.summary.completeness.toFixed(2) }}</div>
                  <div><span>引用</span>{{ r.summary.citationAccuracy.toFixed(2) }}</div>
                  <div><span>检索命中</span>{{ r.summary.retrievalHitRate.toFixed(0) }}%</div>
                  <div><span>幻觉风险</span>{{ r.summary.hallucinationHighRiskRate.toFixed(0) }}%</div>
                </div>
              </div>
            </div>
            <h4 style="margin: 16px 0 8px; font-size: 13px; color: #6b7280;">逐条对比</h4>
            <table class="eval-compare-table">
              <thead>
                <tr>
                  <th style="text-align: left;">用例</th>
                    <th v-for="r in compareReportsLoaded" :key="r.runId">{{ r.name || r.runId }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(caseId, ci) in compareCaseIds" :key="caseId">
                  <td style="text-align: left; font-size: 12px;">{{ caseId }}</td>
                  <td v-for="r in compareReportsLoaded" :key="r.runId" class="text-center">
                    <template v-if="getCompareResult(r, caseId)">
                      <span :class="scoreClass(getCompareResult(r, caseId)!.judge.totalScore)">
                        {{ getCompareResult(r, caseId)!.judge.totalScore.toFixed(2) }}
                      </span>
                    </template>
                    <span v-else style="color: #9ca3af;">—</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </SectionCard>
      </div>
    </div>

    <!-- 删除评测集确认弹窗 -->
    <VDialog
      v-model:visible="showDeleteDatasetDialog"
      type="warning"
      title="删除评测集"
      :description="`确定要删除评测集「${deletingDatasetName}」吗？删除后不可恢复。`"
      confirm-type="danger"
      confirm-text="删除"
      cancel-text="取消"
      :on-confirm="confirmDeleteDataset"
    />

    <!-- 删除实验记录确认弹窗 -->
    <VDialog
      v-model:visible="showDeleteRunDialog"
      type="warning"
      title="删除实验记录"
      :description="`确定要删除实验记录「${deletingRunName}」吗？删除后不可恢复。`"
      confirm-type="danger"
      confirm-text="删除"
      cancel-text="取消"
      :on-confirm="confirmDeleteRun"
    />
  </div>
</template>

<script setup lang="ts">
import { VButton, VDialog, VSpace } from "@halo-dev/components";
import { computed, onUnmounted, ref } from "vue";
import RiBarChartLine from "~icons/ri/bar-chart-line";
import RiFlaskLine from "~icons/ri/flask-line";
import MetricCard from "../components/MetricCard.vue";
import SectionCard from "../components/SectionCard.vue";

const API = "/apis/console.api.ai-suite.halo.run/v1alpha1/evaluations";

const tabs = [
  { key: "run", label: "运行评测" },
  { key: "datasets", label: "评测集管理" },
  { key: "experiments", label: "实验记录" },
] as const;

type TabKey = (typeof tabs)[number]["key"];

type EditableCase = {
  localId: string;
  question: string;
  referenceAnswer: string;
  expectedSourcesText: string;
  tagsText: string;
};

type DatasetDto = {
  id: string;
  name: string;
  description: string;
  defaultDataset: boolean;
  updatedAt: string;
  cases: Array<{
    id: string;
    question: string;
    referenceAnswer: string;
    expectedSources: string[];
    tags: string[];
  }>;
};

type EvalReport = {
  runId: string;
  name: string;
  startedAt: string;
  durationMs: number;
  summary: {
    totalCases: number;
    avgScore: number;
    relevance: number;
    correctness: number;
    faithfulness: number;
    completeness: number;
    citationAccuracy: number;
    retrievalHitRate: number;
    hallucinationHighRiskRate: number;
    failedCases: number;
  };
  results: Array<{
    id: string;
    question: string;
    referenceAnswer: string;
    expectedSources: string[];
    tags: string[];
    answer: string;
    retrievedDocuments: Array<Record<string, any>>;
    retrieval: { hit: boolean; firstHitRank: number; precisionAt5: number; matchedSources: string[]; topSources: string[] };
    judge: { relevance: number; correctness: number; faithfulness: number; completeness: number; citationAccuracy: number; hallucinationRisk: string; reason: string; totalScore: number; error?: string };
    latencyMs: number;
    error?: string;
  }>;
};

type RunRecord = {
  runId: string;
  datasetId: string;
  name: string;
  startedAt: string;
  durationMs: number;
  summary: EvalReport["summary"];
};

// ===== State =====
const activeTab = ref<TabKey>("run");
const running = ref(false);
/** 评测轮询取消标志 — 组件卸载时置 true, pollRunStatus 每次循环检查, 避免卸载后继续 fetch + 写已销毁状态 */
let pollCancelled = false;
const runProgress = ref<{ runId: string; completed: number; total: number; current: string } | null>(null);
const errorMessage = ref("");
const datasetMessage = ref("");
const report = ref<EvalReport | null>(null);
const runRecords = ref<RunRecord[]>([]);
const datasets = ref<DatasetDto[]>([]);
const selectedDatasetId = ref("");
const runCaseLimit = ref(0);
const savingDataset = ref(false);
const loadingRuns = ref(false);
const showDeleteDatasetDialog = ref(false);
const deletingDatasetName = ref("");
const deletingDatasetId = ref("");
const showDeleteRunDialog = ref(false);
const deletingRunId = ref("");
const deletingRunName = ref("");
const showCompare = ref(false);
const selectedRuns = ref<Set<string>>(new Set());

// 编辑状态
const editingDataset = ref<{ id: string; name: string } | null>(null);
const editingCases = ref<EditableCase[]>([]);
const expandedCaseId = ref("");
const editingCaseIndex = ref(-1);
const editingCaseDraft = ref<EditableCase | null>(null);
const pageSize = ref(10);
const currentPage = ref(1);

// ===== Computed =====
const validCases = computed(() =>
  editingCases.value
    .filter((item) => item.question.trim())
    .map((item, index) => ({
      id: `case-${index + 1}`,
      question: item.question.trim(),
      referenceAnswer: item.referenceAnswer.trim(),
      expectedSources: splitList(item.expectedSourcesText),
      tags: splitList(item.tagsText),
    }))
);

const selectedDataset = computed(() =>
  datasets.value.find((item) => item.id === selectedDatasetId.value) || null
);

const selectedDatasetValidCases = computed(() => {
  const ds = selectedDataset.value;
  if (!ds) return [];
  return (ds.cases || [])
    .filter((item) => (item.question || "").trim())
    .map((item, index) => ({
      id: item.id || `case-${index + 1}`,
      question: item.question.trim(),
      referenceAnswer: (item.referenceAnswer || "").trim(),
      expectedSources: item.expectedSources || [],
      tags: item.tags || [],
    }));
});

const runCases = computed(() => {
  if (!runCaseLimit.value) return selectedDatasetValidCases.value;
  return selectedDatasetValidCases.value.slice(0, runCaseLimit.value);
});

const currentSummary = computed(() => report.value?.summary || runRecords.value[0]?.summary || null);

const currentDurationText = computed(() => {
  const duration = report.value?.durationMs ?? runRecords.value[0]?.durationMs ?? 0;
  if (!duration) return "最近一次实验";
  return formatDuration(duration);
});

const totalPages = computed(() => Math.max(1, Math.ceil(editingCases.value.length / pageSize.value)));
const pageStart = computed(() => (currentPage.value - 1) * pageSize.value);
const pagedCases = computed(() => editingCases.value.slice(pageStart.value, pageStart.value + pageSize.value));

const allRunsSelected = computed(() =>
  runRecords.value.length > 0 && runRecords.value.every((r) => selectedRuns.value.has(r.runId))
);

const compareCaseIds = computed(() => {
  const ids = new Set<string>();
  for (const r of compareReportsLoaded.value) {
    for (const result of r.results) {
      ids.add(result.id);
    }
  }
  return [...ids];
});

const issueGroups = computed(() => {
  const results = report.value?.results || [];
  return [
    {
      key: "high-risk",
      title: "高幻觉风险",
      items: results.filter((item) => item.judge.hallucinationRisk === "high"),
    },
    {
      key: "missed-retrieval",
      title: "检索未命中",
      items: results.filter((item) => !item.retrieval.hit),
    },
    {
      key: "low-score",
      title: "低分用例",
      items: results.filter((item) => item.judge.totalScore < 3.5),
    },
  ];
});

// ===== Helpers =====
function newCase(partial?: Partial<EditableCase>): EditableCase {
  return {
    localId: `case-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    question: "",
    referenceAnswer: "",
    expectedSourcesText: "",
    tagsText: "快速评测",
    ...partial,
  };
}

function splitList(text: string) {
  return text.split(",").map((item) => item.trim()).filter(Boolean);
}

function scoreClass(score: number): string {
  if (score >= 4) return "eval-score-good";
  if (score >= 3) return "eval-score-ok";
  return "eval-score-bad";
}

function riskLabel(risk: string) {
  if (risk === "low") return "低";
  if (risk === "high") return "高";
  return "中";
}

function truncateStr(s: string, n: number): string {
  if (!s) return "";
  return s.length > n ? s.substring(0, n) + "..." : s;
}

const progressPct = computed(() => {
  if (!runProgress.value || runProgress.value.total === 0) return 0;
  return Math.round((runProgress.value.completed * 100) / runProgress.value.total);
});

function scoreFmt(value: number) { return value.toFixed(2); }
function pctFmt(value: number) { return `${value.toFixed(1)}%`; }

function formatDuration(ms: number) {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function formatTime(iso: string) {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  const mo = String(d.getMonth() + 1).padStart(2, "0");
  const da = String(d.getDate()).padStart(2, "0");
  const h = String(d.getHours()).padStart(2, "0");
  const mi = String(d.getMinutes()).padStart(2, "0");
  return `${mo}-${da} ${h}:${mi}`;
}

function formatUpdatedAt(iso: string) {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  const y = d.getFullYear();
  const mo = String(d.getMonth() + 1).padStart(2, "0");
  const da = String(d.getDate()).padStart(2, "0");
  return `${y}-${mo}-${da}`;
}

function getCompareResult(report: EvalReport, caseId: string) {
  return report.results.find((r) => r.id === caseId) || null;
}

// ===== Tab 切换 =====
function switchTab(key: TabKey) {
  activeTab.value = key;
  errorMessage.value = "";
  datasetMessage.value = "";
}

// ===== 评测集 CRUD =====
function applyDataset(data: DatasetDto) {
  editingCases.value = (data.cases || []).map((item: any) =>
    newCase({
      question: item.question || "",
      referenceAnswer: item.referenceAnswer || "",
      expectedSourcesText: (item.expectedSources || []).join(", "),
      tagsText: (item.tags || []).join(", "),
    })
  );
  currentPage.value = 1;
  expandedCaseId.value = editingCases.value[0]?.localId || "";
}

async function loadDatasets() {
  errorMessage.value = "";
  datasetMessage.value = "";
  try {
    const resp = await fetch(`${API}/datasets`);
    const data = await resp.json();
    if (!resp.ok) throw new Error(data.error || "加载评测集失败");
    datasets.value = Array.isArray(data) ? data : [];
    if (!selectedDatasetId.value && datasets.value.length > 0) {
      selectedDatasetId.value = datasets.value[0].id;
    }
    if (datasets.value.length === 0) {
      await loadTemplate();
    }
  } catch (err: any) {
    errorMessage.value = err?.message || "加载评测集失败";
  }
}

function editDataset(ds: DatasetDto) {
  editingDataset.value = { id: ds.id, name: ds.name };
  applyDataset(ds);
  errorMessage.value = "";
  datasetMessage.value = "";
}

function backToList() {
  editingDataset.value = null;
  editingCases.value = [];
  errorMessage.value = "";
  datasetMessage.value = "";
}

function newDatasetDraft() {
  editingDataset.value = { id: "", name: "新评测集" };
  editingCases.value = [];
  addCase();
  errorMessage.value = "";
  datasetMessage.value = "";
}

async function loadTemplate() {
  errorMessage.value = "";
  datasetMessage.value = "";
  try {
    const resp = await fetch(`${API}/template`);
    if (!resp.ok) throw new Error("恢复内置模板失败");
    const data = await resp.json();
    // 直接跳到编辑内置模板
    editingDataset.value = { id: data.id, name: data.name };
    applyDataset(data);
    if (!selectedDatasetId.value) selectedDatasetId.value = data.id;
    datasetMessage.value = "已加载内置模板，点击「保存」可持久化";
  } catch (err: any) {
    errorMessage.value = err?.message || "恢复内置模板失败";
  }
}

async function saveDataset() {
  if (!editingDataset.value) return;
  savingDataset.value = true;
  errorMessage.value = "";
  datasetMessage.value = "";
  try {
    const isUpdate = !!editingDataset.value.id;
    const url = isUpdate ? `${API}/datasets/${editingDataset.value.id}` : `${API}/datasets`;
    const resp = await fetch(url, {
      method: isUpdate ? "PUT" : "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        id: editingDataset.value.id,
        name: editingDataset.value.name,
        description: "",
        cases: validCases.value,
      }),
    });
    const data = await resp.json();
    if (!resp.ok) throw new Error(data.error || "保存评测集失败");
    const next = data as DatasetDto;
    editingDataset.value = { id: next.id, name: next.name };
    selectedDatasetId.value = next.id;
    await loadDatasets();
    datasetMessage.value = "评测集已保存";
  } catch (err: any) {
    errorMessage.value = err?.message || "保存评测集失败";
  } finally {
    savingDataset.value = false;
  }
}

async function copyDataset(ds: DatasetDto) {
  errorMessage.value = "";
  datasetMessage.value = "";
  try {
    const resp = await fetch(`${API}/datasets`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        name: ds.name + " (副本)",
        description: "",
        cases: ds.cases,
      }),
    });
    const data = await resp.json();
    if (!resp.ok) throw new Error(data.error || "复制评测集失败");
    await loadDatasets();
    if (data?.id) selectedDatasetId.value = data.id;
    datasetMessage.value = "已复制为新评测集";
  } catch (err: any) {
    errorMessage.value = err?.message || "复制评测集失败";
  }
}

function askDeleteDataset(ds: DatasetDto) {
  deletingDatasetId.value = ds.id;
  deletingDatasetName.value = ds.name || ds.id;
  showDeleteDatasetDialog.value = true;
}

async function confirmDeleteDataset() {
  if (!deletingDatasetId.value) return;
  errorMessage.value = "";
  datasetMessage.value = "";
  try {
    const resp = await fetch(`${API}/datasets/${deletingDatasetId.value}`, { method: "DELETE" });
    if (!resp.ok) {
      const data = await resp.json();
      throw new Error(data.error || "删除评测集失败");
    }
    await loadDatasets();
    if (selectedDatasetId.value === deletingDatasetId.value) {
      selectedDatasetId.value = datasets.value[0]?.id || "";
    }
    datasetMessage.value = "评测集已删除";
  } catch (err: any) {
    errorMessage.value = err?.message || "删除评测集失败";
  }
}

// ===== 用例编辑 =====
function addCase() {
  const item = newCase();
  editingCases.value.push(item);
  currentPage.value = totalPages.value;
  expandedCaseId.value = item.localId;
}

function removeCase(index: number) {
  const removed = editingCases.value[index];
  editingCases.value.splice(index, 1);
  if (removed?.localId === expandedCaseId.value) {
    expandedCaseId.value = editingCases.value[Math.min(index, editingCases.value.length - 1)]?.localId || "";
  }
}

function toggleCase(id: string) {
  expandedCaseId.value = expandedCaseId.value === id ? "" : id;
}

function openCaseEditor(index: number) {
  editingCaseIndex.value = index;
  editingCaseDraft.value = { ...editingCases.value[index] };
}

function closeCaseEditor() {
  editingCaseIndex.value = -1;
  editingCaseDraft.value = null;
}

function saveCaseEdit() {
  if (editingCaseIndex.value >= 0 && editingCaseDraft.value) {
    editingCases.value[editingCaseIndex.value] = { ...editingCaseDraft.value };
  }
  closeCaseEditor();
}

function changePageSize() {
  currentPage.value = 1;
  expandedCaseId.value = pagedCases.value[0]?.localId || "";
}

// ===== 运行评测 =====
function editSelectedDataset() {
  if (!selectedDataset.value) return;
  editDataset(selectedDataset.value);
  activeTab.value = "datasets";
}

async function runSelectedDataset() {
  if (!selectedDataset.value || runCases.value.length === 0) return;
  running.value = true;
  runProgress.value = null;
  errorMessage.value = "";
  datasetMessage.value = "";
  try {
    const resp = await fetch(`${API}/run`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        name: selectedDataset.value.name,
        datasetId: selectedDataset.value.id,
        cases: runCases.value,
      }),
    });
    const data = await resp.json();
    if (!resp.ok) throw new Error(data.error || "提交评测任务失败");
    const runId = data.runId;
    runProgress.value = { runId, completed: 0, total: runCases.value.length, current: "" };
    await pollRunStatus(runId);
  } catch (err: any) {
    errorMessage.value = err?.message || "评测失败";
    running.value = false;
    runProgress.value = null;
  }
}

async function runFromEdit() {
  if (!editingDataset.value) return;
  running.value = true;
  runProgress.value = null;
  errorMessage.value = "";
  datasetMessage.value = "";
  try {
    // 提交评测任务，立即返回 runId
    const resp = await fetch(`${API}/run`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        name: editingDataset.value.name,
        datasetId: editingDataset.value.id,
        cases: validCases.value,
      }),
    });
    const data = await resp.json();
    if (!resp.ok) throw new Error(data.error || "提交评测任务失败");
    const runId = data.runId;
    runProgress.value = { runId, completed: 0, total: validCases.value.length, current: "" };
    // 轮询状态
    await pollRunStatus(runId);
  } catch (err: any) {
    errorMessage.value = err?.message || "评测失败";
    running.value = false;
    runProgress.value = null;
  }
}

async function pollRunStatus(runId: string) {
  const maxWait = 10 * 60 * 1000; // 10 分钟超时
  const interval = 2000; // 2 秒轮询
  const start = Date.now();
  while (Date.now() - start < maxWait) {
    // 组件卸载后立即退出, 避免继续 fetch + 写已销毁的 ref
    if (pollCancelled) return;
    try {
      const resp = await fetch(`${API}/runs/${runId}/status`);
      const data = await resp.json();
      if (!resp.ok) {
        throw new Error(data.error || "查询状态失败");
      }
      if (data.status === "running") {
        runProgress.value = {
          runId,
          completed: data.completedCases || 0,
          total: data.totalCases || 0,
          current: data.currentCase || "",
        };
        await new Promise((r) => setTimeout(r, interval));
        continue;
      }
      if (data.status === "completed") {
        report.value = data.report;
        runProgress.value = null;
        datasetMessage.value = "评测完成，报告已保存到实验记录";
        await loadRuns();
        running.value = false;
        return;
      }
      if (data.status === "failed") {
        throw new Error(data.error || "评测任务失败");
      }
    } catch (err: any) {
      errorMessage.value = err?.message || "轮询状态失败";
      running.value = false;
      runProgress.value = null;
      return;
    }
  }
  errorMessage.value = "评测超时（10 分钟）";
  running.value = false;
  runProgress.value = null;
}

// 组件卸载时取消进行中的轮询, 防止 10 分钟轮询在离开页面后继续跑
onUnmounted(() => {
  pollCancelled = true;
});

// ===== 实验记录 =====
async function loadRuns() {
  loadingRuns.value = true;
  try {
    const resp = await fetch(`${API}/runs`);
    const data = await resp.json();
    if (!resp.ok) throw new Error(data.error || "加载历史记录失败");
    runRecords.value = Array.isArray(data) ? data : [];
    if (!report.value && runRecords.value[0]?.runId) {
      await selectRun(runRecords.value[0].runId);
    }
  } catch (err: any) {
    errorMessage.value = err?.message || "加载历史记录失败";
  } finally {
    loadingRuns.value = false;
  }
}

async function selectRun(runId: string) {
  errorMessage.value = "";
  try {
    const resp = await fetch(`${API}/runs/${runId}`);
    const data = await resp.json();
    if (!resp.ok) throw new Error(data.error || "加载历史报告失败");
    report.value = data;
    showCompare.value = false;
  } catch (err: any) {
    errorMessage.value = err?.message || "加载历史报告失败";
  }
}

function askDeleteRun(item: RunRecord) {
  deletingRunId.value = item.runId;
  deletingRunName.value = item.name || item.runId;
  showDeleteRunDialog.value = true;
}

async function confirmDeleteRun() {
  if (!deletingRunId.value) return;
  errorMessage.value = "";
  try {
    const resp = await fetch(`${API}/runs/${deletingRunId.value}`, { method: "DELETE" });
    if (!resp.ok) {
      const data = await resp.json();
      throw new Error(data.error || "删除历史报告失败");
    }
    runRecords.value = runRecords.value.filter((item) => item.runId !== deletingRunId.value);
    selectedRuns.value.delete(deletingRunId.value);
    if (report.value?.runId === deletingRunId.value) report.value = null;
    deletingRunId.value = "";
    deletingRunName.value = "";
  } catch (err: any) {
    errorMessage.value = err?.message || "删除历史报告失败";
  }
}

// ===== 对比 =====
function toggleRunSelect(runId: string) {
  const next = new Set(selectedRuns.value);
  if (next.has(runId)) next.delete(runId); else next.add(runId);
  selectedRuns.value = next;
}

function toggleSelectAllRuns() {
  if (allRunsSelected.value) {
    selectedRuns.value = new Set();
  } else {
    selectedRuns.value = new Set(runRecords.value.map((r) => r.runId));
  }
}

async function openCompare() {
  if (selectedRuns.value.size < 2) return;
  errorMessage.value = "";
  // 加载所有选中的完整报告
  const reports: EvalReport[] = [];
  for (const runId of selectedRuns.value) {
    try {
      const resp = await fetch(`${API}/runs/${runId}`);
      const data = await resp.json();
      if (resp.ok) reports.push(data);
    } catch {}
  }
  // 用加载到的完整报告替换 compareReports 的数据
  compareReportsLoaded.value = reports;
  showCompare.value = true;
}

const compareReportsLoaded = ref<EvalReport[]>([]);

// ===== Init =====
loadDatasets();
loadRuns();
</script>

<style scoped>
.evaluation-page { padding: 0; }

/* ===== Tab 导航 ===== */
.eval-tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid #e5e7eb;
  margin-bottom: 20px;
  padding: 0 4px;
}
.ai-tab-btn {
  position: relative;
  padding: 10px 14px;
  border: none;
  background: transparent;
  color: #8b95a5;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.15s;
}
.ai-tab-btn::after {
  content: "";
  position: absolute;
  bottom: -1px;
  left: 14px;
  right: 14px;
  height: 2px;
  background: transparent;
  border-radius: 1px;
  transition: background 0.15s;
}
.ai-tab-btn.active {
  color: #111827;
  font-weight: 600;
}
.ai-tab-btn.active::after {
  background: #4f46e5;
}
.ai-tab-btn:hover:not(.active) {
  color: #6b7280;
}

/* ===== 运行工作台 ===== */
.eval-workbench {
  display: block;
}
.eval-run-grid {
  display: grid;
  grid-template-columns: minmax(280px, 0.86fr) minmax(360px, 1.14fr);
  gap: 16px;
  align-items: start;
}
.eval-run-panel {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  padding: 14px;
  min-width: 0;
}
.eval-panel-title {
  margin-bottom: 12px;
  font-size: 14px;
  font-weight: 700;
  color: #111827;
}
.eval-field {
  display: grid;
  gap: 6px;
  margin-bottom: 12px;
}
.eval-field span {
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
}
.eval-select {
  width: 100%;
  height: 34px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #fff;
  color: #111827;
  padding: 0 10px;
  font-size: 13px;
}
.eval-dataset-summary {
  margin-bottom: 12px;
  padding: 12px;
  border-radius: 8px;
  background: #f9fafb;
  border: 1px solid #f3f4f6;
}
.eval-dataset-summary strong {
  display: block;
  color: #111827;
  font-size: 14px;
  margin-bottom: 4px;
}
.eval-dataset-summary p {
  margin: 0 0 10px;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.5;
}
.eval-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.eval-chip {
  border-radius: 999px;
  background: #eef2ff;
  color: #4338ca;
  padding: 3px 8px;
  font-size: 12px;
  font-weight: 600;
}
.eval-run-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.eval-run-progress {
  margin: 14px 0 0;
}
.eval-progress-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
  color: #1d4ed8;
}
.eval-report-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}
.eval-report-summary div {
  border-radius: 8px;
  background: #f9fafb;
  border: 1px solid #f3f4f6;
  padding: 10px;
}
.eval-report-summary span {
  display: block;
  color: #6b7280;
  font-size: 12px;
  margin-bottom: 4px;
}
.eval-report-summary strong {
  font-size: 20px;
}
.eval-issue-groups {
  display: grid;
  gap: 12px;
}
.eval-issue-group {
  border-top: 1px solid #f3f4f6;
  padding-top: 12px;
}
.eval-issue-head {
  display: flex;
  justify-content: space-between;
  color: #374151;
  font-size: 13px;
  margin-bottom: 8px;
}
.eval-issue-head span {
  color: #9ca3af;
  font-weight: 700;
}
.eval-issue-card {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 10px;
  margin-bottom: 8px;
  background: #fff;
}
.eval-issue-card strong {
  display: block;
  font-size: 13px;
  color: #111827;
  margin-bottom: 4px;
}
.eval-issue-card p {
  margin: 0 0 6px;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.5;
}
.eval-issue-empty {
  padding: 10px;
  color: #9ca3af;
  font-size: 12px;
  background: #f9fafb;
  border-radius: 8px;
}
.eval-recent-block {
  margin-top: 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 14px;
  background: #fff;
}

/* ===== 工具栏 ===== */
.eval-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.eval-toolbar-right {
  display: flex;
  gap: 6px;
}
.eval-case-count {
  font-size: 13px;
  color: #6b7280;
  font-weight: 600;
}

/* ===== 评测集表格 ===== */
.eval-dataset-table,
.eval-experiment-table,
.eval-recent-table,
.eval-compare-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.eval-dataset-table th,
.eval-experiment-table th,
.eval-recent-table th,
.eval-compare-table th {
  padding: 8px 10px;
  border-bottom: 1px solid #e5e7eb;
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
  text-align: center;
}
.eval-dataset-table td,
.eval-experiment-table td,
.eval-recent-table td,
.eval-compare-table td {
  padding: 10px;
  border-bottom: 1px solid #f3f4f6;
  text-align: center;
}
.eval-empty-cell {
  text-align: center !important;
  color: #9ca3af;
  padding: 32px 10px !important;
}
.eval-dataset-row {
  cursor: pointer;
  transition: background 0.1s;
}
.eval-dataset-row:hover td {
  background: #f9fafb;
}
.eval-dataset-name-link {
  color: #111827;
  transition: color 0.1s;
}
.eval-dataset-row:hover .eval-dataset-name-link {
  color: #4f46e5;
}
.eval-row-active td {
  background: #eff6ff !important;
}

.eval-badge-default {
  background: #dbeafe;
  color: #1d4ed8;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}
.eval-badge-custom {
  background: #f3f4f6;
  color: #6b7280;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
}
.eval-score-good { color: #059669; font-weight: 700; }
.eval-score-ok { color: #d97706; font-weight: 700; }
.eval-score-bad { color: #dc2626; font-weight: 700; }
.eval-risk-high { color: #dc2626; font-weight: 600; }
.eval-risk-low { color: #059669; }

/* ===== 编辑页头部 ===== */
.eval-edit-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.eval-edit-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
}
.eval-name-label {
  font-size: 13px;
  color: #6b7280;
  flex-shrink: 0;
}
.eval-name-input {
  flex: 1;
  max-width: 320px;
  height: 34px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  padding: 0 10px;
  font-size: 13px;
  color: #111827;
  background: #fff;
}
.eval-edit-actions {
  display: flex;
  gap: 6px;
  margin-left: auto;
}

/* ===== 用例列表 ===== */
.eval-case-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.eval-pagination-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}
.eval-page-size {
  height: 28px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #fff;
  color: #111827;
  padding: 0 8px;
  font-size: 12px;
}
.eval-page-btn {
  width: 28px;
  height: 28px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #fff;
  color: #111827;
  cursor: pointer;
  font-size: 14px;
}
.eval-page-btn:disabled { color: #9ca3af; cursor: not-allowed; background: #f9fafb; }
.eval-page-info { font-size: 12px; color: #6b7280; min-width: 40px; text-align: center; }
.eval-icon-btn { border: 0; background: transparent; color: #dc2626; cursor: pointer; font-size: 13px; }

/* ===== 用例表格 ===== */
.eval-case-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.eval-case-table th {
  padding: 8px 10px;
  border-bottom: 1px solid #e5e7eb;
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
  text-align: center;
}
.eval-case-table td {
  padding: 10px;
  border-bottom: 1px solid #f3f4f6;
  text-align: center;
}
.eval-case-td-question {
  max-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.eval-case-empty { color: #9ca3af; font-style: italic; }
.eval-case-tags { display: flex; gap: 4px; flex-wrap: wrap; justify-content: center; }
.eval-tag {
  background: #f3f4f6;
  color: #6b7280;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
}

/* ===== 用例编辑弹窗 ===== */
.eval-modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 1000;
  animation: fadeIn 0.15s ease;
}
.eval-modal {
  position: fixed;
  inset: 0;
  z-index: 1001;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.eval-modal-dialog {
  width: 100%;
  max-width: 600px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  animation: modalSlideUp 0.2s ease;
}
.eval-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #e5e7eb;
}
.eval-modal-header h3 { margin: 0; font-size: 16px; font-weight: 600; color: #111827; }
.eval-modal-close {
  width: 28px; height: 28px;
  border: none; background: transparent;
  font-size: 20px; color: #6b7280;
  cursor: pointer; border-radius: 6px;
  display: flex; align-items: center; justify-content: center;
}
.eval-modal-close:hover { background: #f3f4f6; color: #111827; }
.eval-modal-body {
  padding: 20px;
  display: grid;
  gap: 14px;
  overflow-y: auto;
  max-height: 60vh;
}
.eval-modal-body label { display: grid; gap: 6px; }
.eval-modal-label { font-size: 13px; font-weight: 600; color: #374151; }
.eval-modal-body input, .eval-modal-body textarea {
  border: 1px solid #d1d5db;
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 13px;
  color: #111827;
  background: #fff;
}
.eval-modal-body textarea { resize: vertical; }
.eval-modal-footer {
  padding: 12px 20px;
  border-top: 1px solid #e5e7eb;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes modalSlideUp { from { opacity: 0; transform: translateY(16px); } to { opacity: 1; transform: translateY(0); } }

/* ===== 实验详情 ===== */
.eval-score-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.eval-score-item { border: 1px solid #e5e7eb; border-radius: 8px; padding: 12px; display: grid; gap: 6px; }
.eval-score-item span { font-size: 12px; color: #6b7280; }
.eval-score-item strong { font-size: 20px; }
.eval-result-list { display: grid; gap: 12px; }
.eval-result { border: 1px solid #e5e7eb; border-radius: 8px; padding: 14px; background: #fff; }
.eval-result.weak { border-color: #fecaca; background: #fffafa; }
.eval-result-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.eval-result-meta { font-size: 12px; color: #6b7280; }
.eval-result-score { font-size: 24px; font-weight: 700; color: #111827; white-space: nowrap; }
.eval-result-score span { font-size: 12px; color: #6b7280; }
.eval-badges { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; }
.eval-badges span { border-radius: 999px; background: #f3f4f6; padding: 4px 8px; font-size: 12px; }
.eval-badges .danger, .eval-error { color: #dc2626; }
.eval-reason { margin: 10px 0 0; color: #111827; font-size: 13px; }
.eval-result details { margin-top: 12px; }
.eval-result summary { cursor: pointer; color: #2563eb; font-size: 13px; }
.eval-result h4 { margin: 12px 0 6px; font-size: 13px; }
.eval-result pre { white-space: pre-wrap; word-break: break-word; background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 10px; font-size: 12px; }
.eval-result ul { margin: 0; padding-left: 18px; }
.eval-result li { margin-bottom: 10px; }
.eval-result li span, .eval-result li p { color: #6b7280; font-size: 12px; }

/* ===== 对比视图 ===== */
.eval-compare-header { margin-bottom: 12px; }
.eval-compare-summary { display: flex; gap: 16px; overflow-x: auto; }
.eval-compare-col {
  flex: 1;
  min-width: 180px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px;
  background: #fff;
}
.eval-compare-col strong { display: block; margin-bottom: 8px; font-size: 13px; }
.eval-compare-metrics { display: grid; gap: 4px; }
.eval-compare-metrics div { display: flex; justify-content: space-between; font-size: 12px; }
.eval-compare-metrics span { color: #6b7280; }
.eval-compare-table th, .eval-compare-table td { font-size: 12px; }

/* ===== 通用 ===== */
.eval-error-panel {
  margin-bottom: 12px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  background: #fef2f2;
  color: #b91c1c;
  padding: 10px 12px;
  font-size: 13px;
}
.eval-dataset-msg {
  margin-bottom: 12px;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #eff6ff;
  color: #1d4ed8;
  padding: 10px 12px;
  font-size: 13px;
}
.eval-progress-panel {
  margin-bottom: 12px;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #eff6ff;
  padding: 12px;
}
.eval-progress-bar {
  height: 6px;
  background: #dbeafe;
  border-radius: 3px;
  overflow: hidden;
  margin-bottom: 6px;
}
.eval-progress-fill {
  height: 100%;
  background: #4f46e5;
  border-radius: 3px;
  transition: width 0.3s ease;
}
.eval-progress-text {
  font-size: 12px;
  color: #4f46e5;
  font-weight: 600;
}
.ai-empty-panel {
  display: grid;
  place-items: center;
  min-height: 160px;
  border: 1px dashed #d1d5db;
  border-radius: 8px;
  background: #f9fafb;
  text-align: center;
  padding: 24px;
}
.ai-empty-title { font-size: 15px; font-weight: 600; }
.ai-empty-desc { margin-top: 8px; font-size: 13px; color: #6b7280; }
.text-center { text-align: center; }

@media (max-width: 768px) {
  .eval-run-grid { grid-template-columns: 1fr; }
  .eval-report-summary { grid-template-columns: 1fr; }
  .eval-edit-header { flex-direction: column; align-items: stretch; }
  .eval-edit-actions { margin-left: 0; }
  .eval-case-grid, .eval-score-grid { grid-template-columns: 1fr; }
  .eval-compare-summary { flex-direction: column; }
}
</style>
