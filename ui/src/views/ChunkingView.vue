<template>
  <div class="chunking-page">
    <div class="ai-content">
      <div class="ai-excerpt-cols">
        <SectionCard title="切片规则" :icon-component="RiScissorsLine" headerTitle="参数配置" headerDesc="设定文档分段的基本参数，控制每个检索片段的大小与粒度">
          <div class="ai-card-body">
            <div class="ai-form-grid-3">
              <div class="ai-form-field">
                <label class="ai-field-label">切片模式</label>
                <select class="ai-input ai-select" v-model="form.chunkMode">
                  <option value="auto">自动（按段落/标题）</option>
                  <option value="custom">自定义</option>
                </select>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">切片大小（字符）</label>
                <input class="ai-input" v-model.number="form.chunkSize" type="number" min="100" max="2000" />
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">切片重叠（字符）</label>
                <input class="ai-input" v-model.number="form.chunkOverlap" type="number" min="0" max="500" />
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">自定义分隔符</label>
                <input class="ai-input" v-model="form.chunkSeparator" placeholder="默认 \n\n，自定义如 ---" />
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">最小切片长度（字符）</label>
                <input class="ai-input" v-model.number="form.minChunkSize" type="number" min="0" max="300" />
              </div>
            </div>

            <div class="ai-option-grid">
              <OptionCard v-model="form.markdownHeadingAware" title="Markdown 标题感知" desc="按 Markdown 标题层级自动分段，保留语义结构完整性" />
              <OptionCard v-model="form.sentenceAware" title="按句子切分" desc="在切片边界优先选择句子终点，避免从句子中间截断关键信息" />
              <OptionCard v-model="form.cleanWhitespace" title="自动清理空白" desc="移除多余空行、空格和制表符，减少无效内容干扰" />
              <OptionCard v-model="form.autoKeywords" title="自动提取关键词" desc="为每个切片自动提取关键词标签，辅助检索与分类" />
            </div>
            <div v-if="form.autoKeywords" class="ai-keywords-extra">
              <div class="ai-keywords-grid">
                <div class="ai-form-field">
                  <label class="ai-field-label">每个切片关键词数量</label>
                  <input class="ai-input" v-model.number="form.autoKeywordsCount" type="number" min="1" max="10" />
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">响应 Token 上限</label>
                  <input class="ai-input" v-model.number="form.keywordsMaxTokens" type="number" min="256" max="65536" step="128" />
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">每批切片数量</label>
                  <input class="ai-input" v-model.number="form.keywordsBatchSize" type="number" min="5" max="50" />
                </div>
              </div>
            </div>

            <div class="ai-card-actions">
              <VButton type="default" @click="resetDefaults">恢复默认</VButton>
              <VButton type="primary" :disabled="saving" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
            </div>
          </div>
        </SectionCard>

        <SectionCard title="优化建议" :icon-component="RiLightbulbLine" headerTitle="使用指南" headerDesc="切片参数配置的注意事项与最佳实践">
          <div class="ai-card-body">
            <ul class="ai-tips-list">
              <li>切片大小建议在 300-800 字符之间，过长会降低检索精度</li>
              <li>重叠设置 50-100 字符可避免关键信息被截断</li>
              <li>开启 Markdown 标题感知可提升结构化文档的切片质量</li>
              <li>开启按句子切分可避免从句子中间截断，对中文内容效果显著</li>
              <li>最小切片长度建议 30-80 字符，过短的碎片没有检索价值</li>
              <li>自动提取关键词可增强语义检索的召回率</li>
              <li>自动清理空白可减少索引噪音，建议始终开启</li>
              <li>修改切片参数后需在知识库页面手动重建索引才能生效</li>
              <li>切片大小应小于 Embedding 模型的最大输入长度，避免截断丢失信息</li>
            </ul>
          </div>
        </SectionCard>
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
import RiScissorsLine from "~icons/ri/scissors-line";
import RiLightbulbLine from "~icons/ri/lightbulb-line";

const DEFAULTS = {
  chunkMode: "auto",
  chunkSize: 500,
  chunkOverlap: 50,
  chunkSeparator: "\n\n",
  minChunkSize: 50,
  sentenceAware: true,
  markdownHeadingAware: true,
  autoKeywords: false,
  autoKeywordsCount: 3,
  keywordsMaxTokens: 1024,
  keywordsBatchSize: 20,
  cleanWhitespace: true,
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
  await saveGroup("chunking", form, saving, saveMsg, saveOk);
  if (saveOk.value) {
    Toast.success(saveMsg.value || "保存成功");
  } else {
    Toast.error(saveMsg.value || "保存失败");
  }
}

onMounted(async () => {
  await loadGroup("chunking", form);
});
</script>

<style scoped>
.chunking-page {
  min-height: 100%;
  background: #f5f7fb;
}

/* ===== 顶部两栏布局 ===== */
.ai-excerpt-cols {
  display: flex;
  gap: 24px;
  margin-bottom: 24px;
  align-items: flex-start;
}

.ai-excerpt-cols > :deep(.ai-section-block) {
  flex: 1;
  min-width: 0;
}

.ai-excerpt-cols > :deep(.ai-section-block:last-child) .ai-section-card {
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
.ai-form-grid-3 {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 18px;
  margin-bottom: 20px;
}

.ai-form-field .ai-input {
  border-color: #94a3b8 !important;
}

.ai-input[type="number"] {
  border: 1px solid #94a3b8 !important;
  background: #fff !important;
  -webkit-appearance: none;
  -moz-appearance: textfield;
  appearance: none;
}

.ai-field-full {
  grid-column: 1 / -1;
}

/* ===== 高级选项 ===== */
.ai-option-grid {
  display: flex;
  flex-direction: column;
  gap: 1px;
  border: 1px solid #e5e7eb;
  border-radius: 14px;
  overflow: hidden;
  margin-bottom: 14px;
}

.ai-keywords-extra {
  margin-bottom: 18px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 14px;
  padding: 18px 20px;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.03);
  animation: slideDown 0.2s ease;
}

.ai-keywords-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 18px;
}

@keyframes slideDown {
  from { opacity: 0; transform: translateY(-8px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 1024px) {
  .ai-excerpt-cols { flex-direction: column; }
  .ai-form-grid-3 { grid-template-columns: 1fr 1fr; }
  .ai-field-full { grid-column: 1 / -1; }
}
@media (max-width: 768px) {
  .ai-form-grid-3 { grid-template-columns: 1fr; }
}
</style>
