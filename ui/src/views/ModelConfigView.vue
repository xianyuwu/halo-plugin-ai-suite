<template>
  <div class="ai-model-page">
    <div class="ai-content">
      <div class="ai-section-block">
        <div class="ai-section-heading">
          <h2>核心模型</h2>
          <span class="ai-section-tag ai-tag-required">AI Foundation</span>
        </div>

        <div class="ai-source-panel">
          <div class="ai-source-row">
            <div>
              <div class="ai-source-title">模型统一由 Halo AI Foundation 管理</div>
              <div class="ai-source-desc">密钥、供应商和模型连接在官方 AI Foundation 插件中配置；这里仅选择 AI 智能套件各业务使用的模型。</div>
            </div>
            <VButton type="secondary" @click="openAiFoundationDefaults">配置 AI Foundation</VButton>
          </div>
        </div>

        <div class="ai-model-grid">
          <article class="ai-model-card">
            <div class="ai-model-card-header">
              <div class="ai-model-title-wrap">
                <div class="ai-model-icon"><RiChatSmileLine /></div>
                <div>
                  <div class="ai-model-title">对话模型</div>
                  <div class="ai-model-subtitle">负责回答生成、上下文理解与指令执行</div>
                </div>
              </div>
              <div class="ai-model-status">
                <span class="ai-status-chip required">必配能力</span>
                <span class="ai-status-chip">
                  <span class="ai-dot" :class="statusDotClass('chat')"></span>
                  {{ statusLabel('chat') }}
                </span>
              </div>
            </div>
            <div class="ai-model-card-body">
              <div v-if="modelOptionsError" class="ai-test-feedback error">
                <RiCloseLine /> {{ modelOptionsError }}
              </div>
              <div class="ai-form-grid">
                <div class="ai-form-field">
                  <label class="ai-field-label">语言模型 <span class="ai-label-hint">来自 AI Foundation</span></label>
                  <select class="ai-input ai-select" v-model="form.aiFoundationChatModelName" :disabled="modelOptionsLoading">
                    <option value="">{{ defaultOptionLabel("language", "使用 AI Foundation 默认语言模型") }}</option>
                    <option v-for="option in languageOptions" :key="option.name" :value="option.name">
                      {{ modelOptionLabel(option) }}
                    </option>
                    <option v-if="unknownSelectedOption('language', form.aiFoundationChatModelName)" :value="form.aiFoundationChatModelName">
                      当前配置：{{ form.aiFoundationChatModelName }}
                    </option>
                  </select>
                  <span class="ai-helper-text">{{ selectedModelHint("language", form.aiFoundationChatModelName, "留空时使用 AI Foundation 默认语言模型") }}</span>
                </div>
              </div>
              <div v-if="testResult.chat" class="ai-test-feedback" :class="testResult.chat.ok ? 'success' : 'error'">
                <template v-if="testResult.chat.ok"><RiCheckLine /> 连接成功 ({{ testResult.chat.reply }})</template>
                <template v-else><RiCloseLine /> {{ testResult.chat.error }}</template>
              </div>
              <div style="justify-content: flex-end;" class="ai-card-actions">
                <VButton @click="testChat" :disabled="testing.chat">{{ testing.chat ? '测试中...' : '测试连通性' }}</VButton>
                <VButton type="primary" @click="saveModel('chat')" :disabled="saving.chat">{{ saving.chat ? '保存中...' : '保存配置' }}</VButton>
              </div>
            </div>
          </article>

          <article class="ai-model-card">
            <div class="ai-model-card-header">
              <div class="ai-model-title-wrap">
                <div class="ai-model-icon"><RiStackLine /></div>
                <div>
                  <div class="ai-model-title">Embedding 模型</div>
                  <div class="ai-model-subtitle">负责文档向量化、语义召回与相似度计算</div>
                </div>
              </div>
              <div class="ai-model-status">
                <span class="ai-status-chip required">必配能力</span>
                <span class="ai-status-chip">
                  <span class="ai-dot" :class="statusDotClass('embedding')"></span>
                  {{ statusLabel('embedding') }}
                </span>
              </div>
            </div>
            <div class="ai-model-card-body">
              <div class="ai-form-grid">
                <div class="ai-form-field">
                  <label class="ai-field-label">Embedding 模型 <span class="ai-label-hint">来自 AI Foundation</span></label>
                  <select class="ai-input ai-select" v-model="form.aiFoundationEmbeddingModelName" :disabled="modelOptionsLoading">
                    <option value="">{{ defaultOptionLabel("embedding", "使用 AI Foundation 默认嵌入模型") }}</option>
                    <option v-for="option in embeddingOptions" :key="option.name" :value="option.name">
                      {{ modelOptionLabel(option) }}
                    </option>
                    <option v-if="unknownSelectedOption('embedding', form.aiFoundationEmbeddingModelName)" :value="form.aiFoundationEmbeddingModelName">
                      当前配置：{{ form.aiFoundationEmbeddingModelName }}
                    </option>
                  </select>
                  <span class="ai-helper-text">{{ selectedModelHint("embedding", form.aiFoundationEmbeddingModelName, "留空时使用 AI Foundation 默认嵌入模型") }}</span>
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">向量维度</label>
                  <input class="ai-input" v-model.number="form.embeddingDimensions" type="number" min="256" max="4096" step="128" />
                </div>
              </div>
              <div v-if="testResult.embedding" class="ai-test-feedback" :class="testResult.embedding.ok ? 'success' : 'error'">
                <template v-if="testResult.embedding.ok"><RiCheckLine /> 连接成功 — 模型: {{ testResult.embedding.model || '默认模型' }}，维度: {{ testResult.embedding.dimensions }}</template>
                <template v-else><RiCloseLine /> {{ testResult.embedding.error }}</template>
              </div>
              <div style="justify-content: flex-end;" class="ai-card-actions">
                <VButton @click="testEmbedding" :disabled="testing.embedding">{{ testing.embedding ? '测试中...' : '测试连通性' }}</VButton>
                <VButton type="primary" @click="saveModel('embedding')" :disabled="saving.embedding">{{ saving.embedding ? '保存中...' : '保存配置' }}</VButton>
              </div>
            </div>
          </article>
        </div>
      </div>

      <div class="ai-section-block">
        <div class="ai-section-heading">
          <h2>高级模型</h2>
          <span class="ai-section-tag ai-tag-optional">可选</span>
        </div>

        <div class="ai-advanced-note">
          <div class="ai-note-icon">i</div>
          <div>
            Rerank 可优化召回结果排序；查询改写可将用户问题改写为更适合检索的表达。留空时使用 AI Foundation 默认模型或复用对话模型。
          </div>
        </div>

        <div class="ai-model-grid">
          <article class="ai-model-card">
            <div class="ai-model-card-header">
              <div class="ai-model-title-wrap">
                <div class="ai-model-icon"><RiSortDesc /></div>
                <div>
                  <div class="ai-model-title">Rerank 重排序</div>
                  <div class="ai-model-subtitle">对召回片段进行二次排序，提高答案相关性</div>
                </div>
              </div>
              <div class="ai-model-status">
                <span class="ai-status-chip" :class="form.rerankEnabled ? 'enabled' : 'optional'" @click="form.rerankEnabled = !form.rerankEnabled" style="cursor: pointer;" :title="form.rerankEnabled ? '点击停用' : '点击启用'">
                  <RiCheckLine v-if="form.rerankEnabled" class="ai-check-mark" />
                  {{ form.rerankEnabled ? '已启用' : '未启用 · 点击启用' }}
                </span>
                <span v-if="form.rerankEnabled" class="ai-status-chip">
                  <span class="ai-dot" :class="statusDotClass('rerank')"></span>
                  {{ statusLabel('rerank') }}
                </span>
              </div>
            </div>
            <div class="ai-model-card-body" v-if="form.rerankEnabled">
              <div class="ai-form-grid">
                <div class="ai-form-field">
                  <label class="ai-field-label">Rerank 模型 <span class="ai-label-hint">来自 AI Foundation</span></label>
                  <select class="ai-input ai-select" v-model="form.aiFoundationRerankModelName" :disabled="modelOptionsLoading">
                    <option value="">{{ defaultOptionLabel("rerank", "使用 AI Foundation 默认 Rerank 模型") }}</option>
                    <option v-for="option in rerankOptions" :key="option.name" :value="option.name">
                      {{ modelOptionLabel(option) }}
                    </option>
                    <option v-if="unknownSelectedOption('rerank', form.aiFoundationRerankModelName)" :value="form.aiFoundationRerankModelName">
                      当前配置：{{ form.aiFoundationRerankModelName }}
                    </option>
                  </select>
                  <span class="ai-helper-text">{{ selectedModelHint("rerank", form.aiFoundationRerankModelName, "留空时使用 AI Foundation 默认 Rerank 模型") }}</span>
                </div>
              </div>
              <div v-if="testResult.rerank" class="ai-test-feedback" :class="testResult.rerank.ok ? 'success' : 'error'">
                <template v-if="testResult.rerank.ok"><RiCheckLine /> 连接成功 — 相关度: {{ testResult.rerank.relevanceScore }}</template>
                <template v-else><RiCloseLine /> {{ testResult.rerank.error }}</template>
              </div>
              <div style="justify-content: flex-end;" class="ai-card-actions">
                <VButton @click="testRerank" :disabled="testing.rerank">{{ testing.rerank ? '测试中...' : '测试连通性' }}</VButton>
                <VButton type="primary" @click="saveModel('rerank')" :disabled="saving.rerank">{{ saving.rerank ? '保存中...' : '保存配置' }}</VButton>
              </div>
            </div>
            <div class="ai-model-card-body" v-else style="padding: 28px 22px; text-align: center; color: #9ca3af; font-size: 14px;">
              点击右上角「未启用」标签即可开启重排序
            </div>
          </article>

          <article class="ai-model-card">
            <div class="ai-model-card-header">
              <div class="ai-model-title-wrap">
                <div class="ai-model-icon"><RiSearchAiLine /></div>
                <div>
                  <div class="ai-model-title">查询改写</div>
                  <div class="ai-model-subtitle">改写用户问题，提升检索召回与语义匹配</div>
                </div>
              </div>
              <div class="ai-model-status">
                <span class="ai-status-chip" :class="form.queryRewriteEnabled ? 'enabled' : 'optional'" @click="form.queryRewriteEnabled = !form.queryRewriteEnabled" style="cursor: pointer;" :title="form.queryRewriteEnabled ? '点击停用' : '点击启用'">
                  <RiCheckLine v-if="form.queryRewriteEnabled" class="ai-check-mark" />
                  {{ form.queryRewriteEnabled ? '已启用' : '未启用 · 点击启用' }}
                </span>
                <span v-if="form.queryRewriteEnabled" class="ai-status-chip">
                  <span class="ai-dot" :class="statusDotClass('queryRewrite')"></span>
                  {{ statusLabel('queryRewrite') }}
                </span>
              </div>
            </div>
            <div class="ai-model-card-body" v-if="form.queryRewriteEnabled">
              <div class="ai-form-grid">
                <div class="ai-form-field">
                  <label class="ai-field-label">查询改写模型 <span class="ai-label-hint">语言模型</span></label>
                  <select class="ai-input ai-select" v-model="form.aiFoundationQueryRewriteModelName" :disabled="modelOptionsLoading">
                    <option value="">复用对话模型</option>
                    <option v-for="option in languageOptions" :key="option.name" :value="option.name">
                      {{ modelOptionLabel(option) }}
                    </option>
                    <option v-if="unknownSelectedOption('language', form.aiFoundationQueryRewriteModelName)" :value="form.aiFoundationQueryRewriteModelName">
                      当前配置：{{ form.aiFoundationQueryRewriteModelName }}
                    </option>
                  </select>
                  <span class="ai-helper-text">{{ selectedModelHint("language", form.aiFoundationQueryRewriteModelName, "留空时复用对话模型") }}</span>
                </div>
              </div>
              <div v-if="testResult.queryRewrite" class="ai-test-feedback" :class="testResult.queryRewrite.ok ? 'success' : 'error'">
                <template v-if="testResult.queryRewrite.ok"><RiCheckLine /> 连接成功 — {{ testResult.queryRewrite.reply }}</template>
                <template v-else><RiCloseLine /> {{ testResult.queryRewrite.error }}</template>
              </div>
              <div style="justify-content: flex-end;" class="ai-card-actions">
                <VButton @click="testQueryRewrite" :disabled="testing.queryRewrite">{{ testing.queryRewrite ? '测试中...' : '测试连通性' }}</VButton>
                <VButton type="primary" @click="saveModel('queryRewrite')" :disabled="saving.queryRewrite">{{ saving.queryRewrite ? '保存中...' : '保存配置' }}</VButton>
              </div>
            </div>
            <div class="ai-model-card-body" v-else style="padding: 28px 22px; text-align: center; color: #9ca3af; font-size: 14px;">
              点击右上角「未启用」标签即可开启查询改写
            </div>
          </article>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted } from "vue";
