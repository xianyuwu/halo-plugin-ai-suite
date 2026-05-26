<template>
  <div class="model-config-page">
    <VPageHeader title="模型配置" />

    <div class="page-body">
      <!-- ===== 核心模型区 ===== -->
      <section class="config-section">
        <div class="section-header">
          <h3 class="section-title">核心模型</h3>
          <span class="badge badge-required">必配</span>
        </div>
        <div class="model-grid">
          <!-- 对话模型卡片 -->
          <VCard>
            <template #header>
              <div class="card-header">
                <div class="card-header-left">
                  <span class="header-icon">💬</span>
                  <span class="header-title">对话模型</span>
                  <span class="status-dot" :class="statusClass('chat')"></span>
                  <span class="status-text">{{ statusLabel('chat') }}</span>
                </div>
              </div>
            </template>
            <div class="card-form">
              <div class="ai-field">
                <label>厂商预设</label>
                <select v-model="vendorPreset.chat" @change="applyVendor('chat', $event)">
                  <option value="">选择厂商快速填充...</option>
                  <option v-for="v in CHAT_VENDORS" :key="v.name" :value="v.name">{{ v.name }}</option>
                </select>
              </div>
              <div class="ai-field">
                <label>Base URL</label>
                <input v-model="form.chatBaseUrl" placeholder="https://api.deepseek.com/v1" />
              </div>
              <div class="ai-field">
                <label>API Key</label>
                <div class="input-with-toggle">
                  <input v-model="form.chatApiKey" :type="showKey.chat ? 'text' : 'password'" placeholder="sk-..." />
                  <button class="toggle-vis" @click="showKey.chat = !showKey.chat" tabindex="-1">
                    {{ showKey.chat ? '隐藏' : '显示' }}
                  </button>
                </div>
              </div>
              <div class="ai-field">
                <label>模型名称</label>
                <input v-model="form.chatModel" placeholder="deepseek-chat" />
              </div>
            </div>
            <div class="card-actions">
              <VButton size="sm" :loading="testing.chat" @click="testChat">
                测试连通性
              </VButton>
              <VButton type="primary" size="sm" :loading="saving.chat" @click="saveModel('chat')">
                保存配置
              </VButton>
            </div>
            <div v-if="testResult.chat" class="test-feedback" :class="testResult.chat.ok ? 'success' : 'error'">
              {{ testResult.chat.ok ? '✓ 连接成功 (' + testResult.chat.reply + ')' : '✗ ' + testResult.chat.error }}
            </div>
          </VCard>

          <!-- Embedding 模型卡片 -->
          <VCard>
            <template #header>
              <div class="card-header">
                <div class="card-header-left">
                  <span class="header-icon">🧩</span>
                  <span class="header-title">Embedding 模型</span>
                  <span class="status-dot" :class="statusClass('embedding')"></span>
                  <span class="status-text">{{ statusLabel('embedding') }}</span>
                </div>
              </div>
            </template>
            <div class="card-form">
              <div class="ai-field">
                <label>厂商预设</label>
                <select v-model="vendorPreset.embedding" @change="applyVendor('embedding', $event)">
                  <option value="">选择厂商快速填充...</option>
                  <option v-for="v in EMBEDDING_VENDORS" :key="v.name" :value="v.name">{{ v.name }}</option>
                </select>
              </div>
              <div class="ai-field">
                <label>Base URL</label>
                <input v-model="form.embeddingBaseUrl" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" />
              </div>
              <div class="ai-field">
                <label>API Key</label>
                <div class="input-with-toggle">
                  <input v-model="form.embeddingApiKey" :type="showKey.embedding ? 'text' : 'password'" />
                  <button class="toggle-vis" @click="showKey.embedding = !showKey.embedding" tabindex="-1">
                    {{ showKey.embedding ? '隐藏' : '显示' }}
                  </button>
                </div>
              </div>
              <div class="ai-field">
                <label>模型名称</label>
                <input v-model="form.embeddingModel" placeholder="text-embedding-v3" />
              </div>
              <div class="ai-field">
                <label>向量维度</label>
                <input v-model.number="form.embeddingDimensions" type="number" min="256" max="4096" />
              </div>
            </div>
            <div class="card-actions">
              <VButton size="sm" :loading="testing.embedding" @click="testEmbedding">
                测试连通性
              </VButton>
              <VButton type="primary" size="sm" :loading="saving.embedding" @click="saveModel('embedding')">
                保存配置
              </VButton>
            </div>
            <div v-if="testResult.embedding" class="test-feedback" :class="testResult.embedding.ok ? 'success' : 'error'">
              {{ testResult.embedding.ok
                ? '✓ 连接成功 — 模型: ' + testResult.embedding.model + '，维度: ' + testResult.embedding.dimensions
                : '✗ ' + testResult.embedding.error }}
            </div>
          </VCard>
        </div>
      </section>

      <!-- ===== 高级模型区 ===== -->
      <section class="config-section">
        <div class="section-header">
          <h3 class="section-title">高级模型</h3>
          <span class="badge badge-optional">可选</span>
        </div>
        <div class="model-grid">
          <!-- Rerank 模型卡片 -->
          <VCard>
            <template #header>
              <div class="card-header">
                <div class="card-header-left">
                  <span class="header-icon">🎯</span>
                  <span class="header-title">Rerank 重排序</span>
                  <label class="enable-toggle">
                    <input type="checkbox" v-model="form.rerankEnabled" />
                    <span>{{ form.rerankEnabled ? '已启用' : '未启用' }}</span>
                  </label>
                </div>
                <span v-if="form.rerankEnabled" class="status-dot" :class="statusClass('rerank')"></span>
                <span v-if="form.rerankEnabled" class="status-text">{{ statusLabel('rerank') }}</span>
              </div>
            </template>
            <template v-if="form.rerankEnabled">
              <div class="card-form">
                <div class="ai-field">
                  <label>厂商预设</label>
                  <select v-model="vendorPreset.rerank" @change="applyVendor('rerank', $event)">
                    <option value="">选择厂商快速填充...</option>
                    <option v-for="v in RERANK_VENDORS" :key="v.name" :value="v.name">{{ v.name }}</option>
                  </select>
                </div>
                <div class="ai-field">
                  <label>Base URL</label>
                  <input v-model="form.rerankBaseUrl" placeholder="https://api.siliconflow.cn/v1" />
                </div>
                <div class="ai-field">
                  <label>API Key</label>
                  <div class="input-with-toggle">
                    <input v-model="form.rerankApiKey" :type="showKey.rerank ? 'text' : 'password'" />
                    <button class="toggle-vis" @click="showKey.rerank = !showKey.rerank" tabindex="-1">
                      {{ showKey.rerank ? '隐藏' : '显示' }}
                    </button>
                  </div>
                </div>
                <div class="ai-field">
                  <label>模型名称</label>
                  <input v-model="form.rerankModel" placeholder="BAAI/bge-reranker-v2-m3" />
                </div>
              </div>
              <div class="card-actions">
                <VButton size="sm" :loading="testing.rerank" @click="testRerank">
                  测试连通性
                </VButton>
                <VButton type="primary" size="sm" :loading="saving.rerank" @click="saveModel('rerank')">
                  保存配置
                </VButton>
              </div>
              <div v-if="testResult.rerank" class="test-feedback" :class="testResult.rerank.ok ? 'success' : 'error'">
                {{ testResult.rerank.ok
                  ? '✓ 连接成功 — 相关度: ' + testResult.rerank.relevanceScore
                  : '✗ ' + testResult.rerank.error }}
              </div>
            </template>
          </VCard>

          <!-- 查询改写模型卡片 -->
          <VCard>
            <template #header>
              <div class="card-header">
                <div class="card-header-left">
                  <span class="header-icon">🔍</span>
                  <span class="header-title">查询改写</span>
                  <label class="enable-toggle">
                    <input type="checkbox" v-model="form.queryRewriteEnabled" />
                    <span>{{ form.queryRewriteEnabled ? '已启用' : '未启用' }}</span>
                  </label>
                </div>
                <span v-if="form.queryRewriteEnabled" class="status-dot" :class="statusClass('queryRewrite')"></span>
                <span v-if="form.queryRewriteEnabled" class="status-text">{{ statusLabel('queryRewrite') }}</span>
              </div>
            </template>
            <template v-if="form.queryRewriteEnabled">
              <div class="card-form">
                <div class="ai-field">
                  <label>厂商预设</label>
                  <select v-model="vendorPreset.queryRewrite" @change="applyVendor('queryRewrite', $event)">
                    <option value="">选择厂商快速填充...</option>
                    <option v-for="v in QUERY_REWRITE_VENDORS" :key="v.name" :value="v.name">{{ v.name }}</option>
                  </select>
                </div>
                <div class="ai-field">
                  <label>Base URL</label>
                  <input v-model="form.queryRewriteBaseUrl" placeholder="https://open.bigmodel.cn/api/paas/v4/" />
                  <span class="ai-help">留空则复用对话模型配置</span>
                </div>
                <div class="ai-field">
                  <label>API Key</label>
                  <div class="input-with-toggle">
                    <input v-model="form.queryRewriteApiKey" :type="showKey.queryRewrite ? 'text' : 'password'" />
                    <button class="toggle-vis" @click="showKey.queryRewrite = !showKey.queryRewrite" tabindex="-1">
                      {{ showKey.queryRewrite ? '隐藏' : '显示' }}
                    </button>
                  </div>
                  <span class="ai-help">留空则复用对话模型 Key</span>
                </div>
                <div class="ai-field">
                  <label>模型名称</label>
                  <input v-model="form.queryRewriteModel" placeholder="glm-4-flash" />
                </div>
              </div>
              <div class="card-actions">
                <VButton size="sm" :loading="testing.queryRewrite" @click="testQueryRewrite">
                  测试连通性
                </VButton>
                <VButton type="primary" size="sm" :loading="saving.queryRewrite" @click="saveModel('queryRewrite')">
                  保存配置
                </VButton>
              </div>
              <div v-if="testResult.queryRewrite" class="test-feedback" :class="testResult.queryRewrite.ok ? 'success' : 'error'">
                {{ testResult.queryRewrite.ok
                  ? '✓ 连接成功 — ' + testResult.queryRewrite.reply
                  : '✗ ' + testResult.queryRewrite.error }}
              </div>
            </template>
          </VCard>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from "vue";
