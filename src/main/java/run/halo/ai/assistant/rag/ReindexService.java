package run.halo.ai.assistant.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.assistant.config.AIProperties;
import run.halo.ai.assistant.llm.LlmClient;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
    // 调到 4 在主流 OpenAI 兼容厂商（智谱/通义等 60~300 RPM）下也基本安全。
    private static final int POST_CONCURRENCY = 4;

    // 失败重试延迟 — 主轮完成后等 2 秒再重试，给 LLM 限流恢复的时间
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    // 失败追踪：postName → title
    private final Map<String, String> failedPosts = new ConcurrentHashMap<>();
    // 关键词提取状态：postName → "ok" | "truncated" | "failed"
    private final Map<String, String> keywordStatus = new ConcurrentHashMap<>();
    // 每篇文章被截断的关键词数：postName → count
    private final Map<String, Integer> keywordTruncated = new ConcurrentHashMap<>();
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
                        return processPost(post, modelConfig, chunkConfig,
                            new AtomicInteger(0), new AtomicInteger(0))
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

    // ===== 异步重建进度 =====

    public record ReindexProgress(
        String phase,               // idle | clearing | listing | processing | completed | error
        int totalArticles,
        int processedArticles,
        int successArticles,
        int failedArticles,
        String currentArticleTitle,
        int totalChunks,
        String errorMessage,
        int percentage,
        String detail,              // 子步骤：提取关键词 / 生成向量 / 写入索引 等
        int truncatedKeywords,       // 被截断关键词的切片数
        int keywordsFailed           // 关键词提取完全失败的文章数
    ) {}

    // 广播进度事件（multicast：多个 SSE 客户端可同时订阅）
    private final Sinks.Many<ReindexProgress> progressSink =
        Sinks.many().multicast().onBackpressureBuffer(256, false);

    // 保存当前快照，新 SSE 连接先拿到当前状态
    private final AtomicReference<ReindexProgress> currentProgress =
        new AtomicReference<>(new ReindexProgress("idle", 0, 0, 0, 0, "", 0, null, 0, "", 0, 0));

    /**
     * 获取进度流 — 先发快照，再接实时更新
     */
    public Flux<ReindexProgress> progressStream() {
        ReindexProgress snapshot = currentProgress.get();
        // 如果已经完成或空闲，只发一次快照就结束
        if ("idle".equals(snapshot.phase()) || "completed".equals(snapshot.phase()) || "error".equals(snapshot.phase())) {
            return Flux.just(snapshot);
        }
        return Flux.concat(Flux.just(snapshot), progressSink.asFlux());
    }

    /**
     * 获取当前进度快照
     */
    public ReindexProgress currentProgress() {
        return currentProgress.get();
    }

    /**
     * 异步触发全量重建，通过 progressStream() 推送进度。
     * 如果已有重建在进行中则返回 error。
     * pipeline 在 boundedElastic 后台线程执行，本方法立即返回。
     */
    public Mono<Void> startReindexAsync() {
        if (indexing) {
            return Mono.error(new IllegalStateException("索引正在重建中"));
        }
        return aiProperties.getModelConfig().flatMap(modelConfig -> {
            if (modelConfig.getEmbeddingApiKey() == null
                || modelConfig.getEmbeddingApiKey().isBlank()) {
                return Mono.error(new IllegalStateException("Embedding API Key 未配置"));
            }
            return aiProperties.getChunkConfig().flatMap(chunkConfig -> {
                log.info("[ReindexService] 开始全量重建索引（异步）...");
                indexing = true;
                failedPosts.clear();
                keywordStatus.clear();
                keywordTruncated.clear();

                List<String> failedTitles = Collections.synchronizedList(new ArrayList<>());
                AtomicInteger successCount = new AtomicInteger(0);
                AtomicInteger failCount = new AtomicInteger(0);
                AtomicInteger chunkSum = new AtomicInteger(0);
                AtomicInteger processed = new AtomicInteger(0);
                AtomicInteger truncatedTotal = new AtomicInteger(0);
                AtomicInteger keywordsFailedTotal = new AtomicInteger(0);

                // phase: clearing
                emitProgress("clearing", 0, 0, 0, 0, "", 0, null, "", 0, 0);

                // 构建完整 pipeline，在后台订阅执行
                Mono.fromCallable(() -> { luceneIndexService.clearAll(); return true; })
                    .subscribeOn(Schedulers.boundedElastic())
                    .thenMany(listPublishedPosts().collectList())
                    .flatMap(posts -> {
                        int total = posts.size();
                        if (total == 0) {
                            log.info("[ReindexService] 没有已发布的文章，跳过");
                            emitProgress("completed", 0, 0, 0, 0, "", 0, null, "", 0, 0);
                            return Flux.empty();
                        }
                        emitProgress("listing", total, 0, 0, 0, "", 0, null, "", 0, 0);

                        // 构建 postName → Post 映射，供重试阶段用
                        Map<String, Post> postMap = new LinkedHashMap<>();
                        posts.forEach(p -> postMap.put(p.getMetadata().getName(), p));

                        return Flux.fromIterable(posts)
                            .flatMap(post -> {
                                String title = post.getSpec().getTitle();
                                String postName = post.getMetadata().getName();
                                // 发射子步骤提示
                                String step = chunkConfig.isAutoKeywords()
                                    ? "提取关键词" : "生成向量";
                                emitProgress("processing", total,
                                    processed.get(), successCount.get(), failCount.get(),
                                    title, chunkSum.get(), null, step, 0, 0);
                                return processPost(post, modelConfig, chunkConfig,
                                    truncatedTotal, keywordsFailedTotal)
                                    .doOnNext(chunks -> {
                                        if (chunks > 0) {
                                            successCount.incrementAndGet();
                                            chunkSum.addAndGet(chunks);
                                        } else {
                                            failCount.incrementAndGet();
                                        }
                                        int done = processed.incrementAndGet();
                                        emitProgress("processing", total, done,
                                            successCount.get(), failCount.get(),
                                            title, chunkSum.get(), null, "写入索引", 0, 0);
                                    })
                                    .onErrorResume(e -> {
                                        log.error("[ReindexService] 处理文章 '{}' 失败: {}", title, e.getMessage());
                                        failedTitles.add(title);
                                        failedPosts.put(postName, title);
                                        failCount.incrementAndGet();
                                        int done = processed.incrementAndGet();
                                        emitProgress("processing", total, done,
                                            successCount.get(), failCount.get(),
                                            title, chunkSum.get(), null, "失败", 0, 0);
                                        return Mono.just(0);
                                    });
                            }, POST_CONCURRENCY)
                            // 主轮完成后，对失败文章自动重试一轮
                            .then(Mono.defer(() -> {
                                if (failedPosts.isEmpty()) return Mono.empty();

                                // 收集失败的 postName，清空失败状态准备重试
                                List<String> retryNames = new ArrayList<>(failedPosts.keySet());
                                int retryTotal = retryNames.size();
                                log.info("[ReindexService] 主轮完成，{} 篇失败，{} 秒后开始重试",
                                    retryTotal, RETRY_DELAY.getSeconds());
                                emitProgress("retrying", total, total,
                                    successCount.get(), failCount.get(),
                                    "", chunkSum.get(), null,
                                    "重试失败文章（" + retryTotal + " 篇）",
                                    truncatedTotal.get(), keywordsFailedTotal.get());

                                AtomicInteger retryDone = new AtomicInteger(0);
                                AtomicInteger retryRecovered = new AtomicInteger(0);

                                return Mono.delay(RETRY_DELAY)
                                    .thenMany(Flux.fromIterable(retryNames))
                                    .concatMap(postName -> {
                                        Post post = postMap.get(postName);
                                        if (post == null) return Mono.just(0);

                                        String title = post.getSpec().getTitle();
                                        emitProgress("retrying", total, total,
                                            successCount.get(), failCount.get(),
                                            title, chunkSum.get(), null, "重试中",
                                            truncatedTotal.get(), keywordsFailedTotal.get());

                                        return processPost(post, modelConfig, chunkConfig,
                                            truncatedTotal, keywordsFailedTotal)
                                            .doOnNext(chunks -> {
                                                int done = retryDone.incrementAndGet();
                                                if (chunks > 0) {
                                                    // 重试成功：更新计数
                                                    retryRecovered.incrementAndGet();
                                                    failedPosts.remove(postName);
                                                    failedTitles.remove(title);
                                                    failCount.decrementAndGet();
                                                    successCount.incrementAndGet();
                                                    chunkSum.addAndGet(chunks);
                                                    log.info("[ReindexService] 重试成功 '{}': {} 个 chunk",
                                                        title, chunks);
                                                }
                                                emitProgress("retrying", total, total,
                                                    successCount.get(), failCount.get(),
                                                    title, chunkSum.get(), null,
                                                    "重试 " + done + "/" + retryTotal,
                                                    truncatedTotal.get(), keywordsFailedTotal.get());
                                            })
                                            .onErrorResume(e -> {
                                                log.warn("[ReindexService] 重试仍失败 '{}': {}",
                                                    title, e.getMessage());
                                                int done = retryDone.incrementAndGet();
                                                emitProgress("retrying", total, total,
                                                    successCount.get(), failCount.get(),
                                                    title, chunkSum.get(), null,
                                                    "重试 " + done + "/" + retryTotal,
                                                    truncatedTotal.get(), keywordsFailedTotal.get());
                                                return Mono.just(0);
                                            });
                                    })
                                    .then(Mono.fromRunnable(() ->
                                        log.info("[ReindexService] 重试完成，恢复 {} 篇，仍有 {} 篇失败",
                                            retryRecovered.get(), failedPosts.size())
                                    ));
                            }))
                            .then(Mono.fromRunnable(() -> {
                                int totalChunks = chunkSum.get();
                                log.info("[ReindexService] 全量重建完成，共 {} 个 chunk，成功 {} 篇，失败 {} 篇",
                                    totalChunks, successCount.get(), failCount.get());
                                emitProgress("completed", total, total,
                                    successCount.get(), failCount.get(),
                                    "", totalChunks, null, "",
                                    truncatedTotal.get(), keywordsFailedTotal.get());
                            }));
                    })
                    .then()
                    .doFinally(signal -> indexing = false)
                    // 后台订阅 — 不阻塞 HTTP 响应
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                        v -> {},
                        e -> {
                            log.error("[ReindexService] 重建异常: {}", e.getMessage());
                            emitProgress("error", 0, 0, 0, 0, "", 0, e.getMessage(), "", 0, 0);
                            indexing = false;
                        }
                    );

                return Mono.empty(); // 立即返回
            });
        });
    }

    private void emitProgress(String phase, int total, int processed,
                              int success, int failed, String currentTitle,
                              int chunks, String error, String detail,
                              int truncatedKeywords, int keywordsFailed) {
        int pct = total > 0 ? Math.min(100, processed * 100 / total) : 0;
        if ("completed".equals(phase) || "error".equals(phase)) pct = 100;
        if ("clearing".equals(phase) || "listing".equals(phase)) pct = 0;
        ReindexProgress p = new ReindexProgress(phase, total, processed,
            success, failed, currentTitle != null ? currentTitle : "",
            chunks, error, pct, detail != null ? detail : "",
            truncatedKeywords, keywordsFailed);
        currentProgress.set(p);
        progressSink.tryEmitNext(p);
    }

    public Map<String, String> getFailedPosts() {
        return Map.copyOf(failedPosts);
    }

    public Map<String, String> getKeywordStatus() {
        return Map.copyOf(keywordStatus);
    }

    public Map<String, Integer> getKeywordTruncated() {
        return Map.copyOf(keywordTruncated);
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
                        return processPost(post, modelConfig, chunkConfig,
                            new AtomicInteger(0), new AtomicInteger(0));
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

    // ===== 单篇带进度重建 =====

    private static final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * 单篇文章带进度的重建索引 — 在后台线程执行，通过 Sinks 广播进度 JSON
     * 返回 Sink，调用方通过 sink.asFlux() 订阅进度流
     */
    public Sinks.Many<String> reindexPostWithProgress(String postName) {
        Sinks.Many<String> sink = Sinks.many().replay().latest();

        aiProperties.getModelConfig().flatMap(modelConfig -> {
            if (modelConfig.getEmbeddingApiKey() == null
                || modelConfig.getEmbeddingApiKey().isBlank()) {
                return Mono.error(new IllegalStateException("Embedding API Key 未配置"));
            }
            return aiProperties.getChunkConfig().flatMap(chunkConfig ->
                extensionClient.fetch(Post.class, postName)
                    .flatMap(post -> {
                        if (!post.isPublished() || post.isDeleted()
                            || post.getSpec() == null
                            || !Boolean.TRUE.equals(post.getSpec().getPublish())
                            || !Post.isPublic(post.getSpec())) {
                            try {
                                luceneIndexService.deleteByPostId(postName);
                            } catch (Exception ex) {
                                log.error("[ReindexService] 清除索引失败: {}", ex.getMessage());
                            }
                            emitSingle(sink, "done", "", 0, 100);
                            return Mono.just(0);
                        }
                        return processPostWithProgress(post, modelConfig, chunkConfig, sink);
                    })
            );
        })
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(
            chunks -> {
                emitSingle(sink, "done", "", chunks, 100);
                sink.tryEmitComplete();
            },
            e -> {
                log.error("[ReindexService] 单篇重建失败 {}: {}", postName, e.getMessage());
                emitSingle(sink, "error", e.getMessage(), 0, 0);
                sink.tryEmitComplete();
            }
        );

        return sink;
    }

    private Mono<Integer> processPostWithProgress(Post post,
                                                    AIProperties.ModelConfig modelConfig,
                                                    AIProperties.ChunkConfig chunkConfig,
                                                    Sinks.Many<String> sink) {
        String postName = post.getMetadata().getName();
        String title = post.getSpec().getTitle();

        emitSingle(sink, "fetching_content", "", 0, 5);

        // 短暂延迟，让前端 SSE 连接有时间建立，用户能看到阶段变化
        return Mono.delay(Duration.ofMillis(300))
            .then(postContentService.getReleaseContent(postName))
            .map(contentWrapper -> {
                String content = contentWrapper.getRaw();
                if (content == null || content.isBlank()) {
                    content = contentWrapper.getContent();
                }
                return content != null ? content : "";
            })
            .defaultIfEmpty("")
            .flatMap(content -> {
                if (content.isBlank()) {
                    return Mono.just(0);
                }

                emitSingle(sink, "chunking", "", 0, 10);
                List<TextChunk> chunks = documentChunker.chunk(postName, title, content, chunkConfig);
                if (chunks.isEmpty()) {
                    return Mono.just(0);
                }

                Mono<List<TextChunk>> chunksMono;
                if (chunkConfig.isAutoKeywords() && chunkConfig.getAutoKeywordsCount() > 0) {
                    emitSingle(sink, "keywords", "", 0, 20);
                    int maxTokens = Math.max(256, chunkConfig.getKeywordsMaxTokens());
                    int batchSize = Math.max(5, chunkConfig.getKeywordsBatchSize());
                    AtomicInteger truncated = new AtomicInteger(0);
                    AtomicInteger kwFailed = new AtomicInteger(0);
                    chunksMono = extractKeywords(chunks, chunkConfig.getAutoKeywordsCount(),
                        maxTokens, batchSize)
                        .map(result -> {
                            truncated.addAndGet(result.truncatedCount());
                            if (result.failed()) {
                                kwFailed.incrementAndGet();
                                keywordStatus.put(postName, "failed");
                            } else if (result.truncatedCount() > 0) {
                                keywordStatus.put(postName, "truncated");
                                keywordTruncated.put(postName, result.truncatedCount());
                            } else {
                                keywordStatus.put(postName, "ok");
                            }
                            return result.chunks();
                        });
                } else {
                    chunksMono = Mono.just(chunks);
                }

                return chunksMono.flatMap(enrichedChunks -> {
                    emitSingle(sink, "embedding", "", 0, 60);
                    return Mono.fromCallable(() -> {
                        luceneIndexService.deleteByPostId(postName);
                        return true;
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .then(embedAndIndexChunks(enrichedChunks, modelConfig));
                });
            });
    }

    private void emitSingle(Sinks.Many<String> sink, String stage, String error, int chunks, int percent) {
        try {
            ObjectNode node = jsonMapper.createObjectNode();
            node.put("stage", stage);
            node.put("error", error != null ? error : "");
            node.put("chunks", chunks);
            node.put("percent", percent);
            sink.tryEmitNext(jsonMapper.writeValueAsString(node));
        } catch (Exception e) {
            log.warn("[ReindexService] 进度序列化失败: {}", e.getMessage());
        }
    }

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
                                       AIProperties.ChunkConfig chunkConfig,
                                       AtomicInteger truncatedKeywords,
                                       AtomicInteger keywordsFailed) {
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

                // 自动提取关键词（如果开启）
                Mono<List<TextChunk>> chunksMono;
                if (chunkConfig.isAutoKeywords() && chunkConfig.getAutoKeywordsCount() > 0) {
                    int maxTokens = Math.max(256, chunkConfig.getKeywordsMaxTokens());
                    int batchSize = Math.max(5, chunkConfig.getKeywordsBatchSize());
                    chunksMono = extractKeywords(chunks, chunkConfig.getAutoKeywordsCount(),
                        maxTokens, batchSize)
                        .map(result -> {
                            truncatedKeywords.addAndGet(result.truncatedCount());
                            if (result.failed()) {
                                keywordsFailed.incrementAndGet();
                                keywordStatus.put(postName, "failed");
                            } else if (result.truncatedCount() > 0) {
                                keywordStatus.put(postName, "truncated");
                                keywordTruncated.put(postName, result.truncatedCount());
                            } else {
                                keywordStatus.put(postName, "ok");
                            }
                            return result.chunks();
                        });
                } else {
                    chunksMono = Mono.just(chunks);
                }

                return chunksMono.flatMap(enrichedChunks ->
                    Mono.fromCallable(() -> {
                        luceneIndexService.deleteByPostId(postName);
                        return true;
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .then(embedAndIndexChunks(enrichedChunks, modelConfig))
                );
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

    /**
     * 调用 LLM 为切片提取关键词，按每批 20 个切片分批以避免 token 溢出。
     * 返回 KeywordExtractResult 包含富关键词的切片列表和被截断的切片数。
     */
    private Mono<KeywordExtractResult> extractKeywords(List<TextChunk> chunks, int count,
                                                        int maxTokens, int batchSize) {
        int safeBatchSize = Math.max(1, Math.min(batchSize, 50)); // 钳制 1~50
        List<List<TextChunk>> batches = splitIntoBatches(chunks, safeBatchSize);
        AtomicInteger truncatedTotal = new AtomicInteger(0);

        return aiProperties.getModelConfig()
            .flatMapMany(modelConfig ->
                Flux.fromIterable(batches)
                    .index()
                    .concatMap(indexed -> {
                        long batchIdx = indexed.getT1();
                        List<TextChunk> batch = indexed.getT2();

                        StringBuilder sb = new StringBuilder();
                        sb.append("为以下文本分别提取").append(count)
                            .append("个最相关的关键词，用逗号分隔。只输出关键词，每行一个文本，不要加序号或任何解释。\n\n");
                        for (int i = 0; i < batch.size(); i++) {
                            sb.append("文本").append(i + 1).append(": ")
                                .append(batch.get(i).content()).append("\n\n");
                        }

                        return llmClient.chatInternal(
                            modelConfig.getChatBaseUrl(),
                            modelConfig.getChatApiKey(),
                            modelConfig.getChatModel(),
                            List.of(Map.of("role", "user", "content", sb.toString())),
                            0.1f,
                            maxTokens
                        )
                        .map(response -> {
                            String[] lines = response.trim().split("\n");
                            int truncated = Math.max(0, batch.size() - lines.length);
                            truncatedTotal.addAndGet(truncated);
                            if (truncated > 0) {
                                log.warn("[ReindexService] 关键词响应截断: {}/{} 个切片获取到关键词",
                                    lines.length, batch.size());
                            }
                            List<TextChunk> result = new ArrayList<>();
                            for (int i = 0; i < batch.size(); i++) {
                                TextChunk original = batch.get(i);
                                List<String> keywords;
                                if (i < lines.length) {
                                    String line = lines[i].replaceAll("[,，、；;]", ",").trim();
                                    keywords = java.util.Arrays.stream(line.split(","))
                                        .map(String::trim)
                                        .filter(k -> !k.isEmpty())
                                        .limit(count)
                                        .toList();
                                } else {
                                    keywords = List.of();
                                }
                                result.add(new TextChunk(
                                    original.id(), original.postId(), original.postTitle(),
                                    original.content(), original.chunkIndex(), keywords
                                ));
                            }
                            return result;
                        });
                    })
            )
            .collectList()
            .map(batchResults -> {
                List<TextChunk> all = new ArrayList<>();
                for (List<TextChunk> batch : batchResults) {
                    all.addAll(batch);
                }
                all.sort(java.util.Comparator.comparingInt(TextChunk::chunkIndex));
                return new KeywordExtractResult(all, truncatedTotal.get(), false);
            })
            .onErrorResume(e -> {
                log.warn("[ReindexService] 关键词提取失败，跳过: {}", e.getMessage());
                return Mono.just(new KeywordExtractResult(chunks, 0, true));
            });
    }

    private record KeywordExtractResult(List<TextChunk> chunks, int truncatedCount,
                                         boolean failed) {}
}
