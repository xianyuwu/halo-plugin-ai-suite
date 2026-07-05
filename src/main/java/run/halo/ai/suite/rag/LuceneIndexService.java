package run.halo.ai.suite.rag;

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
import org.apache.lucene.util.Bits;
import org.springframework.stereotype.Component;
import run.halo.ai.suite.config.AIProperties;

import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

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
    private static final String METADATA_FILE = "ai-suite-index.properties";
    private static final String META_EMBEDDING_MODEL = "embeddingModel";
    private static final String META_EMBEDDING_DIMENSIONS = "embeddingDimensions";

    private final AIProperties aiProperties;

    // 用 Spring 注入而非 System.getProperty，因为 Docker 容器中 Halo 不一定把
    // halo.work-dir 设为 JVM 系统属性，走 fallback "." 会导致索引写到未挂载的目录
    @Value("${halo.work-dir:#{systemProperties['user.home'] + '/.halo2'}}")
    private String workDir;

    private MMapDirectory directory;
    private Path indexPath;
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

        // 索引目录：{halo.work-dir}/data/ai-suite/lucene/
        indexPath = Path.of(workDir, "data", "ai-suite", "lucene");
        Path legacyIndexPath = Path.of(workDir, "data", "ai-assistant", "lucene");
        if (!Files.exists(indexPath) && Files.exists(legacyIndexPath)) {
            Files.createDirectories(indexPath.getParent());
            Files.move(legacyIndexPath, indexPath);
            log.info("[LuceneIndexService] 已迁移旧索引目录 {} -> {}",
                legacyIndexPath.toAbsolutePath(), indexPath.toAbsolutePath());
        }
        Files.createDirectories(indexPath);
        log.info("[LuceneIndexService] 索引目录: {}", indexPath.toAbsolutePath());

        // 用局部变量逐步创建资源，全部成功后才赋值给 field。
        // 原因：new IndexWriter 一旦成功就拿到了 NativeFSLockFactory 的锁
        // （LOCK_HELD static Set 由 Halo 主 ClassLoader 持有，跨插件 reload 残留），
        // 若后续 commit / SearcherManager 构造抛异常，必须主动 close 释放锁，
        // 否则下次初始化会撞 "Lock held by this virtual machine"。
        MMapDirectory dir = new MMapDirectory(indexPath);
        // SmartChineseAnalyzer — 中文分词效果比 StandardAnalyzer 好很多
        Analyzer az = new SmartChineseAnalyzer();
        IndexWriter iw = null;
        SearcherManager sm = null;
        try {
            IndexWriterConfig config = new IndexWriterConfig(az);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            iw = new IndexWriter(dir, config);
            iw.commit();
            // SearcherManager：自动管理 IndexSearcher 的 open/refresh/close
            sm = new SearcherManager(iw, false, false, null);
        } catch (IOException e) {
            // 初始化失败：回滚所有已创建资源，尤其释放 IndexWriter 持有的锁。
            // 不给任何 field 赋值，保持「要么完全初始化、要么完全干净」的不变式。
            closeQuietly(sm);
            closeQuietly(iw);
            closeQuietly(az);
            closeQuietly(dir);
            throw e;
        }

        // 全部成功，一次性提交给 field
        directory = dir;
        analyzer = az;
        indexWriter = iw;
        searcherManager = sm;

        // 统计已有文档数（失败不致命，totalChunks 仅作缓存计数）
        try {
            totalChunks = countDocuments();
        } catch (IOException e) {
            log.warn("[LuceneIndexService] 统计已有文档数失败，忽略: {}", e.getMessage());
        }
        log.info("[LuceneIndexService] 初始化完成，已有 {} 个 chunk", totalChunks);
    }

    /**
     * 索引一个切片（带向量）
     */
    public void indexChunk(TextChunk chunk, float[] embedding) throws IOException {
        ensureInitialized();

        Document doc = toDocument(chunk, embedding, true);
        // 用 upsert 语义：先删旧的，再插入
        indexWriter.updateDocument(new Term(FIELD_ID, chunk.id()), doc);
        totalChunks++;
    }

    /**
     * 原子替换单篇文章索引。
     * <p>调用方需要先完成切片、关键词、Embedding 等易失败步骤；这里才删除旧分片并写入新分片，
     * 避免“先删旧索引，后续失败导致该文章分片归零”。</p>
     */
    public synchronized void replacePost(List<IndexedChunk> chunks, String model, int dimensions)
        throws IOException {
        ensureInitialized();
        recordVectorConfig(model, dimensions);
        String postId = chunks.isEmpty() ? null : chunks.get(0).chunk().postId();
        if (postId == null || postId.isBlank()) return;

        indexWriter.deleteDocuments(new Term(FIELD_POST_ID, postId));
        for (IndexedChunk item : chunks) {
            indexWriter.addDocument(toDocument(item.chunk(), item.embedding(), true));
        }
        indexWriter.commit();
        searcherManager.maybeRefresh();
        totalChunks = countDocuments();
        log.info("[LuceneIndexService] 已替换文章 {} 的 {} 个 chunk", postId, chunks.size());
    }

    /**
     * 用临时目录完整构建新索引，全部成功后再切换到正式目录。
     * <p>这是全量重建的“非破坏式提交”：构建或切换失败时保留旧索引，避免分片归零。</p>
     */
    public synchronized void replaceAll(List<IndexedChunk> chunks, String model, int dimensions)
        throws IOException {
        ensureInitialized();
        if (indexPath == null) {
            throw new IOException("Lucene 索引目录未初始化");
        }

        Path parent = indexPath.getParent();
        Files.createDirectories(parent);
        Path tempPath = parent.resolve("lucene-rebuild-" + System.nanoTime());
        Path backupPath = parent.resolve("lucene-backup-" + System.nanoTime());
        buildStandaloneIndex(tempPath, chunks, model, dimensions);

        boolean movedCurrent = false;
        close();
        try {
            if (Files.exists(indexPath)) {
                moveDirectory(indexPath, backupPath);
                movedCurrent = true;
            }
            moveDirectory(tempPath, indexPath);
            deleteRecursively(backupPath);
            ensureInitialized();
            totalChunks = countDocuments();
            log.info("[LuceneIndexService] 全量索引已替换，共 {} 个 chunk", totalChunks);
        } catch (IOException e) {
            if (Files.exists(indexPath)) {
                deleteRecursively(indexPath);
            }
            if (movedCurrent && Files.exists(backupPath)) {
                moveDirectory(backupPath, indexPath);
            }
            deleteRecursively(tempPath);
            ensureInitialized();
            throw e;
        }
    }

    /**
     * 删除一篇文章的所有切片。
     * <p>必须 synchronized: 与 {@link #replaceAll} 共用同一把锁。{@code replaceAll}
     * 在全量重建时会 {@link #close()} 整个索引(IndexWriter/SearcherManager/Directory),
     * 若 {@code deleteByPostId} 不持锁并发进入, 会访问到已关闭的 {@code indexWriter}
     * 导致 NPE/AlreadyClosedException.
     */
    public synchronized void deleteByPostId(String postId) throws IOException {
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
     * BM25 关键词搜索 — 用 SmartChinese 分词后匹配 content 和 title 字段.
     *
     * <p>实现说明: title/content 在索引时被 SmartChineseAnalyzer 分词, 所以查询时必须先
     * {@link #tokenize} 再逐词构 TermQuery, 才能命中分词后的索引. 历史实现把整个 query
     * 当单个 TermQuery(title) / PhraseQuery(content), 这两路对多 token 的 query 几乎不可能
     * 命中分词后的索引, 属无效查询; 且 title 完全没走分词, 标题里的词搜不到.
     * 现改为: title/content 都走分词逐词匹配, title 加 boost 2.0 提权.
     */
    public List<SearchHit> searchKeyword(String query, int topK) throws IOException {
        ensureInitialized();

        BooleanQuery.Builder boolBuilder = new BooleanQuery.Builder();
        List<String> terms = tokenize(query);
        for (String term : terms) {
            // title 权重更高(boost 2.0): 命中标题比命中正文排序靠前
            boolBuilder.add(new BoostQuery(
                new TermQuery(new Term(FIELD_TITLE, term)), 2.0f), BooleanClause.Occur.SHOULD);
            // content 逐词匹配, 召回率主力
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
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        ensureVectorCompatible(queryVector != null ? queryVector.length : 0);

        Query vectorQuery = new KnnFloatVectorQuery(FIELD_VECTOR, queryVector, topK);
        return executeSearch(vectorQuery, topK);
    }

    /**
     * 记录当前索引用到的 embedding 配置。若已有非空索引维度不同，要求先重建索引。
     */
    public synchronized void recordVectorConfig(String model, int dimensions) throws IOException {
        ensureInitialized();
        if (dimensions <= 0) return;

        Properties props = readMetadata();
        String storedDimensions = props.getProperty(META_EMBEDDING_DIMENSIONS);
        if (storedDimensions != null && !storedDimensions.isBlank()
            && !storedDimensions.equals(String.valueOf(dimensions))
            && countDocuments() > 0) {
            throw new IllegalStateException("当前 Lucene 索引向量维度为 " + storedDimensions
                + "，但配置维度为 " + dimensions + "。请先清空并重建知识库索引。");
        }

        props.setProperty(META_EMBEDDING_DIMENSIONS, String.valueOf(dimensions));
        props.setProperty(META_EMBEDDING_MODEL, model != null ? model : "");
        writeMetadata(props);
    }

    private void ensureVectorCompatible(int dimensions) throws IOException {
        if (dimensions <= 0) return;
        Properties props = readMetadata();
        String storedDimensions = props.getProperty(META_EMBEDDING_DIMENSIONS);
        if (storedDimensions == null || storedDimensions.isBlank()) {
            return;
        }
        if (!storedDimensions.equals(String.valueOf(dimensions))) {
            throw new IllegalStateException("查询向量维度 " + dimensions
                + " 与索引维度 " + storedDimensions + " 不一致。请重建知识库索引。");
        }
    }

    private Document toDocument(TextChunk chunk, float[] embedding, boolean checkVectorCompatibility)
        throws IOException {
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
            if (checkVectorCompatibility) {
                ensureVectorCompatible(embedding.length);
            }
            doc.add(new KnnFloatVectorField(FIELD_VECTOR, embedding,
                VectorSimilarityFunction.COSINE));
        }
        return doc;
    }

    private void buildStandaloneIndex(Path path, List<IndexedChunk> chunks, String model, int dimensions)
        throws IOException {
        deleteRecursively(path);
        Files.createDirectories(path);
        try (MMapDirectory dir = new MMapDirectory(path);
             Analyzer az = new SmartChineseAnalyzer();
             IndexWriter writer = new IndexWriter(dir,
                 new IndexWriterConfig(az).setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {
            for (IndexedChunk item : chunks) {
                writer.addDocument(toDocument(item.chunk(), item.embedding(), false));
            }
            writer.commit();
        }
        Properties props = new Properties();
        if (dimensions > 0) props.setProperty(META_EMBEDDING_DIMENSIONS, String.valueOf(dimensions));
        props.setProperty(META_EMBEDDING_MODEL, model != null ? model : "");
        try (OutputStream out = Files.newOutputStream(path.resolve(METADATA_FILE))) {
            props.store(out, "Halo AI Assistant Lucene index metadata");
        }
    }

    private Properties readMetadata() throws IOException {
        Properties props = new Properties();
        if (indexPath == null) return props;
        Path metadataPath = indexPath.resolve(METADATA_FILE);
        if (!Files.exists(metadataPath)) return props;
        try (InputStream in = Files.newInputStream(metadataPath)) {
            props.load(in);
        }
        return props;
    }

    private void writeMetadata(Properties props) throws IOException {
        if (indexPath == null) return;
        Path metadataPath = indexPath.resolve(METADATA_FILE);
        try (OutputStream out = Files.newOutputStream(metadataPath)) {
            props.store(out, "Halo AI Assistant Lucene index metadata");
        }
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
     * 获取所有已索引文章的切片计数。
     * <p>
     * 实现说明：必须按 liveDocs 统计。Lucene 的 {@link TermsEnum#docFreq()}
     * 会包含已删除但尚未段合并的文档，单篇重建后会把旧切片也算进去，导致后台
     * 误显示“关键词覆盖不完整”。
     */
    public Map<String, Integer> getPostChunkCounts() throws IOException {
        ensureInitialized();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            Map<String, Integer> counts = new HashMap<>();
            forEachLiveDoc(searcher, doc -> {
                String postId = doc.get(FIELD_POST_ID);
                if (postId != null) counts.merge(postId, 1, Integer::sum);
            });
            return counts;
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * 统计每篇文章已提取关键词的切片数。
     * <p>
     * 实现说明：{@code keywords} 是 TextField（分词），无法像 postId 那样按单 term
     * 聚合，必须读存储字段判断非空。这里遍历 {@link IndexReader#leaves()} 的
     * liveDocs（仅未删除文档），逐文档读字段——内存常量级（不构造 ScoreDoc 数组），
     * 替代了原先 {@code MatchAllDocsQuery + Integer.MAX_VALUE} 的全表搜索（OOM 风险）。
     */
    public Map<String, Integer> getPostKeywordChunkCounts() throws IOException {
        ensureInitialized();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            Map<String, Integer> counts = new HashMap<>();
            forEachLiveDoc(searcher, doc -> {
                String kw = doc.get(FIELD_KEYWORDS);
                if (kw != null && !kw.isBlank()) {
                    String postId = doc.get(FIELD_POST_ID);
                    if (postId != null) counts.merge(postId, 1, Integer::sum);
                }
            });
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
     * 统计已提取关键词的切片数。遍历 liveDocs 判断 keywords 字段非空，内存常量级。
     * （原实现构造了从未使用的 {@code TermQuery} 死代码 + 全表 MatchAllDocs 搜索。）
     */
    public int countKeywordChunks() {
        try {
            ensureInitialized();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                int[] count = {0};
                forEachLiveDoc(searcher, doc -> {
                    String kw = doc.get(FIELD_KEYWORDS);
                    if (kw != null && !kw.isBlank()) count[0]++;
                });
                return count[0];
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            log.error("[LuceneIndexService] 统计关键词切片失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 遍历当前 searcher 中所有未删除的文档（live docs），逐文档回调。
     * <p>
     * 相比 {@code MatchAllDocsQuery + Integer.MAX_VALUE}，本方法不构造 ScoreDoc
     * 数组、不调用 {@code TopDocs} 聚合，内存占用为常量级（一次只持有一个 Document），
     * 适合统计/扫描类查询，不会因索引规模增长而 OOM。
     * <p>
     * 注意：本方法会读存储字段，调用方应确保只在必要时使用，且已包裹在
     * {@code searcherManager.acquire()/release()} 之内。
     */
    private void forEachLiveDoc(IndexSearcher searcher, java.util.function.Consumer<Document> action)
        throws IOException {
        IndexReader reader = searcher.getIndexReader();
        for (LeafReaderContext ctx : reader.leaves()) {
            LeafReader leaf = ctx.reader();
            Bits liveDocs = leaf.getLiveDocs();
            int maxDoc = leaf.maxDoc();
            StoredFields storedFields = leaf.storedFields();
            for (int docId = 0; docId < maxDoc; docId++) {
                // liveDocs 为 null 表示该 segment 无已删除文档，全部 live
                if (liveDocs == null || liveDocs.get(docId)) {
                    action.accept(storedFields.document(docId));
                }
            }
        }
    }

    @PreDestroy
    public synchronized void close() {
        // 每个资源先摘引用再关，互相不阻断：避免 searcherManager.close() 抛异常
        // 导致 indexWriter 不关闭、锁泄漏到 JVM 退出。
        // 关闭顺序：先关使用者（searcherManager），再关底层数据源
        // （indexWriter → directory → analyzer）。
        SearcherManager sm = searcherManager;
        searcherManager = null;
        closeQuietly(sm);
        IndexWriter iw = indexWriter;
        indexWriter = null;
        closeQuietly(iw);
        MMapDirectory dir = directory;
        directory = null;
        closeQuietly(dir);
        Analyzer az = analyzer;
        analyzer = null;
        closeQuietly(az);
        log.info("[LuceneIndexService] 已关闭");
    }

    /**
     * 静默关闭 Closeable，失败只记日志不抛出 —— 用于 close() 和初始化回滚。
     */
    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            log.warn("[LuceneIndexService] 关闭 {} 失败: {}",
                c.getClass().getSimpleName(), e.getMessage());
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            List<Path> items = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path item : items) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
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

    public record IndexedChunk(
        TextChunk chunk,
        float[] embedding
    ) {}
}