import { Toast, VButton } from "@halo-dev/components";
import RiChatSmileLine from "~icons/ri/chat-smile-line";
import RiStackLine from "~icons/ri/stack-line";
import RiSortDesc from "~icons/ri/sort-desc";
import RiSearchAiLine from "~icons/ri/search-ai-line";
import RiCheckLine from "~icons/ri/check-line";
import RiCloseLine from "~icons/ri/close-line";

const CONFIG_API = "/apis/console.api.ai-suite.halo.run/v1alpha1/config";
const AI_FOUNDATION_API = "/apis/console.api.aifoundation.halo.run/v1alpha1";

type ModelType = "language" | "embedding" | "rerank";

interface ModelOption {
  name: string;
  modelId?: string;
  displayName?: string;
  modelType: ModelType;
  enabled: boolean;
  available: boolean;
  features?: string[];
  provider?: {
    displayName?: string;
    providerTypeDisplayName?: string;
    phase?: string;
  };
}

interface DefaultModelSlots {
  languageModelName?: string;
  embeddingModelName?: string;
  rerankModelName?: string;
}

const MODEL_FIELDS: Record<string, string[]> = {
  chat: ["aiFoundationChatModelName"],
  embedding: ["aiFoundationEmbeddingModelName", "embeddingDimensions"],
  rerank: ["rerankEnabled", "aiFoundationRerankModelName"],
  queryRewrite: ["queryRewriteEnabled", "aiFoundationQueryRewriteModelName"],
};

