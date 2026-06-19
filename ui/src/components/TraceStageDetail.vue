<template>
  <!-- Rerank 精排：阈值 + 通过状态 -->
  <template v-if="stage.name === 'rerank' && hasDocuments(stage)">
    <div class="ts-kv-list" style="margin-bottom: 8px;">
      <div class="ts-kv-row">
        <span class="ts-kv-key">分数阈值</span>
        <span class="ts-kv-val">{{ stage.data?.threshold ?? '-' }}</span>
      </div>
    </div>
    <table class="ts-doc-table">
      <thead>
        <tr>
          <th>#</th>
          <th>文章标题</th>
          <th>Chunk</th>
          <th>Rerank 分数</th>
          <th>状态</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(doc, di) in getDocuments(stage)" :key="di"
            :class="{ 'ts-row-filtered': doc.passed === false }">
          <td class="ts-col-idx">{{ di + 1 }}</td>
          <td class="ts-col-title">{{ doc.title || '未命名' }}</td>
          <td class="ts-col-chunk">#{{ doc.chunkIndex }}</td>
          <td class="ts-col-score">{{ formatScore(doc.score) }}</td>
          <td class="ts-col-passed">
            <span v-if="doc.passed" class="ts-passed-yes">通过</span>
            <span v-else class="ts-passed-no">低于阈值</span>
          </td>
        </tr>
      </tbody>
    </table>
  </template>

  <!-- 通用文档类阶段：混合检索 / 原查询兜底 / 跨语言检索 -->
  <template v-else-if="hasDocuments(stage)">
    <table class="ts-doc-table">
      <thead>
        <tr><th>#</th><th>文章标题</th><th>Chunk</th><th>分数</th></tr>
      </thead>
      <tbody>
        <tr v-for="(doc, di) in getDocuments(stage)" :key="di">
          <td class="ts-col-idx">{{ di + 1 }}</td>
          <td class="ts-col-title">{{ doc.title || '未命名' }}</td>
          <td class="ts-col-chunk">#{{ doc.chunkIndex }}</td>
          <td class="ts-col-score">{{ formatScore(doc.score) }}</td>
        </tr>
      </tbody>
    </table>
  </template>

  <!-- 意图识别 -->
  <template v-else-if="stage.name === 'chat_intent'">
    <div class="ts-kv-list">
      <div class="ts-kv-row">
        <span class="ts-kv-key">识别结果</span>
        <span class="ts-kv-val">{{ formatIntent(stage.detail) }}</span>
      </div>
      <div class="ts-kv-row">
        <span class="ts-kv-key">原始值</span>
        <span class="ts-kv-val muted">{{ stage.detail }}</span>
      </div>
    </div>
  </template>

  <!-- 查询改写 -->
  <template v-else-if="stage.name === 'query_rewrite'">
    <div class="ts-kv-list">
      <div class="ts-kv-row">
        <span class="ts-kv-key">改写结果</span>
        <span class="ts-kv-val">{{ stage.detail }}</span>
      </div>
    </div>
  </template>

  <!-- 向量编码 -->
  <template v-else-if="stage.name === 'embed'">
    <div class="ts-kv-list">
      <div class="ts-kv-row">
        <span class="ts-kv-key">向量维度</span>
        <span class="ts-kv-val">{{ stage.data?.dimensions || '-' }}</span>
      </div>
    </div>
  </template>

  <!-- 合并去重 -->
  <template v-else-if="stage.name === 'merge_dedup'">
    <div class="ts-kv-list">
      <div class="ts-kv-row">
        <span class="ts-kv-key">去重前</span>
        <span class="ts-kv-val">{{ stage.data?.before || 0 }} 条</span>
      </div>
      <div class="ts-kv-row">
        <span class="ts-kv-key">去重后</span>
        <span class="ts-kv-val">{{ stage.data?.after || 0 }} 条</span>
      </div>
    </div>
  </template>

  <!-- 构建上下文 -->
  <template v-else-if="stage.name === 'build_context'">
    <div class="ts-kv-list">
      <div class="ts-kv-row">
        <span class="ts-kv-key">引用文档</span>
        <span class="ts-kv-val">{{ stage.data?.docCount || 0 }} 篇</span>
      </div>
      <div class="ts-kv-row">
        <span class="ts-kv-key">上下文长度</span>
        <span class="ts-kv-val">{{ stage.data?.contextLength || 0 }} 字符</span>
      </div>
    </div>
  </template>

  <!-- 注入上下文到 LLM -->
  <template v-else-if="stage.name === 'inject_context'">
    <div class="ts-kv-list">
      <div class="ts-kv-row" v-if="stage.data?.ragDocCount != null">
        <span class="ts-kv-key">RAG 文档数</span>
        <span class="ts-kv-val">{{ stage.data.ragDocCount }} 篇</span>
      </div>
      <div class="ts-kv-row" v-if="stage.data?.contextLength != null">
        <span class="ts-kv-key">注入字符数</span>
        <span class="ts-kv-val">{{ stage.data.contextLength }} 字符</span>
      </div>
      <div class="ts-kv-row" v-if="stage.status === 'fallback' || stage.status === 'skipped'">
        <span class="ts-kv-key">原因</span>
        <span class="ts-kv-val muted">{{ stage.detail }}</span>
      </div>
    </div>
  </template>

  <!-- 跳过/降级阶段 -->
  <template v-else-if="stage.status === 'skipped' || stage.status === 'fallback'">
    <div class="ts-kv-list">
      <div class="ts-kv-row">
        <span class="ts-kv-key">原因</span>
        <span class="ts-kv-val muted">{{ stage.detail }}</span>
      </div>
    </div>
  </template>
