<template>
  <div class="enhancement-page">
    <div class="ai-content">
      <div class="ai-layout-main">
        <div class="ai-layout-left">
          <!-- 增强策略（合并卡片） -->
          <SectionCard title="增强策略" :icon-component="RiSparkling2Line" headerTitle="RAG 增强管线" headerDesc="按需开启查询改写、HyDE、Rerank 与跨语言检索，提升召回质量">
            <div class="ai-card-body">

              <!-- 查询改写 -->
              <div class="ai-enhance-section">
                <div class="ai-enhance-header">
                  <div>
                    <div class="ai-enhance-title">查询改写（Query Rewrite）</div>
                    <div class="ai-enhance-desc">使用 LLM 对用户问题进行扩展与优化，生成更利于检索的查询语句</div>
                  </div>
                  <label class="ai-switch"><input type="checkbox" v-model="form.queryRewriteToggle" /><span class="ai-switch-slider" /></label>
                </div>
                <div class="ai-enhance-body" v-show="form.queryRewriteToggle">
                  <div class="ai-form-field">
                    <label class="ai-field-label">改写提示词</label>
                    <textarea class="ai-input ai-textarea" v-model="form.queryRewritePrompt" rows="4" placeholder="你是一位检索优化助手，请将用户问题改写为 3 条更利于向量检索的查询..."></textarea>
                  </div>
                  <div class="ai-option-grid" style="margin-top: 14px">
                    <OptionCard v-model="form.queryRewriteWithHistory" title="包含对话历史" desc="改写时结合多轮对话上下文" />
                    <OptionCard v-model="form.keepOriginalQuery" title="保留原始查询" desc="仍使用原问题进行一次检索作为兜底" />
                  </div>
                </div>
              </div>

              <!-- HyDE -->
              <div class="ai-enhance-section">
                <div class="ai-enhance-header">
                  <div>
                    <div class="ai-enhance-title">HyDE 假设性文档嵌入</div>
                    <div class="ai-enhance-desc">让 LLM 先生成一段假设性文档，再用该文档的 Embedding 去检索真实片段</div>
                  </div>
                  <label class="ai-switch"><input type="checkbox" v-model="form.hydeEnabled" /><span class="ai-switch-slider" /></label>
                </div>
                <div class="ai-enhance-body" v-show="form.hydeEnabled">
                  <div class="ai-form-field">
                    <label class="ai-field-label">HyDE 提示词</label>
                    <textarea class="ai-input ai-textarea" v-model="form.hydePrompt" rows="3" placeholder="请针对下面的问题撰写一段专业、客观的短文，长度 150-250 字..."></textarea>
                  </div>
                </div>
              </div>

              <!-- Rerank -->
              <div class="ai-enhance-section">
                <div class="ai-enhance-header">
                  <div>
                    <div class="ai-enhance-title">Rerank 精排</div>
                    <div class="ai-enhance-desc">对初步召回的片段做二次打分，剔除低相关结果</div>
                  </div>
                  <label class="ai-switch"><input type="checkbox" v-model="form.rerankToggle" /><span class="ai-switch-slider" /></label>
                </div>
                <div class="ai-enhance-body" v-show="form.rerankToggle">
                  <div class="ai-form-grid-2">
                    <div class="ai-form-field">
                      <label class="ai-field-label">相关性阈值 <span class="ai-field-value-hint">{{ form.rerankScoreThreshold.toFixed(2) }}</span></label>
                      <input class="ai-range" type="range" min="0" max="1" step="0.05" v-model.number="form.rerankScoreThreshold" />
                      <div class="ai-range-labels"><span>宽松</span><span>平衡</span><span>严格</span></div>
                    </div>
                    <div class="ai-form-field">
                      <label class="ai-field-label">保留片段数量</label>
                      <input class="ai-input" type="number" min="1" max="20" v-model.number="form.rerankTopN" />
                    </div>
                  </div>
                  <div class="ai-rerank-notice">开启后，Rerank 的保留数量将覆盖检索策略中的 Top-N 设置</div>
                </div>
              </div>

              <!-- 跨语言检索 -->
              <div class="ai-enhance-section">
                <div class="ai-enhance-header">
                  <div>
                    <div class="ai-enhance-title">跨语言检索</div>
                    <div class="ai-enhance-desc">将用户查询自动翻译为目标语言后再进行检索，提升多语料库场景召回率</div>
                  </div>
                  <label class="ai-switch"><input type="checkbox" v-model="form.crossLanguageEnabled" /><span class="ai-switch-slider" /></label>
                </div>
                <div class="ai-enhance-body" v-show="form.crossLanguageEnabled">
                  <div class="ai-form-grid-2">
                    <div class="ai-form-field">
                      <label class="ai-field-label">目标检索语言</label>
                      <select class="ai-input ai-select" v-model="form.crossLanguageTargets">
                        <option value="zh">中文</option>
                        <option value="en">英语</option>
                        <option value="ja">日语</option>
                        <option value="ko">韩语</option>
                        <option value="de">德语</option>
                        <option value="fr">法语</option>
                      </select>
                    </div>
                    <div class="ai-form-field">
                      <label class="ai-field-label">语言策略</label>
                      <select class="ai-input ai-select" v-model="form.crossLanguageStrategy">
                        <option value="both">原文 + 译文同时检索</option>
                        <option value="target_only">仅使用译文检索</option>
                        <option value="auto_detect">自动识别并决策</option>
                      </select>
                    </div>
                    <div class="ai-form-field">
                      <label class="ai-field-label">最大贡献条数</label>
                      <input class="ai-input" v-model.number="form.crossLanguageMaxResults" type="number" min="1" max="20" />
                      <div class="ai-helper-text">跨语言检索结果最多取几条参与合并，防止高分噪声淹没主检索</div>
                    </div>
                  </div>
                </div>
              </div>

              <div class="ai-card-actions">
                <VButton type="default" @click="resetDefaults">恢复默认</VButton>
                <VButton type="primary" :disabled="saving" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
              </div>
            </div>
          </SectionCard>
        </div>

        <div class="ai-layout-right">
          <SectionCard title="增强流程" :icon-component="RiListCheck3" headerTitle="处理流程" headerDesc="RAG 增强管线的完整处理链路">
            <div class="ai-flow-steps">
              <div class="ai-flow-step">
                <div class="ai-flow-num">1</div>
                <div class="ai-flow-body">
                  <div class="ai-flow-title">查询改写</div>
                  <div class="ai-flow-desc">扩展问题语义，生成多条改写查询</div>
                </div>
              </div>
              <div class="ai-flow-connector" />
              <div class="ai-flow-step">
                <div class="ai-flow-num">2</div>
                <div class="ai-flow-body">
                  <div class="ai-flow-title">HyDE 生成</div>
                  <div class="ai-flow-desc">生成假设性文档，用其 Embedding 检索</div>
                </div>
              </div>
              <div class="ai-flow-connector" />
              <div class="ai-flow-step">
                <div class="ai-flow-num">3</div>
                <div class="ai-flow-body">
                  <div class="ai-flow-title">混合检索</div>
                  <div class="ai-flow-desc">向量 + BM25 召回候选片段</div>
                </div>
              </div>
              <div class="ai-flow-connector" />
              <div class="ai-flow-step">
                <div class="ai-flow-num">4</div>
                <div class="ai-flow-body">
                  <div class="ai-flow-title">Rerank 精排</div>
                  <div class="ai-flow-desc">二次打分过滤低相关结果</div>
                </div>
              </div>
              <div class="ai-flow-connector" />
              <div class="ai-flow-step">
                <div class="ai-flow-num">5</div>
                <div class="ai-flow-body">
                  <div class="ai-flow-title">带引用回答</div>
                  <div class="ai-flow-desc">生成回答并附带引用来源</div>
                </div>
              </div>
            </div>
          </SectionCard>

          <SectionCard title="配置建议" :icon-component="RiLightbulbLine" headerTitle="使用指南" headerDesc="检索增强配置的注意事项与最佳实践">
            <div class="ai-card-body">
              <ul class="ai-tips-list">
                <li>查询改写在多轮对话场景效果最显著，建议开启「包含对话历史」</li>
                <li>HyDE 适合事实类问题，开放性问答场景收益较低</li>
                <li>Rerank 阈值建议从 0.3 起步，根据实际召回效果微调</li>
                <li>跨语言检索对 Embedding 模型多语能力依赖较大，请先确认模型支持目标语言</li>
                <li>保存配置后需重新提问才能生效，不会影响进行中的对话</li>
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
import RiSparkling2Line from "~icons/ri/sparkling-2-line";
import RiListCheck3 from "~icons/ri/list-check-3";
import RiLightbulbLine from "~icons/ri/lightbulb-line";