const form = reactive({
  aiFoundationChatModelName: "",
  aiFoundationEmbeddingModelName: "",
  aiFoundationRerankModelName: "",
  aiFoundationQueryRewriteModelName: "",
  embeddingDimensions: 1024,
  rerankEnabled: false,
  queryRewriteEnabled: false,
});

const saving = reactive({ chat: false, embedding: false, rerank: false, queryRewrite: false });
const testing = reactive({ chat: false, embedding: false, rerank: false, queryRewrite: false });
const modelOptionsLoading = ref(false);
const modelOptionsError = ref("");
const modelOptions = reactive<Record<ModelType, ModelOption[]>>({
  language: [],
  embedding: [],
  rerank: [],
});
const defaultSlots = reactive<DefaultModelSlots>({});

const testResult = reactive<Record<string, { ok: boolean; reply?: string; model?: string; dimensions?: number; relevanceScore?: number; error?: string } | null>>({
  chat: null,
  embedding: null,
  rerank: null,
  queryRewrite: null,
});

const languageOptions = computed(() => modelOptions.language);
const embeddingOptions = computed(() => modelOptions.embedding);
const rerankOptions = computed(() => modelOptions.rerank);

function modelOptionLabel(option: ModelOption) {
  const modelName = option.displayName || option.modelId || option.name;
  const provider = option.provider?.displayName || option.provider?.providerTypeDisplayName;
  return provider ? `${modelName} · ${provider}` : modelName;
}