import { VPageHeader, VButton, VCard, Toast } from "@halo-dev/components";

// ===== 常量 =====
const CONFIG_API = "/apis/console.api.ai-assistant.halo.run/v1alpha1/config";

// 每个模型包含的配置字段，用于独立保存
const MODEL_FIELDS: Record<string, string[]> = {
  chat: ["chatBaseUrl", "chatApiKey", "chatModel"],
  embedding: ["embeddingBaseUrl", "embeddingApiKey", "embeddingModel", "embeddingDimensions"],
  rerank: ["rerankEnabled", "rerankBaseUrl", "rerankApiKey", "rerankModel"],
  queryRewrite: ["queryRewriteEnabled", "queryRewriteBaseUrl", "queryRewriteApiKey", "queryRewriteModel"],
};

// 厂商预设 —— 选择后自动填充对应字段
interface VendorPreset {
  name: string;
  baseUrl: string;
  model: string;
  dimensions?: number;
}

const CHAT_VENDORS: VendorPreset[] = [
  { name: "DeepSeek", baseUrl: "https://api.deepseek.com/v1", model: "deepseek-chat" },
  { name: "阿里通义千问", baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1", model: "qwen-plus" },
  { name: "智谱 GLM", baseUrl: "https://open.bigmodel.cn/api/paas/v4/", model: "glm-4-flash" },
  { name: "Moonshot", baseUrl: "https://api.moonshot.cn/v1", model: "moonshot-v1-8k" },
  { name: "硅基流动", baseUrl: "https://api.siliconflow.cn/v1", model: "deepseek-ai/DeepSeek-V3" },
  { name: "OpenAI", baseUrl: "https://api.openai.com/v1", model: "gpt-4o" },
];

const EMBEDDING_VENDORS: VendorPreset[] = [
  { name: "阿里通义千问", baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1", model: "text-embedding-v3", dimensions: 1024 },
  { name: "智谱 GLM", baseUrl: "https://open.bigmodel.cn/api/paas/v4/", model: "embedding-2", dimensions: 1024 },
  { name: "硅基流动", baseUrl: "https://api.siliconflow.cn/v1", model: "BAAI/bge-large-zh-v1.5", dimensions: 1024 },
];

const RERANK_VENDORS: VendorPreset[] = [
  { name: "硅基流动", baseUrl: "https://api.siliconflow.cn/v1", model: "BAAI/bge-reranker-v2-m3" },
  { name: "阿里通义千问", baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1", model: "gte-rerank" },
];

const QUERY_REWRITE_VENDORS: VendorPreset[] = [
  { name: "智谱 GLM", baseUrl: "https://open.bigmodel.cn/api/paas/v4/", model: "glm-4-flash" },
  { name: "DeepSeek", baseUrl: "https://api.deepseek.com/v1", model: "deepseek-chat" },
  { name: "阿里通义千问", baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1", model: "qwen-turbo" },
];

// ===== 表单状态 =====
const form = reactive({
  chatBaseUrl: "",
  chatApiKey: "",
  chatModel: "",
  embeddingBaseUrl: "",
  embeddingApiKey: "",
  embeddingModel: "",
  embeddingDimensions: 1024,
  rerankEnabled: false,
  rerankBaseUrl: "",
  rerankApiKey: "",
  rerankModel: "",
  queryRewriteEnabled: false,
  queryRewriteBaseUrl: "",
  queryRewriteApiKey: "",
  queryRewriteModel: "",
});

const saving = reactive({ chat: false, embedding: false, rerank: false, queryRewrite: false });
const testing = reactive({ chat: false, embedding: false, rerank: false, queryRewrite: false });
const showKey = reactive({ chat: false, embedding: false, rerank: false, queryRewrite: false });

const testResult = reactive<Record<string, { ok: boolean; reply?: string; model?: string; dimensions?: number; relevanceScore?: number; error?: string } | null>>({
  chat: null,
  embedding: null,
  rerank: null,
  queryRewrite: null,
});

// 厂商预设下拉的当前选中值（纯 UI 状态，不持久化）
const vendorPreset = reactive({ chat: "", embedding: "", rerank: "", queryRewrite: "" });

// ===== 状态指示器逻辑 =====
function getConnectionStatus(model: string): "connected" | "error" | "testing" | "configured" | "empty" {
  const tr = testResult[model];
  if (testing[model]) return "testing";
  if (tr?.ok) return "connected";
  if (tr && !tr.ok) return "error";

  // 未测试过，检查是否有填写
  const prefix = model === "queryRewrite" ? "queryRewrite" : model;
  const keyField = (form as any)[prefix + "BaseUrl"] || (form as any)[prefix + "ApiKey"];
  if (keyField) return "configured";
  return "empty";
}

function statusClass(model: string) {
  const s = getConnectionStatus(model);
  return {
    connected: "dot-green",
    error: "dot-red",
    testing: "dot-blue",
    configured: "dot-yellow",
    empty: "dot-gray",
  }[s];
}

function statusLabel(model: string) {
  const s = getConnectionStatus(model);
  return {
    connected: "已连接",
    error: "连接失败",
    testing: "测试中...",
    configured: "已填写",
    empty: "未配置",
  }[s];
}

// ===== 厂商预设填充 =====
function applyVendor(model: string, event: Event) {
  const name = (event.target as HTMLSelectElement).value;
  if (!name) return;

  let vendors: VendorPreset[];
  if (model === "chat") vendors = CHAT_VENDORS;
  else if (model === "embedding") vendors = EMBEDDING_VENDORS;
  else if (model === "rerank") vendors = RERANK_VENDORS;
  else vendors = QUERY_REWRITE_VENDORS;

  const vendor = vendors.find((v) => v.name === name);
  if (!vendor) return;

  const prefix = model === "queryRewrite" ? "queryRewrite" : model;
  (form as any)[prefix + "BaseUrl"] = vendor.baseUrl;
  (form as any)[prefix + "Model"] = vendor.model;
  if (vendor.dimensions !== undefined && model === "embedding") {
    (form as any).embeddingDimensions = vendor.dimensions;
  }
}

// ===== 独立保存 =====
async function saveModel(model: string) {
  saving[model] = true;
  try {
    // 收集当前模型的字段
    const fields: Record<string, any> = {};
    for (const key of MODEL_FIELDS[model]) {
      fields[key] = (form as any)[key];
    }

    // 读取全量配置，保证其他模型 / group 不丢失
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

// ===== 连通性测试（与原逻辑一致） =====
async function testChat() {
  testing.chat = true;
  testResult.chat = null;
  try {
    const resp = await fetch(CONFIG_API + "/test-model", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ baseUrl: form.chatBaseUrl, apiKey: form.chatApiKey, model: form.chatModel }),
    });
    const data = await resp.json();
    testResult.chat = { ok: data.connected, reply: data.reply, error: data.error };
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
      body: JSON.stringify({ baseUrl: form.embeddingBaseUrl, apiKey: form.embeddingApiKey, model: form.embeddingModel, dimensions: form.embeddingDimensions }),
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
      body: JSON.stringify({ baseUrl: form.rerankBaseUrl, apiKey: form.rerankApiKey, model: form.rerankModel }),
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
      body: JSON.stringify({ baseUrl: form.queryRewriteBaseUrl, apiKey: form.queryRewriteApiKey, model: form.queryRewriteModel }),
    });
    const data = await resp.json();
    testResult.queryRewrite = { ok: data.connected, model: data.model, reply: data.reply, error: data.error };
  } catch (e: any) {
    testResult.queryRewrite = { ok: false, error: e.message };
  } finally {
    testing.queryRewrite = false;
  }
}

// ===== 初始化 =====
onMounted(async () => {
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
});
</script>

<style scoped>
.model-config-page {
  max-width: 960px;
}

/* ===== 分区标题 ===== */
.config-section {
  margin-bottom: 24px;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #374151;
}

.badge {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 10px;
  font-weight: 500;
}

.badge-required {
  background: #fee2e2;
  color: #991b1b;
}

.badge-optional {
  background: #f3f4f6;
  color: #6b7280;
}

/* ===== 双栏网格 ===== */
.model-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

@media (max-width: 800px) {
  .model-grid {
    grid-template-columns: 1fr;
  }
}

/* ===== 卡片头部 ===== */
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.card-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-icon {
  font-size: 16px;
}

.header-title {
  font-size: 14px;
  font-weight: 600;
  color: #374151;
}

/* ===== 状态指示 ===== */
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.dot-green  { background: #22c55e; }
.dot-red    { background: #ef4444; }
.dot-blue   { background: #3b82f6; }
.dot-yellow { background: #eab308; }
.dot-gray   { background: #d1d5db; }

.status-text {
  font-size: 12px;
  color: #6b7280;
}

/* ===== 启用开关（高级模型卡片） ===== */
.enable-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  color: #6b7280;
  user-select: none;
}

.enable-toggle input[type="checkbox"] {
  width: 16px;
  height: 16px;
  accent-color: #4CCBA0;
  cursor: pointer;
}

/* ===== 表单 ===== */
.card-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ai-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ai-field label {
  font-size: 13px;
  font-weight: 500;
  color: #374151;
}

.ai-field input,
.ai-field select {
  padding: 7px 10px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  outline: none;
  transition: border-color 0.2s;
  font-family: inherit;
}

.ai-field input:focus,
.ai-field select:focus {
  border-color: #4CCBA0;
}

.ai-help {
  font-size: 12px;
  color: #9ca3af;
}

/* ===== 密码输入 + 显示/隐藏 ===== */
.input-with-toggle {
  display: flex;
  gap: 0;
}

.input-with-toggle input {
  flex: 1;
  border-top-right-radius: 0;
  border-bottom-right-radius: 0;
  border-right: none;
}

.toggle-vis {
  padding: 0 10px;
  border: 1px solid #d9d9d9;
  border-left: none;
  border-radius: 0 4px 4px 0;
  background: #f9fafb;
  font-size: 12px;
  color: #6b7280;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s;
}

.toggle-vis:hover {
  background: #f3f4f6;
}

/* ===== 卡片底部操作行 ===== */
.card-actions {
  margin-top: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

/* ===== 测试反馈 ===== */
.test-feedback {
  margin-top: 8px;
  padding: 6px 10px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.5;
}

.test-feedback.success {
  background: #ecfdf5;
  color: #065f46;
}

.test-feedback.error {
  background: #fef2f2;
  color: #991b1b;
}
</style>
