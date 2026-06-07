package run.halo.ai.assistant.rag;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.MMapDirectory;
import org.springframework.stereotype.Component;
import run.halo.ai.assistant.config.AIProperties;

import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lucene 索引服务 — 管理 BM25 + HNSW 向量索引
 *
 * Lucene 10.x 同时支持：
 * - BM25 关键词搜索：用 TextField + StandardAnalyzer/SmartChineseAnalyzer
 * - HNSW 向量搜索：用 KnnFloatVectorField 存储向量
 *
 * 两种搜索能力在同一个索引中并存，HybridRetriever 可以分别查询再融合。
 *
 * 线程安全：IndexWriter 线程安全，IndexSearcher 需要 refresh 后才能看到新数据。
 * 所有 Lucene I/O 是阻塞操作，调用方必须用 Schedulers.boundedElastic() 包裹。
 */
@Slf4j
@Component
public class LuceneIndexService {

    // Lucene 字段名常量
    private static final String FIELD_ID = "id";
    private static final String FIELD_POST_ID = "postId";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_CHUNK_INDEX = "chunkIndex";
    private static final String FIELD_KEYWORDS = "keywords";

    private final AIProperties aiProperties;

    // 用 Spring 注入而非 System.getProperty，因为 Docker 容器中 Halo 不一定把
    // halo.work-dir 设为 JVM 系统属性，走 fallback "." 会导致索引写到未挂载的目录
    @Value("${halo.work-dir:#{systemProperties['user.home'] + '/.halo2'}}")
    private String workDir;

    private MMapDirectory directory;
    private IndexWriter indexWriter;
    private Analyzer analyzer;
    // SearcherManager 管理 IndexSearcher 的生命周期，自动 refresh
    private SearcherManager searcherManager;

    private volatile int totalChunks = 0;