function defaultSlotName(type: ModelType) {
  if (type === "language") return defaultSlots.languageModelName || "";
  if (type === "embedding") return defaultSlots.embeddingModelName || "";
  return defaultSlots.rerankModelName || "";
}

function findModelOption(type: ModelType, name: string) {
  if (!name) return null;
  return modelOptions[type].find((option) => option.name === name) || null;
}

function defaultOptionLabel(type: ModelType, fallback: string) {
  const slotName = defaultSlotName(type);
  const option = findModelOption(type, slotName);
  return option ? `使用 AI Foundation 默认：${modelOptionLabel(option)}` : fallback;
}

function selectedModelHint(type: ModelType, name: string, emptyHint: string) {
  if (!name) return emptyHint;
  const option = findModelOption(type, name);
  if (!option) return "当前保存的模型未出现在可用列表中，请检查 AI Foundation 配置";
  const modelId = option.modelId ? `供应商模型 ID：${option.modelId}` : "";
  const features = option.features?.length ? `能力：${option.features.join(", ")}` : "";
  return [modelId, features].filter(Boolean).join("；") || "已选择 AI Foundation 模型";
}

function unknownSelectedOption(type: ModelType, name: string) {
  return !!name && !findModelOption(type, name);
}

function openAiFoundationDefaults() {
  window.location.href = "/console/ai-foundation/defaults";
}

