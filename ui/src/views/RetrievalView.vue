<template>
  <div class="retrieval-page">
    <div class="ai-content">
      <div class="ai-excerpt-cols">
        <SectionCard title="检索参数" :icon-component="RiSearchLine" headerTitle="混合检索配置" headerDesc="设定检索模式、权重分配、候选数量与过滤阈值">
          <div class="ai-card-body">
            <div class="ai-form-grid-2col">
              <div class="ai-form-field">
                <label class="ai-field-label">检索模式</label>
                <select class="ai-input ai-select" v-model="form.searchMode">
                  <option value="hybrid">混合检索（推荐）</option>
                  <option value="vector">仅语义检索</option>
                  <option value="keyword">仅关键词检索</option>
                </select>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">结果排序策略</label>
                <select class="ai-input ai-select" v-model="form.sortStrategy">
                  <option value="relevance">按相关度排序</option>
                  <option value="date">按日期排序</option>
                  <option value="hybrid">综合排序</option>
                </select>
              </div>
              <div class="ai-form-field ai-field-full">
                <div class="ai-range-header">
                  <label class="ai-field-label">语义检索权重</label>
                  <span class="ai-field-value">{{ form.semanticWeight.toFixed(1) }}</span>
                </div>
                <div class="ai-range-wrap">
                  <span class="ai-range-label">关键词</span>
                  <input class="ai-range" type="range" v-model.number="form.semanticWeight" min="0" max="1" step="0.1" />
                  <span class="ai-range-label">语义</span>
                </div>
                <div class="ai-helper-text">关键词权重 = {{ (1 - form.semanticWeight).toFixed(1) }}</div>
              </div>
              <div class="ai-form-field ai-field-full">
                <div class="ai-range-header">
                  <label class="ai-field-label">相似度阈值</label>
                  <span class="ai-field-value">{{ form.similarityThreshold.toFixed(2) }}</span>
                </div>
                <div class="ai-range-wrap">
                  <span class="ai-range-label">宽松</span>
                  <input class="ai-range" type="range" v-model.number="form.similarityThreshold" min="0" max="1" step="0.05" />
                  <span class="ai-range-label">严格</span>
                </div>
                <div class="ai-helper-text">低于此阈值的候选文档将被过滤</div>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">候选文档数 (Top-K)</label>
                <input class="ai-input" v-model.number="form.topK" type="number" min="1" max="100" />
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">最终返回数 (Top-N)</label>
                <input class="ai-input" v-model.number="form.topN" type="number" min="1" max="20" />
              </div>
            </div>

            <div class="ai-section-divider"></div>

            <div class="ai-form-field">
              <label class="ai-field-label">无结果时行为</label>
              <select class="ai-input ai-select" v-model="form.noMatchBehavior">
                <option value="continue">继续让 AI 回答</option>
                <option value="fixed_reply">返回固定回复</option>
              </select>
            </div>
            <div v-if="form.noMatchBehavior === 'fixed_reply'" class="ai-form-field" style="margin-top: 14px">
              <label class="ai-field-label">无结果固定回复</label>
              <textarea class="ai-input ai-textarea" v-model="form.noMatchReply" rows="2" placeholder="抱歉，未在博客中找到与您问题相关的内容。"></textarea>
            </div>
            <div class="ai-option-grid" style="margin-top: 18px">
              <OptionCard v-model="form.showReferences" title="显示引用来源" desc="在回答底部展示引用的原始文章链接" />
            </div>

            <div class="ai-card-actions">
              <VButton type="default" @click="resetDefaults">恢复默认</VButton>
              <VButton type="primary" :disabled="saving" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
            </div>
          </div>
        </SectionCard>

        <div class="ai-right-col">
          <SectionCard title="检索流程" :icon-component="RiListCheck3" headerTitle="处理流程" headerDesc="从用户问题到最终返回的完整检索链路">
            <div class="ai-flow-steps">
              <div class="ai-flow-step">
                <div class="ai-flow-num">1</div>
                <div class="ai-flow-body">
                  <div class="ai-flow-title">解析问题</div>
                  <div class="ai-flow-desc">将用户输入的自然语言解析为结构化查询</div>
                </div>
              </div>
              <div class="ai-flow-connector" />
              <div class="ai-flow-step">
                <div class="ai-flow-num">2</div>
                <div>
                  <div class="ai-flow-title">混合召回</div>
                  <div class="ai-flow-desc">语义检索（权重 {{ form.semanticWeight.toFixed(1) }}）+ 关键词检索（权重 {{ (1 - form.semanticWeight).toFixed(1) }}），召回 Top-{{ form.topK }} 候选</div>
                </div>
              </div>
              <div class="ai-flow-connector" />
              <div class="ai-flow-step">
                <div class="ai-flow-num">3</div>
                <div>
                  <div class="ai-flow-title">阈值过滤</div>
                  <div class="ai-flow-desc">过滤相似度低于 {{ form.similarityThreshold.toFixed(2) }} 的低质量候选</div>
                </div>
              </div>
              <div class="ai-flow-connector" />
              <div class="ai-flow-step">
                <div class="ai-flow-num">4</div>
                <div>
                  <div class="ai-flow-title">返回 Top-N</div>
                  <div class="ai-flow-desc">取相似度最高的 {{ form.topN }} 个文档传递给 AI 生成回答</div>
                </div>
              </div>
            </div>
          </SectionCard>

          <SectionCard title="优化建议" :icon-component="RiLightbulbLine" headerTitle="使用指南" headerDesc="检索参数配置的注意事项与最佳实践">
          <div class="ai-card-body">
            <ul class="ai-tips-list">
              <li>混合检索模式适合大多数场景，建议作为默认选项</li>
              <li>语义权重 0.6-0.8 之间通常能获得最佳召回效果</li>
              <li>Top-K 设为 20-30 可平衡召回率与计算成本</li>
              <li>相似度阈值不建议超过 0.8，否则可能过滤过多结果</li>
              <li>开启引用来源可提升用户对 AI 回答的信任度</li>
            </ul>
          </div>
        </SectionCard>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from "vue";
