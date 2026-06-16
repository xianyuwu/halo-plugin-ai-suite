<template>
  <div class="ai-model-page">
    <div class="ai-content">
      <!-- ===== 核心模型区 ===== -->
      <div class="ai-section-block">
        <div class="ai-section-heading">
          <h2>核心模型</h2>
          <span class="ai-section-tag ai-tag-required">必配</span>
        </div>

        <div class="ai-model-grid">
          <!-- 对话模型卡片 -->
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
                <span class="ai-status-chip required">必填</span>
                <span class="ai-status-chip">
                  <span class="ai-dot" :class="statusDotClass('chat')"></span>
                  {{ statusLabel('chat') }}
                </span>
              </div>
            </div>
            <div class="ai-model-card-body">
              <div class="ai-form-grid">
                <div class="ai-form-field">
                  <label class="ai-field-label">厂商预设</label>
                  <select class="ai-input ai-select" v-model="vendorPreset.chat">
                    <option value="">选择厂商快速填充...</option>
                    <option v-for="v in CHAT_VENDORS" :key="v.name" :value="v.name">{{ v.name }}</option>
                  </select>
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">Base URL</label>
                  <input class="ai-input" v-model="form.chatBaseUrl" placeholder="https://api.deepseek.com/v1" />
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">
                    API Key
                    <span class="ai-field-hint">{{ form.chatApiKey ? '已加密保存' : '' }}</span>
                  </label>
                  <div class="ai-input-group">
                    <input class="ai-input" v-model="form.chatApiKey" :type="showKey.chat ? 'text' : 'password'" placeholder="sk-..." />
                    <button class="ai-input-addon" @click="showKey.chat = !showKey.chat" tabindex="-1">
                      {{ showKey.chat ? '隐藏' : '显示' }}
                    </button>
                  </div>
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">模型名称</label>
                  <input class="ai-input" v-model="form.chatModel" placeholder="deepseek-chat" />
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

          <!-- Embedding 模型卡片 -->
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
                <span class="ai-status-chip required">必填</span>
                <span class="ai-status-chip">
                  <span class="ai-dot" :class="statusDotClass('embedding')"></span>
                  {{ statusLabel('embedding') }}
                </span>
              </div>
            </div>
            <div class="ai-model-card-body">
              <div class="ai-form-grid">
                <div class="ai-form-field">
                  <label class="ai-field-label">厂商预设</label>
                  <select class="ai-input ai-select" v-model="vendorPreset.embedding">
                    <option value="">选择厂商快速填充...</option>
                    <option v-for="v in EMBEDDING_VENDORS" :key="v.name" :value="v.name">{{ v.name }}</option>
                  </select>
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">Base URL</label>
                  <input class="ai-input" v-model="form.embeddingBaseUrl" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" />
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">
                    API Key
                    <span class="ai-field-hint">{{ form.embeddingApiKey ? '已加密保存' : '' }}</span>
                  </label>
                  <div class="ai-input-group">
                    <input class="ai-input" v-model="form.embeddingApiKey" :type="showKey.embedding ? 'text' : 'password'" />
                    <button class="ai-input-addon" @click="showKey.embedding = !showKey.embedding" tabindex="-1">
                      {{ showKey.embedding ? '隐藏' : '显示' }}
                    </button>
                  </div>
                </div>
                <div class="ai-two-col">
                  <div class="ai-form-field">
                    <label class="ai-field-label">模型名称</label>
                    <input class="ai-input" v-model="form.embeddingModel" placeholder="text-embedding-v3" />
                  </div>
                  <div class="ai-form-field">
                    <label class="ai-field-label">向量维度</label>
                    <input class="ai-input" v-model.number="form.embeddingDimensions" type="number" min="256" max="4096" step="128" />
                  </div>
                </div>
              </div>
              <div v-if="testResult.embedding" class="ai-test-feedback" :class="testResult.embedding.ok ? 'success' : 'error'">
                <template v-if="testResult.embedding.ok"><RiCheckLine /> 连接成功 — 模型: {{ testResult.embedding.model }}，维度: {{ testResult.embedding.dimensions }}</template>
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

      <!-- ===== 高级模型区 ===== -->
      <div class="ai-section-block">
        <div class="ai-section-heading">
          <h2>高级模型</h2>
          <span class="ai-section-tag ai-tag-optional">可选</span>
        </div>

        <div class="ai-advanced-note">
          <div class="ai-note-icon">i</div>
          <div>
            高级模型用于进一步提升检索质量和问答准确率。Rerank 可优化召回结果排序；查询改写可将用户问题改写为更适合检索的表达。
          </div>
        </div>

        <div class="ai-model-grid">
          <!-- Rerank 模型卡片 -->
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
                  <label class="ai-field-label">厂商预设</label>
                  <select class="ai-input ai-select" v-model="vendorPreset.rerank">
                    <option value="">选择厂商快速填充...</option>
                    <option v-for="v in RERANK_VENDORS" :key="v.name" :value="v.name">{{ v.name }}</option>
                  </select>
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">Base URL</label>
                  <input class="ai-input" v-model="form.rerankBaseUrl" placeholder="https://api.siliconflow.cn/v1" />
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">
                    API Key
                    <span class="ai-field-hint">{{ form.rerankApiKey ? '已加密保存' : '' }}</span>
                  </label>
                  <div class="ai-input-group">
                    <input class="ai-input" v-model="form.rerankApiKey" :type="showKey.rerank ? 'text' : 'password'" />
                    <button class="ai-input-addon" @click="showKey.rerank = !showKey.rerank" tabindex="-1">
                      {{ showKey.rerank ? '隐藏' : '显示' }}
                    </button>
                  </div>
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">模型名称</label>
                  <input class="ai-input" v-model="form.rerankModel" placeholder="BAAI/bge-reranker-v2-m3" />
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

          <!-- 查询改写模型卡片 -->
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
                  <label class="ai-field-label">厂商预设</label>
                  <select class="ai-input ai-select" v-model="vendorPreset.queryRewrite">
                    <option value="">选择厂商快速填充...</option>
                    <option v-for="v in QUERY_REWRITE_VENDORS" :key="v.name" :value="v.name">{{ v.name }}</option>
                  </select>
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">Base URL <span class="ai-label-hint">留空则复用对话模型</span></label>
                  <input class="ai-input" v-model="form.queryRewriteBaseUrl" placeholder="https://open.bigmodel.cn/api/paas/v4/" />
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">
                    API Key <span class="ai-label-hint">留空则复用对话模型 Key</span>
                    <span class="ai-field-hint">{{ form.queryRewriteApiKey ? '已加密保存' : '' }}</span>
                  </label>
                  <div class="ai-input-group">
                    <input class="ai-input" v-model="form.queryRewriteApiKey" :type="showKey.queryRewrite ? 'text' : 'password'" />
                    <button class="ai-input-addon" @click="showKey.queryRewrite = !showKey.queryRewrite" tabindex="-1">
                      {{ showKey.queryRewrite ? '隐藏' : '显示' }}
                    </button>
                  </div>
                </div>
                <div class="ai-form-field">
                  <label class="ai-field-label">模型名称</label>
                  <input class="ai-input" v-model="form.queryRewriteModel" placeholder="glm-4-flash" />
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
import { reactive, ref, watch, onMounted } from "vue";
import { Toast , VButton, VSpace} from "@halo-dev/components";
import RiChatSmileLine from "~icons/ri/chat-smile-line";
import RiStackLine from "~icons/ri/stack-line";
import RiSortDesc from "~icons/ri/sort-desc";
import RiSearchAiLine from "~icons/ri/search-ai-line";
import RiCheckLine from "~icons/ri/check-line";
import RiCloseLine from "~icons/ri/close-line";

