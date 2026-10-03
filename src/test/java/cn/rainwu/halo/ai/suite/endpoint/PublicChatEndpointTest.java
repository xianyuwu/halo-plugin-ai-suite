package cn.rainwu.halo.ai.suite.endpoint;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.llm.LlmClient.StreamEvent;
import cn.rainwu.halo.ai.suite.rag.PipelineTrace;
import cn.rainwu.halo.ai.suite.service.ChatLogger;
import cn.rainwu.halo.ai.suite.service.ChatService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class PublicChatEndpointTest {

    private final PublicChatEndpoint endpoint = new PublicChatEndpoint(
        mock(ChatService.class), mock(AIProperties.class), mock(ChatLogger.class),
        mock(cn.rainwu.halo.ai.suite.service.PetStore.class));
    private final WebTestClient client = WebTestClient
        .bindToRouterFunction(endpoint.endpoint())
        .build();

    @Test
    void petWidgetConfigCarriesCropWhileStaticModeKeepsOriginalIcon() {
        var properties = mock(AIProperties.class);
        var chat = new AIProperties.ChatConfig();
        chat.setWidgetTriggerType("pet"); chat.setWidgetPetPreset("mint-robot");
        chat.setWidgetPetAvatarCrops("{\"preset:mint-robot\":{\"centerX\":0.6,\"centerY\":0.4,\"size\":0.3}}");
        when(properties.getChatConfig()).thenReturn(Mono.just(chat));
        when(properties.getRetrievalConfig()).thenReturn(Mono.just(new AIProperties.RetrievalConfig()));
        when(properties.getSearchConfig()).thenReturn(Mono.just(new AIProperties.SearchConfig()));
        when(properties.getMindMapConfig()).thenReturn(Mono.just(new AIProperties.MindMapConfig()));
        var web = WebTestClient.bindToRouterFunction(new PublicChatEndpoint(mock(ChatService.class),
            properties, mock(ChatLogger.class), mock(cn.rainwu.halo.ai.suite.service.PetStore.class)).endpoint()).build();
        web.get().uri("/widget-config").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.petManifest.avatarCrop.centerX").isEqualTo(0.6)
            .jsonPath("$.petManifest.avatarCrop.size").isEqualTo(0.3);
        chat.setWidgetTriggerType("icon");
        web.get().uri("/widget-config").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.petManifest").doesNotExist();
    }

    @Test
    void generatedPetAvatarUsesMotherAndFaceRegionDefault() {
        var properties = mock(AIProperties.class);
        var chat = new AIProperties.ChatConfig(); chat.setWidgetTriggerType("pet"); chat.setWidgetPetId("a");
        when(properties.getChatConfig()).thenReturn(Mono.just(chat));
        when(properties.getRetrievalConfig()).thenReturn(Mono.just(new AIProperties.RetrievalConfig()));
        when(properties.getSearchConfig()).thenReturn(Mono.just(new AIProperties.SearchConfig()));
        when(properties.getMindMapConfig()).thenReturn(Mono.just(new AIProperties.MindMapConfig()));
        var store = mock(cn.rainwu.halo.ai.suite.service.PetStore.class);
        var pet = new cn.rainwu.halo.ai.suite.service.PetStore.PetRecord(); pet.setName("test");
        pet.getImages().put("idle", "/mother.png"); pet.getImages().put("happy", "/happy.png");
        var region = new cn.rainwu.halo.ai.suite.service.PetStore.ExpressionRegion();
        region.setCenterX(0.6); region.setCenterY(0.4); region.setWidth(0.3); region.setHeight(0.2); pet.setExpressionRegion(region);
        when(store.get("a")).thenReturn(Mono.just(pet));
        WebTestClient.bindToRouterFunction(new PublicChatEndpoint(mock(ChatService.class), properties,
            mock(ChatLogger.class), store).endpoint()).build().get().uri("/widget-config").exchange()
            .expectStatus().isOk().expectBody().jsonPath("$.petManifest.images.idle").isEqualTo("/mother.png")
            .jsonPath("$.petManifest.avatarCrop.centerX").isEqualTo(0.6)
            .jsonPath("$.petManifest.avatarCrop.size").isEqualTo(0.39);
    }

    @Test
    void pendingMotherNeverLeaksAndExistingPublicationSurvivesDraftChanges() {
        var properties = mock(AIProperties.class);
        var chat = new AIProperties.ChatConfig(); chat.setWidgetTriggerType("pet"); chat.setWidgetPetId("a");
        when(properties.getChatConfig()).thenReturn(Mono.just(chat));
        when(properties.getRetrievalConfig()).thenReturn(Mono.just(new AIProperties.RetrievalConfig()));
        when(properties.getSearchConfig()).thenReturn(Mono.just(new AIProperties.SearchConfig()));
        when(properties.getMindMapConfig()).thenReturn(Mono.just(new AIProperties.MindMapConfig()));
        var store = mock(cn.rainwu.halo.ai.suite.service.PetStore.class);
        var pet = new cn.rainwu.halo.ai.suite.service.PetStore.PetRecord(); pet.setBackgroundStatus("pending");
        pet.getImages().put("idle", "/unapproved.png");
        when(store.get("a")).thenReturn(Mono.just(pet));
        var web = WebTestClient.bindToRouterFunction(new PublicChatEndpoint(mock(ChatService.class), properties,
            mock(ChatLogger.class), store).endpoint()).build();
        web.get().uri("/widget-config").exchange().expectStatus().isOk().expectBody().jsonPath("$.petManifest").doesNotExist();
        var published = new cn.rainwu.halo.ai.suite.service.PetStore.PublishedVersion(); published.setName("old");
        published.setStyle("pixel"); published.setPixelGridSize(96); published.getImages().put("idle", "/approved.png");
        pet.setPublishedVersion(published);
        web.get().uri("/widget-config").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.petManifest.images.idle").isEqualTo("/approved.png")
            .jsonPath("$.petManifest.name").isEqualTo("old")
            .jsonPath("$.petManifest.imageRendering").isEqualTo("pixelated");
        when(store.get("a")).thenReturn(Mono.empty());
        web.get().uri("/widget-config").exchange().expectStatus().isOk().expectBody().jsonPath("$.petManifest").doesNotExist();
    }

    @Test
    void parsePetPhrasesTrimsAndFiltersBlankLines() {
        var phrases = PublicChatEndpoint.parsePetPhrases("  有问题随时问我~ \n\n \n点我聊聊吧！\r\n第三条 ");
        org.junit.jupiter.api.Assertions.assertEquals(
            List.of("有问题随时问我~", "点我聊聊吧！", "第三条"), phrases);
    }

    @Test
    void parsePetPhrasesHandlesEmptyAndLimits() {
        org.junit.jupiter.api.Assertions.assertEquals(List.of(), PublicChatEndpoint.parsePetPhrases(null));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(), PublicChatEndpoint.parsePetPhrases("  \n  "));

        String longLine = "很长的语录".repeat(20);
        org.junit.jupiter.api.Assertions.assertEquals(
            40, PublicChatEndpoint.parsePetPhrases(longLine).get(0).length());

        String many = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk\nl";
        org.junit.jupiter.api.Assertions.assertEquals(10, PublicChatEndpoint.parsePetPhrases(many).size());
    }

    @Test
    void streamRouteAcceptsPostJsonBody() {
        client.post()
            .uri("/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isBadRequest()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String.class)
            .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                .contains("message 必填", "[DONE]"));
    }

    @Test
    void nonStreamRouteAcceptsPostJsonBody() {
        client.post()
            .uri("/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isBadRequest()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.error").isEqualTo("message 必填");
    }

    @Test
    void legacyGetChatRoutesAreRemoved() {
        client.get().uri("/chat/stream?message=test")
            .exchange()
            .expectStatus().isNotFound();

        client.get().uri("/chat?message=test")
            .exchange()
            .expectStatus().isNotFound();

        client.get().uri("/chat/feedback?logId=test&type=like")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void feedbackRouteOnlyAcceptsPost() {
        client.post().uri("/chat/feedback")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.success").isEqualTo(false)
            .jsonPath("$.error").isEqualTo("logId 必填");
    }

    @Test
    void v1alpha2CompatibilityEndpointDelegatesChatRoutes() {
        PublicChatV1alpha2Endpoint compatibilityEndpoint =
            new PublicChatV1alpha2Endpoint(endpoint);
        WebTestClient compatibilityClient = WebTestClient
            .bindToRouterFunction(compatibilityEndpoint.endpoint())
            .build();

        org.assertj.core.api.Assertions.assertThat(compatibilityEndpoint.groupVersion())
            .isEqualTo(new run.halo.app.extension.GroupVersion(
                "api.ai-suite.halo.run", "v1alpha2"));
        compatibilityClient.post()
            .uri("/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void streamSeparatesReasoningEventsFromAnswerTokens() {
        ChatService chatService = mock(ChatService.class);
        AIProperties properties = mock(AIProperties.class);
        ChatLogger logger = mock(ChatLogger.class);
        PublicChatEndpoint reasoningEndpoint = new PublicChatEndpoint(chatService, properties,
            logger, mock(cn.rainwu.halo.ai.suite.service.PetStore.class));
        WebTestClient reasoningClient = WebTestClient
            .bindToRouterFunction(reasoningEndpoint.endpoint())
            .build();

        AIProperties.ChatConfig config = new AIProperties.ChatConfig();
        config.setAllowGuest(true);
        config.setAllowVisitorReasoning(true);
        config.setReasoningDefaultEnabled(true);
        when(properties.getChatConfig()).thenReturn(Mono.just(config));
        Flux<StreamEvent> events = Flux.just(
            new StreamEvent(StreamEvent.REASONING_START, ""),
            new StreamEvent(StreamEvent.REASONING_DELTA, "private analysis"),
            new StreamEvent(StreamEvent.REASONING_END, ""),
            StreamEvent.text("final answer"));
        when(chatService.chatStreamWithDebug(eq("hello"), eq(List.of()), any(), any(),
            eq("enabled")))
            .thenReturn(Mono.just(new ChatService.DebugChatResponse(
                Flux.just("final answer"), List.of(), null,
                new PipelineTrace("hello", "NORMAL_CHAT"), events)));

        reasoningClient.post()
            .uri("/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue("{\"message\":\"hello\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:reasoning_start", "event:reasoning_delta",
                    "private analysis", "final answer", "[DONE]"));
    }
}
