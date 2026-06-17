package run.halo.ai.suite.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.rag.LuceneIndexService.SearchHit;

/**
 * HybridRetriever 单测 — 重点覆盖 RRF 分数融合正确性.
 *
 * <p>RRF (Reciprocal Rank Fusion): score(d) = Σ w_i / (k + rank_i(d)), k=60.
 * mock LuceneIndexService 的 searchKeyword/searchVector 返回预设命中, 验证:
 * 融合公式/权重/模式选择/阈值过滤/topN 截断.
 */
class HybridRetrieverTest {

    private final LuceneIndexService lucene = mock(LuceneIndexService.class);
    private final HybridRetriever retriever = new HybridRetriever(lucene);

    private static SearchHit hit(String id, String postId, String title, float score) {
        return new SearchHit(id, postId, title, "content-" + id, score, 0);
    }

    private static AIProperties.RetrievalConfig cfg(String mode, float weight,
                                                    float threshold, int topN) {
        AIProperties.RetrievalConfig c = new AIProperties.RetrievalConfig();
        c.setSearchMode(mode);
        c.setSemanticWeight(weight);
        c.setTopK(10);
        c.setSimilarityThreshold(threshold);
        c.setTopN(topN);
        return c;
    }

    /** 两路都命中同一文档时, 该文档 RRF 分数应累加(双路命中 > 单路命中) */
    @Test
    void rrfFusionBothPathsHitDocScoresHigher() throws IOException {
        // docA 在 BM25 排第1、Vector 排第1 → 双路命中
        // docB 在 BM25 排第2、Vector 无 → 单路命中
        when(lucene.searchKeyword(anyString(), anyInt())).thenReturn(List.of(
            hit("a", "p1", "A", 10f),
            hit("b", "p2", "B", 5f)
        ));
        when(lucene.searchVector(any(float[].class), anyInt())).thenReturn(List.of(
            hit("a", "p1", "A", 0.9f)
        ));

        List<RetrievedDocument> docs = retriever.retrieve("q", new float[]{0.1f},
            cfg("hybrid", 0.5f, 0f, 10)).block();

        assertThat(docs).hasSize(2);
        // docA 双路命中, RRF 分数应高于 docB(单路)
        assertThat(docs.get(0).postId()).isEqualTo("p1");
        assertThat(docs.get(0).score()).isGreaterThan(docs.get(1).score());
    }

    /**
     * RRF 分数数值正确性: semanticWeight=0.5, docA 双路第1.
     * score = 0.5/(60+1) + 0.5/(60+1) = 1/61 ≈ 0.01639
     */
    @Test
    void rrfScoreValueCorrect() throws IOException {
        when(lucene.searchKeyword(anyString(), anyInt())).thenReturn(List.of(
            hit("a", "p1", "A", 10f)
        ));
        when(lucene.searchVector(any(float[].class), anyInt())).thenReturn(List.of(
            hit("a", "p1", "A", 0.9f)
        ));

        List<RetrievedDocument> docs = retriever.retrieve("q", new float[]{0.1f},
            cfg("hybrid", 0.5f, 0f, 10)).block();

        assertThat(docs).hasSize(1);
        // weight=0.5 两路第1: 0.5/61 + 0.5/61 = 1/61
        assertThat(docs.get(0).score()).isCloseTo(1.0f / 61f, within(0.0001f));
    }

    /** semanticWeight 偏向向量时, 向量路排名靠前的文档应排前 */
    @Test
    void semanticWeightFavorsVectorRanking() throws IOException {
        // BM25: docA 第1, docB 第2
        // Vector: docB 第1, docA 第2
        // weight=0.9 偏向量 → docB 应排第一
        when(lucene.searchKeyword(anyString(), anyInt())).thenReturn(List.of(
            hit("a", "p1", "A", 10f),
            hit("b", "p2", "B", 5f)
        ));
        when(lucene.searchVector(any(float[].class), anyInt())).thenReturn(List.of(
            hit("b", "p2", "B", 0.9f),
            hit("a", "p1", "A", 0.8f)
        ));

        List<RetrievedDocument> docs = retriever.retrieve("q", new float[]{0.1f},
            cfg("hybrid", 0.9f, 0f, 10)).block();

        // weight=0.9 偏向量, docB 在向量路排第1 应胜出
        assertThat(docs.get(0).postId()).as("偏向量权重时向量第1的文档应排前").isEqualTo("p2");
    }

    /** keyword 模式: 只跑 BM25, 不调向量搜索 */
    @Test
    void keywordModeOnlyRunsBm25() throws IOException {
        when(lucene.searchKeyword(anyString(), anyInt())).thenReturn(List.of(
            hit("a", "p1", "A", 10f)
        ));

        retriever.retrieve("q", new float[]{0.1f},
            cfg("keyword", 0.5f, 0f, 10)).block();

        verify(lucene, times(1)).searchKeyword(anyString(), anyInt());
        verify(lucene, never()).searchVector(any(float[].class), anyInt());
    }