// ===== 常量 =====
const CONFIG_API = "/apis/console.api.ai-suite.halo.run/v1alpha1/config";

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

// 厂商预设下拉的当前选中值
const vendorPreset = reactive({ chat: "", embedding: "", rerank: "", queryRewrite: "" });

// ===== 状态指示器 =====
function getConnectionStatus(model: string): "connected" | "error" | "testing" | "configured" | "empty" {
  const tr = testResult[model];
  if (testing[model]) return "testing";
  if (tr?.ok) return "connected";
  if (tr && !tr.ok) return "error";
  const prefix = model === "queryRewrite" ? "queryRewrite" : model;
  const keyField = (form as any)[prefix + "BaseUrl"] || (form as any)[prefix + "ApiKey"];
  if (keyField) return "configured";
  return "empty";
}

// 状态圆点颜色
function statusDotClass(model: string) {
  const s = getConnectionStatus(model);
  return {
    connected: "ai-dot-green",
    error: "ai-dot-red",
    testing: "ai-dot-blue",
    configured: "ai-dot-green",
    empty: "ai-dot-gray",
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

// ===== 厂商预设：监听下拉变化，自动填充对应字段 =====
watch(() => vendorPreset.chat, (name) => {
  if (!name) return;
  const v = CHAT_VENDORS.find(x => x.name === name);
  if (v) { form.chatBaseUrl = v.baseUrl; form.chatModel = v.model; }
});
watch(() => vendorPreset.embedding, (name) => {
  if (!name) return;
  const v = EMBEDDING_VENDORS.find(x => x.name === name);
  if (v) { form.embeddingBaseUrl = v.baseUrl; form.embeddingModel = v.model; if (v.dimensions) form.embeddingDimensions = v.dimensions; }
});
watch(() => vendorPreset.rerank, (name) => {
  if (!name) return;
  const v = RERANK_VENDORS.find(x => x.name === name);
  if (v) { form.rerankBaseUrl = v.baseUrl; form.rerankModel = v.model; }
});
watch(() => vendorPreset.queryRewrite, (name) => {
  if (!name) return;
  const v = QUERY_REWRITE_VENDORS.find(x => x.name === name);
  if (v) { form.queryRewriteBaseUrl = v.baseUrl; form.queryRewriteModel = v.model; }
});

// ===== 独立保存 =====
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

// ===== 连通性测试 =====
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
/* ===== 双栏卡片网格 ===== */

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
</style>
