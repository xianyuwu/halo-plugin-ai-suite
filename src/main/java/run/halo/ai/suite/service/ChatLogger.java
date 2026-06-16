package run.halo.ai.suite.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.suite.extension.ChatLog;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.query.Queries;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 访客问答日志 + 反馈.
 *
 * <p>存储: Halo Extension (CRD) {@link ChatLog} — 每条记录独立对象, 由 Halo
 * 负责持久化 (H2/MySQL/Postgres) + 乐观锁 + GC. 通过 {@code ReactiveExtensionClient}
 * 读写, 字段索引在 {@code AISuitePlugin.start()} 声明.
 *
 * <p>写并发: {@link ReentrantLock} 串行化 clearAll 这种「读全部 + 删全部」类操作,
 * 避免与 append 抢同一批记录.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatLogger {

    private final ReactiveExtensionClient client;

    private final SchemeManager schemeManager;

    // 自己 new，不依赖 Halo 是否把 ObjectMapper 共享给插件 context
    // （重启/插件升级时该共享不稳定，曾导致插件 START_ERROR）
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ReentrantLock writeLock = new ReentrantLock();

    // ===== DTO records (与 ChatLog Extension 解耦, 方便 PublicChatEndpoint / ConsoleEndpoint 复用) =====

    public record ChatLogEntry(
        String id,
        Instant timestamp,
        String ip,
        String userAgent,
        String question,
        String answer,
        String model,
        List<Map<String, String>> citations,
        Feedback feedback,
        String traceIntent,
        String traceStagesJson
    ) {}

    public record Feedback(String type, String comment, Instant timestamp) {
        public static Feedback like() {
            return new Feedback("like", "", Instant.now());
        }
        public static Feedback dislike(String comment) {
            return new Feedback("dislike", comment == null ? "" : comment, Instant.now());
        }
    }

    public record PageResult<T>(List<T> items, long total, int page, int size) {}

    public enum FeedbackFilter { ALL, LIKE, DISLIKE, NONE }

    public record LogFilter(
    Instant from,
    Instant to,
    String model,
    FeedbackFilter feedback,
    String question
  ) {
    public static LogFilter empty() {
      return new LogFilter(null, null, null, FeedbackFilter.ALL, null);
    }
  }

    public record StatsResult(
        long totalLogs,
        Map<String, Long> totalByModel,
        long likes,
        long dislikes,
        long none,
        long last7Days,
        long todayNew,
        double dislikeRate,
        Yesterday yesterday
    ) {}

    /** 昨日数据 — 用于前端 "vs 昨日" 对比基线 */
    public record Yesterday(long newLogs, long dislikes) {}

    // ===== 写入 =====

    /**
     * 异步追加一条日志 — fire-and-forget.
     *
     * <p>内部直接 {@code Schedulers.boundedElastic().schedule(runnable)} 丢到后台,
     * 立即返回, 调用方无需订阅. 失败仅 log.warn.
     */
    public void appendLogAsync(ChatLogEntry entry) {
        Schedulers.boundedElastic().schedule(() -> {
            try {
                client.create(toExtension(entry)).block();
            } catch (Exception e) {
                log.warn("[ChatLogger] 写日志失败: {}", e.getMessage());
            }
        });
    }

    public Mono<Void> updateFeedback(String logId, String type, String comment) {
        return updateFeedbackWithTrace(logId, type, comment, null, null);
    }

    /** 更新反馈并写入管线追踪（点踩时调用） */
    public Mono<Void> updateFeedbackWithTrace(String logId, String type, String comment,
                                               String traceIntent,
                                               List<Map<String, Object>> traceStages) {
        return Mono.fromRunnable(() -> {
            try {
                writeLock.lock();
                ChatLog existing = client.fetch(ChatLog.class, logId).block();
                if (existing == null) {
                    throw new IllegalArgumentException("logId 不存在: " + logId);
                }
                ChatLog.Spec spec = existing.getSpec();
                if (spec == null) {
                    spec = new ChatLog.Spec();
                    existing.setSpec(spec);
                }
                ChatLog.Feedback fb = new ChatLog.Feedback();
                fb.setType(type);
                fb.setComment(comment != null ? comment : "");
                fb.setTimestamp(Instant.now());
                spec.setFeedback(fb);
                spec.setFeedbackType(type);
                // 写入管线追踪（仅点踩时有值，存为 JSON 字符串避免 schema 验证问题）
                if (traceIntent != null) {
                    spec.setTraceIntent(traceIntent);
                }
                if (traceStages != null) {
                    spec.setTraceStagesJson(objectMapper.writeValueAsString(traceStages));
                }
                updateWithCurrentScheme(existing);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                writeLock.unlock();
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    // ===== 读取 =====

    public Mono<PageResult<ChatLogEntry>> listLogs(LogFilter filter, int page, int size) {
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));
    Sort sort = Sort.by(Sort.Order.desc("spec.timestamp"), Sort.Order.asc("metadata.name"));
    // question 搜索需要后过滤，先拉大批次再分页
    boolean hasQuestion = filter != null && filter.question() != null
        && !filter.question().isBlank();
    int fetchSize = hasQuestion ? Math.max(safeSize * 5, 200) : safeSize;
    // 数据库分页页码（1-based）
    int fetchPage = hasQuestion ? 1 : safePage + 1;
    PageRequestImpl pageable = PageRequestImpl.of(fetchPage, fetchSize, sort);

    return Mono.fromCallable(() -> {
      ListOptions options = buildListOptions(filter);
      var result = client.listBy(ChatLog.class, options, pageable).block();
      long total = result == null ? 0 : result.getTotal();
      List<ChatLogEntry> items = result == null ? List.of()
          : result.getItems().stream().map(ChatLogger::fromExtension).toList();
      // 后过滤 question
      if (hasQuestion) {
        String q = filter.question().toLowerCase();
        items = items.stream()
            .filter(e -> e.question() != null
                && e.question().toLowerCase().contains(q))
            .toList();
        total = items.size();
        // 在已拉取的大批次内做手动分页（offset 相对于本批次）
        int fetchBatchOffset = ((safePage * safeSize) % fetchSize);
        int to = Math.min(fetchBatchOffset + safeSize, items.size());
        items = fetchBatchOffset < items.size()
            ? items.subList(fetchBatchOffset, to) : List.of();
      }
      // 非问题搜索：数据库已直接返回目标页，无需再切
      return new PageResult<>(items, total, safePage, safeSize);
    }).subscribeOn(Schedulers.boundedElastic());
  }

    public Mono<ChatLogEntry> getLog(String id) {
        return Mono.fromCallable(() -> {
            ChatLog ext = client.fetch(ChatLog.class, id).block();
            return ext == null ? null : fromExtension(ext);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<StatsResult> getStats(Instant from, Instant to) {
        return Mono.fromCallable(() -> {
            ListOptions.ListOptionsBuilder builder = ListOptions.builder();
            if (from != null) {
                builder.andQuery(Queries.greaterThan("spec.timestamp", from.toString()));
            }
            if (to != null) {
                builder.andQuery(Queries.lessThan("spec.timestamp", to.toString()));
            }
            List<ChatLog> all = client.listAll(ChatLog.class, builder.build(),
                Sort.by(Sort.Order.desc("spec.timestamp"))).collectList().block();
            long likes = 0, dislikes = 0, none = 0;
            Map<String, Long> byModel = new LinkedHashMap<>();
            Instant now = Instant.now();
            Instant sevenDaysAgo = now.minusSeconds(7 * 24 * 3600);
            long last7 = 0;
            ZoneId zone = ZoneId.systemDefault();
            LocalDate todayDate = LocalDate.now(zone);
            String today = todayDate.toString();
            String yesterday = todayDate.minusDays(1).toString();
            long todayNew = 0;
            long yesterdayNew = 0;
            long yesterdayDislikes = 0;

            if (all != null) {
                for (var e : all) {
                    var spec = e.getSpec();
                    if (spec == null) continue;
                    String ft = spec.getFeedbackType();
                    if (ft == null || "none".equals(ft)) none++;
                    else if ("like".equals(ft)) likes++;
                    else if ("dislike".equals(ft)) dislikes++;
                    if (spec.getModel() != null) {
                        byModel.merge(spec.getModel(), 1L, Long::sum);
                    } else {
                        byModel.merge("unknown", 1L, Long::sum);
                    }
                    if (spec.getTimestamp() != null) {
                        String ts = spec.getTimestamp().toString();
                        if (spec.getTimestamp().isAfter(sevenDaysAgo)) last7++;
                        if (ts.startsWith(today)) todayNew++;
                        if (ts.startsWith(yesterday)) {
                            yesterdayNew++;
                            if ("dislike".equals(ft)) yesterdayDislikes++;
                        }
                    }
                }
            }
            double rate = (likes + dislikes) > 0
                ? Math.round(dislikes * 10000.0 / (likes + dislikes)) / 100.0 : 0.0;
            long total = all == null ? 0 : all.size();
            return new StatsResult(total, byModel, likes, dislikes, none, last7, todayNew, rate,
                new Yesterday(yesterdayNew, yesterdayDislikes));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ===== 删除 =====

    public Mono<Void> deleteLog(String id) {
        return Mono.fromRunnable(() -> {
            try {
                writeLock.lock();
                ChatLog existing = client.fetch(ChatLog.class, id).block();
                if (existing == null) return;  // 幂等
                deleteWithCurrentScheme(existing);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                writeLock.unlock();
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Void> clearAll() {
        return client.listAll(ChatLog.class, ListOptions.builder().build(),
                Sort.by(Sort.Order.asc("metadata.name")))
            .collectList()
            .flatMap(all -> Mono.fromRunnable(() -> {
                try {
                    writeLock.lock();
                    for (ChatLog ext : all) {
                        try {
                            deleteWithCurrentScheme(ext);
                        } catch (Exception e) {
                            log.warn("[ChatLogger] 删除 {} 失败: {}",
                                ext.getMetadata().getName(), e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    writeLock.unlock();
                }
            }).subscribeOn(Schedulers.boundedElastic())).then();
    }

    // ===== ChatLog Extension <-> ChatLogEntry DTO 转换 =====

    static ChatLog toExtension(ChatLogEntry entry) {
        ChatLog ext = new ChatLog();
        Metadata md = new Metadata();
        md.setName(entry.id() != null ? entry.id()
            : java.util.UUID.randomUUID().toString());
        ext.setMetadata(md);

        ChatLog.Spec spec = new ChatLog.Spec();
        spec.setTimestamp(entry.timestamp() != null ? entry.timestamp() : Instant.now());
        spec.setIp(entry.ip() != null ? entry.ip() : "");
        spec.setUserAgent(entry.userAgent() != null ? entry.userAgent() : "");
        spec.setQuestion(entry.question() != null ? entry.question() : "");
        spec.setAnswer(entry.answer() != null ? entry.answer() : "");
        spec.setModel(entry.model() != null ? entry.model() : "unknown");
        spec.setCitations(entry.citations() != null ? entry.citations() : List.of());
        if (entry.feedback() != null) {
            spec.setFeedback(toFeedbackExtension(entry.feedback()));
            spec.setFeedbackType(entry.feedback().type());
        } else {
            spec.setFeedbackType("none");
        }
        ext.setSpec(spec);
        return ext;
    }

    static ChatLogEntry fromExtension(ChatLog ext) {
        ChatLog.Spec spec = ext.getSpec();
        if (spec == null) {
            return new ChatLogEntry(
                ext.getMetadata() != null ? ext.getMetadata().getName() : null,
                null, "", "", "", "", "unknown", List.of(), null, null, null);
        }
        Feedback fb = null;
        if (spec.getFeedback() != null) {
            fb = new Feedback(spec.getFeedback().getType(),
                spec.getFeedback().getComment(),
                spec.getFeedback().getTimestamp());
        }
        return new ChatLogEntry(
            ext.getMetadata() != null ? ext.getMetadata().getName() : null,
            spec.getTimestamp(),
            spec.getIp(),
            spec.getUserAgent(),
            spec.getQuestion(),
            spec.getAnswer(),
            spec.getModel(),
            spec.getCitations(),
            fb,
            spec.getTraceIntent(),
            spec.getTraceStagesJson());
    }

    static ChatLog.Feedback toFeedbackExtension(Feedback fb) {
        ChatLog.Feedback ext = new ChatLog.Feedback();
        ext.setType(fb.type());
        ext.setComment(fb.comment() != null ? fb.comment() : "");
        ext.setTimestamp(fb.timestamp() != null ? fb.timestamp() : Instant.now());
        return ext;
    }

    private void updateWithCurrentScheme(ChatLog ext) {
        client.update(toCurrentSchemeExtension(ext)).block();
    }

    private void deleteWithCurrentScheme(ChatLog ext) {
        client.delete(toCurrentSchemeExtension(ext)).block();
    }

    private Extension toCurrentSchemeExtension(ChatLog ext) {
        Class<? extends Extension> schemeType = schemeManager.get(ext.groupVersionKind()).type();
        if (schemeType.equals(ext.getClass())) {
            return ext;
        }
        return objectMapper.convertValue(ext, schemeType);
    }

    // ===== ListOptions builder =====

    private static ListOptions buildListOptions(LogFilter filter) {
        ListOptions.ListOptionsBuilder builder = ListOptions.builder();
        if (filter == null) return builder.build();

        if (filter.from() != null) {
            builder.andQuery(Queries.greaterThan("spec.timestamp", filter.from().toString()));
        }
        if (filter.to() != null) {
            builder.andQuery(Queries.lessThan("spec.timestamp", filter.to().toString()));
        }
        if (filter.model() != null && !filter.model().isEmpty()) {
            builder.andQuery(Queries.equal("spec.model", filter.model()));
        }
        switch (filter.feedback()) {
            case LIKE -> builder.andQuery(Queries.equal("spec.feedbackType", "like"));
            case DISLIKE -> builder.andQuery(Queries.equal("spec.feedbackType", "dislike"));
            case NONE -> builder.andQuery(Queries.equal("spec.feedbackType", "none"));
            case ALL -> { /* pass */ }
        }
        return builder.build();
    }
}