    public LuceneIndexService(AIProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    /**
     * 初始化 Lucene 索引 — 在第一次使用时延迟初始化
     *
     * 不用 @PostConstruct，因为需要拿到 embeddingDimensions 配置
     */
    public synchronized void ensureInitialized() throws IOException {
        if (indexWriter != null) return;

        // 索引目录：{halo.work-dir}/data/ai-assistant/lucene/
        Path indexPath = Path.of(workDir, "data", "ai-assistant", "lucene");
        Files.createDirectories(indexPath);
        log.info("[LuceneIndexService] 索引目录: {}", indexPath.toAbsolutePath());

        directory = new MMapDirectory(indexPath);

        // SmartChineseAnalyzer — 中文分词效果比 StandardAnalyzer 好很多
        analyzer = new SmartChineseAnalyzer();

        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

        indexWriter = new IndexWriter(directory, config);
        indexWriter.commit();

        // SearcherManager：自动管理 IndexSearcher 的 open/refresh/close
        searcherManager = new SearcherManager(indexWriter, false, false, null);

        // 统计已有文档数
        totalChunks = countDocuments();
        log.info("[LuceneIndexService] 初始化完成，已有 {} 个 chunk", totalChunks);
    }

    /**
     * 索引一个切片（带向量）
     */
    public void indexChunk(TextChunk chunk, float[] embedding) throws IOException {
        ensureInitialized();

        Document doc = new Document();
        doc.add(new StringField(FIELD_ID, chunk.id(), Field.Store.YES));
        doc.add(new StringField(FIELD_POST_ID, chunk.postId(), Field.Store.YES));
        doc.add(new TextField(FIELD_TITLE, chunk.postTitle(), Field.Store.YES));
        doc.add(new TextField(FIELD_CONTENT, chunk.content(), Field.Store.YES));
        doc.add(new IntPoint(FIELD_CHUNK_INDEX, chunk.chunkIndex()));
        doc.add(new StoredField(FIELD_CHUNK_INDEX, chunk.chunkIndex()));

        // 关键词：用 TextField 分词后可被检索命中
        if (chunk.keywords() != null && !chunk.keywords().isEmpty()) {
            doc.add(new TextField(FIELD_KEYWORDS,
                String.join(" ", chunk.keywords()), Field.Store.YES));
        }

        // 向量字段：用 COSINE 相似度（语义搜索最常用）
        if (embedding != null && embedding.length > 0) {
            doc.add(new KnnFloatVectorField(FIELD_VECTOR, embedding,
                VectorSimilarityFunction.COSINE));
        }

        // 用 upsert 语义：先删旧的，再插入
        indexWriter.updateDocument(new Term(FIELD_ID, chunk.id()), doc);
        totalChunks++;
    }

    /**
     * 删除一篇文章的所有切片
     */
    public void deleteByPostId(String postId) throws IOException {
        ensureInitialized();
        indexWriter.deleteDocuments(new Term(FIELD_POST_ID, postId));
        indexWriter.commit();
        searcherManager.maybeRefresh();
        totalChunks = countDocuments();
        log.debug("[LuceneIndexService] 删除文章 {} 的所有 chunk", postId);
    }

    /**
     * 提交索引变更并刷新 Searcher
     */
    public void commit() throws IOException {
        ensureInitialized();
        indexWriter.commit();
        searcherManager.maybeRefresh();
        totalChunks = countDocuments();
        log.info("[LuceneIndexService] 提交完成，共 {} 个 chunk", totalChunks);
    }

    /**
     * BM25 关键词搜索 — 用 SmartChinese 分词后匹配 content 和 title 字段
     */
    public List<SearchHit> searchKeyword(String query, int topK) throws IOException {
        ensureInitialized();

        // 构建多字段查询：同时搜索 title 和 content
        BooleanQuery.Builder boolBuilder = new BooleanQuery.Builder();

        // title 字段权重更高（boost = 2.0）
        TermQuery titleTerm = new TermQuery(new Term(FIELD_TITLE, query));
        boolBuilder.add(new BoostQuery(titleTerm, 2.0f), BooleanClause.Occur.SHOULD);

        // content 字段用短语查询（精确匹配优先）
        PhraseQuery contentPhrase = new PhraseQuery(FIELD_CONTENT, query);
        boolBuilder.add(contentPhrase, BooleanClause.Occur.SHOULD);

        // content 字段逐词匹配（召回率更高）
        // SmartChineseAnalyzer 会把 query 分词，我们手动拆成 TermQuery
        List<String> terms = tokenize(query);
        for (String term : terms) {
            boolBuilder.add(new TermQuery(new Term(FIELD_CONTENT, term)),
                BooleanClause.Occur.SHOULD);
        }

        return executeSearch(boolBuilder.build(), topK);
    }

    /**
     * HNSW 向量搜索 — 用 cosine 相似度找最近的向量
     */
    public List<SearchHit> searchVector(float[] queryVector, int topK) throws IOException {
        ensureInitialized();

        Query vectorQuery = new KnnFloatVectorQuery(FIELD_VECTOR, queryVector, topK);
        return executeSearch(vectorQuery, topK);
    }

    /**
     * 执行搜索并提取结果
     */
    private List<SearchHit> executeSearch(Query query, int topK) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs topDocs = searcher.search(query, topK);
            List<SearchHit> hits = new ArrayList<>();

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                hits.add(new SearchHit(
                    doc.get(FIELD_ID),
                    doc.get(FIELD_POST_ID),
                    doc.get(FIELD_TITLE),
                    doc.get(FIELD_CONTENT),
                    scoreDoc.score,
                    doc.getField(FIELD_CHUNK_INDEX) != null
                        ? Integer.parseInt(doc.get(FIELD_CHUNK_INDEX)) : 0
                ));
            }
            return hits;
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * 用 SmartChineseAnalyzer 分词
     */
    private List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream("", text)) {
            CharTermAttribute attr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                terms.add(attr.toString());
            }
            ts.end();
        } catch (IOException e) {
            log.warn("[LuceneIndexService] 分词失败: {}", e.getMessage());
            // 降级：按空格分
            for (String word : text.split("\\s+")) {
                if (!word.isEmpty()) terms.add(word);
            }
        }
        return terms;
    }

    /**
     * 统计索引中的文档总数
     */
    private int countDocuments() throws IOException {
        if (searcherManager == null) return 0;
        IndexSearcher searcher = searcherManager.acquire();
        try {
            return searcher.getIndexReader().numDocs();
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * 清空整个索引
     */
    public void clearAll() throws IOException {
        ensureInitialized();
        indexWriter.deleteAll();
        indexWriter.commit();
        searcherManager.maybeRefresh();
        totalChunks = 0;
        log.info("[LuceneIndexService] 索引已清空");
    }

    /**
     * 获取所有已索引文章的切片计数（用 MatchAllDocs 查询）
     */
    public Map<String, Integer> getPostChunkCounts() throws IOException {
        ensureInitialized();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            // 用 MatchAllDocs 查所有文档，Lucene 自动跳过已删除的
            TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), Integer.MAX_VALUE);
            Map<String, Integer> counts = new HashMap<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                String postId = doc.get(FIELD_POST_ID);
                if (postId != null) counts.merge(postId, 1, Integer::sum);
            }
            return counts;
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * 统计每篇文章已提取关键词的切片数
     */
    public Map<String, Integer> getPostKeywordChunkCounts() throws IOException {
        ensureInitialized();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), Integer.MAX_VALUE);
            Map<String, Integer> counts = new HashMap<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                String kw = doc.get(FIELD_KEYWORDS);
                if (kw != null && !kw.isBlank()) {
                    String postId = doc.get(FIELD_POST_ID);
                    if (postId != null) counts.merge(postId, 1, Integer::sum);
                }
            }
            return counts;
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * 获取指定文章的切片列表（按 chunkIndex 排序，用于预览）
     */
    public List<ChunkPreview> getChunksByPostId(String postId) throws IOException {
        ensureInitialized();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            Query query = new TermQuery(new Term(FIELD_POST_ID, postId));
            TopDocs topDocs = searcher.search(query, 1000);
            List<ChunkPreview> chunks = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                String chunkIndexStr = doc.get(FIELD_CHUNK_INDEX);
                chunks.add(new ChunkPreview(
                    doc.get(FIELD_ID),
                    doc.get(FIELD_POST_ID),
                    doc.get(FIELD_CONTENT),
                    chunkIndexStr != null ? Integer.parseInt(chunkIndexStr) : 0,
                    doc.get(FIELD_KEYWORDS) != null ? doc.get(FIELD_KEYWORDS) : ""
                ));
            }
            chunks.sort(Comparator.comparingInt(ChunkPreview::chunkIndex));
            return chunks;
        } finally {
            searcherManager.release(searcher);
        }
    }

    public int getDocumentCount() {
        return totalChunks;
    }

    /**
     * 统计已提取关键词的切片数
     */
    public int countKeywordChunks() {
        try {
            ensureInitialized();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                // 搜索 keywords 字段非空的所有文档
                Query query = new TermQuery(new Term(FIELD_KEYWORDS, ""));
                // WildcardQuery 匹配非空：搜索所有文档再过滤
                TopDocs allDocs = searcher.search(new MatchAllDocsQuery(), Integer.MAX_VALUE);
                int count = 0;
                for (ScoreDoc sd : allDocs.scoreDocs) {
                    Document doc = searcher.storedFields().document(sd.doc);
                    String kw = doc.get(FIELD_KEYWORDS);
                    if (kw != null && !kw.isBlank()) count++;
                }
                return count;
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            log.error("[LuceneIndexService] 统计关键词切片失败: {}", e.getMessage());
            return 0;
        }
    }

    @PreDestroy
    public void close() {
        try {
            if (searcherManager != null) searcherManager.close();
            if (indexWriter != null) indexWriter.close();
            if (directory != null) directory.close();
            if (analyzer != null) analyzer.close();
            log.info("[LuceneIndexService] 已关闭");
        } catch (IOException e) {
            log.error("[LuceneIndexService] 关闭失败: {}", e.getMessage());
        }
    }

    /**
     * 搜索结果 — 内部使用，包含 Lucene 打分
     */
    public record SearchHit(
        String id,
        String postId,
        String title,
        String content,
        float score,
        int chunkIndex
    ) {}

    public record ChunkPreview(
        String id,
        String postId,
        String content,
        int chunkIndex,
        String keywords
    ) {}
}
