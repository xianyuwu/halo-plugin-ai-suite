package cn.rainwu.halo.ai.suite.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.llm.LlmClient;
import cn.rainwu.halo.ai.suite.llm.UsageScenario;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final int KEYWORD_OUTPUT_TOKENS = 256;
    private static final List<String> KEYWORD_STOP_WORDS = List.of(
        "一个", "一些", "这个", "这些", "我们", "你们", "他们", "进行", "通过", "使用", "可以",
        "需要", "如果", "因为", "所以", "时候", "里面", "然后", "没有", "不是", "以及",
        "the", "and", "for", "with", "from", "this", "that", "http", "https"
    );

    // 跨文章并发上限 — 同时最多 4 篇文章在调 embedding。
    // Reactor flatMap 默认并发是 256，容易触发 AI Foundation 背后的模型服务限流。
    private static final int POST_CONCURRENCY = 4;

    // 失败重试延迟 — 主轮完成后等 2 秒再重试，给 LLM 限流恢复的时间
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    // 失败追踪：postName → title
    private final Map<String, String> failedPosts = new ConcurrentHashMap<>();
    // 关键词提取状态：postName → "ok" | "truncated" | "failed"
    private final Map<String, String> keywordStatus = new ConcurrentHashMap<>();
    // 每篇文章被截断的关键词数：postName → count
    private final Map<String, Integer> keywordTruncated = new ConcurrentHashMap<>();
    private final AtomicBoolean indexing = new AtomicBoolean(false);

    /**
     * 全量重建索引 — 删除旧索引，重新索引所有已发布公开文章
     *
     * @return ReindexResult 包含成功/失败详情
     */
    public Mono<ReindexResult> reindexAllWithDetails() {
        if (!indexing.compareAndSet(false, true)) {
            return Mono.error(new IllegalStateException("索引正在重建中"));
        }
        return aiProperties.getModelConfig().flatMap(modelConfig -> {
            return aiProperties.getChunkConfig().flatMap(chunkConfig -> {
                log.info("[ReindexService] 开始全量重建索引...");
                failedPosts.clear();

                List<String> failedTitles = Collections.synchronizedList(new ArrayList<>());
                List<PostIndexData> preparedPosts = Collections.synchronizedList(new ArrayList<>());
                AtomicInteger successCount = new AtomicInteger(0);

                return listPublishedPosts()
                    .flatMap(post -> {
                        String title = post.getSpec().getTitle();
                        String postName = post.getMetadata().getName();
                        return preparePostIndex(post, modelConfig, chunkConfig,
                            new AtomicInteger(0), new AtomicInteger(0))
                            .doOnNext(chunks -> {
                                if (chunks.totalChunks() > 0) successCount.incrementAndGet();
                            })
                            .onErrorResume(e -> {
                                log.error("[ReindexService] 处理文章 '{}' 失败: {}", title, e.getMessage());
                                failedTitles.add(title);
                                failedPosts.put(postName, title);
                                return Mono.just(PostIndexData.empty(postName));
                            });
                    }, POST_CONCURRENCY)
                    .collectList()
                    .flatMap(results -> {
                        int totalChunks = results.stream().mapToInt(PostIndexData::totalChunks).sum();
                        List<LuceneIndexService.IndexedChunk> indexedChunks = results.stream()
                            .flatMap(data -> data.chunks().stream())
                            .toList();
                        if (!failedTitles.isEmpty()) {
                            return Mono.error(new IllegalStateException(
                                "部分文章重建失败，已保留旧索引。失败文章：" + String.join("、", failedTitles)));
                        }
                        return Mono.fromCallable(() -> {
                                luceneIndexService.replaceAll(indexedChunks,
                                    modelConfig.getEffectiveEmbeddingModel(), modelConfig.getEmbeddingDimensions());
                                return totalChunks;
                            })
                            .subscribeOn(Schedulers.boundedElastic());
                    })
                    .map(totalChunks -> {
                        log.info("[ReindexService] 全量重建完成，共 {} 个 chunk，成功 {} 篇，失败 {} 篇",
                            totalChunks, successCount.get(), failedTitles.size());
                        return new ReindexResult(totalChunks, successCount.get(),
                            failedTitles.size(), List.copyOf(failedTitles));
                    })
                    .doFinally(signal -> indexing.set(false));
            });
        }).doOnError(e -> indexing.set(false));
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
        if (!indexing.compareAndSet(false, true)) {
            return Mono.error(new IllegalStateException("索引正在重建中"));
        }
        return aiProperties.getModelConfig().flatMap(modelConfig -> {
            return aiProperties.getChunkConfig().flatMap(chunkConfig -> {
                log.info("[ReindexService] 开始全量重建索引（异步）...");
                failedPosts.clear();
                keywordStatus.clear();
                keywordTruncated.clear();

                List<String> failedTitles = Collections.synchronizedList(new ArrayList<>());
                List<PostIndexData> preparedPosts = Collections.synchronizedList(new ArrayList<>());
                AtomicInteger successCount = new AtomicInteger(0);
                AtomicInteger failCount = new AtomicInteger(0);
                AtomicInteger chunkSum = new AtomicInteger(0);
                AtomicInteger processed = new AtomicInteger(0);
                AtomicInteger truncatedTotal = new AtomicInteger(0);
                AtomicInteger keywordsFailedTotal = new AtomicInteger(0);

                // phase: preparing
                emitProgress("preparing", 0, 0, 0, 0, "", 0, null, "准备临时索引", 0, 0);

                // 构建完整 pipeline，在后台订阅执行
                listPublishedPosts().collectList()
                    .flatMap(posts -> {
                        int total = posts.size();
                        if (total == 0) {
                            log.info("[ReindexService] 没有已发布的文章，跳过");
                            emitProgress("completed", 0, 0, 0, 0, "", 0, null, "", 0, 0);
                            return Mono.empty();
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
                                return preparePostIndex(post, modelConfig, chunkConfig,
                                    truncatedTotal, keywordsFailedTotal)
                                    .doOnNext(data -> {
                                        int chunks = data.totalChunks();
                                        if (chunks > 0) {
                                            successCount.incrementAndGet();
                                            chunkSum.addAndGet(chunks);
                                            preparedPosts.add(data);
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
                                        return Mono.just(PostIndexData.empty(postName));
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
	                                        if (post == null) return Mono.empty();

                                        String title = post.getSpec().getTitle();
                                        emitProgress("retrying", total, total,
                                            successCount.get(), failCount.get(),
                                            title, chunkSum.get(), null, "重试中",
                                            truncatedTotal.get(), keywordsFailedTotal.get());

                                        return preparePostIndex(post, modelConfig, chunkConfig,
                                            truncatedTotal, keywordsFailedTotal)
                                            .doOnNext(data -> {
                                                int chunks = data.totalChunks();
                                                int done = retryDone.incrementAndGet();
                                                if (chunks > 0) {
                                                    // 重试成功：更新计数
                                                    retryRecovered.incrementAndGet();
                                                    failedPosts.remove(postName);
                                                    failedTitles.remove(title);
                                                    failCount.decrementAndGet();
                                                    successCount.incrementAndGet();
                                                    chunkSum.addAndGet(chunks);
                                                    preparedPosts.add(data);
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
                                                return Mono.just(PostIndexData.empty(postName));
                                            });
                                    })
                                    .then(Mono.fromRunnable(() ->
                                        log.info("[ReindexService] 重试完成，恢复 {} 篇，仍有 {} 篇失败",
                                            retryRecovered.get(), failedPosts.size())
	                                    ));
	                            }))
	                            .then(Mono.defer(() -> {
	                                if (!failedPosts.isEmpty()) {
	                                    return Mono.<Void>error(new IllegalStateException(
	                                        "部分文章重建失败，已保留旧索引。失败文章：" + String.join("、", failedPosts.values())));
	                                }
                                List<LuceneIndexService.IndexedChunk> allChunks = preparedPosts.stream()
                                    .flatMap(data -> data.chunks().stream())
                                    .toList();
                                return Mono.fromRunnable(() -> {
                                    try {
                                        luceneIndexService.replaceAll(allChunks,
                                            modelConfig.getEffectiveEmbeddingModel(),
                                            modelConfig.getEmbeddingDimensions());
                                    } catch (Exception e) {
                                        throw new IllegalStateException(e.getMessage(), e);
                                    }
                                }).subscribeOn(Schedulers.boundedElastic());
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
                    .doFinally(signal -> indexing.set(false))
                    // 后台订阅 — 不阻塞 HTTP 响应
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                        v -> {},
                        e -> {
                            log.error("[ReindexService] 重建异常: {}", e.getMessage());
                            emitProgress("error", 0, 0, 0, 0, "", 0, e.getMessage(), "", 0, 0);
                            indexing.set(false);
                        }
                    );

                return Mono.<Void>empty(); // 立即返回
            });
        }).doOnError(e -> indexing.set(false));
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
        return indexing.get();
    }

    /**
     * 增量索引单篇文章 — 发布/更新时调用
     * 防御性检查：未发布/已删除的文章直接删除索引，不重新索引
     */
    public Mono<Integer> reindexPost(String postName) {
        if (indexing.get()) {
            return Mono.error(new IllegalStateException("全量索引正在重建中，请稍后重试"));
        }
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
        if (indexing.get()) {
            return Mono.error(new IllegalStateException("全量索引正在重建中，请稍后重试"));
        }
        return Mono.fromRunnable(() -> {
            try {
                luceneIndexService.deleteByPostId(postName);
            } catch (Exception e) {
                log.error("[ReindexService] 删除索引失败: {}", e.getMessage());
                throw new IllegalStateException(e.getMessage(), e);
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
        // CAS 占用 indexing 标志: 原实现仅 get() 检查(非 CAS), 不占标志, 多个调用方可
        // 同时进入并行调 embedding + 写 Lucene. CAS 占用后, 在成功/失败回调里释放,
        // 确保与全量重建互斥, 且并发调用快速失败.
        if (!indexing.compareAndSet(false, true)) {
            emitSingle(sink, "error", "全量索引正在重建中，请稍后重试", 0, 0);
            sink.tryEmitComplete();
            return sink;
        }

        aiProperties.getModelConfig().flatMap(modelConfig -> {
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
                indexing.set(false);
                emitSingle(sink, "done", "", chunks, 100);
                sink.tryEmitComplete();
            },
            e -> {
                indexing.set(false);
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
                    int inputTokenLimit = Math.max(512, chunkConfig.getKeywordsMaxTokens());
                    int batchSize = Math.max(1, chunkConfig.getKeywordsBatchSize());
                    AtomicInteger truncated = new AtomicInteger(0);
                    AtomicInteger kwFailed = new AtomicInteger(0);
                    chunksMono = extractKeywords(chunks, chunkConfig.getAutoKeywordsCount(),
                        inputTokenLimit, batchSize)
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
                    return embedChunks(enrichedChunks, modelConfig)
                        .flatMap(indexedChunks -> Mono.fromCallable(() -> {
                                luceneIndexService.replacePost(indexedChunks,
                                    modelConfig.getEffectiveEmbeddingModel(),
                                    modelConfig.getEmbeddingDimensions());
                                return indexedChunks.size();
                            })
                            .subscribeOn(Schedulers.boundedElastic()));
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
        return preparePostIndex(post, modelConfig, chunkConfig, truncatedKeywords, keywordsFailed)
            .flatMap(data -> Mono.fromCallable(() -> {
                    luceneIndexService.replacePost(data.chunks(),
                        modelConfig.getEffectiveEmbeddingModel(), modelConfig.getEmbeddingDimensions());
                    return data.totalChunks();
                })
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<PostIndexData> preparePostIndex(Post post,
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
                    return Mono.just(PostIndexData.empty(postName));
                }

                // 切片
                List<TextChunk> chunks = documentChunker.chunk(postName, title, content, chunkConfig);
                if (chunks.isEmpty()) {
                    return Mono.just(PostIndexData.empty(postName));
                }

                // 自动提取关键词（如果开启）
                Mono<List<TextChunk>> chunksMono;
                if (chunkConfig.isAutoKeywords() && chunkConfig.getAutoKeywordsCount() > 0) {
                    int inputTokenLimit = Math.max(512, chunkConfig.getKeywordsMaxTokens());
                    int batchSize = Math.max(1, chunkConfig.getKeywordsBatchSize());
                    chunksMono = extractKeywords(chunks, chunkConfig.getAutoKeywordsCount(),
                        inputTokenLimit, batchSize)
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
                    embedChunks(enrichedChunks, modelConfig)
                        .map(indexedChunks -> new PostIndexData(postName, indexedChunks))
                );
            });
    }

    /**
     * 批量 embed 并索引 — 按 EMBED_BATCH_SIZE 分批调 embedding API
     */
    private Mono<Integer> embedAndIndexChunks(List<TextChunk> chunks,
                                               AIProperties.ModelConfig modelConfig) {
        return embedChunks(chunks, modelConfig)
            .flatMap(indexedChunks -> Mono.fromCallable(() -> {
                    luceneIndexService.replacePost(indexedChunks,
                        modelConfig.getEffectiveEmbeddingModel(), modelConfig.getEmbeddingDimensions());
                    return indexedChunks.size();
                })
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<List<LuceneIndexService.IndexedChunk>> embedChunks(List<TextChunk> chunks,
                                               AIProperties.ModelConfig modelConfig) {
        List<List<TextChunk>> batches = splitIntoBatches(chunks, EMBED_BATCH_SIZE);

        return Flux.fromIterable(batches)
            .concatMap(batch -> embedBatch(batch, modelConfig)
                .map(embeddings -> {
                    List<LuceneIndexService.IndexedChunk> result = new ArrayList<>();
                    for (int i = 0; i < batch.size(); i++) {
                        float[] embedding = i < embeddings.size() ? embeddings.get(i) : null;
                        result.add(new LuceneIndexService.IndexedChunk(batch.get(i), embedding));
                    }
                    return result;
                }))
            .collectList()
            .map(batchResults -> {
                List<LuceneIndexService.IndexedChunk> all = new ArrayList<>();
                for (List<LuceneIndexService.IndexedChunk> batch : batchResults) {
                    all.addAll(batch);
                }
                return all;
            });
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
            modelConfig.getEffectiveEmbeddingModel(),
            texts,
            modelConfig.getEmbeddingDimensions(),
            UsageScenario.INDEX_EMBEDDING
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
     * 调用 LLM 为切片提取关键词。
     * <p>关键词提取必须保持切片与响应一一对应。批量提示词容易被模型合并成一行输出，
     * 导致后续切片全部拿不到关键词，所以这里按单切片请求，仅用 keywordsBatchSize 控制并发。
     */
    private Mono<KeywordExtractResult> extractKeywords(List<TextChunk> chunks, int count,
                                                        int inputTokenLimit, int batchSize) {
        int keywordCount = Math.max(1, Math.min(count, 10));
        int concurrency = Math.max(1, Math.min(batchSize, 4));
        AtomicInteger truncatedTotal = new AtomicInteger(0);

        return aiProperties.getModelConfig()
            .flatMapMany(modelConfig ->
                Flux.fromIterable(chunks)
                    .flatMapSequential(chunk -> extractKeywordChunk(chunk, keywordCount, inputTokenLimit,
                        modelConfig, truncatedTotal), concurrency)
            )
            .collectList()
            .map(results -> {
                List<TextChunk> all = new ArrayList<>(results);
                all.sort(java.util.Comparator.comparingInt(TextChunk::chunkIndex));
                return new KeywordExtractResult(all, truncatedTotal.get(), false);
            })
            .onErrorResume(e -> {
                log.warn("[ReindexService] 关键词提取失败，跳过: {}", e.getMessage());
                return Mono.just(new KeywordExtractResult(chunks, 0, true));
            });
    }

    private Mono<TextChunk> extractKeywordChunk(TextChunk chunk,
                                                int count,
                                                int inputTokenLimit,
                                                AIProperties.ModelConfig modelConfig,
                                                AtomicInteger truncatedTotal) {
        String prompt = buildSingleKeywordPrompt(chunk, count, inputTokenLimit);
        int outputTokens = keywordOutputTokens(count);
        return llmClient.chatInternal(
                modelConfig.getEffectiveChatModel(),
                List.of(Map.of("role", "user", "content", prompt)),
                0.1f,
                outputTokens,
                null,
                UsageScenario.KEYWORD_EXTRACT
            )
            .map(response -> {
                List<String> keywords = parseKeywordList(response, count);
                if (keywords.isEmpty()) {
                    keywords = fallbackKeywords(chunk, count);
                    if (keywords.isEmpty()) {
                        truncatedTotal.incrementAndGet();
                    }
                    log.warn("[ReindexService] 单个切片关键词响应为空，使用本地兜底: postId={}, title={}, chunkIndex={}, chars={}, fallback={}, response={}",
                        chunk.postId(), chunk.postTitle(), chunk.chunkIndex(),
                        chunk.content() == null ? 0 : chunk.content().length(), keywords,
                        abbreviate(response, 200));
                }
                return withKeywords(chunk, keywords);
            })
            .onErrorResume(e -> {
                String error = keywordError(e);
                List<String> fallback = fallbackKeywords(chunk, count);
                if (fallback.isEmpty()) {
                    truncatedTotal.incrementAndGet();
                }
                log.warn("[ReindexService] 单个切片关键词提取失败，使用本地兜底: postId={}, title={}, chunkIndex={}, chars={}, fallback={}, error={}",
                    chunk.postId(), chunk.postTitle(), chunk.chunkIndex(),
                    chunk.content() == null ? 0 : chunk.content().length(), fallback, error);
                return Mono.just(withKeywords(chunk, fallback));
            });
    }

    private String buildSingleKeywordPrompt(TextChunk chunk, int count, int inputTokenLimit) {
        String title = sanitizeKeywordContent(chunk.postTitle());
        String prefix = "为下面这一段博客内容提取" + count
            + "个最相关的中文关键词。只输出关键词，用中文逗号分隔；不要输出序号、标题、解释或 Markdown。\n\n"
            + "文章标题: " + title + "\n"
            + "内容: ";
        int contentBudget = Math.max(64, inputTokenLimit - estimateTokens(prefix));
        String content = truncateByEstimatedTokens(sanitizeKeywordContent(chunk.content()), contentBudget);
        return prefix + content;
    }

    private String sanitizeKeywordContent(String content) {
        if (content == null || content.isBlank()) return "";
        return content.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ");
    }

    private TextChunk withKeywords(TextChunk original, List<String> keywords) {
        return new TextChunk(
            original.id(), original.postId(), original.postTitle(),
            original.content(), original.chunkIndex(), keywords
        );
    }

    private String keywordError(Throwable e) {
        Throwable cur = e;
        while (cur instanceof RuntimeException && cur.getCause() != null) {
            cur = cur.getCause();
        }
        if (cur instanceof WebClientResponseException responseException) {
            String body = responseException.getResponseBodyAsString();
            String msg = responseException.getStatusCode() + " " + responseException.getStatusText();
            if (body != null && !body.isBlank()) {
                msg += " body=" + body;
            }
            return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
        }
        String msg = cur.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cur.getClass().getSimpleName();
        }
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }

    private int keywordOutputTokens(int keywordCount) {
        return Math.min(KEYWORD_OUTPUT_TOKENS, Math.max(128, Math.max(1, keywordCount) * 32 + 64));
    }

    private String truncateByEstimatedTokens(String text, int maxTokens) {
        if (text == null || text.isBlank() || maxTokens <= 0) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int cost = estimateTokenCost(ch);
            if (used + cost > maxTokens) {
                break;
            }
            result.append(ch);
            used += cost;
        }
        return result.toString();
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            tokens += estimateTokenCost(text.charAt(i));
        }
        return tokens;
    }

    private int estimateTokenCost(char ch) {
        if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
            return 1;
        }
        if (Character.isWhitespace(ch)) {
            return 0;
        }
        return 1;
    }

    private List<String> parseKeywordList(String response, int count) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        String normalized = response
            .replace("```json", "")
            .replace("```", "")
            .replaceAll("(?i)keywords?\\s*[:：]", "")
            .replaceAll("关键词\\s*[:：]", "")
            .replaceAll("[\\[\\]\"'`]", "")
            .replaceAll("[，、；;\\n\\r]+", ",");
        return java.util.Arrays.stream(normalized.split(","))
            .map(String::trim)
            .map(item -> item.replaceFirst("^[-*•\\d.、:：)）\\s]+", "").trim())
            .filter(keyword -> !keyword.isBlank())
            .distinct()
            .limit(Math.max(1, count))
            .toList();
    }

    private List<String> fallbackKeywords(TextChunk chunk, int count) {
        Map<String, Integer> scores = new HashMap<>();
        addKeywordTerms(scores, chunk.postTitle(), 3);
        addKeywordTerms(scores, chunk.content(), 1);
        return scores.entrySet().stream()
            .sorted((left, right) -> {
                int score = Integer.compare(right.getValue(), left.getValue());
                if (score != 0) {
                    return score;
                }
                return Integer.compare(right.getKey().length(), left.getKey().length());
            })
            .map(Map.Entry::getKey)
            .limit(Math.max(1, count))
            .toList();
    }

    private void addKeywordTerms(Map<String, Integer> scores, String text, int weight) {
        if (text == null || text.isBlank()) {
            return;
        }
        try (SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer();
             TokenStream stream = analyzer.tokenStream("content", text)) {
            CharTermAttribute termAttr = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                String term = termAttr.toString().trim().toLowerCase(java.util.Locale.ROOT);
                if (isUsefulKeywordTerm(term)) {
                    scores.merge(term, weight, Integer::sum);
                }
            }
            stream.end();
        } catch (Exception e) {
            log.debug("[ReindexService] 本地关键词兜底分词失败: {}", e.getMessage());
        }
    }

    private boolean isUsefulKeywordTerm(String term) {
        if (term == null || term.length() < 2 || term.length() > 32) {
            return false;
        }
        if (KEYWORD_STOP_WORDS.contains(term)) {
            return false;
        }
        if (term.matches("\\d+")) {
            return false;
        }
        return term.matches(".*[\\p{IsHan}A-Za-z].*");
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength
            ? normalized
            : normalized.substring(0, maxLength) + "...";
    }

    private record KeywordExtractResult(List<TextChunk> chunks, int truncatedCount,
                                         boolean failed) {}

    private record PostIndexData(String postName, List<LuceneIndexService.IndexedChunk> chunks) {
        int totalChunks() {
            return chunks.size();
        }

        static PostIndexData empty(String postName) {
            return new PostIndexData(postName, List.of());
        }
    }
}
