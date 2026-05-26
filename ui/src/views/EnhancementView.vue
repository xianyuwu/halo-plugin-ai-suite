<template>
  <div class="ai-page">
    <VPageHeader title="检索增强">
      <template #actions>
        <VButton type="primary" size="sm" :loading="saving" @click="save">保存配置</VButton>
      </template>
    </VPageHeader>
    <p class="ai-desc">查询改写、HyDE、Rerank 精排、跨语言检索</p>

    <div class="page-body">
    <VCard :title="''">
      <template #header>
        <label><input type="checkbox" v-model="form.queryRewriteToggle" /> 启用查询改写</label>
      </template>
      <div v-if="form.queryRewriteToggle" class="ai-form-grid">
        <div class="ai-field">
          <label>改写提示词</label>
          <textarea v-model="form.queryRewritePrompt" rows="2"></textarea>
        </div>
        <label><input type="checkbox" v-model="form.queryRewriteWithHistory" /> 包含对话历史</label>
      </div>
    </VCard>

    <VCard :title="''">
      <template #header>
        <label><input type="checkbox" v-model="form.hydeEnabled" /> 启用 HyDE（假设性文档嵌入）</label>
      </template>
      <div v-if="form.hydeEnabled" class="ai-form-grid">
        <div class="ai-field">
          <label>HyDE 提示词</label>
          <textarea v-model="form.hydePrompt" rows="2"></textarea>
        </div>
      </div>
    </VCard>

    <VCard :title="''">
      <template #header>
        <label><input type="checkbox" v-model="form.rerankToggle" /> 启用 Rerank 精排</label>
      </template>
      <div v-if="form.rerankToggle" class="ai-form-grid">
        <div class="ai-field">
          <label>Rerank 后阈值</label>
          <input v-model.number="form.rerankScoreThreshold" type="range" min="0" max="1" step="0.05" />
          <span class="ai-help">{{ form.rerankScoreThreshold }}</span>
        </div>
        <div class="ai-field">
          <label>Rerank 后保留数</label>
          <input v-model.number="form.rerankTopN" type="number" min="1" max="20" />
        </div>
      </div>
    </VCard>

    <VCard :title="''">
      <template #header>
        <label><input type="checkbox" v-model="form.crossLanguageEnabled" /> 启用跨语言检索</label>
      </template>
      <div v-if="form.crossLanguageEnabled" class="ai-form-grid">
        <div class="ai-field">
          <label>目标语言</label>
          <select v-model="form.crossLanguageTargets">
            <option value="en">英语</option>
            <option value="ja">日语</option>
            <option value="ko">韩语</option>
          </select>
        </div>
      </div>
    </VCard>

    <VCard title="引用来源">
      <label><input type="checkbox" v-model="form.showCitations" /> 展示引用来源</label>
    </VCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from "vue";
import { VPageHeader, VButton, VCard, Toast } from "@halo-dev/components";
import { saveGroup, loadGroup } from "../utils/config";

const form = reactive({
  queryRewriteToggle: false, queryRewritePrompt: "", queryRewriteWithHistory: true,
  hydeEnabled: false, hydePrompt: "", rerankToggle: false, rerankScoreThreshold: 0,
  rerankTopN: 5, crossLanguageEnabled: false, crossLanguageTargets: "en", showCitations: true,
});
const saving = ref(false);
const saveMsg = ref(""), saveOk = ref(false);

async function save() {
  await saveGroup("enhancement", form, saving, saveMsg, saveOk);
  if (saveOk.value) {
    Toast.success(saveMsg.value || "保存成功");
  } else {
    Toast.error(saveMsg.value || "保存失败");
  }
}
onMounted(async () => { await loadGroup("enhancement", form); });
</script>
