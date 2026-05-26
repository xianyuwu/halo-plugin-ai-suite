<template>
  <div class="chunking-page">
    <VPageHeader title="文档切片">
      <template #actions>
        <VButton type="primary" size="sm" :loading="saving" @click="save">保存配置</VButton>
      </template>
    </VPageHeader>

    <div class="page-body">
    <VCard title="切片参数">
      <div class="ai-form-grid">
        <div class="ai-field">
          <label>切片模式</label>
          <select v-model="form.chunkMode">
            <option value="auto">自动（按段落/标题）</option>
            <option value="custom">自定义</option>
          </select>
        </div>
        <div class="ai-field">
          <label>切片大小（字符）</label>
          <input v-model.number="form.chunkSize" type="number" min="100" max="2000" />
          <span class="ai-help">每个切片的最大字符数</span>
        </div>
        <div class="ai-field">
          <label>切片重叠（字符）</label>
          <input v-model.number="form.chunkOverlap" type="number" min="0" max="500" />
        </div>
        <div class="ai-field">
          <label>自定义分隔符</label>
          <input v-model="form.chunkSeparator" />
          <span class="ai-help">仅自定义模式生效</span>
        </div>
      </div>
    </VCard>

    <VCard title="高级选项">
      <div class="ai-form-grid">
        <label><input type="checkbox" v-model="form.markdownHeadingAware" /> Markdown 标题感知</label>
        <label><input type="checkbox" v-model="form.cleanWhitespace" /> 自动清理空白</label>
        <label><input type="checkbox" v-model="form.autoKeywords" /> 自动提取关键词</label>
        <div v-if="form.autoKeywords" class="ai-field">
          <label>每个切片关键词数</label>
          <input v-model.number="form.autoKeywordsCount" type="number" min="1" max="10" />
        </div>
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
  chunkMode: "auto", chunkSize: 500, chunkOverlap: 50, chunkSeparator: "\n\n",
  markdownHeadingAware: true, autoKeywords: false, autoKeywordsCount: 3, cleanWhitespace: true,
});
const saving = ref(false);
const saveMsg = ref(""), saveOk = ref(false);

async function save() {
  await saveGroup("chunking", form, saving, saveMsg, saveOk);
  if (saveOk.value) {
    Toast.success(saveMsg.value || "保存成功");
  } else {
    Toast.error(saveMsg.value || "保存失败");
  }
}
onMounted(async () => { await loadGroup("chunking", form); });
</script>

<style scoped>
.chunking-page {
  max-width: 960px;
}
</style>
