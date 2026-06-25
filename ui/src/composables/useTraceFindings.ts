import { computed, type ComputedRef } from "vue";

// ===== 类型定义（与 PipelineTrace.TraceStage 字段一致）=====
export interface TraceStage {
  name: string;
  label: string;
  startedAt: number;
  finishedAt: number;
  status: "ok" | "fallback" | "skipped" | "error";
  statusLabel: string;
  detail: string;
  data: any;
  durationMs: number;
}

export interface TraceSummary {
  totalMs: number;
  stageCount: number;
  intent: string;
}

export interface Finding {
  level: "ok" | "info" | "warning" | "error";
  title: string;
  causes: string[];
  suggestions: string[];
  relatedStages: string[];
}

/**
 * 智能诊断：基于 stages + summary 计算 findings 列表
 * 8 条规则按严重度排：rag_error → rerank 全过滤 → 上下文为空 → 注入降级
 *                  → 意图特殊 → 耗时异常 → 无引用 → 全正常
 *
 * @param stages 管线阶段列表
 * @param summary 汇总（totalMs / intent），可空
 * @param citations 引用文章列表，用于"无引用"检查，可空
 */
export function computeFindings(
  stages: TraceStage[],
  summary: TraceSummary | null | undefined,
  citations?: { length: number } | null
): Finding[] {
  const result: Finding[] = [];
  const stageList = stages || [];
  const totalMs = summary?.totalMs || 0;
  const intent = summary?.intent;

  // 1. RAG 检索异常（最高优先级）
  const ragErr = stageList.find(s => s.name === "rag_error");
  if (ragErr) {
    result.push({
      level: "error",
      title: "RAG 检索异常",
      causes: [ragErr.detail || "未知异常"],
      suggestions: [
        "查看 Halo 日志中的 RAG 错误详情",
        "确认 AI Foundation 中的 Embedding 模型配置正确",
        "在「模型配置」测一下 Embedding 连通性",
      ],
      relatedStages: ["rag_error"],
    });
  }

  // 2. Rerank 全部被过滤
  const rerank = stageList.find(s => s.name === "rerank");
  if (rerank && rerank.data) {
    const docs = Array.isArray(rerank.data) ? rerank.data : (rerank.data.documents || []);
    if (docs.length > 0) {
      const passed = docs.filter((d: any) => d.passed !== false).length;
      if (passed === 0) {
        const threshold = rerank.data.threshold ?? 0.3;
        result.push({
          level: "warning",
          title: `Rerank 全部被过滤（${docs.length} → 0 条）`,
          causes: [
            `当前阈值 ${threshold} 对该查询过严`,
            "知识库中可能没有与问题语义相关的文档",
            "Rerank 模型对中英文混排 / 短查询支持可能较弱",
          ],
          suggestions: [
            "在「索引中心」搜关键词，确认知识库有相关内容",
            `把 Rerank 阈值调到 0.1~0.2（在「检索设置」）`,
            "临时关闭 Rerank 看是否能召回，作为对照",
          ],
          relatedStages: ["rerank"],
        });
      }
    }
  }

  // 3. 上下文为空
  const buildCtx = stageList.find(s => s.name === "build_context");
  if (buildCtx && buildCtx.status === "ok") {
    const docCount = buildCtx.data?.docCount || 0;
    if (docCount === 0) {
      result.push({
        level: "error",
        title: "上下文为空（0 篇文档）",
        causes: ["召回阶段没有可用文档可注入 LLM"],
        suggestions: [
          "检查向量检索/关键词检索的召回数量",
          "在「索引中心」验证相关切片存在",
          "考虑调低相似度阈值或扩大 topK",
        ],
        relatedStages: ["build_context"],
      });
    }
  }

  // 4. 注入上下文被跳过/降级
  const injectCtx = stageList.find(s => s.name === "inject_context");
  if (injectCtx && (injectCtx.status === "skipped" || injectCtx.status === "fallback")) {
    result.push({
      level: "warning",
      title: "LLM 未使用知识库",
      causes: [injectCtx.detail || "上下文被跳过或降级"],
      suggestions: [
        "AI 回复基于通用知识，可能与博客内容无关",
        "向上排查召回/重排/上下文构建链路",
      ],
      relatedStages: ["inject_context"],
    });
  }

  // 5. 意图是热门/最新文章（不走 RAG）
  if (intent === "HOT_ARTICLES" || intent === "builtin-hot-articles") {
    result.push({
      level: "info",
      title: "走热门文章路径（未走 RAG）",
      causes: ["意图识别为热门文章推荐，直接调用 LLM 包装推荐结果"],
      suggestions: ["如误判，可在「意图路由」调整触发词和优先级"],
      relatedStages: ["chat_intent"],
    });
  } else if (intent === "LATEST_ARTICLES" || intent === "builtin-latest-posts") {
    result.push({
      level: "info",
      title: "走最新文章路径（未走 RAG）",
      causes: ["意图识别为最新文章推荐"],
      suggestions: ["如误判，可在「意图路由」调整触发词和优先级"],
      relatedStages: ["chat_intent"],
    });
  }

  // 6. 耗时异常：单阶段 > 总耗时 60% 且 > 500ms
  if (totalMs > 0) {
    for (const stage of stageList) {
      if (stage.durationMs > totalMs * 0.6 && stage.durationMs > 500) {
        const pct = Math.round((stage.durationMs / totalMs) * 100);
        result.push({
          level: "warning",
          title: `${stage.label} 耗时过长（${pct}%）`,
          causes: [
            `占整体耗时 ${stage.durationMs}ms / ${totalMs}ms`,
            "网络延迟 / 模型推理慢 / 服务端排队都可能造成",
          ],
          suggestions: [
            "检查网络与服务商可用区",
            "考虑换更快的模型（如 deepseek-chat / gpt-4o-mini）",
            "Embedding 慢可考虑本地部署小模型",
          ],
          relatedStages: [stage.name],
        });
      }
    }
  }

  // 7. 全部正常但无引用
  const hasErrors = result.some(f => f.level === "error");
  if (
    !hasErrors &&
    stageList.length > 0 &&
    citations !== undefined &&
    citations !== null &&
    citations.length === 0
  ) {
    result.push({
      level: "info",
      title: "已生成回答但未引用知识库",
      causes: ["LLM 没引用任何文章，可能是 RAG 召回为空或 LLM 决定不引用"],
      suggestions: [
        "检查 inject_context 阶段的 ragDocCount",
        "如召回为空，参考「Rerank 全部被过滤」或「上下文为空」建议",
      ],
      relatedStages: ["inject_context"],
    });
  }

  // 8. 全流程正常
  if (result.length === 0 && stageList.length > 0 && summary) {
    result.push({
      level: "ok",
      title: "管线运行正常",
      causes: [
        `${summary.stageCount} 个阶段全部完成，总耗时 ${totalMs}ms`,
      ],
      suggestions: ["可查看下方的 AI 回复确认结果质量"],
      relatedStages: [],
    });
  }

  // 按严重度排序：error > warning > info > ok
  const order: Record<Finding["level"], number> = { error: 0, warning: 1, info: 2, ok: 3 };
  result.sort((a, b) => order[a.level] - order[b.level]);
  return result;
}

/**
 * 计算 findings 的响应式封装
 */
export function useTraceFindings(
  stages: ComputedRef<TraceStage[]> | (() => TraceStage[]),
  summary: ComputedRef<TraceSummary | null> | (() => TraceSummary | null | undefined),
  citations?: ComputedRef<{ length: number } | null> | (() => { length: number } | null | undefined)
) {
  return computed(() => {
    const s = typeof stages === "function" ? stages() : stages.value;
    const sum = typeof summary === "function" ? summary() : summary.value;
    const cit = citations
      ? (typeof citations === "function" ? citations() : citations.value)
      : undefined;
    return computeFindings(s || [], sum, cit);
  });
}
