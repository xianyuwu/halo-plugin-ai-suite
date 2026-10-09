package cn.rainwu.halo.ai.suite.endpoint;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.llm.LlmClient;
import cn.rainwu.halo.ai.suite.llm.UsageScenario;
import cn.rainwu.halo.ai.suite.service.ChatService;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchangeDecorator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.aifoundation.image.GenerateImageResult;

class ConsoleModelTestJobsTest {
    private final AIProperties properties = mock(AIProperties.class);
    private final LlmClient models = mock(LlmClient.class);
    private final ModelTestJobs jobs = new ModelTestJobs();
    private final ConsoleConfigEndpoint endpoint = new ConsoleConfigEndpoint(properties, models,
        mock(ReactiveExtensionClient.class), mock(ChatService.class), jobs);

    @AfterEach void stop() { jobs.stop(); }

    private WebTestClient client(String owner) {
        return WebTestClient.bindToRouterFunction(endpoint.endpoint())
            .webFilter((exchange, chain) -> chain.filter(new ServerWebExchangeDecorator(exchange) {
                @Override public <T extends Principal> Mono<T> getPrincipal() {
                    return owner == null ? Mono.empty() : Mono.just((T) (Principal) () -> owner);
                }
            })).build();
    }
    private ModelTestJobs.TestRequest request(String kind) {
        return new ModelTestJobs.TestRequest(UUID.randomUUID().toString(), kind, "selected", 1024);
    }
    private void config() {
        var config = new AIProperties.ModelConfig();
        config.setEmbeddingDimensions(1024);
        when(properties.getModelConfig()).thenReturn(Mono.just(config));
    }

    @Test void allFiveTestsPublishTheirResultsThroughTheTaskApi() {
        config();
        when(models.chatStream(eq("selected"), anyList(), eq(0.0f), eq(128), isNull(), isNull(),
            eq(UsageScenario.MODEL_TEST))).thenReturn(Flux.just("连接", "成功"));
        when(models.embed("selected", "Hello", 1024, UsageScenario.MODEL_TEST))
            .thenReturn(Mono.just(new float[1024]));
        when(models.rerank(eq("selected"), anyString(), anyList(), eq(1), eq(UsageScenario.MODEL_TEST)))
            .thenReturn(Mono.just(List.of(new LlmClient.RerankResult(0, 0.8f, "text"))));
        when(models.generateImage(eq("selected"), anyString(), isNull(), eq("1024x1024"),
            eq(UsageScenario.MODEL_TEST)))
            .thenReturn(Mono.just(GenerateImageResult.builder().images(List.of()).build()));
        var client = client("admin");
        for (String kind : List.of("chat", "queryRewrite", "embedding", "rerank", "image")) {
            var request = request(kind);
            client.post().uri("/config/test-jobs").bodyValue(request).exchange()
                .expectStatus().isAccepted().expectBody().jsonPath("$.jobId").isEqualTo(request.requestId());
            await().atMost(Duration.ofSeconds(2)).until(() -> !jobs.get("admin", request.requestId()).running());
            var response = client.get().uri("/config/test-jobs/" + request.requestId()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.job.status").isEqualTo("done")
                .jsonPath("$.job.result.model").isEqualTo("selected");
            switch (kind) {
                case "chat", "queryRewrite" -> response.jsonPath("$.job.result.reply").isEqualTo("连接成功");
                case "embedding" -> response.jsonPath("$.job.result.dimensions").isEqualTo(1024);
                case "rerank" -> response.jsonPath("$.job.result.relevanceScore").isEqualTo(0.8);
                case "image" -> response.jsonPath("$.job.result.imageCount").isEqualTo(0);
            }
        }
    }

    @Test void pendingImageJobSurvivesSubmittingResponseAndIsDeduplicated() {
        config();
        var image = Sinks.<GenerateImageResult>one();
        when(models.generateImage(eq("selected"), anyString(), isNull(), anyString(), anyString()))
            .thenReturn(image.asMono());
        var request = request("image");
        var client = client("admin");
        for (int i = 0; i < 2; i++) client.post().uri("/config/test-jobs").bodyValue(request).exchange()
            .expectStatus().isAccepted().expectBody().jsonPath("$.job.status").isEqualTo("pending");
        client("other").get().uri("/config/test-jobs/" + request.requestId()).exchange().expectStatus().isNotFound();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            verify(models, times(1)).generateImage(anyString(), anyString(), isNull(), anyString(), anyString()));
        image.tryEmitError(new IllegalStateException("provider unavailable"));
        await().atMost(Duration.ofSeconds(2)).until(() -> !jobs.get("admin", request.requestId()).running());
        client.get().uri("/config/test-jobs/" + request.requestId()).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.job.status").isEqualTo("failed")
            .jsonPath("$.job.error").isEqualTo("provider unavailable");
    }

    @Test void invalidAndAnonymousRequestsNeverInvokeModels() {
        var invalid = new ModelTestJobs.TestRequest("invalid", "image", "selected", 0);
        client("admin").post().uri("/config/test-jobs").bodyValue(invalid).exchange().expectStatus().isBadRequest();
        client(null).post().uri("/config/test-jobs").bodyValue(request("image")).exchange().expectStatus().isUnauthorized();
        client(null).get().uri("/config/test-jobs/unknown").exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(models, properties);
    }
}
