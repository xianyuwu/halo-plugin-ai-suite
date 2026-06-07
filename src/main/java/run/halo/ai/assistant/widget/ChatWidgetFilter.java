package run.halo.ai.assistant.widget;

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
import run.halo.ai.assistant.config.AIProperties;
import run.halo.app.security.AdditionalWebFilter;

import java.nio.charset.StandardCharsets;
import java.security.Principal;

/**
 * 全局注入过滤器 — 在所有 HTML 页面的 </body> 前注入 AI 聊天浮窗
 *
 * 关键点：内容类型检查必须放在 writeWith() 内部，
 * 因为 filter() 执行时响应头还没设置，getContentType() 会返回 null。
 */
@Component
public class ChatWidgetFilter implements AdditionalWebFilter {

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

        if (path.startsWith("/console") || path.startsWith("/login")
                || path.startsWith("/apis/") || path.startsWith("/api/") || path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        // 读取配置，若不允许游客则鉴权
        return aiProperties.getChatConfig()
            .flatMap(chatConfig -> {
                if (!chatConfig.isAllowGuest()) {
                    return exchange.getPrincipal()
                        .flatMap(principal -> injectWidget(exchange, chain))
                        .switchIfEmpty(chain.filter(exchange)); // 未登录，不注入
                }
                return injectWidget(exchange, chain);
            });
    }

    private Mono<Void> injectWidget(ServerWebExchange exchange, WebFilterChain chain) {

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
                    .flatMapMany(buffers -> {
                        DataBuffer joined = originalResponse.bufferFactory().join(buffers);
                        byte[] bytes = new byte[joined.readableByteCount()];
                        joined.read(bytes);
                        DataBufferUtils.release(joined);

                        String html = new String(bytes, StandardCharsets.UTF_8);

                        // 防重复注入
                        if (html.contains("chat-widget.js")) {
                            return Flux.just(originalResponse.bufferFactory().wrap(bytes));
                        }

                        String widgetHtml = buildWidgetHtml();
                        String modifiedHtml = html.replace("</body>", widgetHtml + "</body>");

                        byte[] modifiedBytes = modifiedHtml.getBytes(StandardCharsets.UTF_8);
                        return Flux.just(originalResponse.bufferFactory().wrap(modifiedBytes));
                    });

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

    private String buildWidgetHtml() {
        String base = "/plugins/ai-assistant/assets/res";
        String v = "?v=" + assetVersion;
        // marked + DOMPurify 用 defer 异步加载，不阻塞页面渲染；chat-widget.js 启动时若它们已就绪即用 Markdown，否则降级为纯文本
        return "<link rel=\"stylesheet\" href=\"" + base + "/css/chat-widget.css" + v + "\">\n"
             + "<script src=\"" + base + "/js/marked.min.js" + v + "\" defer></script>\n"
             + "<script src=\"" + base + "/js/purify.min.js" + v + "\" defer></script>\n"
             + "<script src=\"" + base + "/js/chat-widget.js" + v + "\" defer></script>\n";
    }
}
