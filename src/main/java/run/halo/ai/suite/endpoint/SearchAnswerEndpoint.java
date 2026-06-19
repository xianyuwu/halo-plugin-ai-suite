package run.halo.ai.suite.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.service.ChatService;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 搜索结果页 AI 综合回答 API — 公开接口，无需认证。
 *
 * <p>调用方: chat-widget.js 在 /search?keyword=xxx 页面自动注入 AI 回答卡片时调用。
 *
 * <p>SSE 协议（与 PublicChatEndpoint 完全一致，前端可共用 parseSseStream）:
 * <ol>
 *   <li>{@code event: citations} → data:[{title, postId, url}, ...]（有引用时）</li>
 *   <li>连续 {@code data: {"content":"token"}} 流式 token</li>
 *   <li>{@code data: [DONE]} → 终止</li>
 * </ol>
 *
 * <p>实现上完全复用 {@link ChatService#chatStreamWithCitations} —— 把搜索 keyword
 * 当作 userMessage，history 为空，走完整的 RAG 检索 → LLM 流式输出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchAnswerEndpoint implements CustomEndpoint {

    private final ChatService chatService;
    private final AIProperties aiProperties;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_KEYWORD_CHARS = 500;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST("/search/answer", this::handleSearchAnswer)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.ai-suite.halo.run", "v1alpha1");
    }

    /**
     * POST /search/answer {"keyword":"xxx"} — SSE 流式 AI 回答
     */
    private Mono<ServerResponse> handleSearchAnswer(ServerRequest request) {
        return request.bodyToMono(SearchAnswerRequest.class)
            .defaultIfEmpty(new SearchAnswerRequest(""))
            .flatMap(body -> handleKeyword(body.keyword()));
    }

    private Mono<ServerResponse> handleKeyword(String rawKeyword) {
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        if (keyword.isEmpty()) {
            return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(simpleErrorStream("请输入搜索关键词"), ServerSentEvent.class);
        }
        if (keyword.length() > MAX_KEYWORD_CHARS) {
            return ServerResponse.badRequest()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(simpleErrorStream("搜索关键词超过 " + MAX_KEYWORD_CHARS + " 字符限制"),
                    ServerSentEvent.class);
        }

        // 检查搜索配置：功能开关 + AI 回答开关
        return aiProperties.getSearchConfig().flatMap(searchConfig -> {
            if (!searchConfig.isEnabled() || !searchConfig.isShowAiAnswer()) {
                return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(simpleErrorStream("AI 搜索已关闭"), ServerSentEvent.class);
            }
            return doStream(keyword);
        });
    }

    /** SSE 流式核心 — 复刻 PublicChatEndpoint.doStreamChat 的三段式 */
    private Mono<ServerResponse> doStream(String keyword) {
        return chatService.searchAnswerStreamWithCitations(keyword)
            .flatMap(chatResp -> {
                // 1) citations 首帧
                Flux<ServerSentEvent<String>> citationFrame = Flux.empty();
                if (chatResp.citations() != null && !chatResp.citations().isEmpty()) {
                    citationFrame = Flux.just(
                        ServerSentEvent.<String>builder()
                            .event("citations")
                            .data(toJsonSafe(chatResp.citations()))
                            .build()
                    );
                }

                // 2) token 帧：JSON 包装保空格换行
                Flux<ServerSentEvent<String>> tokenFrames = chatResp.stream()
                    .filter(token -> token != null && !token.isEmpty())
                    .map(token -> ServerSentEvent.<String>builder()
                        .data(wrapToken(token))
                        .build());

                // 3) [DONE] 终止帧
                Flux<ServerSentEvent<String>> doneFrame = Flux.just(
                    ServerSentEvent.<String>builder().data("[DONE]").build()
                );

                Flux<ServerSentEvent<String>> sseStream = citationFrame
                    .concatWith(tokenFrames)
                    .concatWith(doneFrame)
                    .onErrorResume(e -> {
                        log.error("[SearchAnswerEndpoint] 流式中断: {}", e.getMessage());
                        return simpleErrorStream("AI 搜索暂不可用");
                    });

                return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(sseStream, ServerSentEvent.class);
            })
            .onErrorResume(e -> {
                log.error("[SearchAnswerEndpoint] 搜索回答失败: {}", e.getMessage());
                return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(simpleErrorStream("AI 搜索暂不可用"), ServerSentEvent.class);
            });
    }

    /** 单条错误 token + [DONE] 兜底流 */
    private Flux<ServerSentEvent<String>> simpleErrorStream(String msg) {
        return Flux.just(
            ServerSentEvent.<String>builder().data(wrapToken(msg)).build(),
            ServerSentEvent.<String>builder().data("[DONE]").build()
        );
    }

    private String wrapToken(String token) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of("content", token));
        } catch (Exception e) {
            return "{\"content\":\"\"}";
        }
    }

    private String toJsonSafe(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    record SearchAnswerRequest(String keyword) {}
}
