package run.halo.ai.suite.widget;

import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.config.AIProperties;
import run.halo.app.security.AdditionalWebFilter;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全局注入过滤器 — 在所有 HTML 页面的 </body> 前注入 AI 聊天浮窗
 *
 * 关键点：内容类型检查必须放在 writeWith() 内部，
 * 因为 filter() 执行时响应头还没设置，getContentType() 会返回 null。
 */
@Component
public class ChatWidgetFilter implements AdditionalWebFilter {

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile(
        "<[^>]*>",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DATA_TARGET_POST_PATTERN = Pattern.compile(
        "\\bdata-target\\s*=\\s*(['\"])Post\\1",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DATA_ID_PATTERN = Pattern.compile(
        "\\bdata-id\\s*=\\s*(['\"])([^'\"]+)\\1",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern COMMENT_POST_PATTERN = Pattern.compile(
        "\\bcomment-content-halo-run-Post-([A-Za-z0-9._:-]+)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private final AIProperties aiProperties;

    /** 插件启动时的时间戳，用作静态资源版本号，每次重启自动刷新浏览器缓存 */
    private final String assetVersion = String.valueOf(System.currentTimeMillis());

    public ChatWidgetFilter(AIProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (path.startsWith("/console") || path.startsWith("/login") || path.startsWith("/uc")
                || path.startsWith("/apis/") || path.startsWith("/api/") || path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        // 读取配置，若不允许游客则鉴权；配置读取失败时默认注入（allowGuest 默认 true）
        return aiProperties.getChatConfig()
            .flatMap(chatConfig -> {
                if (!chatConfig.isAllowGuest()) {
                    return exchange.getPrincipal()
                        .flatMap(principal -> injectWidget(exchange, chain, path))
                        .switchIfEmpty(chain.filter(exchange)); // 未登录，不注入
                }
                return injectWidget(exchange, chain, path);
            })
            .onErrorResume(e -> injectWidget(exchange, chain, path));
    }

    private Mono<Void> injectWidget(ServerWebExchange exchange, WebFilterChain chain, String path) {

        ServerHttpResponse originalResponse = exchange.getResponse();

        // Response decorator：拦截响应体，检查内容类型后注入浮窗
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                // 在 writeWith 内部检查内容类型，此时响应头已经设置好了
                MediaType contentType = getHeaders().getContentType();
                if (contentType == null || !contentType.includes(MediaType.TEXT_HTML)) {
                    // 非 HTML 响应，直接透传
                    return super.writeWith(body);
                }

                Flux<DataBuffer> flux = Flux.from(body)
                    .collectList()
                    .flatMap(buffers -> {
                        DataBuffer joined = originalResponse.bufferFactory().join(buffers);
                        byte[] bytes = new byte[joined.readableByteCount()];
                        joined.read(bytes);
                        DataBufferUtils.release(joined);

                        String html = new String(bytes, StandardCharsets.UTF_8);

                        // 防重复注入（检查任一已注入的资源）
                        if (html.contains("chat-widget.js") || html.contains("mindmap-widget.js")) {
                            return Mono.just(originalResponse.bufferFactory().wrap(bytes));
                        }

                        // buildWidgetHtml 读配置可能失败 → 兜底只注入聊天浮窗基础资源
                        return buildWidgetHtml(html, path)
                            .onErrorReturn(buildChatWidgetHtml())
                            .map(widgetHtml -> {
                                // replaceFirst: 只替换第一个 </body>（真正的页面闭合标签）。
                                // 全局 replace 会在 HTML 含多个 </body>（如被转义的示例代码）时注入多次。
                                String modifiedHtml = html.replaceFirst("</body>",
                                    Matcher.quoteReplacement(widgetHtml) + "</body>");
                                byte[] modifiedBytes = modifiedHtml.getBytes(StandardCharsets.UTF_8);
                                // 注入后字节数变化, 必须移除原 Content-Length, 否则浏览器按旧长度
                                // 截断响应(widget 脚本加载不全)或卡死。移除后框架改用 chunked 编码。
                                getHeaders().remove("Content-Length");
                                return originalResponse.bufferFactory().wrap(modifiedBytes);
                            })
                            // 最终兜底：buildWidgetHtml 成功但 map 出错 → 返回原始 HTML
                            .onErrorResume(e -> Mono.just(originalResponse.bufferFactory().wrap(bytes)));
                    })
                    .flux();

                return super.writeWith(flux);
            }

            @Override
            public Mono<Void> writeAndFlushWith(
                    org.reactivestreams.Publisher<? extends org.reactivestreams.Publisher<? extends DataBuffer>> body) {
                return writeWith(Flux.from(body).flatMap(p -> p));
            }
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }

    /**
     * 注入聊天浮窗 + 思维导图资源 — 全响应式，不在 Netty 线程阻塞。
     *
     * 思维导图资源受 mindmapConfig.enabled 开关控制。
     */
    private Mono<String> buildWidgetHtml(String html, String path) {
        String chatHtml = buildChatWidgetHtml();
        Optional<PostPageContext> postPageContext = detectPostPageContext(html, path);

        return aiProperties.getMindMapConfig()
            .map(cfg -> cfg != null && cfg.isEnabled())
            .defaultIfEmpty(true)
            .map(mindmapEnabled -> {
                String base = "/plugins/ai-suite/assets/res";
                String v = "?v=" + assetVersion;
                StringBuilder sb = new StringBuilder(chatHtml);
                // mindmap 脚本必须全局注入：pjax 导航只替换 .column-main，不会加载文章页 head 的脚本，
                // 若只在文章页注入，从首页 pjax 进文章页时脚本从未执行，脑图永远不显示。
                // 非文章页由脚本内 isArticlePage() 自动跳过渲染，全局注入安全。
                if (mindmapEnabled) {
                    sb.append("<link rel=\"stylesheet\" href=\"").append(base)
                        .append("/css/mindmap-widget.css").append(v).append("\">\n");
                    sb.append("<script src=\"").append(base)
                        .append("/js/mindmap-widget.js").append(v).append("\" defer></script>\n");
                }
                // page-context（postName）仅文章页注入；pjax 进文章页后由 data-target='Post' 兜底拿 postName
                if (mindmapEnabled && postPageContext.isPresent()) {
                    PostPageContext context = postPageContext.get();
                    sb.append("<script id=\"ai-suite-page-context\" type=\"application/json\">")
                        .append("{\"type\":\"post\",\"postName\":").append(toJsonString(context.postName()))
                        .append("}</script>\n");
                }
                return sb.toString();
            });
    }

    /** 聊天浮窗基础资源 — 纯字符串，不读配置，永远不报错 */
    private String buildChatWidgetHtml() {
        String base = "/plugins/ai-suite/assets/res";
        String v = "?v=" + assetVersion;
        return "<link rel=\"stylesheet\" href=\"" + base + "/css/chat-widget.css" + v + "\">\n"
             + "<script src=\"" + base + "/js/marked.min.js" + v + "\" defer></script>\n"
             + "<script src=\"" + base + "/js/purify.min.js" + v + "\" defer></script>\n"
             + "<script src=\"" + base + "/js/chat-widget.js" + v + "\" defer></script>\n";
    }

    private Optional<PostPageContext> detectPostPageContext(String html, String path) {
        if (html == null || html.isBlank() || isListPagePath(path)) {
            return Optional.empty();
        }
        Matcher tagMatcher = HTML_TAG_PATTERN.matcher(html);
        while (tagMatcher.find()) {
            String tag = tagMatcher.group();
            if (!DATA_TARGET_POST_PATTERN.matcher(tag).find()) {
                continue;
            }
            Matcher dataIdMatcher = DATA_ID_PATTERN.matcher(tag);
            if (dataIdMatcher.find()) {
                return Optional.of(new PostPageContext(dataIdMatcher.group(2)));
            }
        }
        Matcher commentPostMatcher = COMMENT_POST_PATTERN.matcher(html);
        if (commentPostMatcher.find()) {
            return Optional.of(new PostPageContext(commentPostMatcher.group(1)));
        }
        return Optional.empty();
    }

    private boolean isListPagePath(String path) {
        String normalized = normalizePath(path);
        if ("/".equals(normalized)) return true;
        if ("/archives".equals(normalized) || normalized.matches("^/archives/page/\\d+(/|$)")) {
            return true;
        }
        return normalized.matches("^/(categories|tags)(/|$)")
            || normalized.matches("^/(search|links|about)(/|$)")
            || normalized.matches(".*/page/\\d+(/|$)");
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        String normalized = path.replaceAll("/+$", "");
        return normalized.isEmpty() ? "/" : normalized;
    }

    private String toJsonString(String value) {
        if (value == null) return "\"\"";
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("<", "\\u003C")
            .replace(">", "\\u003E")
            .replace("&", "\\u0026")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
            + "\"";
    }

    private record PostPageContext(String postName) {
    }
}
