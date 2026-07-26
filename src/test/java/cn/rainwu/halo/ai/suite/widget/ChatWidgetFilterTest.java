package cn.rainwu.halo.ai.suite.widget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import cn.rainwu.halo.ai.suite.config.AIProperties;

class ChatWidgetFilterTest {

    @Test
    void injectsSearchAndMindMapAssetsWithoutConsultingGuestChatAccess() {
        AIProperties properties = mock(AIProperties.class);
        AIProperties.MindMapConfig mindMapConfig = new AIProperties.MindMapConfig();
        mindMapConfig.setEnabled(true);
        when(properties.getMindMapConfig()).thenReturn(Mono.just(mindMapConfig));

        ChatWidgetFilter filter = new ChatWidgetFilter(properties);
        WebTestClient client = WebTestClient.bindToWebHandler(exchange -> {
                byte[] body = "<html><body><main>article</main></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponse().getHeaders().setContentType(MediaType.TEXT_HTML);
                return exchange.getResponse().writeWith(Mono.just(
                    exchange.getResponse().bufferFactory().wrap(body)));
            })
            .webFilter(filter)
            .build();

        String body = client.get()
            .uri("/archives/example")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

        assertThat(body)
            .contains("/js/chat-widget.js")
            .contains("/js/mindmap-widget.js");
        verify(properties).getMindMapConfig();
        verifyNoMoreInteractions(properties);
    }
}