</template>

<script setup lang="ts">
import type { TraceStage } from "../composables/useTraceFindings";

defineProps<{ stage: TraceStage }>();

function getDocuments(stage: TraceStage): any[] {
  if (Array.isArray(stage.data) && stage.data.length > 0) return stage.data;
  if (Array.isArray(stage.data?.documents) && stage.data.documents.length > 0) {
    return stage.data.documents;
  }
  return [];
}

function hasDocuments(stage: TraceStage): boolean {
  return getDocuments(stage).length > 0;
}

function formatScore(score: any): string {
  if (typeof score === "number") return score.toFixed(4);
  return "-";
}

function formatIntent(raw: string): string {
  const map: Record<string, string> = {
    NORMAL_CHAT: "普通对话（RAG 问答）",
    HOT_ARTICLES: "热门文章推荐",
    LATEST_ARTICLES: "最新文章",
    "builtin-hot-articles": "热门文章推荐",
    "builtin-latest-posts": "最新文章",
    "builtin-by-tag": "按标签查询",
    "builtin-by-category": "按分类查询",
  };
  return map[raw] || raw;
}
</script>

<style scoped>
/* 键值对列表 */
.ts-kv-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ts-kv-row {
  display: flex;
  gap: 8px;
  font-size: 12px;
}
.ts-kv-key {
  color: #6b7280;
  flex-shrink: 0;
  min-width: 70px;
}
.ts-kv-val {
  color: #1f2937;
  word-break: break-all;
}
.ts-kv-val.muted {
  color: #9ca3af;
  font-style: italic;
}

/* 文档表格 */
.ts-doc-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.ts-doc-table th {
  text-align: left;
  font-weight: 600;
  color: #6b7280;
  padding: 4px 6px;
  border-bottom: 1px solid #e5e7eb;
  font-size: 11px;
}
.ts-doc-table td {
  padding: 4px 6px;
  border-bottom: 1px solid #f3f4f6;
  color: #374151;
}
.ts-col-idx {
  width: 24px;
  text-align: center;
  font-weight: 700;
  color: #4f46e5;
}
.ts-col-title {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ts-col-chunk {
  color: #9ca3af;
  font-variant-numeric: tabular-nums;
}
.ts-col-score {
  font-variant-numeric: tabular-nums;
  color: #6b7280;
  text-align: right;
}
.ts-col-passed {
  text-align: center;
}

/* Rerank 通过/过滤状态 */
.ts-row-filtered {
  opacity: 0.45;
}
.ts-passed-yes {
  font-size: 11px;
  font-weight: 600;
  color: #059669;
  background: #ecfdf5;
  padding: 1px 6px;
  border-radius: 4px;
}
.ts-passed-no {
  font-size: 11px;
  font-weight: 600;
  color: #dc2626;
  background: #fef2f2;
  padding: 1px 6px;
  border-radius: 4px;
}
</style>
