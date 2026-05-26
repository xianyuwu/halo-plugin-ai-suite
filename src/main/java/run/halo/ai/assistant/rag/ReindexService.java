package run.halo.ai.assistant.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.assistant.config.AIProperties;
import run.halo.ai.assistant.llm.LlmClient;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 索引服务 — 负责全量重建索引和增量更新
 *
 * 核心流程：
 * 1. 列出所有已发布的 Post
 * 2. 获取每篇文章的 Markdown 内容（通过 PostContentService）
 * 3. 切片（DocumentChunker）
 * 4. 批量 Embedding（LlmClient）
 * 5. 写入 Lucene 索引（LuceneIndexService）
 *
 * Lucene I/O 是阻塞的，所有操作都在 Schedulers.boundedElastic() 上执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReindexService {

    private final ReactiveExtensionClient extensionClient;
    private final PostContentService postContentService;
    private final DocumentChunker documentChunker;
    private final LuceneIndexService luceneIndexService;
    private final LlmClient llmClient;
    private final AIProperties aiProperties;

    // 批量 embedding 的批次大小
    // 每个文本会拼接标题+内容后发给 embedding API，响应含 N 个 1024 维向量
    // Spring WebFlux 默认 buffer 上限 262144 字节，向量响应容易超限
    // 5 个 chunk × ~8KB/向量 ≈ 40KB，远低于上限，留足余量
    private static final int EMBED_BATCH_SIZE = 5;

    // 跨文章并发上限 — 同时最多 4 篇文章在调 embedding。
    // Reactor flatMap 默认并发是 256，对几乎所有 embedding 厂商都会触发限流，
    // 调到 4 在国内厂商（智谱/通义/百度等 60~300 RPM）下也基本安全。
    private static final int POST_CONCURRENCY = 4;

    // 失败追踪：postName → title
    private final Map<String, String> failedPosts = new ConcurrentHashMap<>();
    private volatile boolean indexing = false;

    /**
     * 全量重建索引 — 删除旧索引，重新索引所有已发布公开文章
     *
     * @return ReindexResult 包含成功/失败详情
     */
    public Mono<ReindexResult> reindexAllWithDetails() {
        return aiProperties.getModelConfig().flatMap(modelConfig -> {
            if (modelConfig.getEmbeddingApiKey() == null
                || modelConfig.getEmbeddingApiKey().isBlank()) {
                return Mono.error(new IllegalStateException("Embedding API Key 未配置"));
            }

            return aiProperties.getChunkConfig().flatMap(chunkConfig -> {
                log.info("[ReindexService] 开始全量重建索引...");
                indexing = true;
                failedPosts.clear();

                List<String> failedTitles = Collections.synchronizedList(new ArrayList<>());
                AtomicInteger successCount = new AtomicInteger(0);

                return Mono.fromCallable(() -> {
                        luceneIndexService.clearAll();
                        return true;
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .thenMany(listPublishedPosts())
                    .flatMap(post -> {
                        String title = post.getSpec().getTitle();
                        String postName = post.getMetadata().getName();
                        return processPost(post, modelConfig, chunkConfig)
                            .doOnNext(chunks -> {
                                if (chunks > 0) successCount.incrementAndGet();
                            })
                            .onErrorResume(e -> {
                                log.error("[ReindexService] 处理文章 '{}' 失败: {}", title, e.getMessage());
                                failedTitles.add(title);
                                failedPosts.put(postName, title);
                                return Mono.just(0);
                            });
                    }, POST_CONCURRENCY)
                    .collectList()
                    .map(results -> {
                        int totalChunks = results.stream().mapToInt(Integer::intValue).sum();
                        log.info("[ReindexService] 全量重建完成，共 {} 个 chunk，成功 {} 篇，失败 {} 篇",
                            totalChunks, successCount.get(), failedTitles.size());
                        return new ReindexResult(totalChunks, successCount.get(),
                            failedTitles.size(), List.copyOf(failedTitles));
                    })
                    .doFinally(signal -> indexing = false);
            });
        });
    }

    /**
     * 兼容旧调用 — 只返回 chunk 数
     */
    public Mono<Integer> reindexAll() {
        return reindexAllWithDetails().map(ReindexResult::totalChunks);
    }

    public record ReindexResult(
        int totalChunks,
        int successPosts,
        int failedPosts,
        List<String> failedTitles
    ) {}

    public Map<String, String> getFailedPosts() {
        return Map.copyOf(failedPosts);
    }

    public boolean isIndexing() {
        return indexing;
    }

    /**
     * 增量索引单篇文章 — 发布/更新时调用
     * 防御性检查：未发布/已删除的文章直接删除索引，不重新索引
     */
    public Mono<Integer> reindexPost(String postName) {
        return Mono.zip(aiProperties.getModelConfig(), aiProperties.getChunkConfig())
            .flatMap(tuple -> {
                AIProperties.ModelConfig modelConfig = tuple.getT1();
                AIProperties.ChunkConfig chunkConfig = tuple.getT2();

                return extensionClient.fetch(Post.class, postName)
                    .flatMap(post -> {
                        // 未发布、已删除、私有 → 清除索引，不重新索引
                        if (!post.isPublished() || post.isDeleted()
                            || post.getSpec() == null
                            || !Boolean.TRUE.equals(post.getSpec().getPublish())
                            || !Post.isPublic(post.getSpec())) {
                            log.debug("[ReindexService] 文章 {} 未发布、已删除或私有，清除索引", postName);
                            return deletePostIndex(postName).thenReturn(0);
                        }
                        return processPost(post, modelConfig, chunkConfig);
                    });
            });
    }

    /**
     * 删除一篇文章的索引
     */
    public Mono<Void> deletePostIndex(String postName) {
        return Mono.fromRunnable(() -> {
            try {
                luceneIndexService.deleteByPostId(postName);
            } catch (Exception e) {
                log.error("[ReindexService] 删除索引失败: {}", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    // ===== 内部方法 =====

    /**
     * 列出所有已发布的 Post
     */
    private Flux<Post> listPublishedPosts() {
        return extensionClient.list(Post.class,
            post -> post.isPublished()
                && !post.isDeleted()
                && post.getSpec() != null
                && Boolean.TRUE.equals(post.getSpec().getPublish())
                && Post.isPublic(post.getSpec()),
            null);
    }

    /**
     * 处理单篇文章：获取内容 → 切片 → embed → 索引
     */
    private Mono<Integer> processPost(Post post,
                                       AIProperties.ModelConfig modelConfig,
                                       AIProperties.ChunkConfig chunkConfig) {
        String postName = post.getMetadata().getName();
        String title = post.getSpec().getTitle();

        return postContentService.getReleaseContent(postName)
            .map(contentWrapper -> {
                // 拿到 Markdown 原文
                String content = contentWrapper.getRaw();
                if (content == null || content.isBlank()) {
                    content = contentWrapper.getContent(); // 降级到 HTML
                }
                return content != null ? content : "";
            })
            .defaultIfEmpty("")
            .flatMap(content -> {
                if (content.isBlank()) {
                    log.debug("[ReindexService] 文章 '{}' 内容为空，跳过", title);
                    return Mono.just(0);
                }

                // 切片
                List<TextChunk> chunks = documentChunker.chunk(postName, title, content, chunkConfig);
                if (chunks.isEmpty()) {
                    return Mono.just(0);
                }

                // 先删除旧索引，再重建
                return Mono.fromCallable(() -> {
                        luceneIndexService.deleteByPostId(postName);
                        return true;
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    // 批量 embedding + 索引
                    .then(embedAndIndexChunks(chunks, modelConfig));
            });
    }

    /**
     * 批量 embed 并索引 — 按 EMBED_BATCH_SIZE 分批调 embedding API
     */
    private Mono<Integer> embedAndIndexChunks(List<TextChunk> chunks,
                                               AIProperties.ModelConfig modelConfig) {
        AtomicInteger indexed = new AtomicInteger(0);
        List<List<TextChunk>> batches = splitIntoBatches(chunks, EMBED_BATCH_SIZE);

        return Flux.fromIterable(batches)
            .concatMap(batch -> embedBatch(batch, modelConfig)
                .doOnNext(embeddings -> {
                    try {
                        for (int i = 0; i < batch.size(); i++) {
                            float[] embedding = i < embeddings.size() ? embeddings.get(i) : null;
                            luceneIndexService.indexChunk(batch.get(i), embedding);
                            indexed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.error("[ReindexService] 索引写入失败: {}", e.getMessage());
                    }
                }))
            .then(Mono.fromCallable(() -> {
                luceneIndexService.commit();
                return indexed.get();
            }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 调用 Embedding API 获取一批切片的向量
     *
     * 失败处理：直接让错误冒泡。原本"批量失败 → 逐个 embed"的降级被移除，
     * 因为批量失败通常意味着限流或服务异常，逐个发只会让 RPS 翻倍、火上浇油。
     * 错误冒泡后会被上层 processPost 的 onErrorResume 接住，单篇置 0 chunk 继续后续文章。
     */
    private Mono<List<float[]>> embedBatch(List<TextChunk> batch,
                                            AIProperties.ModelConfig modelConfig) {
        List<String> texts = batch.stream()
            .map(chunk -> chunk.postTitle() + "\n" + chunk.content())
            .toList();

        return llmClient.embedBatch(
            modelConfig.getEmbeddingBaseUrl(),
            modelConfig.getEmbeddingApiKey(),
            modelConfig.getEmbeddingModel(),
            texts,
            modelConfig.getEmbeddingDimensions()
        );
    }

    /**
     * 把列表按 batchSize 分批
     */
    private <T> List<List<T>> splitIntoBatches(List<T> list, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            batches.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return batches;
    }
}