const DEFAULTS = {
  queryRewriteToggle: false,
  queryRewritePrompt: "",
  queryRewriteWithHistory: true,
  keepOriginalQuery: false,
  hydeEnabled: false,
  hydePrompt: "",
  rerankToggle: false,
  rerankScoreThreshold: 0,
  rerankTopN: 5,
  crossLanguageEnabled: false,
  crossLanguageTargets: "en",
  crossLanguageStrategy: "both",
  crossLanguageMaxResults: 5,
};

const form = reactive({ ...DEFAULTS });
const saving = ref(false);
const saveMsg = ref("");
const saveOk = ref(false);

function resetDefaults() {
  Object.assign(form, DEFAULTS);
  Toast.success("已恢复默认配置");
}

async function save() {
  await saveGroup("enhancement", form, saving, saveMsg, saveOk);
  if (saveOk.value) {
    Toast.success(saveMsg.value || "保存成功");
  } else {
    Toast.error(saveMsg.value || "保存失败");
  }
}

onMounted(async () => {
  await loadGroup("enhancement", form);
});
</script>

<style scoped>
.enhancement-page {
  min-height: 100%;
  background: #f5f7fb;
}

.ai-layout-main {
  display: flex;
  gap: 24px;
  align-items: flex-start;
}

.ai-layout-left {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.ai-layout-right {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.ai-layout-right > :deep(.ai-section-block:last-child) .ai-section-card {
  background: #f8fafc;
  border-color: #e2e8f0;
  box-shadow: none;
}

/* ===== 增强策略子区域 ===== */
.ai-enhance-section {
  padding: 18px 20px;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  margin-bottom: 14px;
}

.ai-enhance-section:last-of-type {
  margin-bottom: 18px;
}

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

.ai-enhance-body {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid #f3f4f6;
}

.ai-rerank-notice {
  margin-top: 14px;
  padding: 8px 12px;
  background: #eff6ff;
  border-radius: 8px;
  font-size: 12px;
  color: #2563eb;
  line-height: 1.5;
}

/* ===== Tips ===== */
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

/* ===== Switch Toggle ===== */
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

/* ===== 表单 ===== */
.ai-form-grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}

.ai-field-value-hint {
  font-size: 12px;
  font-weight: 700;
  color: #6b7280;
  padding: 2px 8px;
  background: #f3f4f6;
  border-radius: 6px;
}

/* ===== Range Slider ===== */
.ai-range {
  width: 100%;
  height: 6px;
  border-radius: 999px;
  background: #e5e7eb;
  appearance: none;
  outline: none;
  margin-top: 10px;
}

.ai-range::-webkit-slider-thumb {
  appearance: none;
  width: 22px;
  height: 22px;
  border-radius: 999px;
  background: #111827;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.25);
  border: 3px solid #fff;
}

.ai-range::-moz-range-thumb {
  width: 22px;
  height: 22px;
  border-radius: 999px;
  background: #111827;
  cursor: pointer;
  border: 3px solid #fff;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.25);
}

.ai-range-labels {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  font-size: 12px;
  color: #8a94a6;
  font-weight: 700;
}

/* ===== 增强流程 ===== */
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

.ai-flow-body { flex: 1; min-width: 0; }

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
  margin-left: 30px;
}


@media (max-width: 1024px) {
  .ai-layout-main { flex-direction: column; }
}
</style>
