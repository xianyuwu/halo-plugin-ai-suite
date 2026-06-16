package run.halo.ai.suite.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.ai.suite.service.ChatLogger.ChatLogEntry;
import run.halo.ai.suite.service.ChatLogger.Feedback;

/**
 * 旧 ConfigMap JSON 数组存储 → 新 ChatLog Extension 一次性迁移.
 *
 * <p>在 plugin 完全启动 (ApplicationReadyEvent) 后跑一次, 避免与其它并发写冲突.
 * 检测到旧 ConfigMap + 有数据 → 逐条转 ChatLog 写入 → 删 ConfigMap.
 * 失败仅 log.warn, 不抛 (旧数据本就是"丢失"状态, 不能再影响启动).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatLogMigrator {

    private static final String OLD_CM_NAME = "ai-assistant-chat-logs";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReactiveExtensionClient client;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        Schedulers.boundedElastic().schedule(this::doMigrate);
    }

    private void doMigrate() {
        try {
            ConfigMap old = client.fetch(ConfigMap.class, OLD_CM_NAME).block();
            if (old == null) {
                log.debug("[ChatLogMigrator] 旧 ConfigMap {} 不存在, 无需迁移", OLD_CM_NAME);
                return;
            }
            Map<String, String> data = old.getData();
            if (data == null) {
                log.debug("[ChatLogMigrator] 旧 ConfigMap {} 无 data, 跳过", OLD_CM_NAME);
                return;
            }
            String json = data.get("logs");
            if (json == null || json.isBlank() || "[]".equals(json.trim())) {
                log.debug("[ChatLogMigrator] 旧 ConfigMap {} logs 为空, 跳过", OLD_CM_NAME);
                // 顺手清掉空 ConfigMap, 避免下次启动再走一遍
                safeDelete(old);
                return;
            }

            List<Map<String, Object>> records = MAPPER.readValue(json,
                MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
            log.info("[ChatLogMigrator] 发现 {} 条旧记录, 开始迁移到 ChatLog Extension", records.size());

            int ok = 0, fail = 0;
            for (var rec : records) {
                try {
                    ChatLogEntry entry = toEntry(rec);
                    ChatLog ext = toExtension(entry);
                    client.create(ext).block();
                    ok++;
                } catch (Exception e) {
                    fail++;
                    log.warn("[ChatLogMigrator] 迁移单条失败: {}", e.getMessage());
                }
            }
            log.info("[ChatLogMigrator] 迁移完成: {} 成功, {} 失败", ok, fail);

            // 迁移完后删旧 ConfigMap (失败也不重试, 反正新数据已经写到 ChatLog 了)
            safeDelete(old);
        } catch (Exception e) {
            log.warn("[ChatLogMigrator] 迁移过程出错: {}", e.getMessage());
        }
    }

    private void safeDelete(ConfigMap old) {
        try {
            client.delete(old).block();
            log.info("[ChatLogMigrator] 已删除旧 ConfigMap {}", OLD_CM_NAME);
        } catch (Exception e) {
            log.warn("[ChatLogMigrator] 删除旧 ConfigMap {} 失败: {}", OLD_CM_NAME, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static ChatLogEntry toEntry(Map<String, Object> rec) {
        Object fbObj = rec.get("feedback");
        Feedback fb = null;
        if (fbObj instanceof Map<?, ?> fbMap) {
            Map<String, Object> m = (Map<String, Object>) fbMap;
            fb = new Feedback(
                String.valueOf(m.get("type")),
                String.valueOf(m.get("comment")),
                parseInstant(m.get("timestamp")));
        }
        List<Map<String, String>> citations = (List<Map<String, String>>)
            rec.getOrDefault("citations", List.of());
        return new ChatLogEntry(
            String.valueOf(rec.get("id")),
            parseInstant(rec.get("timestamp")),
            String.valueOf(rec.get("ip")),
            String.valueOf(rec.get("userAgent")),
            String.valueOf(rec.get("question")),
            String.valueOf(rec.get("answer")),
            String.valueOf(rec.get("model")),
            citations,
            fb,
            null,
            null);
    }

    private static Instant parseInstant(Object o) {
        if (o == null) return null;
        try { return Instant.parse(String.valueOf(o)); }
        catch (Exception e) { return null; }
    }

    private static ChatLog toExtension(ChatLogEntry entry) {
        ChatLog ext = new ChatLog();
        run.halo.app.extension.Metadata md = new run.halo.app.extension.Metadata();
        md.setName(entry.id() != null ? entry.id() : java.util.UUID.randomUUID().toString());
        ext.setMetadata(md);

        ChatLog.Spec spec = new ChatLog.Spec();
        spec.setTimestamp(entry.timestamp() != null ? entry.timestamp() : Instant.now());
        spec.setIp(nz(entry.ip()));
        spec.setUserAgent(nz(entry.userAgent()));
        spec.setQuestion(nz(entry.question()));
        spec.setAnswer(nz(entry.answer()));
        spec.setModel(nz(entry.model()));
        spec.setCitations(entry.citations() != null ? entry.citations() : List.of());
        if (entry.feedback() != null) {
            ChatLog.Feedback fb = new ChatLog.Feedback();
            fb.setType(entry.feedback().type());
            fb.setComment(nz(entry.feedback().comment()));
            fb.setTimestamp(entry.feedback().timestamp() != null
                ? entry.feedback().timestamp() : Instant.now());
            spec.setFeedback(fb);
            spec.setFeedbackType(entry.feedback().type());
        } else {
            spec.setFeedbackType("none");
        }
        ext.setSpec(spec);
        return ext;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
