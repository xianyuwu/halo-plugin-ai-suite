<template>
  <div class="ai-page">
    <VPageHeader title="检索设置">
      <template #actions>
        <VButton type="primary" size="sm" :loading="saving" @click="save">保存配置</VButton>
      </template>
    </VPageHeader>
    <p class="ai-desc">配置混合检索、相似度阈值等参数</p>

    <div class="page-body">
    <VCard title="检索参数">
      <div class="ai-form-grid">
        <div class="ai-field">
          <label>检索模式</label>
          <select v-model="form.searchMode">
            <option value="hybrid">混合检索（推荐）</option>
            <option value="vector">纯语义检索</option>
            <option value="keyword">纯关键词检索</option>
          </select>
        </div>
        <div class="ai-field">
          <label>语义检索权重</label>
          <input v-model.number="form.semanticWeight" type="range" min="0" max="1" step="0.1" />
          <span class="ai-help">{{ form.semanticWeight }}（关键词权重 = {{ (1 - form.semanticWeight).toFixed(1) }}）</span>
        </div>
        <div class="ai-field">
          <label>候选文档数 (Top-K)</label>
          <input v-model.number="form.topK" type="number" min="1" max="100" />
        </div>
        <div class="ai-field">
          <label>相似度阈值</label>
          <input v-model.number="form.similarityThreshold" type="range" min="0" max="1" step="0.05" />
          <span class="ai-help">{{ form.similarityThreshold }}</span>
        </div>
        <div class="ai-field">
          <label>最终返回数 (Top-N)</label>
          <input v-model.number="form.topN" type="number" min="1" max="20" />
        </div>
      </div>
    </VCard>

    <VCard title="无结果处理">
      <div class="ai-form-grid">
        <div class="ai-field">
          <label>无结果时行为</label>
          <select v-model="form.noMatchBehavior">
            <option value="continue">继续让 AI 回答</option>
            <option value="fixed_reply">返回固定回复</option>
          </select>
        </div>
        <div v-if="form.noMatchBehavior === 'fixed_reply'" class="ai-field">
          <label>无结果固定回复</label>
          <textarea v-model="form.noMatchReply" rows="2"></textarea>
        </div>
        <label><input type="checkbox" v-model="form.highlightResults" /> 检索结果高亮</label>
      </div>
    </VCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from "vue";
import { VPageHeader, VButton, VCard, Toast } from "@halo-dev/components";
import { saveGroup, loadGroup } from "../utils/config";

const form = reactive({
  searchMode: "hybrid", semanticWeight: 0.7, topK: 20, similarityThreshold: 0.5,
  topN: 5, highlightResults: false, noMatchBehavior: "continue",
  noMatchReply: "抱歉，未在博客中找到与您问题相关的内容。",
});
const saving = ref(false);
const saveMsg = ref(""), saveOk = ref(false);

async function save() {
  await saveGroup("retrieval", form, saving, saveMsg, saveOk);
  if (saveOk.value) {
    Toast.success(saveMsg.value || "保存成功");
  } else {
    Toast.error(saveMsg.value || "保存失败");
  }
}
onMounted(async () => { await loadGroup("retrieval", form); });
</script>