import { Toast , VButton, VSpace} from "@halo-dev/components";
import { saveGroup, loadGroup } from "../utils/config";
import SectionCard from "../components/SectionCard.vue";
import OptionCard from "../components/OptionCard.vue";
import RiSearchLine from "~icons/ri/search-line";
import RiListCheck3 from "~icons/ri/list-check-3";
import RiLightbulbLine from "~icons/ri/lightbulb-line";

const DEFAULTS = {
  searchMode: "hybrid",
  semanticWeight: 0.7,
  topK: 20,
  similarityThreshold: 0.5,
  topN: 5,
  showReferences: false,
  noMatchBehavior: "continue",
  noMatchReply: "抱歉，未在博客中找到与您问题相关的内容。",
  sortStrategy: "relevance",
  displayMode: "inline",
};

const form = reactive({ ...DEFAULTS });
const saving = ref(false);
const saveMsg = ref("");
const saveOk = ref(false);

function resetDefaults() {
  Object.assign(form, { ...DEFAULTS });
}

async function save() {
  await saveGroup("retrieval", form, saving, saveMsg, saveOk);
  if (saveOk.value) {
    Toast.success(saveMsg.value || "保存成功");
  } else {
    Toast.error(saveMsg.value || "保存失败");
  }
}

onMounted(async () => {
  await loadGroup("retrieval", form);
});
</script>

<style scoped>
.retrieval-page {
  min-height: 100%;
  background: #f5f7fb;
}

/* ===== 顶部两栏布局 ===== */
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

.ai-right-col > :deep(.ai-section-block:first-child) .ai-section-card {
  /* 检索流程卡片保持默认白底 */
}

.ai-right-col > :deep(.ai-section-block:last-child) .ai-section-card {
  background: #f8fafc;
  border-color: #e2e8f0;
  box-shadow: none;
}

/* ===== Tips 列表 ===== */
.ai-tips-list {
  padding: 0;
  margin: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.ai-tips-list li {
  font-size: 13px;
  color: #4b5563;
  line-height: 2;
  padding: 0;
}

/* ===== 表单 ===== */
.ai-form-grid-2col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
  margin-bottom: 20px;
}

.ai-field-full {
  grid-column: 1 / -1;
}

.ai-section-divider {
  height: 1px;
  background: #e5e7eb;
  margin: 0 0 20px 0;
}

/* ===== Range Slider ===== */
.ai-range-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.ai-range-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.ai-range-label {
  font-size: 12px;
  color: #8a94a6;
  font-weight: 700;
  white-space: nowrap;
  min-width: 36px;
}

.ai-range {
  -webkit-appearance: none;
  appearance: none;
  flex: 1;
  height: 6px;
  border-radius: 3px;
  outline: none;
  cursor: pointer;
  background: #e5e7eb;
}

.ai-range::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #ffffff;
  border: 3px solid #111827;
  cursor: pointer;
  margin-top: -7px;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.15);
  transition: box-shadow 0.2s ease;
}

.ai-range::-webkit-slider-thumb:hover {
  box-shadow: 0 2px 10px rgba(17, 24, 39, 0.25);
}

.ai-range::-moz-range-track {
  height: 6px;
  border-radius: 3px;
  background: #e5e7eb;
}

.ai-range::-moz-range-progress {
  height: 6px;
  border-radius: 3px;
  background: #111827;
}

.ai-range::-moz-range-thumb {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #ffffff;
  border: 3px solid #111827;
  cursor: pointer;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.15);
}

/* ===== 右侧列 ===== */
.ai-right-col {
  display: flex;
  flex-direction: column;
  gap: 24px;
  flex: 1;
  min-width: 0;
}

.ai-right-col > :deep(.ai-section-block) {
  flex: 1;
}

/* ===== 检索流程 ===== */
.ai-flow-steps {
  padding: 12px 8px 12px 16px;
}

.ai-flow-step {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.ai-flow-num {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: #111827;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
  flex-shrink: 0;
  line-height: 1;
  margin-top: 1px;
}

.ai-flow-body {
  flex: 1;
  min-width: 0;
}

.ai-flow-title {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
  margin-bottom: 3px;
  line-height: 1.4;
}

.ai-flow-desc {
  font-size: 12px;
  color: #6b7280;
  line-height: 1.7;
}

.ai-flow-connector {
  width: 2px;
  height: 22px;
  background: #d1d5db;
  margin: 0 0 0 14px;
  margin-left: 30px;
}

/* ===== 卡片底部操作栏（用 VSpace 自带） ===== */

.ai-input[type="number"] {
  border: 1px solid #94a3b8 !important;
  background: #fff !important;
  -webkit-appearance: none;
  -moz-appearance: textfield;
  appearance: none;
}

@media (max-width: 1024px) {
  .ai-excerpt-cols { flex-direction: column; }
  .ai-form-grid-2col { grid-template-columns: 1fr; }
  .ai-field-full { grid-column: auto; }
}

@media (max-width: 560px) {
  .ai-excerpt-cols {
    gap: 16px;
  }
  .ai-range-wrap {
    align-items: stretch;
    flex-direction: column;
    gap: 8px;
  }
  .ai-range-label {
    min-width: 0;
  }
  .ai-flow-steps {
    padding: 8px 0;
  }
  .ai-flow-step {
    gap: 12px;
  }
  .ai-flow-connector {
    margin-left: 14px;
  }
}
</style>
