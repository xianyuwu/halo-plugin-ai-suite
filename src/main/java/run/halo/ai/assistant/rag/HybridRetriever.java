package run.halo.ai.assistant.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.assistant.config.AIProperties;
import run.halo.ai.assistant.rag.LuceneIndexService.SearchHit;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索器 — BM25 关键词 + HNSW 向量语义，用 RRF 融合
 *
 * RRF (Reciprocal Rank Fusion) 公式：
 *   score(d) = Σ  1 / (k + rank_i(d))
 *
 * 其中 k 通常取 60，rank 是文档在某路检索中的排名（从 1 开始）。
 * RRF 的好处：不需要归一化分数，直接用排名融合，两路结果天然可比。
 *
 * semanticWeight 控制两路权重：
 *   score(d) = (1 - w) / (k + rank_bm25) + w / (k + rank_vector)
 *
 * 类比：像一个「双评委」打分系统，一个评委擅长关键词匹配（BM25），
 * 另一个擅长理解语义（Vector），RRF 把两个评委的意见综合起来
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetriever {

    private final LuceneIndexService luceneIndexService;

    // RRF 常数 k — 排名靠前的文档优势更明显
    private static final int RRF_K = 60;

    /**
     * 纯关键词检索 — 用于跨语言检索等场景
     *
     * 只跑 BM25，不跑向量搜索，不做 RRF 融合
     */
    public Mono<List<RetrievedDocument>> searchKeywordOnly(String query, int topK) {
        return Mono.fromCallable(() -> {
            List<SearchHit> hits = luceneIndexService.searchKeyword(query, topK);
            return toRetrievedDocs(hits);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 混合检索 — 根据配置选择检索模式
     *
     * @param query        用户原始 query（用于 BM25）
     * @param queryVector  query 的 embedding 向量（用于语义搜索）
     * @param retrievalConfig 检索配置
     * @return 检索到的文档列表，按相关性排序
     */
    public Mono<List<RetrievedDocument>> retrieve(
            String query,
            float[] queryVector,
            AIProperties.RetrievalConfig retrievalConfig) {

        return Mono.fromCallable(() -> {
            String mode = retrievalConfig.getSearchMode();
            int topK = retrievalConfig.getTopK();

            List<SearchHit> bm25Hits = List.of();
            List<SearchHit> vectorHits = List.of();

            // 根据模式决定哪些路检索
            if ("hybrid".equals(mode) || "keyword".equals(mode)) {
                bm25Hits = luceneIndexService.searchKeyword(query, topK);
                log.debug("[HybridRetriever] BM25 命中 {} 条", bm25Hits.size());
            }
            if ("hybrid".equals(mode) || "vector".equals(mode)) {
                if (queryVector != null && queryVector.length > 0) {
                    vectorHits = luceneIndexService.searchVector(queryVector, topK);
                    log.debug("[HybridRetriever] Vector 命中 {} 条", vectorHits.size());
                }
            }

            // 融合
            List<RetrievedDocument> results;
            if ("hybrid".equals(mode) && !bm25Hits.isEmpty() && !vectorHits.isEmpty()) {
                results = rrfFusion(bm25Hits, vectorHits, retrievalConfig.getSemanticWeight());
            } else if (!bm25Hits.isEmpty()) {
                results = toRetrievedDocs(bm25Hits);
            } else {
                results = toRetrievedDocs(vectorHits);
            }

            // 按相似度阈值过滤（阈值语义按模式区分，避免 hybrid 下 0.5 把所有结果全滤掉）
            results = filterByThreshold(results, retrievalConfig.getSimilarityThreshold(), mode);

            // 取 Top-N
            int topN = retrievalConfig.getTopN();
            if (results.size() > topN) {
                results = results.subList(0, topN);
            }

            log.debug("[HybridRetriever] 最终返回 {} 条结果", results.size());
            return results;
        }).subscribeOn(Schedulers.boundedElastic());
        // Lucene I/O 是阻塞的，切到弹性线程池执行
    }

    /**
     * RRF 融合 — 把两路检索结果按排名融合
     */
    private List<RetrievedDocument> rrfFusion(
            List<SearchHit> bm25Hits, List<SearchHit> vectorHits,
            float semanticWeight) {

        // 按 id 汇总 RRF 分数
        Map<String, Float> scores = new HashMap<>();
        Map<String, SearchHit> hitMap = new HashMap<>();

        float keywordWeight = 1.0f - semanticWeight;

        // BM25 路的排名贡献
        for (int i = 0; i < bm25Hits.size(); i++) {
            SearchHit hit = bm25Hits.get(i);
            float contribution = keywordWeight / (RRF_K + i + 1);
            scores.merge(hit.id(), contribution, Float::sum);
            hitMap.putIfAbsent(hit.id(), hit);
        }

        // Vector 路的排名贡献
        for (int i = 0; i < vectorHits.size(); i++) {
            SearchHit hit = vectorHits.get(i);
            float contribution = semanticWeight / (RRF_K + i + 1);
            scores.merge(hit.id(), contribution, Float::sum);
            hitMap.putIfAbsent(hit.id(), hit);
        }

        // 按 RRF 分数降序排序
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
            .map(entry -> {
                SearchHit hit = hitMap.get(entry.getKey());
                return new RetrievedDocument(
                    hit.postId(),
                    hit.title(),
                    hit.content(),
                    entry.getValue(),
                    hit.chunkIndex()
                );
            })
            .collect(Collectors.toList());
    }

    /**
     * 把 SearchHit 转成 RetrievedDocument
     */
    private List<RetrievedDocument> toRetrievedDocs(List<SearchHit> hits) {
        return hits.stream()
            .map(hit -> new RetrievedDocument(
                hit.postId(),
                hit.title(),
                hit.content(),
                hit.score(),
                hit.chunkIndex()
            ))
            .collect(Collectors.toList());
    }

    /**
     * 按相似度阈值过滤 — 阈值语义按 searchMode 区分
     *
     * 三种模式下原始 score 范围完全不同，统一用同一个 threshold 比较会出问题
     * （历史 bug：hybrid 模式 RRF 分数最高约 0.017，UI 默认配 0.5 直接把所有结果过滤光）：
     *
     *   - vector 模式：score 是 cosine 相似度，范围 [0,1] → threshold 作为「绝对阈值」直接比较
     *   - hybrid 模式：score 是 RRF 融合分数，范围约 [0, 0.017]（depends on weights）
     *   - keyword 模式：score 是 BM25 分数，无固定范围（通常 0~30）
     *
     * 对后两种模式，把 threshold 解释为「相对阈值」：保留 score ≥ 最高分 × threshold 的结果。
     * 这样 UI 上配 0.5 在任何模式下都是直观的「取最相关的那一半」。
     */
    private List<RetrievedDocument> filterByThreshold(
            List<RetrievedDocument> docs, float threshold, String mode) {
        if (threshold <= 0 || docs.isEmpty()) return docs;

        // vector 模式：cosine 已经天然在 [0,1]，threshold 即绝对阈值，保持原行为
        if ("vector".equals(mode)) {
            return docs.stream()
                .filter(doc -> doc.score() >= threshold)
                .collect(Collectors.toList());
        }

        // hybrid / keyword 模式：score 范围不固定，先按本次最高分归一化再比较
        float maxScore = 0f;
        for (RetrievedDocument doc : docs) {
            if (doc.score() > maxScore) maxScore = doc.score();
        }
        if (maxScore <= 0) return docs; // 全 0 分（不应发生），不过滤
        float absThreshold = threshold * maxScore;
        return docs.stream()
            .filter(doc -> doc.score() >= absThreshold)
            .collect(Collectors.toList());
    }
}
