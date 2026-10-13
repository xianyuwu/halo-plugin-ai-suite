<template>
  <VModal v-model:visible="visible" title="生成 AI 标签" :width="580" :layer-closable="!applying" @close="closeTagModal">
    <div class="post-tag-modal">
      <div class="post-tag-title">{{ target?.title || '无标题文章' }}</div>
      <p class="post-tag-note">根据已保存的文章正文生成建议。选择并应用后才会修改文章标签。</p>
      <div class="post-tag-toolbar">
        <label for="post-tag-count">推荐数量</label>
        <select id="post-tag-count" v-model.number="count" class="post-tag-select" :disabled="generating || applying">
          <option v-for="n in [3, 4, 5, 6, 7, 8]" :key="n" :value="n">{{ n }} 个</option>
        </select>
        <VButton size="sm" :disabled="generating || applying" @click="generate">重新生成</VButton>
        <span class="post-tag-progress" role="status" aria-live="polite">{{ generating ? '生成中…' : '' }}</span>
      </div>
      <div v-if="currentTags.length" class="post-tag-current">
        <span>现有标签</span>
        <span v-for="name in currentTags" :key="name" class="post-tag-chip">{{ name }}</span>
      </div>
      <p v-if="error" class="post-tag-error" role="alert">{{ error }}</p>
      <div v-else-if="generating" class="post-tag-empty">正在生成候选标签…</div>
      <div v-else-if="suggestions.length === 0" class="post-tag-empty">暂无候选标签，可以重新生成。</div>
      <div v-else class="post-tag-options">
        <label v-for="item in suggestions" :key="item.name" class="post-tag-option">
          <input v-model="selected" type="checkbox" :value="item.name" :disabled="applying || hasCurrentTag(item.name)" />
          <span>{{ item.name }}</span>
          <small>{{ hasCurrentTag(item.name) ? '已添加' : item.existing ? '已有' : '新建' }}</small>
        </label>
      </div>
    </div>
    <template #footer>
      <div class="post-tag-footer">
        <span class="post-tag-progress" role="status" aria-live="polite">{{ applying ? '应用中…' : `已选 ${selected.length} 个` }}</span>
        <VButton :disabled="applying" @click="closeTagModal">取消</VButton>
        <VButton type="primary" :disabled="generating || applying || selected.length === 0" @click="apply">应用到文章</VButton>
      </div>
    </template>
  </VModal>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { Toast, VButton, VModal } from "@halo-dev/components";
import { closeTagModal, useTagModalState, type ApplyResult } from "./tag-modal-state";

interface SuggestedTag { name: string; existing: boolean }

const { visible, target } = useTagModalState();
const count = ref(5);
const currentTags = ref<string[]>([]);
const suggestions = ref<SuggestedTag[]>([]);
const selected = ref<string[]>([]);
const generating = ref(false);
const applying = ref(false);
const error = ref("");
let generation = 0;
let controller: AbortController | null = null;

function hasCurrentTag(name: string) {
  return currentTags.value.some(tag => tag.toLocaleLowerCase() === name.toLocaleLowerCase());
}

async function requestJson(url: string, init: RequestInit): Promise<any> {
  let response: Response;
  try {
    response = await fetch(url, init);
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === "AbortError") throw cause;
    throw new Error("无法连接 Halo，请稍后重试");
  }
  if (!(response.headers.get("content-type") || "").includes("application/json")) {
    throw new Error(response.redirected ? "登录已失效，请重新登录" : "Halo 服务暂不可用");
  }
  let body;
  try {
    body = await response.json();
  } catch {
    throw new Error("Halo 返回的数据格式异常，请重试");
  }
  if (!response.ok) throw new Error(body.message || "操作失败");
  return body;
}

async function generate() {
  const postName = target.value?.postName;
  if (!postName || applying.value) return;
  controller?.abort();
  controller = new AbortController();
  const requestNumber = ++generation;
  generating.value = true;
  error.value = "";
  suggestions.value = [];
  selected.value = [];
  try {
    const body = await requestJson("/apis/console.api.ai-suite.halo.run/v1alpha1/tags/generate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ postName, count: count.value }),
      signal: controller.signal,
    });
    if (requestNumber !== generation || target.value?.postName !== postName) return;
    currentTags.value = body.currentTags || currentTags.value;
    suggestions.value = body.tags || [];
    selected.value = suggestions.value.filter(item => !hasCurrentTag(item.name)).map(item => item.name);
  } catch (cause) {
    if (requestNumber !== generation || (cause instanceof DOMException && cause.name === "AbortError")) return;
    error.value = cause instanceof Error ? cause.message : "生成标签失败";
  } finally {
    if (requestNumber === generation) generating.value = false;
  }
}

async function apply() {
  const currentTarget = target.value;
  if (!currentTarget || generating.value || applying.value || !selected.value.length) return;
  applying.value = true;
  error.value = "";
  try {
    const body = await requestJson("/apis/console.api.ai-suite.halo.run/v1alpha1/tags/apply", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ postName: currentTarget.postName, tags: selected.value }),
    }) as ApplyResult;
    currentTarget.onApplied(body);
    Toast.success(`已为文章添加 ${body.added || 0} 个标签`);
    closeTagModal();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "应用标签失败";
  } finally {
    applying.value = false;
  }
}

watch(target, next => {
  if (!next) return;
  controller?.abort();
  generation++;
  count.value = 5;
  currentTags.value = next.currentTags;
  suggestions.value = [];
  selected.value = [];
  error.value = "";
  void generate();
});

watch(visible, isVisible => {
  if (!isVisible) {
    controller?.abort();
    generation++;
    generating.value = false;
  }
});
</script>

<style scoped>
.post-tag-modal { display: grid; gap: 14px; padding: 4px 0 12px; }
.post-tag-title { color: #111827; font-size: 16px; font-weight: 600; }
.post-tag-note { margin: 0; color: #6b7280; font-size: 13px; }
.post-tag-toolbar, .post-tag-current, .post-tag-options, .post-tag-footer { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.post-tag-toolbar { font-size: 13px; }
.post-tag-select { min-width: 80px; padding: 6px 8px; border: 1px solid #d1d5db; border-radius: 6px; background: #fff; }
.post-tag-current { color: #6b7280; font-size: 12px; }
.post-tag-chip { padding: 4px 8px; border-radius: 5px; background: #f3f4f6; color: #374151; }
.post-tag-options { align-items: stretch; }
.post-tag-option { display: inline-flex; align-items: center; gap: 7px; min-height: 36px; padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 7px; color: #111827; font-size: 13px; cursor: pointer; }
.post-tag-option:has(input:checked) { border-color: #34b38a; background: #f0fdf7; }
.post-tag-option input { margin: 0; }
.post-tag-option small { color: #6b7280; font-size: 11px; }
.post-tag-empty { margin: 0; padding: 18px 0; color: #6b7280; font-size: 13px; }
.post-tag-error { margin: 0; padding: 10px 12px; border-radius: 6px; background: #fef2f2; color: #b91c1c; font-size: 13px; }
.post-tag-footer { justify-content: flex-end; }
.post-tag-progress { min-width: 70px; margin-right: auto; color: #6b7280; font-size: 12px; }
</style>