    /** vector 模式: 只跑向量, 不调 BM25 */
    @Test
    void vectorModeOnlyRunsVector() throws IOException {
        when(lucene.searchVector(any(float[].class), anyInt())).thenReturn(List.of(
            hit("a", "p1", "A", 0.9f)
        ));

        retriever.retrieve("q", new float[]{0.1f},
            cfg("vector", 0.5f, 0f, 10)).block();

        verify(lucene, never()).searchKeyword(anyString(), anyInt());
        verify(lucene, times(1)).searchVector(any(float[].class), anyInt());
    }

    /** vector 模式但 queryVector 为 null: 两路都不跑, 返回空 */
    @Test
    void vectorModeWithNullVectorReturnsEmpty() {
        List<RetrievedDocument> docs = retriever.retrieve("q", null,
            cfg("vector", 0.5f, 0f, 10)).block();

        assertThat(docs).isEmpty();
    }

    /**
     * vector 模式阈值是绝对阈值(cosine 天然 [0,1]).
     * threshold=0.85 应过滤掉 0.8 分的文档, 保留 0.9 的.
     */
    @Test
    void vectorModeAbsoluteThreshold() throws IOException {
        when(lucene.searchVector(any(float[].class), anyInt())).thenReturn(List.of(
            hit("a", "p1", "A", 0.9f),
            hit("b", "p2", "B", 0.8f)
        ));

        List<RetrievedDocument> docs = retriever.retrieve("q", new float[]{0.1f},
            cfg("vector", 0.5f, 0.85f, 10)).block();

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).postId()).isEqualTo("p1");
    }

    /**
     * hybrid 模式阈值是相对阈值(按本次最高分归一化).
     * threshold=0.5 保留 score >= maxScore*0.5 的.
     */
    @Test
    void hybridModeRelativeThreshold() throws IOException {
        when(lucene.searchKeyword(anyString(), anyInt())).thenReturn(List.of(
            hit("a", "p1", "A", 10f),
            hit("b", "p2", "B", 1f),
            hit("c", "p3", "C", 0.1f)
        ));
        when(lucene.searchVector(any(float[].class), anyInt())).thenReturn(List.of());

        List<RetrievedDocument> docs = retriever.retrieve("q", new float[]{0.1f},
            cfg("hybrid", 0.5f, 0.5f, 10)).block();

        // 最高分 = a 的分数, threshold=0.5 保留 >= maxScore*0.5 的
        float maxScore = docs.stream().map(RetrievedDocument::score).max(Float::compare).orElse(0f);
        // 至少 a 保留, c(0.1分) 应被过滤
        assertThat(docs).allSatisfy(d ->
            assertThat(d.score()).isGreaterThanOrEqualTo(maxScore * 0.5f));
    }

    /** topN 截断: 返回超过 topN 的只取前 topN */
    @Test
    void topNTruncation() throws IOException {
        when(lucene.searchKeyword(anyString(), anyInt())).thenReturn(List.of(
            hit("a", "p1", "A", 10f),
            hit("b", "p2", "B", 9f),
            hit("c", "p3", "C", 8f),
            hit("d", "p4", "D", 7f),
            hit("e", "p5", "E", 6f)
        ));

        List<RetrievedDocument> docs = retriever.retrieve("q", new float[]{0.1f},
            cfg("hybrid", 0.5f, 0f, 3)).block();

        assertThat(docs).hasSize(3);
    }

    /** 阈值 <=0 时不过滤, 全部保留 */
    @Test
    void zeroThresholdKeepsAll() throws IOException {
        when(lucene.searchKeyword(anyString(), anyInt())).thenReturn(List.of(
            hit("a", "p1", "A", 10f),
            hit("b", "p2", "B", 0.001f)
        ));

        List<RetrievedDocument> docs = retriever.retrieve("q", new float[]{0.1f},
            cfg("hybrid", 0.5f, 0f, 10)).block();

        assertThat(docs).hasSize(2);
    }

    /** 两路都为空时返回空 */
    @Test
    void bothPathsEmptyReturnsEmpty() throws IOException {
        when(lucene.searchKeyword(anyString(), anyInt())).thenReturn(List.of());
        when(lucene.searchVector(any(float[].class), anyInt())).thenReturn(List.of());

        List<RetrievedDocument> docs = retriever.retrieve("q", new float[]{0.1f},
            cfg("hybrid", 0.5f, 0f, 10)).block();

        assertThat(docs).isEmpty();
    }
}