function getConnectionStatus(model: string): "connected" | "error" | "testing" | "configured" {
  const tr = testResult[model];
  if (testing[model]) return "testing";
  if (tr?.ok) return "connected";
  if (tr && !tr.ok) return "error";
  return "configured";
}

function statusDotClass(model: string) {
  const s = getConnectionStatus(model);
  return {
    connected: "ai-dot-green",
    error: "ai-dot-red",
    testing: "ai-dot-blue",
    configured: "ai-dot-green",
  }[s];
}

function statusLabel(model: string) {
  const s = getConnectionStatus(model);
  return {
    connected: "已连接",
    error: "连接失败",
    testing: "测试中...",
    configured: "AI Foundation",
  }[s];
}

async function saveModel(model: string) {
  saving[model] = true;
  try {
    const fields: Record<string, any> = {};
    for (const key of MODEL_FIELDS[model]) {
      fields[key] = (form as any)[key];
    }
    let allConfig: Record<string, any> = {};
    try {
      const resp = await fetch(CONFIG_API);
      if (resp.ok) allConfig = await resp.json();
    } catch {}
    allConfig.models = { ...allConfig.models, ...fields };
    const resp = await fetch(CONFIG_API + "/save", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(allConfig),
    });
    const data = await resp.json();
    if (data.saved) {
      Toast.success("保存成功");
    } else {
      Toast.error("保存失败: " + (data.error || ""));
    }
  } catch (e: any) {
    Toast.error("保存失败: " + e.message);
  } finally {
    saving[model] = false;
  }
}

async function testChat() {
  testing.chat = true;
  testResult.chat = null;
  try {
    const resp = await fetch(CONFIG_API + "/test-model", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ model: form.aiFoundationChatModelName }),
    });
    const data = await resp.json();
    testResult.chat = { ok: data.connected, reply: data.reply, model: data.model, error: data.error };
  } catch (e: any) {
    testResult.chat = { ok: false, error: e.message };
  } finally {
    testing.chat = false;
  }
}

async function testEmbedding() {
  testing.embedding = true;
  testResult.embedding = null;
  try {
    const resp = await fetch(CONFIG_API + "/test-embedding", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        model: form.aiFoundationEmbeddingModelName,
        dimensions: form.embeddingDimensions,
      }),
    });
    const data = await resp.json();
    testResult.embedding = { ok: data.connected, model: data.model, dimensions: data.dimensions, error: data.error };
  } catch (e: any) {
    testResult.embedding = { ok: false, error: e.message };
  } finally {
    testing.embedding = false;
  }
}

async function testRerank() {
  testing.rerank = true;
  testResult.rerank = null;
  try {
    const resp = await fetch(CONFIG_API + "/test-rerank", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ model: form.aiFoundationRerankModelName }),
    });
    const data = await resp.json();
    testResult.rerank = { ok: data.connected, model: data.model, relevanceScore: data.relevanceScore, error: data.error };
  } catch (e: any) {
    testResult.rerank = { ok: false, error: e.message };
  } finally {
    testing.rerank = false;
  }
}

async function testQueryRewrite() {
  testing.queryRewrite = true;
  testResult.queryRewrite = null;
  try {
    const resp = await fetch(CONFIG_API + "/test-query-rewrite", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ model: form.aiFoundationQueryRewriteModelName || form.aiFoundationChatModelName }),
    });
    const data = await resp.json();
    testResult.queryRewrite = { ok: data.connected, model: data.model, reply: data.reply, error: data.error };
  } catch (e: any) {
    testResult.queryRewrite = { ok: false, error: e.message };
  } finally {
    testing.queryRewrite = false;
  }
}

onMounted(async () => {
  await Promise.all([loadConfig(), loadAiFoundationModels()]);
});

async function loadConfig() {
  try {
    const resp = await fetch(CONFIG_API);
    if (resp.ok) {
      const data = await resp.json();
      const m = data.models || {};
      Object.keys(form).forEach((k) => {
        if (m[k] !== undefined) (form as any)[k] = m[k];
      });
    }
  } catch {}
}

async function loadAiFoundationModels() {
  modelOptionsLoading.value = true;
  modelOptionsError.value = "";
  try {
    const [language, embedding, rerank, slots] = await Promise.all([
      fetchModelOptions("language"),
      fetchModelOptions("embedding"),
      fetchModelOptions("rerank"),
      fetchDefaultModelSlots(),
    ]);
    modelOptions.language = language;
    modelOptions.embedding = embedding;
    modelOptions.rerank = rerank;
    Object.assign(defaultSlots, slots);
  } catch (e: any) {
    modelOptionsError.value = "无法读取 AI Foundation 模型列表：" + (e?.message || "未知错误");
  } finally {
    modelOptionsLoading.value = false;
  }
}

async function fetchModelOptions(type: ModelType) {
  const params = new URLSearchParams({
    modelType: type,
    available: "true",
    enabled: "true",
  });
  const resp = await fetch(`${AI_FOUNDATION_API}/model-options?${params.toString()}`);
  if (!resp.ok) {
    throw new Error(`模型列表接口返回 HTTP ${resp.status}`);
  }
  return (await resp.json()) as ModelOption[];
}

async function fetchDefaultModelSlots() {
  const resp = await fetch(`${AI_FOUNDATION_API}/default-model-slots`);
  if (!resp.ok) {
    throw new Error(`默认模型接口返回 HTTP ${resp.status}`);
  }
  return (await resp.json()) as DefaultModelSlots;
}
</script>

<style scoped>
/* ===== 双栏卡片网格 ===== */

.ai-source-panel {
  margin-bottom: 18px;
  padding: 16px 18px;
  border: 1px solid #e5e7eb;
  border-radius: 14px;
  background: #ffffff;
}

.ai-source-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.ai-source-title {
  font-size: 15px;
  font-weight: 700;
  color: #111827;
}

.ai-source-desc {
  margin-top: 4px;
  font-size: 13px;
  color: #6b7280;
}

.ai-source-select {
  width: 220px;
  flex-shrink: 0;
}

.ai-source-models {
  margin-top: 14px;
}

/* ===== 双栏卡片网格 ===== */
.ai-model-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 22px;
}

/* ===== 模型卡片 ===== */
.ai-model-card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 18px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
  overflow: hidden;
  min-width: 0;
}

.ai-model-card-header {
  padding: 20px 22px;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: linear-gradient(180deg, #ffffff, #fbfcfe);
}

.ai-model-title-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.ai-model-icon {
  width: 42px;
  height: 42px;
  border-radius: 14px;
  background: #f3f4f6;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  flex-shrink: 0;
}

.ai-model-title {
  font-size: 18px;
  font-weight: 700;
  color: #111827;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ai-model-subtitle {
  margin-top: 4px;
  font-size: 13px;
  color: #8a94a6;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ai-model-status {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

/* ===== 状态芯片 ===== */
.ai-status-chip {
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  background: #f3f4f6;
  color: #111827;
  font-size: 12px;
  font-weight: 600;
  border: 1px solid #e5e7eb;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.ai-status-chip.required {
  background: #111827;
  color: #fff;
  border-color: #111827;
}

.ai-status-chip.enabled {
  background: #f3f4f6;
  color: #111827;
}

.ai-check-mark {
  width: 15px;
  height: 15px;
  border-radius: 4px;
  background: #111827;
  color: #fff;
  font-size: 11px;
  line-height: 15px;
  text-align: center;
  font-weight: 700;
}

/* 状态圆点颜色 */
.ai-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
  background: #111827;
}

.ai-dot-green  { background: #22c55e; }
.ai-dot-red    { background: #ef4444; }
.ai-dot-blue   { background: #3b82f6; }
.ai-dot-yellow { background: #eab308; }
.ai-dot-gray   { background: #d1d5db; }

/* ===== 卡片内容区 ===== */
.ai-model-card-body {
  padding: 22px;
}

.ai-form-grid {
  display: grid;
  gap: 18px;
}

/* ===== 表单字段（仅 Model 页特有组件） ===== */
.ai-input-group {
  display: flex;
  width: 100%;
}

.ai-input-group .ai-input {
  border: 1px solid #cbd5e1;
  border-right: none;
  border-radius: 10px 0 0 10px;
  background: #fff;
  flex: 1;
  min-width: 0;
}

.ai-input-addon {
  height: 46px;
  padding: 0 16px;
  border: 1px solid #cbd5e1;
  border-left: none;
  border-radius: 0 10px 10px 0;
  background: #f9fafb;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #4b5563;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s ease;
}

.ai-input-addon:hover {
  color: #111827;
  background: #f3f4f6;
}

.ai-helper-text {
  margin-top: 7px;
  font-size: 12px;
  color: #8a94a6;
  line-height: 1.5;
}

/* 两列子布局（Embedding 的 模型+维度） */
.ai-two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

/* 向量维度步进器 */
.ai-input[type="number"] {
  border: 1px solid #cbd5e1 !important;
  background: #fff !important;
  -webkit-appearance: none;
  -moz-appearance: textfield;
  appearance: none;
}

.ai-input[type="number"]:focus {
  border-color: #111827 !important;
}

/* ===== Switch 开关（Model 页特有） ===== */
.ai-switch {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: #4b5563;
  font-weight: 700;
  cursor: pointer;
  user-select: none;
}

.ai-switch-track {
  width: 38px;
  height: 22px;
  border-radius: 999px;
  background: #111827;
  position: relative;
  flex-shrink: 0;
}

.ai-switch-track::after {
  content: "";
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  position: absolute;
  top: 3px;
  right: 3px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.18);
}

.ai-switch-track.off {
  background: #d1d5db;
}

.ai-switch-track.off::after {
  right: auto;
  left: 3px;
}

/* ===== 测试反馈 ===== */
.ai-test-feedback {
  margin-top: 14px;
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.5;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.ai-test-feedback.success {
  background: #f0fdf4;
  color: #166534;
  border: 1px solid #bbf7d0;
}

.ai-test-feedback.error {
  background: #fef2f2;
  color: #991b1b;
  border: 1px solid #fecaca;
}

/* ===== 高级模型说明 ===== */
.ai-advanced-note {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 18px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
  padding: 18px 22px;
  margin-bottom: 18px;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  color: #4b5563;
  font-size: 14px;
  line-height: 1.7;
}

.ai-note-icon {
  width: 28px;
  height: 28px;
  border-radius: 999px;
  background: #f3f4f6;
  color: #111827;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-weight: 700;
  font-size: 14px;
}

/* ===== 响应式 ===== */
@media (max-width: 1200px) {
  .ai-model-grid { grid-template-columns: 1fr; }
  .ai-intro-card { align-items: flex-start; flex-direction: column; }
  .ai-intro-meta { justify-content: flex-start; }
}

@media (max-width: 768px) {
  .ai-model-card-header { align-items: flex-start; flex-direction: column; }
  .ai-model-status { justify-content: flex-start; }
  .ai-two-col { grid-template-columns: 1fr; }

}

@media (max-width: 640px) {
  .ai-source-row {
    align-items: stretch;
    flex-direction: column;
  }
  .ai-source-row :deep(.v-button) {
    width: 100%;
    justify-content: center;
  }
  .ai-model-card {
    border-radius: 12px;
  }
  .ai-model-card-header,
  .ai-model-card-body {
    padding: 16px;
  }
  .ai-model-title-wrap {
    width: 100%;
  }
  .ai-model-title-wrap > div:last-child {
    min-width: 0;
  }
  .ai-model-title {
    font-size: 16px;
  }
  .ai-model-subtitle {
    white-space: normal;
    overflow: visible;
  }
  .ai-model-status {
    width: 100%;
  }
  .ai-status-chip {
    max-width: 100%;
    height: auto;
    min-height: 28px;
    white-space: normal;
  }
  .ai-card-actions {
    flex-wrap: wrap;
  }
}
</style>
